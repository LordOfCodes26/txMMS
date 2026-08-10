package com.goodwy.commons.providercache.validation

import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.entities.CallLogEntity

/**
 * Debug-only invariant checks between [recent_groups] / membership and [recent_display_cache].
 * Does not change grouping semantics or production authority.
 *
 * A: each recent_groups row → exactly one recent_display_cache row
 * B: each recent_display_cache row → exactly one recent_groups row
 * C: recent_group_calls count == recent_groups.callCount
 * D: latest_call_id belongs to membership
 * E: latest_timestamp equals max membership timestamp
 */
object RecentDisplayRelationalConsistencyValidator {

    enum class IssueReason {
        MISSING_DISPLAY_ROW,
        EXTRA_DISPLAY_ROW,
        DISPLAY_ROW_COUNT_MISMATCH,
        CALL_COUNT_MISMATCH,
        LATEST_CALL_NOT_IN_MEMBERSHIP,
        LATEST_TIMESTAMP_MISMATCH,
    }

    data class Issue(
        val groupKey: String,
        val reason: IssueReason,
        val detail: String = "",
    )

    data class Result(
        val mode: RecentGroupingMode,
        val issues: List<Issue>,
        val groupCount: Int,
        val displayRowCount: Int,
        /** True when alignment checks were skipped mid-rebuild (not a failure). */
        val deferred: Boolean = false,
        val deferredStateName: String = "",
    ) {
        /** Empty issues — deferred results are not treated as failures. */
        val valid: Boolean get() = issues.isEmpty()

        /** Ready and passing (excludes deferred mid-rebuild state). */
        val readyAndValid: Boolean get() = !deferred && issues.isEmpty()

        fun summary(): String =
            if (deferred) {
                "relationalConsistency mode=${mode.name} state=${deferredStateName.ifEmpty { "DEFERRED" }} " +
                    "groups=$groupCount displayRows=$displayRowCount"
            } else {
                "relationalConsistency mode=${mode.name} valid=$valid " +
                    "groups=$groupCount displayRows=$displayRowCount issues=${issues.size}"
            }
    }

    suspend fun validate(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        rowLimit: Int = 10_000,
    ): Result {
        if (com.goodwy.commons.providercache.display.RecentRelationalValidationDeferredState
                .shouldSkipAlignmentValidation()
        ) {
            val groups = database.recentGroupDao().countGroups(mode.dbValue)
            val displayRows = database.recentDisplayCacheDao().getCount(mode.dbValue)
            return Result(
                mode = mode,
                issues = emptyList(),
                groupCount = groups,
                displayRowCount = displayRows,
                deferred = true,
                deferredStateName = com.goodwy.commons.providercache.display
                    .RecentRelationalValidationDeferredState.phase().name,
            )
        }
        val issues = mutableListOf<Issue>()
        val groups = database.recentGroupDao().getGroups(mode.dbValue).take(rowLimit)
        val displayRows = database.recentDisplayCacheDao().getOrdered(mode.dbValue, rowLimit)
        val displayByKey = displayRows.groupBy { it.groupKey }

        // A: recent_groups -> exactly one display row
        groups.forEach { group ->
            val rows = displayByKey[group.groupKey].orEmpty()
            when {
                rows.isEmpty() -> issues += Issue(
                    groupKey = group.groupKey,
                    reason = IssueReason.MISSING_DISPLAY_ROW,
                )
                rows.size > 1 -> issues += Issue(
                    groupKey = group.groupKey,
                    reason = IssueReason.DISPLAY_ROW_COUNT_MISMATCH,
                    detail = "displayRows=${rows.size}",
                )
            }
        }

        // B: recent_display_cache -> exactly one recent_groups row
        val groupKeys = groups.map { it.groupKey }.toSet()
        displayRows.forEach { row ->
            if (row.groupKey !in groupKeys) {
                issues += Issue(
                    groupKey = row.groupKey,
                    reason = IssueReason.EXTRA_DISPLAY_ROW,
                    detail = "callId=${row.callId}",
                )
            }
        }

        // C/D/E per group
        val membershipCallIdsNeeded = mutableSetOf<Int>()
        val membershipByGroup = LinkedHashMap<String, List<Long>>()
        groups.forEach { group ->
            val callIds = database.recentGroupCallDao().getCallIdsForGroup(mode.dbValue, group.groupKey)
            membershipByGroup[group.groupKey] = callIds
            callIds.forEach { membershipCallIdsNeeded += it.toInt() }

            if (callIds.size != group.callCount) {
                issues += Issue(
                    groupKey = group.groupKey,
                    reason = IssueReason.CALL_COUNT_MISMATCH,
                    detail = "membership=${callIds.size} groupCallCount=${group.callCount}",
                )
            }
            if (group.latestCallId != 0L && group.latestCallId !in callIds) {
                issues += Issue(
                    groupKey = group.groupKey,
                    reason = IssueReason.LATEST_CALL_NOT_IN_MEMBERSHIP,
                    detail = "latestCallId=${group.latestCallId}",
                )
            }
        }

        val callsById: Map<Int, CallLogEntity> = if (membershipCallIdsNeeded.isEmpty()) {
            emptyMap()
        } else {
            database.callLogDao().getByCallIds(membershipCallIdsNeeded.toList()).associateBy { it.callId }
        }

        groups.forEach { group ->
            val callIds = membershipByGroup[group.groupKey].orEmpty()
            if (callIds.isEmpty()) return@forEach
            val maxTs = callIds.mapNotNull { callsById[it.toInt()]?.startTS }.maxOrNull()
            if (maxTs != null && maxTs != group.latestTimestamp) {
                issues += Issue(
                    groupKey = group.groupKey,
                    reason = IssueReason.LATEST_TIMESTAMP_MISMATCH,
                    detail = "groupTs=${group.latestTimestamp} maxMembershipTs=$maxTs",
                )
            }
        }

        return Result(
            mode = mode,
            issues = issues,
            groupCount = groups.size,
            displayRowCount = displayRows.size,
        )
    }
}
