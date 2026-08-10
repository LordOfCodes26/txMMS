package com.goodwy.commons.providercache.debug

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/** Structured first-paint timings for the Contacts tab full-list path. */
object ContactsFirstPaintLogger {
    private const val TAG = "contactsFirstPaint"

    @Volatile
    private var sessionStartElapsedMs: Long = 0L

    /** Guards [firstFrameWithRows] so only the first frame of a session claims to be first paint. */
    private val firstFrameReported = AtomicBoolean(false)

    fun beginSession() {
        sessionStartElapsedMs = runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L)
        firstFrameReported.set(false)
    }

    fun sessionTotalMs(): Long =
        if (sessionStartElapsedMs <= 0L) 0L else elapsedMs(sessionStartElapsedMs)

    /**
     * Reports the first frame that carried rows — once per session.
     *
     * The publish path this sits on is not startup-only: clearing a search query republishes the
     * full list through the same code, and that used to re-emit FIRST_FRAME_WITH_ROWS with
     * [sessionTotalMs] still measured from process start. A captured log showed
     * `FIRST_FRAME_WITH_ROWS totalMs=450267 rows=2` seven and a half minutes in, which makes the
     * metric useless for the one thing it exists to measure.
     *
     * Closing the session after reporting means a later publish neither re-reports nor revives the
     * stale start stamp; only a real [beginSession] opens a new one.
     */
    fun firstFrameWithRows(rows: Int) {
        if (sessionStartElapsedMs <= 0L) return
        if (!firstFrameReported.compareAndSet(false, true)) return
        stage("FIRST_FRAME_WITH_ROWS", "totalMs=${sessionTotalMs()} rows=$rows")
        sessionStartElapsedMs = 0L
    }

    fun stage(stage: String, extra: String = "") {
        val thread = Thread.currentThread().name
        val msg = if (extra.isEmpty()) {
            "contactsFirstPaint stage=$stage thread=$thread"
        } else {
            "contactsFirstPaint stage=$stage $extra thread=$thread"
        }
        try {
            Log.d(TAG, msg)
        } catch (_: RuntimeException) {
            // JVM unit tests
        }
        ProviderCacheDebugLogger.log(msg)
    }

    fun publish(version: Long, rows: Int, sections: Int, mode: String) {
        stage(
            "PUBLISH",
            "contactsSnapshotPublish version=$version rows=$rows sections=$sections mode=$mode",
        )
    }

    fun skipped(reason: String, version: Long = -1L) {
        val extra = if (version >= 0L) {
            "contactsSnapshotSkipped reason=$reason version=$version"
        } else {
            "contactsSnapshotSkipped reason=$reason"
        }
        stage("SKIPPED", extra)
    }

    fun elapsedMs(startElapsedRealtime: Long): Long =
        runCatching { SystemClock.elapsedRealtime() - startElapsedRealtime }.getOrDefault(0L)
}
