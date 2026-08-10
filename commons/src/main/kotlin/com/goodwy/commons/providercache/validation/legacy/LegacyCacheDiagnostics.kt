package com.goodwy.commons.providercache.validation.legacy

import android.util.Log

/** Structured logging when legacy disk/memory caches are read, written, or evicted (Phase L/5). */
object LegacyCacheDiagnostics {

    private const val TAG = "LegacyCache"

    fun logRead(cache: String, allowed: Boolean, detail: String = "") {
        if (allowed) LegacyCacheCounters.recordRead()
        val suffix = if (detail.isEmpty()) "" else " detail=$detail"
        Log.d(TAG, "read cache=$cache allowed=$allowed$suffix")
    }

    fun logWrite(cache: String, allowed: Boolean, rowCount: Int = 0) {
        if (allowed) LegacyCacheCounters.recordWrite()
        Log.d(TAG, "write cache=$cache allowed=$allowed rows=$rowCount")
    }

    fun logPaintAttempt(cache: String, blocked: Boolean, reason: String = "") {
        LegacyCacheCounters.recordPaintAttempt(blocked)
        val suffix = if (reason.isEmpty()) "" else " reason=$reason"
        Log.d(TAG, "paint cache=$cache blocked=$blocked$suffix")
    }

    fun logEvicted(cache: String, reason: String = "room_authoritative") {
        Log.d(TAG, "evict cache=$cache reason=$reason")
    }

    fun logSkipped(path: String, reason: String) {
        Log.d(TAG, "skip path=$path reason=$reason")
    }
}
