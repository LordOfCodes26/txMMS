package com.goodwy.commons.providercache.debug

import android.util.Log

/**
 * Debug timing thresholds — logs warnings when exceeded; never crashes production.
 */
object CachePerformanceMonitor {

    private const val TAG = "CachePerf"

    const val CONTACTS_DISPLAY_QUERY_MS = 100L
    const val RECENTS_DISPLAY_QUERY_MS = 50L
    const val DATE_HEADER_BIND_MS = 5L
    const val AVATAR_BIND_MS = 8L

    private val reconcileCountByVersion = mutableMapOf<String, Int>()

    fun recordContactsDisplayQuery(durationMs: Long, rowCount: Int = 0) {
        if (durationMs > CONTACTS_DISPLAY_QUERY_MS) {
            Log.w(TAG, "contactsDisplayQuery slowMs=$durationMs rows=$rowCount threshold=$CONTACTS_DISPLAY_QUERY_MS")
        }
    }

    fun recordRecentsDisplayQuery(durationMs: Long, rowCount: Int = 0) {
        if (durationMs > RECENTS_DISPLAY_QUERY_MS) {
            Log.w(TAG, "recentsDisplayQuery slowMs=$durationMs rows=$rowCount threshold=$RECENTS_DISPLAY_QUERY_MS")
        }
    }

    fun recordDateHeaderBind(durationMs: Long) {
        if (durationMs > DATE_HEADER_BIND_MS) {
            Log.w(TAG, "dateHeaderBind slowMs=$durationMs threshold=$DATE_HEADER_BIND_MS")
        }
    }

    fun recordAvatarBind(durationMs: Long) {
        if (durationMs > AVATAR_BIND_MS) {
            Log.w(TAG, "avatarBind slowMs=$durationMs threshold=$AVATAR_BIND_MS")
        }
    }

    fun recordReconcile(domain: String, version: Long) {
        val key = "$domain:$version"
        val count = (reconcileCountByVersion[key] ?: 0) + 1
        reconcileCountByVersion[key] = count
        if (count > 1) {
            Log.w(TAG, "duplicateReconcileCount domain=$domain version=$version count=$count")
        }
    }

    fun resetForDebug() {
        reconcileCountByVersion.clear()
    }

    fun dumpReconcileCounts(): String =
        if (reconcileCountByVersion.isEmpty()) {
            "none"
        } else {
            reconcileCountByVersion.entries.joinToString(" ") { "${it.key}=${it.value}" }
        }
}
