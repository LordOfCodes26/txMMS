package com.goodwy.commons.providercache.grouping

import com.goodwy.commons.providercache.display.AffectedRecentGroups
import com.goodwy.commons.providercache.display.RecentGroupRelationalBuilder
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.entities.CallLogEntity

/**
 * Production grouping engine — delegates membership to relational tables semantics.
 */
object DefaultRecentGroupingEngine : RecentGroupingEngine {

    override suspend fun build(
        mode: RecentGroupingMode,
        calls: List<CallLogEntity>,
        contactSnapshot: RecentContactResolutionSnapshot,
        limit: Int,
    ): RecentGroupingResult {
        RecentGroupingPathLogger.logPath(
            source = "DefaultRecentGroupingEngine.build",
            engine = RecentGroupingPathLogger.Engine.DEFAULT_RECENT_GROUPING_ENGINE,
            mode = mode,
            inputRows = calls.size,
            alreadyGrouped = false,
        )
        if (calls.isEmpty()) {
            return RecentGroupingResult(
                groups = emptyList(),
                memberships = emptyList(),
                numbers = emptyList(),
                validation = RecentGroupingValidationResult.empty(),
            )
        }
        val context = contactSnapshot.toIdentityContext()
        val relational = RecentGroupRelationalBuilder.build(calls, mode, context, limit)
        val validation = RecentGroupingInvariantValidator.validate(
            mode = mode,
            eligibleCalls = calls,
            groups = relational.groups,
            memberships = relational.calls,
        )
        RecentGroupingPathLogger.logOutput(
            source = "DefaultRecentGroupingEngine.build",
            engine = RecentGroupingPathLogger.Engine.DEFAULT_RECENT_GROUPING_ENGINE,
            mode = mode,
            groups = relational.groups.size,
        )
        return RecentGroupingResult(
            groups = relational.groups,
            memberships = relational.calls,
            numbers = relational.numbers,
            validation = validation,
        )
    }

    override suspend fun rebuildAffected(
        mode: RecentGroupingMode,
        affected: AffectedRecentGroups,
        calls: List<CallLogEntity>,
        contactSnapshot: RecentContactResolutionSnapshot,
        limit: Int,
    ): RecentGroupingResult {
        RecentGroupingPathLogger.logPath(
            source = "DefaultRecentGroupingEngine.rebuildAffected",
            engine = RecentGroupingPathLogger.Engine.DEFAULT_RECENT_GROUPING_ENGINE,
            mode = mode,
            inputRows = calls.size,
            alreadyGrouped = false,
        )
        if (affected.allGroupKeys.isEmpty() || calls.isEmpty()) {
            return RecentGroupingResult(
                groups = emptyList(),
                memberships = emptyList(),
                numbers = emptyList(),
                validation = RecentGroupingValidationResult.empty(),
            )
        }
        val context = contactSnapshot.toIdentityContext()
        val affectedCallIds = calls.filter { call ->
            val identity = com.goodwy.commons.providercache.display.RecentGroupIdentityResolver
                .resolve(call, mode, context)
            identity.groupKey in affected.allGroupKeys ||
                identity.normalizedNumbers.any { it in affected.affectedNumbers }
        }.map { it.callId }.toSet()
        val relational = RecentGroupRelationalBuilder.buildForCallIds(
            allCalls = calls,
            affectedCallIds = affectedCallIds,
            mode = mode,
            context = context,
            affectedGroupKeys = affected.allGroupKeys,
            affectedNumbers = affected.affectedNumbers,
        )
        val regroupedCallIds = relational.calls.map { it.callId.toInt() }.toSet()
        val validation = RecentGroupingInvariantValidator.validate(
            mode = mode,
            eligibleCalls = calls.filter { it.callId in regroupedCallIds },
            groups = relational.groups,
            memberships = relational.calls,
        )
        RecentGroupingPathLogger.logOutput(
            source = "DefaultRecentGroupingEngine.rebuildAffected",
            engine = RecentGroupingPathLogger.Engine.DEFAULT_RECENT_GROUPING_ENGINE,
            mode = mode,
            groups = relational.groups.size,
        )
        return RecentGroupingResult(
            groups = relational.groups,
            memberships = relational.calls,
            numbers = relational.numbers,
            validation = validation,
        )
    }
}
