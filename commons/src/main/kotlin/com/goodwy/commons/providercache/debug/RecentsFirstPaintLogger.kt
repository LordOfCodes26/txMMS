package com.goodwy.commons.providercache.debug

import android.os.SystemClock
import android.util.Log

/** Structured first-paint timings for the Recents tab warm-cache path. */
object RecentsFirstPaintLogger {
    private const val TAG = "recentsFirstPaint"

    @Volatile
    private var sessionStartElapsedMs: Long = 0L

    fun beginSession() {
        sessionStartElapsedMs = runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L)
        stage(
            "PROCESS_START",
            "startupSession=${com.goodwy.commons.providercache.startup.StartupSessionLogger.currentSessionId()}",
        )
    }

    fun sessionTotalMs(): Long =
        if (sessionStartElapsedMs <= 0L) 0L else elapsedMs(sessionStartElapsedMs)

    fun stage(stage: String, extra: String = "") {
        val thread = Thread.currentThread().name
        val session = com.goodwy.commons.providercache.startup.StartupSessionLogger.currentSessionId()
        val elapsed = com.goodwy.commons.providercache.startup.StartupSessionLogger.elapsedSinceProcessStartMs()
        val msg = buildString {
            append("recentsFirstPaint stage=$stage startupSession=$session elapsedMs=$elapsed thread=$thread")
            if (extra.isNotEmpty()) append(' ').append(extra)
        }
        try {
            Log.d(TAG, msg)
        } catch (_: RuntimeException) {
            // JVM unit tests
        }
        ProviderCacheDebugLogger.log(msg)
    }

    fun publish(version: Long, rows: Int, mode: String) {
        stage(
            "PUBLISH",
            "recentsSnapshotPublish version=$version rows=$rows mode=$mode",
        )
    }

    fun skipped(reason: String, version: Long = -1L) {
        val extra = if (version >= 0L) {
            "recentsSnapshotSkipped reason=$reason version=$version"
        } else {
            "recentsSnapshotSkipped reason=$reason"
        }
        stage("SKIPPED", extra)
    }

    fun blockedLegacyPaint(source: String) {
        stage("LEGACY_BLOCKED", "source=$source")
    }

    fun elapsedMs(startElapsedRealtime: Long): Long =
        runCatching { SystemClock.elapsedRealtime() - startElapsedRealtime }.getOrDefault(0L)
}
