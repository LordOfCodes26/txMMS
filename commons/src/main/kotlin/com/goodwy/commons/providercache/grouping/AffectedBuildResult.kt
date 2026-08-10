package com.goodwy.commons.providercache.grouping

/**
 * Explicit outcome for affected engine builds — incremental bugs must not hide behind silent full fallback.
 * [FullFallback] must never be applied via [com.goodwy.commons.providercache.transaction.ProviderCacheTransactions.replaceAffectedRecentGroups]
 * with a partial affected key set; callers must run a true full display swap instead.
 */
enum class AffectedFallbackReason {
    AFFECTED_SET_INCOMPLETE,
    VALIDATION_FAILED,
    CONTACT_RESOLUTION_CHANGED,
    GROUP_KEY_CHANGED,
    MEMBERSHIP_GAP,
    EMPTY_AFFECTED_RESULT,
    INTERNAL_ERROR,
}

sealed class AffectedBuildResult {
    data class Incremental(
        val bundle: EngineWriteBundle,
    ) : AffectedBuildResult()

    data class FullFallback(
        val bundle: EngineWriteBundle,
        val reason: AffectedFallbackReason,
        val detail: String = "",
    ) : AffectedBuildResult()

    data class Failed(
        val reason: AffectedFallbackReason,
        val detail: String,
    ) : AffectedBuildResult()

    fun bundleOrNull(): EngineWriteBundle? = when (this) {
        is Incremental -> bundle
        is FullFallback -> bundle
        is Failed -> null
    }
}

object AffectedBuildCounters {
    @Volatile
    var affectedIncrementalCount: Long = 0L
        private set

    @Volatile
    var affectedFullFallbackCount: Long = 0L
        private set

    @Volatile
    var affectedFailureCount: Long = 0L
        private set

    fun record(result: AffectedBuildResult) {
        when (result) {
            is AffectedBuildResult.Incremental -> affectedIncrementalCount++
            is AffectedBuildResult.FullFallback -> affectedFullFallbackCount++
            is AffectedBuildResult.Failed -> affectedFailureCount++
        }
    }

    fun resetForDebug() {
        affectedIncrementalCount = 0L
        affectedFullFallbackCount = 0L
        affectedFailureCount = 0L
    }
}
