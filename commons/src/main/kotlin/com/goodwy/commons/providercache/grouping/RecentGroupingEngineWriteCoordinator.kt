package com.goodwy.commons.providercache.grouping

import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.display.AffectedRecentGroups
import com.goodwy.commons.providercache.display.RecentGroupBuildContextLoader
import com.goodwy.commons.providercache.display.RecentGroupRelationalBuilder
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.entities.CallLogEntity

/**
 * Production write-side grouping — all cache mutations must route membership through
 * [DefaultRecentGroupingEngine], not [RecentGroupRelationalBuilder] directly.
 */
object RecentGroupingEngineWriteCoordinator {

    private const val TAG = "RecentAffectedBuild"
    private val engine = DefaultRecentGroupingEngine

    suspend fun buildFull(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        limit: Int = Int.MAX_VALUE,
    ): EngineWriteBundle {
        val calls = RecentGroupBuildContextLoader.loadCalls(database, limit)
        val snapshot = RecentContactResolutionSnapshotLoader.load(database)
        RecentGroupingPathLogger.logPath(
            source = "DISPLAY_CACHE_BUILDER",
            engine = RecentGroupingPathLogger.Engine.DEFAULT_RECENT_GROUPING_ENGINE,
            mode = mode,
            inputRows = calls.size,
            alreadyGrouped = false,
        )
        val result = engine.build(mode, calls, snapshot, limit)
        RecentGroupingPathLogger.logOutput(
            source = "DISPLAY_CACHE_BUILDER",
            engine = RecentGroupingPathLogger.Engine.DEFAULT_RECENT_GROUPING_ENGINE,
            mode = mode,
            groups = result.groups.size,
        )
        return EngineWriteBundle(
            mode = mode,
            calls = calls,
            snapshot = snapshot,
            result = result,
            usedFullBuild = true,
        )
    }

    suspend fun buildAffected(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        affected: AffectedRecentGroups,
        limit: Int = 10_000,
    ): EngineWriteBundle = when (val outcome = buildAffectedResult(database, mode, affected, limit)) {
        is AffectedBuildResult.Incremental -> outcome.bundle
        is AffectedBuildResult.FullFallback -> outcome.bundle
        is AffectedBuildResult.Failed -> throw IllegalStateException(
            "Affected build failed reason=${outcome.reason} detail=${outcome.detail}",
        )
    }

    suspend fun buildAffectedResult(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        affected: AffectedRecentGroups,
        limit: Int = 10_000,
    ): AffectedBuildResult {
        if (affected.allGroupKeys.isEmpty()) {
            val failed = AffectedBuildResult.Failed(
                reason = AffectedFallbackReason.AFFECTED_SET_INCOMPLETE,
                detail = "empty affected keys",
            )
            logAffectedResult(failed)
            return failed
        }
        val calls = RecentGroupBuildContextLoader.loadCalls(database, limit)
        val snapshot = RecentContactResolutionSnapshotLoader.load(database)
        RecentGroupingPathLogger.logPath(
            source = "DISPLAY_CACHE_BUILDER_AFFECTED",
            engine = RecentGroupingPathLogger.Engine.DEFAULT_RECENT_GROUPING_ENGINE,
            mode = mode,
            inputRows = calls.size,
            alreadyGrouped = false,
        )
        val incremental = engine.rebuildAffected(mode, affected, calls, snapshot, limit)
        if (affected.allGroupKeys.isNotEmpty() && incremental.groups.isEmpty() && calls.isNotEmpty()) {
            val fallback = runFullFallback(
                mode = mode,
                calls = calls,
                snapshot = snapshot,
                limit = limit,
                reason = AffectedFallbackReason.EMPTY_AFFECTED_RESULT,
                detail = "affected rebuild produced zero groups",
            )
            logAffectedResult(fallback)
            return fallback
        }
        if (!incremental.validation.valid) {
            val fallback = runFullFallback(
                mode = mode,
                calls = calls,
                snapshot = snapshot,
                limit = limit,
                reason = AffectedFallbackReason.VALIDATION_FAILED,
                detail = incremental.validation.failures.joinToString(";"),
            )
            logAffectedResult(fallback)
            return fallback
        }
        val bundle = EngineWriteBundle(
            mode = mode,
            calls = calls,
            snapshot = snapshot,
            result = incremental,
            usedFullBuild = false,
        )
        if (com.goodwy.commons.providercache.display.RelationalRecentsGroupingFlags.shouldCompareAuthority()) {
            val equivalence = compareAffectedWithFullBuild(
                mode = mode,
                affectedKeys = affected.allGroupKeys,
                affectedResult = incremental,
                fullResult = engine.build(mode, calls, snapshot, limit),
            )
            if (!equivalence.valid) {
                val fallback = runFullFallback(
                    mode = mode,
                    calls = calls,
                    snapshot = snapshot,
                    limit = limit,
                    reason = AffectedFallbackReason.MEMBERSHIP_GAP,
                    detail = equivalence.mismatches.joinToString(";"),
                )
                logAffectedResult(fallback)
                return fallback
            }
        }
        val success = AffectedBuildResult.Incremental(bundle)
        logAffectedResult(success)
        return success
    }

