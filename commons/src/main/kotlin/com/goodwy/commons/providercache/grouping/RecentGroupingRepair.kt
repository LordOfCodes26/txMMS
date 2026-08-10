package com.goodwy.commons.providercache.grouping

import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.display.RecentGroupDualWriteValidator
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.display.RecentRelationalValidationDeferredState
import com.goodwy.commons.providercache.display.RelationalRecentsGroupingFlags
import com.goodwy.commons.providercache.transaction.ProviderCacheTransactions

/**
 * Rebuilds relational recents grouping + display snapshot for one mode (Phase 9).
 * Validation runs only after the display+relational transaction commits.
 */
object RecentGroupingRepair {

    suspend fun repairRecentGrouping(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        displayRows: List<com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity>,
        limit: Int,
        mutationReason: String = "REPAIR_RECENT_GROUPING",
    ): RecentGroupDualWriteValidator.Result {
        val bundle = RecentGroupingEngineWriteCoordinator.buildFull(database, mode, limit).requireValid()
        val relational = bundle.toRelationalBuildResult()
        RecentRelationalValidationDeferredState.markRelationalWritten(mode, mutationReason)
        RecentRelationalValidationDeferredState.markDisplayWritten(mode, mutationReason)
        ProviderCacheTransactions.replaceRecentGroupingMode(
            database = database,
            replacement = com.goodwy.commons.providercache.display.RecentGroupingReplacement(
                mode = mode,
                relational = relational,
                displayRows = displayRows,
                displayLimit = limit,
                mutationReason = mutationReason,
                rowCount = displayRows.size,
            ),
        )
        RecentRelationalValidationDeferredState.markDisplayCommitted(mode, mutationReason)
        RecentRelationalValidationDeferredState.markValidating(mode, mutationReason)
        val result = if (RelationalRecentsGroupingFlags.DUAL_WRITE_VALIDATION_ENABLED) {
            RecentGroupDualWriteValidator.markRepairRequiredIfInvalid(database, mode)
        } else {
            RecentGroupDualWriteValidator.Result(valid = true, 0, 0, emptyList())
        }
        if (result.valid) {
            RecentGroupingRepairCompletion.clearRepairRequiredIfValid(database, mode, limit)
        }
        RecentRelationalValidationDeferredState.markComplete(mode, mutationReason)
        RecentRelationalValidationDeferredState.markIdle()
        return result
    }
}
