package com.goodwy.commons.providercache.validation

import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.display.RecentAuthorityMismatchStore
import com.goodwy.commons.providercache.display.ComparableRecentGroup
import com.goodwy.commons.providercache.display.ComparableRecentGroupDeriver
import com.goodwy.commons.providercache.display.RecentAuthorityComparator
import com.goodwy.commons.providercache.display.RecentGroupDualWriteValidator
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.display.RelationalRecentDisplaySnapshotBuilder
import com.goodwy.commons.providercache.display.RelationalRecentsGroupingFlags
import com.goodwy.commons.providercache.display.RelationalRecentsReadMode
import com.goodwy.commons.providercache.display.RelationalReadAuthorityGate
import com.goodwy.commons.providercache.display.RelationalReadBlockReason
import com.goodwy.commons.providercache.grouping.RecentGroupingRepairCompletion
import com.goodwy.commons.providercache.validation.CacheDebugCommands.maskPhone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Debug commands for relational recents grouping read-authority readiness.
 */
object RecentGroupingDebugCommands {

    private const val LOG_PREFIX = "CacheDebug"

    suspend fun dumpRelationalRecentGroup(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        key: String,
    ): String = withContext(Dispatchers.IO) {
        val group = database.recentGroupDao().getGroup(mode.dbValue, key)
            ?: return@withContext "$LOG_PREFIX relationalGroup mode=${mode.name} key=${maskPhone(key)} missing"
        val callIds = database.recentGroupCallDao().getCallIdsForGroup(mode.dbValue, key)
        val numbers = database.recentGroupNumberDao().getNumbersForGroup(mode.dbValue, key)
        buildString {
            appendLine("$LOG_PREFIX relationalGroup mode=${mode.name} key=${maskPhone(key)}")
            appendLine("  contactId=${group.displayContactId} count=${group.callCount}")
            appendLine("  latestCall=${group.latestCallId} ts=${group.latestTimestamp}")
            appendLine("  primary=${maskPhone(group.primaryNumber)}")
            appendLine("  callIds=${callIds.joinToString(",")}")
            appendLine("  numbers=${numbers.joinToString(",") { maskPhone(it) }}")
        }
    }

    suspend fun dumpLegacyRecentGroup(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        key: String,
    ): String = withContext(Dispatchers.IO) {
        val rows = database.recentDisplayCacheDao().getByGroupKey(key, mode.dbValue)
        if (rows.isEmpty()) {
            return@withContext "$LOG_PREFIX legacyGroup mode=${mode.name} key=${maskPhone(key)} missing"
        }
        buildString {
            appendLine("$LOG_PREFIX legacyGroup mode=${mode.name} key=${maskPhone(key)} rows=${rows.size}")
            rows.forEach { row ->
                appendLine(
                    "  callId=${row.callId} count=${row.callCount} contactId=${row.contactID} " +
                        "ids=${row.groupedCallIds} primary=${maskPhone(row.phoneNumber)}",
                )
            }
        }
    }

    suspend fun compareRecentGrouping(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        rowLimit: Int = 10_000,
    ): String = withContext(Dispatchers.IO) {
        val result = RecentGroupDualWriteValidator.validateDualWrite(database, mode, rowLimit)
        formatCompareResult(mode, result.valid, result.semanticMismatches, result.oldGroupCount, result.newGroupCount)
    }

    suspend fun compareRecentGroup(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        semanticKey: String,
        rowLimit: Int = 10_000,
    ): String = withContext(Dispatchers.IO) {
        val legacy = ComparableRecentGroupDeriver.fromDisplayCache(database, mode, rowLimit)
        val relational = ComparableRecentGroupDeriver.fromRelational(database, mode, rowLimit)
        val old = legacy[semanticKey]
        val new = relational[semanticKey]
        if (old == null && new == null) {
            return@withContext "$LOG_PREFIX compareGroup mode=${mode.name} key=${maskPhone(semanticKey)} missingBoth"
        }
        val mismatches = RecentAuthorityComparator.compareSemanticGroups(
            mode,
            if (old != null) mapOf(semanticKey to old) else emptyMap(),
            if (new != null) mapOf(semanticKey to new) else emptyMap(),
        ).mismatches
        formatCompareResult(mode, mismatches.isEmpty(), mismatches, 1, 1)
    }

    suspend fun validateRelationalRecentGrouping(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        rowLimit: Int = 10_000,
    ): String = withContext(Dispatchers.IO) {
        val dual = RecentGroupDualWriteValidator.validateDualWrite(database, mode, rowLimit)
        val repair = RecentGroupingRepairCompletion.evaluate(database, mode, rowLimit)
        buildString {
            appendLine("$LOG_PREFIX validateRelational mode=${mode.name} dualWriteValid=${dual.valid}")
            appendLine("  repairCanClear=${repair.canClearRepairRequired}")
            if (repair.failures.isNotEmpty()) {
                appendLine("  failures=${repair.failures.joinToString(";")}")
            }
            appendLine(formatCompareResult(mode, dual.valid, dual.semanticMismatches, dual.oldGroupCount, dual.newGroupCount))
        }
    }

    suspend fun repairRelationalRecentGrouping(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        repairHandler: suspend (RecentGroupingMode) -> Unit,
        rowLimit: Int = 10_000,
    ): String = withContext(Dispatchers.IO) {
        repairHandler(mode)
        val evaluation = RecentGroupingRepairCompletion.evaluate(database, mode, rowLimit)
        "$LOG_PREFIX repairRelational mode=${mode.name} canClear=${evaluation.canClearRepairRequired} " +
            "failures=${evaluation.failures.size}"
    }

