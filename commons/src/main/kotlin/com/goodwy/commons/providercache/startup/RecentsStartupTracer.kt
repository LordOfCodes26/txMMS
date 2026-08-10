package com.goodwy.commons.providercache.startup

import android.util.Log

/** Structured Recents warm-start stage tracing with shared session id. */
object RecentsStartupTracer {
    private const val TAG = "recentsStartup"
    private const val SLOW_BLOCK_MS = 16L

    @Volatile
    private var lastStageElapsedMs: Long = 0L
    @Volatile
    private var lastStageName: String = "PROCESS_START"

    fun stage(stage: String, extra: String = "") {
        val elapsed = StartupSessionLogger.elapsedSinceProcessStartMs()
        val gap = elapsed - lastStageElapsedMs
        if (gap > SLOW_BLOCK_MS && lastStageName.isNotEmpty()) {
            Log.d(
                TAG,
                "recentsStartup session=${StartupSessionLogger.currentSessionId()} " +
                    "stage=GAP_BLOCKED blockedAfter=$lastStageName gapMs=$gap thread=${Thread.currentThread().name}",
            )
        }
        lastStageName = stage
        lastStageElapsedMs = elapsed
        val msg = buildString {
            append("recentsStartup session=").append(StartupSessionLogger.currentSessionId())
            append(" stage=").append(stage)
            append(" elapsedMs=").append(elapsed)
            if (extra.isNotEmpty()) append(' ').append(extra)
        }
        Log.d(TAG, msg)
        StartupSessionLogger.log(domain = "RECENTS_STARTUP", stage = stage, extra = extra)
    }

    fun resetForTests() {
        lastStageElapsedMs = 0L
        lastStageName = "PROCESS_START"
    }
}
