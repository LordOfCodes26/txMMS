package com.goodwy.commons.providercache.validation

import android.util.Log
import androidx.room.withTransaction
import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.debug.CompareOnlySoakCounters
import com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger
import com.goodwy.commons.providercache.display.CanonicalPhoneNumberResolver
import com.goodwy.commons.providercache.display.RecentGroupDualWriteValidator
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.display.RecentGroupKey
import com.goodwy.commons.providercache.display.RecentRelationalValidationDeferredState
import com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity
import com.goodwy.commons.providercache.grouping.RecentGroupMembershipChecksum

/**
 * Validates [recent_display_cache] phone-identity groups and relational membership (v15+).
 */
object RecentDisplayCacheValidator {

    /** Per-pass cap on individual checksum-mismatch lines; the totals stay exact either way. */
    private const val MAX_LOGGED_CHECKSUM_MISMATCHES = 5

    enum class IssueReason {
        DUPLICATE_IDENTITY_KEY,
        MISSING_DISPLAY_CONTACT,
        CALL_COUNT_MISMATCH,
        DUPLICATE_RELATIONAL_GROUP_KEY,
        UNASSIGNED_CALL,
        MULTIPLY_ASSIGNED_CALL,
        ORPHAN_MEMBERSHIP,
        EMPTY_RELATIONAL_GROUP,
        RELATIONAL_COUNT_MISMATCH,
        LATEST_CALL_NOT_IN_MEMBERSHIP,
        LATEST_TIMESTAMP_MISMATCH,
        DISPLAY_ROW_WITHOUT_GROUP,
        GROUP_WITHOUT_DISPLAY_ROW,
        STALE_DISPLAY_CONTACT,
        MEMBERSHIP_CHECKSUM_MISMATCH,
    }

    data class Issue(
        val identityKey: String,
        val reason: IssueReason,
        val detail: String = "",
    )

    data class ValidationResult(
        val issues: List<Issue>,
        val invalidIdentityKeys: Set<String>,
        val relationalTotals: RelationalTotals = RelationalTotals(),
        val deferredAlignment: Boolean = false,
    ) {
        val isValid: Boolean get() = issues.isEmpty()
    }

    data class RelationalTotals(
        val rawEligibleCalls: Int = 0,
        val membershipRows: Int = 0,
        val sumGroupCallCounts: Int = 0,
        val groups: Int = 0,
        val unassignedCalls: Int = 0,
        val multiplyAssignedCalls: Int = 0,
        val orphanMemberships: Int = 0,
        val countMismatches: Int = 0,
    )

    /**
     * One snapshot for the whole pass. The display-side reads below (duplicate keys, ordered rows,
     * relational counts, per-row call counts) tear against concurrent writers exactly the way the
     * relational reads did — see [validateRelational] for the failure this cost us.
     */
    suspend fun validate(
        database: ProviderCacheDatabase,
        groupByContact: Int,
        rowLimit: Int = 10_000,
    ): ValidationResult = database.withTransaction {
        validateSnapshot(database, groupByContact, rowLimit)
    }