    private suspend fun runFullFallback(
        mode: RecentGroupingMode,
        calls: List<CallLogEntity>,
        snapshot: RecentContactResolutionSnapshot,
        limit: Int,
        reason: AffectedFallbackReason,
        detail: String,
    ): AffectedBuildResult.FullFallback {
        val full = engine.build(mode, calls, snapshot, limit)
        val bundle = EngineWriteBundle(
            mode = mode,
            calls = calls,
            snapshot = snapshot,
            result = full,
            usedFullBuild = true,
        )
        return AffectedBuildResult.FullFallback(
            bundle = bundle,
            reason = reason,
            detail = detail,
        )
    }

    private fun logAffectedResult(result: AffectedBuildResult) {
        AffectedBuildCounters.record(result)
        val label = when (result) {
            is AffectedBuildResult.Incremental -> "INCREMENTAL"
            is AffectedBuildResult.FullFallback -> "FULL_FALLBACK"
            is AffectedBuildResult.Failed -> "FAILED"
        }
        val reason = when (result) {
            is AffectedBuildResult.Incremental -> ""
            is AffectedBuildResult.FullFallback -> " reason=${result.reason} detail=${result.detail}"
            is AffectedBuildResult.Failed -> " reason=${result.reason} detail=${result.detail}"
        }
        android.util.Log.d(TAG, "recentAffectedBuild result=$label$reason")
    }

    /**
     * Debug/compare helper — affected rebuild must match full build for touched keys.
     */
    fun compareAffectedWithFullBuild(
        mode: RecentGroupingMode,
        affectedKeys: Set<String>,
        affectedResult: RecentGroupingResult,
        fullResult: RecentGroupingResult,
    ): AffectedBuildEquivalence {
        val mismatches = mutableListOf<String>()
        val affectedOnly = affectedResult.groups.filter { it.groupKey in affectedKeys }
        val fullOnly = fullResult.groups.filter { it.groupKey in affectedKeys }
        if (affectedOnly.size != fullOnly.size) {
            mismatches += "groupCount affected=${affectedOnly.size} full=${fullOnly.size}"
        }
        val fullByKey = fullOnly.associateBy { it.groupKey }
        affectedOnly.forEach { group ->
            val full = fullByKey[group.groupKey]
            if (full == null) {
                mismatches += "missingInFull:${group.groupKey}"
                return@forEach
            }
            if (group.callCount != full.callCount) {
                mismatches += "callCount:${group.groupKey} a=${group.callCount} f=${full.callCount}"
            }
            if (group.latestCallId != full.latestCallId) {
                mismatches += "latestCallId:${group.groupKey}"
            }
            if (group.latestTimestamp != full.latestTimestamp) {
                mismatches += "latestTs:${group.groupKey}"
            }
            if (group.displayContactId != full.displayContactId) {
                mismatches += "displayContactId:${group.groupKey}"
            }
            if (group.primaryNumber != full.primaryNumber) {
                mismatches += "primaryNumber:${group.groupKey}"
            }
            val aMembers = affectedResult.memberships
                .filter { it.groupKey == group.groupKey }
                .map { it.callId.toInt() }
                .toSet()
            val fMembers = fullResult.memberships
                .filter { it.groupKey == group.groupKey }
                .map { it.callId.toInt() }
                .toSet()
            if (aMembers != fMembers) {
                mismatches += "members:${group.groupKey} a=$aMembers f=$fMembers"
            }
        }
        return AffectedBuildEquivalence(
            valid = mismatches.isEmpty(),
            affectedKeyCount = affectedKeys.size,
            mismatches = mismatches,
        )
    }
}

data class EngineWriteBundle(
    val mode: RecentGroupingMode,
    val calls: List<CallLogEntity>,
    val snapshot: RecentContactResolutionSnapshot,
    val result: RecentGroupingResult,
    val usedFullBuild: Boolean,
) {
    fun requireValid(): RecentGroupingResult {
        check(result.validation.valid) {
            "Engine grouping invalid mode=${mode.name} failures=${result.validation.failures}"
        }
        return result
    }

    fun toRelationalBuildResult(): RecentGroupRelationalBuilder.BuildResult =
        result.toRelationalBuildResult()
}

data class AffectedBuildEquivalence(
    val valid: Boolean,
    val affectedKeyCount: Int,
    val mismatches: List<String> = emptyList(),
)

fun RecentGroupingResult.toRelationalBuildResult(): RecentGroupRelationalBuilder.BuildResult =
    RecentGroupRelationalBuilder.BuildResult(
        groups = groups,
        calls = memberships,
        numbers = numbers,
    )
