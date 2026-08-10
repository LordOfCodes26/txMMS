package com.goodwy.commons.providercache

import android.os.SystemClock

/**
 * Tracks recent UI interaction so background provider work (photo backfill, heavy index builds)
 * can defer until the user is idle.
 */
object ProviderCacheUserInteractionGate {

    private const val IDLE_MS = 2_000L

    @Volatile
    private var lastInteractionElapsedMs: Long = 0L

    @Volatile
    private var photoBackfillDeferred: Boolean = false

    /** Call on tab switches, dialpad input, and other high-priority UI paths. */
    fun markUserInteracting() {
        lastInteractionElapsedMs = SystemClock.elapsedRealtime()
    }

    fun isUserInteracting(): Boolean {
        val last = lastInteractionElapsedMs
        if (last == 0L) return false
        return SystemClock.elapsedRealtime() - last < IDLE_MS
    }

    fun deferPhotoBackfill() {
        photoBackfillDeferred = true
    }

    fun consumeDeferredPhotoBackfill(): Boolean {
        if (!photoBackfillDeferred) return false
        photoBackfillDeferred = false
        return true
    }

    /** Provider photo probes are allowed only when the user is not interacting. */
    fun allowProviderPhotoProbe(): Boolean = !isUserInteracting()
}