    private suspend fun validateSnapshot(
        database: ProviderCacheDatabase,
        groupByContact: Int,
        rowLimit: Int,
    ): ValidationResult {
        val displayDao = database.recentDisplayCacheDao()
        val callLogDao = database.callLogDao()
        val groupDao = database.recentGroupDao()
        val membershipDao = database.recentGroupCallDao()

        val issues = mutableListOf<Issue>()
        val invalidKeys = mutableSetOf<String>()
        val mode = RecentGroupingMode.fromDbValue(groupByContact)

        displayDao.getDuplicateGroupKeys(groupByContact).forEach { duplicate ->
            issues += Issue(
                identityKey = duplicate.groupKey,
                reason = IssueReason.DUPLICATE_IDENTITY_KEY,
                detail = "rows=${duplicate.count}",
            )
            invalidKeys += duplicate.groupKey
        }

        val rows = displayDao.getOrdered(groupByContact, rowLimit)
        if (rows.isNotEmpty()) {
            val missingDisplayKeys = displayDao.getGroupKeysWithMissingDisplayContact(groupByContact)
            missingDisplayKeys.forEach { key ->
                issues += Issue(
                    identityKey = key,
                    reason = IssueReason.MISSING_DISPLAY_CONTACT,
                )
                invalidKeys += key
            }

            // Digit-only SQL counts cannot match v15 `contact:` / `number:` / `name:` keys
            // (WHERE digits IN ('contact:2') always returns 0 and falsely wipes the display cache).
            val relationalCounts = groupDao.getGroups(groupByContact)
                .associate { it.groupKey to it.callCount }
            val identityKeys = rows.map { identityKeyFromRow(it) }.distinct()
            val digitOnlyKeys = identityKeys.filterNot { isPrefixedGroupKey(it) }
            val digitExpectedCounts = if (digitOnlyKeys.isEmpty()) {
                emptyMap()
            } else {
                callLogDao.getCallCountsByGroupKeys(digitOnlyKeys)
                    .associate { it.groupKey to it.count }
            }

            rows.forEach { row ->
                val identityKey = identityKeyFromRow(row)
                val expected = when {
                    isPrefixedGroupKey(identityKey) -> relationalCounts[identityKey] ?: return@forEach
                    else -> digitExpectedCounts[identityKey] ?: 0
                }
                if (row.callCount != expected) {
                    issues += Issue(
                        identityKey = identityKey,
                        reason = IssueReason.CALL_COUNT_MISMATCH,
                        detail = "cached=${row.callCount} expected=$expected",
                    )
                    invalidKeys += identityKey
                }
            }
        }

        val relationalIssues = validateRelational(database, mode, rowLimit)
        issues += relationalIssues.issues
        invalidKeys += relationalIssues.invalidIdentityKeys

        return ValidationResult(
            issues = issues.distinctBy { it.identityKey to it.reason },
            invalidIdentityKeys = invalidKeys,
            relationalTotals = relationalIssues.relationalTotals,
        )
    }

    /**
     * Relational validation reads the same tables ~30 times (totals, then per-group membership,
     * numbers, checksums). Run unguarded, those reads straddle concurrent repair/sync writes and the
     * pass reports a mixture of two database states as corruption:
     *
     * `membershipRows` summed to 745 while every one of the 304 per-group re-reads of the *same*
     * query returned 0, which fabricated 304 RELATIONAL_COUNT_MISMATCH + 304
     * LATEST_CALL_NOT_IN_MEMBERSHIP issues and 255 phantom UNASSIGNED_CALLs. That escalated to
     * `reason=prefixed_or_empty_membership`, which rebuilt all 1000 calls and re-enriched 304 rows —
     * ~20s of startup work, every launch, against data the post-repair pass then confirmed had been
     * intact all along.
     *
     * One transaction pins a single snapshot, so the totals and the per-group detail can no longer
     * disagree. It does not paper over real corruption: a genuinely torn cache still fails, and now
     * the issue list describes one coherent state instead of the seam between two.
     */
    private suspend fun validateRelational(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        rowLimit: Int,
    ): ValidationResult = database.withTransaction {
        validateRelationalSnapshot(database, mode, rowLimit)
    }

