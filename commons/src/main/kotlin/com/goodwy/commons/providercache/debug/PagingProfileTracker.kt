package com.goodwy.commons.providercache.debug

/**
 * Tracks the slowest observed paging step when [ProviderCacheDebugLogger.isEnabled].
 */
object PagingProfileTracker {

    @Volatile
    var slowestStage: String = ""

    @Volatile
    var slowestMs: Long = 0L

    fun record(stage: String, durationMs: Long) {
        if (!ProviderCacheDebugLogger.isEnabled || durationMs <= slowestMs) return
        slowestMs = durationMs
        slowestStage = stage
    }

    fun reset() {
        slowestStage = ""
        slowestMs = 0L
    }
}
