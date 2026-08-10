package com.goodwy.commons.providercache.startup

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger
import java.util.concurrent.atomic.AtomicLong

/** Shared startup session id for structured first-paint / repair logs. */
object StartupSessionLogger {
    private const val TAG = "startupSession"

    private val sessionId = AtomicLong(0L)
    private val processStartElapsedMs: Long =
        runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L)

    @Volatile
    private var sessionStartElapsedMs: Long = processStartElapsedMs

    fun beginSession(): Long {
        val id = sessionId.incrementAndGet()
        sessionStartElapsedMs = runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L)
        log(domain = "CORE", stage = "SESSION_START", extra = "sessionId=$id pid=${Process.myPid()}")
        return id
    }

    fun currentSessionId(): Long = sessionId.get().coerceAtLeast(1L)

    fun elapsedSinceProcessStartMs(): Long =
        runCatching { SystemClock.elapsedRealtime() - processStartElapsedMs }.getOrDefault(0L)

    fun elapsedSinceSessionStartMs(): Long =
        runCatching { SystemClock.elapsedRealtime() - sessionStartElapsedMs }.getOrDefault(0L)

    fun log(
        domain: String,
        stage: String,
        extra: String = "",
        thread: String = Thread.currentThread().name,
    ) {
        val session = currentSessionId()
        val elapsed = elapsedSinceProcessStartMs()
        val msg = buildString {
            append("startupSession=$session domain=$domain stage=$stage")
            append(" elapsedMs=$elapsed thread=$thread")
            if (extra.isNotEmpty()) append(' ').append(extra)
        }
        try {
            Log.d(TAG, msg)
        } catch (_: RuntimeException) {
            // JVM unit tests
        }
        ProviderCacheDebugLogger.log(msg)
    }
}