    private suspend fun validateRelationalSnapshot(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        rowLimit: Int,
    ): ValidationResult {
        val issues = mutableListOf<Issue>()
        val invalidKeys = mutableSetOf<String>()

        val rawEligibleCalls = database.callLogDao().getCount()
        val groups = database.recentGroupDao().getGroups(mode.dbValue)
        val memberships = groups.flatMap { group ->
            database.recentGroupCallDao()
                .getCallIdsForGroup(mode.dbValue, group.groupKey)
                .map { callId -> group.groupKey to callId }
        }
        val membershipRows = memberships.size
        val sumGroupCallCounts = groups.sumOf { it.callCount }

        val totals = RelationalTotals(
            rawEligibleCalls = rawEligibleCalls,
            membershipRows = membershipRows,
            sumGroupCallCounts = sumGroupCallCounts,
            groups = groups.size,
        )

        if (groups.isEmpty() && rawEligibleCalls > 0) {
            return ValidationResult(
                issues = listOf(
                    Issue(
                        identityKey = "*",
                        reason = IssueReason.EMPTY_RELATIONAL_GROUP,
                        detail = "rawCalls=$rawEligibleCalls relationalGroups=0",
                    ),
                ),
                invalidIdentityKeys = emptySet(),
                relationalTotals = totals,
            )
        }

        val groupKeys = groups.map { it.groupKey }
        if (groupKeys.size != groupKeys.toSet().size) {
            issues += Issue(
                identityKey = "*",
                reason = IssueReason.DUPLICATE_RELATIONAL_GROUP_KEY,
                detail = "groups=${groupKeys.size} unique=${groupKeys.toSet().size}",
            )
        }

        val assignmentCount = mutableMapOf<Long, Int>()
        memberships.forEach { (_, callId) ->
            assignmentCount[callId] = (assignmentCount[callId] ?: 0) + 1
        }
        val unassigned = (database.callLogDao().getFirstEntries(rowLimit).map { it.callId.toLong() }.toSet() -
            assignmentCount.keys)
        val multiplyAssigned = assignmentCount.filter { it.value > 1 }
        var countMismatches = 0

        if (rawEligibleCalls > 0 && membershipRows != rawEligibleCalls) {
            issues += Issue(
                identityKey = "*",
                reason = IssueReason.UNASSIGNED_CALL,
                detail = "raw=$rawEligibleCalls membership=$membershipRows",
            )
        }
        if (sumGroupCallCounts != membershipRows) {
            countMismatches++
            issues += Issue(
                identityKey = "*",
                reason = IssueReason.RELATIONAL_COUNT_MISMATCH,
                detail = "sumCounts=$sumGroupCallCounts membership=$membershipRows",
            )
        }

        multiplyAssigned.forEach { (callId, count) ->
            issues += Issue(
                identityKey = callId.toString(),
                reason = IssueReason.MULTIPLY_ASSIGNED_CALL,
                detail = "assignments=$count",
            )
        }
        unassigned.forEach { callId ->
            issues += Issue(
                identityKey = callId.toString(),
                reason = IssueReason.UNASSIGNED_CALL,
            )
        }

        // A raw-mirror swap invalidates every group checksum at once, so this is all-or-nothing in
        // practice: one bad pass logged ~600 lines and told us nothing the issue list did not.
        var checksumMismatchesLogged = 0

        groups.forEach { group ->
            if (group.callCount == 0) {
                issues += Issue(group.groupKey, IssueReason.EMPTY_RELATIONAL_GROUP)
                invalidKeys += group.groupKey
            }
            val memberIds = database.recentGroupCallDao()
                .getCallIdsForGroup(mode.dbValue, group.groupKey)
            if (memberIds.size != group.callCount) {
                countMismatches++
                issues += Issue(
                    identityKey = group.groupKey,
                    reason = IssueReason.RELATIONAL_COUNT_MISMATCH,
                    detail = "groupCount=${group.callCount} members=${memberIds.size}",
                )
                invalidKeys += group.groupKey
            }
            if (group.latestCallId !in memberIds) {
                issues += Issue(
                    identityKey = group.groupKey,
                    reason = IssueReason.LATEST_CALL_NOT_IN_MEMBERSHIP,
                    detail = "latest=${group.latestCallId}",
                )
                invalidKeys += group.groupKey
            }
            val maxTs = database.callLogDao()
                .getByCallIds(memberIds.map { it.toInt() })
                .maxOfOrNull { it.startTS }
            if (maxTs != null && maxTs != group.latestTimestamp) {
                issues += Issue(
                    identityKey = group.groupKey,
                    reason = IssueReason.LATEST_TIMESTAMP_MISMATCH,
                    detail = "group=${group.latestTimestamp} maxRaw=$maxTs",
                )
                invalidKeys += group.groupKey
            }
            if (group.membershipChecksum != 0L) {
                val numbers = database.recentGroupNumberDao()
                    .getNumbersForGroup(mode.dbValue, group.groupKey)
                val expectedChecksum = RecentGroupMembershipChecksum.computeForGroup(
                    mode = mode,
                    group = group,
                    callIds = memberIds,
                    canonicalNumbers = numbers,
                )
                val matches = expectedChecksum == group.membershipChecksum
                CompareOnlySoakCounters.recordChecksumCompare(matches)
                if (!matches) {
                    checksumMismatchesLogged++
                    if (checksumMismatchesLogged <= MAX_LOGGED_CHECKSUM_MISMATCHES) {
                        Log.e(
                            "RecentGroupChecksum",
                            "recentGroupChecksumMismatch mode=${mode.name} groupKey=${group.groupKey} " +
                                "expected=$expectedChecksum actual=${group.membershipChecksum}",
                        )
                    } else if (checksumMismatchesLogged == MAX_LOGGED_CHECKSUM_MISMATCHES + 1) {
                        Log.e(
                            "RecentGroupChecksum",
                            "recentGroupChecksumMismatch mode=${mode.name} further mismatches suppressed " +
                                "-- see recentGroupValidation MEMBERSHIP_CHECKSUM_MISMATCH for the total",
                        )
                    }
                    issues += Issue(
                        identityKey = group.groupKey,
                        reason = IssueReason.MEMBERSHIP_CHECKSUM_MISMATCH,
                        detail = "expected=$expectedChecksum actual=${group.membershipChecksum}",
                    )
                    invalidKeys += group.groupKey
                }
            }
        }

        val presentCallIds = database.callLogDao().getFirstEntries(rowLimit).map { it.callId.toLong() }.toSet()
        memberships.forEach { (groupKey, callId) ->
            if (callId !in presentCallIds) {
                issues += Issue(
                    identityKey = groupKey,
                    reason = IssueReason.ORPHAN_MEMBERSHIP,
                    detail = "callId=$callId",
                )
            }
        }

        val displayRows = database.recentDisplayCacheDao().getOrdered(mode.dbValue, rowLimit)
        val deferred = RecentRelationalValidationDeferredState.shouldSkipAlignmentValidation()
        if (deferred) {
            ProviderCacheDebugLogger.log(
                "recentGroupValidationAlignment deferred mode=${mode.name} " +
                    "groups=${groups.size} displayRows=${displayRows.size} " +
                    "phase=${RecentRelationalValidationDeferredState.phase().name}",
            )
        } else {
            validateDisplayGroupAlignment(mode, groups, displayRows, issues, invalidKeys)
        }

        return ValidationResult(
            issues = issues,
            invalidIdentityKeys = invalidKeys,
            relationalTotals = totals.copy(
                unassignedCalls = unassigned.size,
                multiplyAssignedCalls = multiplyAssigned.size,
                orphanMemberships = issues.count { it.reason == IssueReason.ORPHAN_MEMBERSHIP },
                countMismatches = countMismatches,
            ),
            deferredAlignment = deferred,
        )
    }

