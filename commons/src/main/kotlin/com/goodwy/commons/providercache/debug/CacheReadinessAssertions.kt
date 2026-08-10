package com.goodwy.commons.providercache.debug

import android.util.Log
import com.goodwy.commons.providercache.display.DisplayCacheReadiness

/**
 * Debug-only readiness / authority invariants. Never changes production paths.
 */
object CacheReadinessAssertions {

    private const val TAG = "CacheReadinessAssert"

    fun assertReadyEmptyRequiresRawSync(
        domain: String,
        readiness: DisplayCacheReadiness,
        rawSyncComplete: Boolean,
    ) {
        if (!ProviderCacheDebugLogger.isEnabled) return
        if (readiness != DisplayCacheReadiness.READY_EMPTY) return
        if (!rawSyncComplete) {
            val msg = "READY_EMPTY without rawSyncComplete domain=$domain"
            runCatching { Log.e(TAG, msg) }
            check(false) { msg }
        }
    }

    fun assertRoomAuthoritativeInvariants(
        domain: String,
        allowed: Boolean,
        readiness: DisplayCacheReadiness,
        fallbackActive: Boolean,
        repairRequired: Boolean,
    ) {
        if (!ProviderCacheDebugLogger.isEnabled || !allowed) return
        val readinessOk = readiness == DisplayCacheReadiness.READY_EMPTY ||
            readiness == DisplayCacheReadiness.READY_WITH_DATA
        if (!readinessOk || fallbackActive || repairRequired) {
            val msg =
                "Room authoritative invariants failed domain=$domain readiness=$readiness " +
                    "fallbackActive=$fallbackActive repairRequired=$repairRequired"
            runCatching { Log.e(TAG, msg) }
            check(false) { msg }
        }
    }
}
