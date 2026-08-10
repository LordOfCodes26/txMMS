package com.goodwy.commons.providercache.debug

import android.os.SystemClock
import android.util.Log

/**
 * Debug-only slow-block tracing for startup main-thread work (>16 ms).
 */
object StartupMainThreadTracer {
    private const val TAG = "startupMainThread"
    private const val SLOW_BLOCK_MS = 16L

    fun <T> trace(name: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        return try {
            block()
        } finally {
            val duration = SystemClock.elapsedRealtime() - start
            if (duration >= SLOW_BLOCK_MS) {
                try {
                    Log.d(TAG, "startupMainThreadBlock name=$name durationMs=$duration")
                } catch (_: RuntimeException) {
                }
            }
        }
    }
}