    private fun validateDisplayGroupAlignment(
        mode: RecentGroupingMode,
        groups: List<com.goodwy.commons.providercache.entities.RecentGroupEntity>,
        displayRows: List<RecentDisplayCacheEntity>,
        issues: MutableList<Issue>,
        invalidKeys: MutableSet<String>,
    ) {
        val relationalKeys = groups.map { it.groupKey }.toSet()
        val displayKeys = displayRows.map {
            CanonicalPhoneNumberResolver.legacyDisplayGroupKey(it.groupKey, mode)
        }.toSet()

        (relationalKeys - displayKeys).forEach { key ->
            issues += Issue(key, IssueReason.GROUP_WITHOUT_DISPLAY_ROW)
            invalidKeys += key
        }
        if (mode == RecentGroupingMode.BY_NUMBER) {
            (displayKeys - relationalKeys).forEach { key ->
                issues += Issue(key, IssueReason.DISPLAY_ROW_WITHOUT_GROUP)
                invalidKeys += key
            }
        }
    }

    suspend fun validateDualWrite(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        rowLimit: Int = 10_000,
    ): RecentGroupDualWriteValidator.Result =
        RecentGroupDualWriteValidator.validateDualWrite(database, mode, rowLimit)

    private fun identityKeyFromRow(row: RecentDisplayCacheEntity): String =
        RecentGroupKey.fromEntity(row)

    private fun isPrefixedGroupKey(groupKey: String): Boolean =
        groupKey.startsWith("contact:") ||
            groupKey.startsWith("number:") ||
            groupKey.startsWith("name:")
}
