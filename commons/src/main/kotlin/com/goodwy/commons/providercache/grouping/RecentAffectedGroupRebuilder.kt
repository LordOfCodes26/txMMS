package com.goodwy.commons.providercache.grouping

import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.display.AffectedRecentGroups
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity
import com.goodwy.commons.providercache.transaction.ProviderCacheTransactions
import androidx.room.withTransaction

/**
 * Rebuilds all groups touched by a multi-group mutation in one transaction via
 * [DefaultRecentGroupingEngine].
 */
object RecentAffectedGroupRebuilder {

    suspend fun rebuildAffectedGroups(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        affected: AffectedRecentGroups,
        displayRows: List<RecentDisplayCacheEntity>,
        limit: Int = 10_000,
    ): AffectedBuildResult {
        if (affected.allGroupKeys.isEmpty()) {
            return AffectedBuildResult.Failed(
                reason = AffectedFallbackReason.AFFECTED_SET_INCOMPLETE,
                detail = "empty affected keys",
            )
        }
        val outcome = RecentGroupingEngineWriteCoordinator.buildAffectedResult(
            database = database,
            mode = mode,
            affected = affected,
            limit = limit,
        )
        // FullFallback carries a full-group bundle; never apply it with a partial affected
        // delete set — callers must run a true full swap.
        if (outcome !is AffectedBuildResult.Incremental) {
            return outcome
        }
        val bundle = outcome.bundle
        if (!bundle.result.validation.valid) {
            return AffectedBuildResult.Failed(
                reason = AffectedFallbackReason.VALIDATION_FAILED,
                detail = bundle.result.validation.failures.joinToString(";"),
            )
        }
        val relational = bundle.toRelationalBuildResult().withMembershipChecksums(mode)
        val groupDao = database.recentGroupDao()
        val displayDao = database.recentDisplayCacheDao()

        val plans = relational.groups.map { newGroup ->
            val existingGroup = groupDao.getGroup(mode.dbValue, newGroup.groupKey)
            val existingDisplay = displayDao.getByGroupKey(newGroup.groupKey, mode.dbValue).firstOrNull()
            val newDisplay = displayRows.firstOrNull { it.groupKey == newGroup.groupKey }
            val callIds = relational.calls.filter { it.groupKey == newGroup.groupKey }.map { it.callId }
            val numbers = relational.numbers.filter { it.groupKey == newGroup.groupKey }.map { it.normalizedNumber }
            RecentAffectedMutationPlanner.planGroupUpdate(
                mode = mode,
                existingGroup = existingGroup,
                newGroup = newGroup,
                newCallIds = callIds,
                newNumbers = numbers,
                existingDisplay = existingDisplay,
                newDisplay = newDisplay,
            )
        }
        if (plans.isNotEmpty() && plans.all { it.result == RecentAffectedMutationPlanner.MutationResult.NO_OP }) {
            return outcome
        }

        val membershipChangedKeys = plans
            .filter { it.result == RecentAffectedMutationPlanner.MutationResult.MEMBERSHIP_CHANGED }
            .map { it.groupKey }
            .toSet()
        val displayOnlyKeys = plans
            .filter { it.result == RecentAffectedMutationPlanner.MutationResult.DISPLAY_ONLY }
            .map { it.groupKey }
            .toSet()

        val touchedDisplayRows = when {
            membershipChangedKeys.isNotEmpty() -> displayRows.filter { row ->
                row.groupKey in membershipChangedKeys ||
                    row.groupKey in affected.allGroupKeys ||
                    row.groupKey in relational.groups.map { it.groupKey }
            }
            displayOnlyKeys.isNotEmpty() -> displayRows.filter { it.groupKey in displayOnlyKeys }
            else -> displayRows.filter { row ->
                row.groupKey in relational.groups.map { it.groupKey } || row.groupKey in affected.allGroupKeys
            }
        }

        if (membershipChangedKeys.isEmpty() && displayOnlyKeys.isNotEmpty()) {
            database.withTransaction {
                touchedDisplayRows.forEach { row -> displayDao.insertAll(listOf(row)) }
            }
            return outcome
        }

        ProviderCacheTransactions.replaceAffectedRecentGroups(
            database = database,
            mode = mode,
            affected = affected,
            relational = relational,
            displayRows = touchedDisplayRows,
        )
        return outcome
    }

    /**
     * Keeps relational grouping tables in sync after display-cache mutations.
     */
    suspend fun syncAffectedRelationalGroups(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        affected: AffectedRecentGroups,
        limit: Int = 10_000,
    ): AffectedBuildResult {
        if (affected.allGroupKeys.isEmpty()) {
            return AffectedBuildResult.Failed(
                reason = AffectedFallbackReason.AFFECTED_SET_INCOMPLETE,
                detail = "empty affected keys",
            )
        }
        val outcome = RecentGroupingEngineWriteCoordinator.buildAffectedResult(
            database = database,
            mode = mode,
            affected = affected,
            limit = limit,
        )
        if (outcome !is AffectedBuildResult.Incremental) {
            return outcome
        }
        val bundle = outcome.bundle
        if (!bundle.result.validation.valid) {
            return AffectedBuildResult.Failed(
                reason = AffectedFallbackReason.VALIDATION_FAILED,
                detail = bundle.result.validation.failures.joinToString(";"),
            )
        }
        ProviderCacheTransactions.replaceAffectedRecentGroupsRelationalOnly(
            database = database,
            mode = mode,
            affected = affected,
            relational = bundle.toRelationalBuildResult().withMembershipChecksums(mode),
        )
        return outcome
    }
}