    suspend fun setRelationalReadModeForDebug(
        database: ProviderCacheDatabase,
        mode: RelationalRecentsReadMode,
        isDebugBuild: Boolean = true,
        groupingMode: RecentGroupingMode = RecentGroupingMode.BY_NUMBER,
    ): String = withContext(Dispatchers.IO) {
        if (mode == RelationalRecentsReadMode.RELATIONAL_DEBUG) {
            val gate = RelationalReadAuthorityGate.evaluateRelationalDebug(
                database = database,
                mode = groupingMode,
                isDebugBuild = isDebugBuild,
            )
            val result = RelationalReadAuthorityGate.trySetReadModeForDebug(mode, gate)
            if (!result.allowed) {
                val reason = result.blockReason?.name ?: RelationalReadBlockReason.VALIDATION_FAILED.name
                return@withContext "$LOG_PREFIX relationalReadBlocked reason=$reason effective=${result.effectiveMode}"
            }
            return@withContext "$LOG_PREFIX setRelationalReadMode=${result.effectiveMode} enabled=${RelationalRecentsGroupingFlags.RELATIONAL_RECENTS_GROUPING_ENABLED}"
        }
        RelationalRecentsGroupingFlags.setReadModeForDebug(mode)
        "$LOG_PREFIX setRelationalReadMode=$mode enabled=${RelationalRecentsGroupingFlags.RELATIONAL_RECENTS_GROUPING_ENABLED}"
    }

    fun setRelationalReadModeForDebug(mode: RelationalRecentsReadMode): String {
        if (mode == RelationalRecentsReadMode.RELATIONAL_DEBUG) {
            val result = RelationalReadAuthorityGate.trySetReadModeForDebug(mode, null)
            val reason = result.blockReason?.name ?: RelationalReadBlockReason.ENRICHMENT_INCOMPLETE.name
            return "$LOG_PREFIX relationalReadBlocked reason=$reason effective=${result.effectiveMode}"
        }
        RelationalRecentsGroupingFlags.setReadModeForDebug(mode)
        return "$LOG_PREFIX setRelationalReadMode=$mode enabled=${RelationalRecentsGroupingFlags.RELATIONAL_RECENTS_GROUPING_ENABLED}"
    }

    fun dumpRecentAuthorityMismatches(): String = RecentAuthorityMismatchStore.dump()

    fun clearRecentAuthorityMismatches(): String {
        RecentAuthorityMismatchStore.clear()
        return "$LOG_PREFIX recentAuthorityMismatches cleared"
    }

    suspend fun dumpRecentCacheInvariant(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode = RecentGroupingMode.BY_NUMBER,
    ): String = withContext(Dispatchers.IO) {
        val result = RecentDisplayRelationalConsistencyValidator.validate(database, mode)
        if (result.deferred) {
            RecentAuthorityMismatchStore.setLastConsistencyDeferred(result.summary())
        } else {
            RecentAuthorityMismatchStore.setLastConsistencyResult(result.valid, result.summary())
        }
        buildString {
            appendLine("$LOG_PREFIX ${result.summary()}")
            result.issues.take(20).forEach { issue ->
                appendLine(
                    "  issue key=${maskPhone(issue.groupKey)} reason=${issue.reason.name} " +
                        "detail=${issue.detail}",
                )
            }
        }.trimEnd()
    }

    suspend fun validateRecentDisplayRelationalConsistency(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        rowLimit: Int = 10_000,
    ): String = withContext(Dispatchers.IO) {
        val result = RecentDisplayRelationalConsistencyValidator.validate(database, mode, rowLimit)
        if (result.deferred) {
            RecentAuthorityMismatchStore.setLastConsistencyDeferred(result.summary())
        } else {
            RecentAuthorityMismatchStore.setLastConsistencyResult(result.valid, result.summary())
        }
        buildString {
            appendLine("$LOG_PREFIX validateRecentDisplayRelationalConsistency ${result.summary()}")
            result.issues.take(20).forEach { issue ->
                appendLine(
                    "  issue key=${maskPhone(issue.groupKey)} reason=${issue.reason.name} " +
                        "detail=${issue.detail}",
                )
            }
        }.trimEnd()
    }

    suspend fun compareDisplaySnapshots(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        rowLimit: Int = 10_000,
    ): String = withContext(Dispatchers.IO) {
        val result = RelationalRecentDisplaySnapshotBuilder.compareWithLegacyDisplay(database, mode, rowLimit)
        formatCompareResult(mode, result.valid, emptyList(), result.legacyGroupCount, result.relationalGroupCount)
    }

    private fun formatCompareResult(
        mode: RecentGroupingMode,
        valid: Boolean,
        mismatches: List<com.goodwy.commons.providercache.display.ComparableRecentGroupMismatch>,
        legacyGroups: Int,
        relationalGroups: Int,
    ): String = buildString {
        appendLine(
            "$LOG_PREFIX recentAuthorityCompare mode=${mode.name} legacyGroups=$legacyGroups " +
                "relationalGroups=$relationalGroups mismatches=${mismatches.size} valid=$valid",
        )
        mismatches.take(10).forEach { mismatch ->
            appendLine(
                "  mismatch key=${maskPhone(mismatch.semanticKey)} field=${mismatch.field} " +
                    "old=${maskPhone(mismatch.oldValue)} new=${maskPhone(mismatch.newValue)}",
            )
        }
    }
}
