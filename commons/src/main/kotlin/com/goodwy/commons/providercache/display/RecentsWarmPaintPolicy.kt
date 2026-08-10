package com.goodwy.commons.providercache.display

import android.util.Log
import com.goodwy.commons.providercache.startup.StartupSessionLogger

enum class DisplayPaintReason {
    CLEAN_DISPLAY_CACHE,
    /** Non-empty snapshot may paint while dirty/repair rebuild continues in background. */
    STALE_DISPLAY_CACHE,
    AUTHORITATIVE_EMPTY,
    DISPLAY_DIRTY,
    DISPLAY_REPAIR_REQUIRED,
    DISPLAY_STRUCTURAL_FAILURE,
    PERMISSION_BLOCKED,
    NO_DISPLAY_SNAPSHOT,
    RAW_ONLY_REPAIR_PENDING,
}

data class DisplayPaintDecision(
    val allowed: Boolean,
    val reason: DisplayPaintReason,
)

/**
 * Decides whether a warm [recent_display_cache] snapshot may paint before raw repair completes.
 * RAW and DISPLAY readiness are independent.
 *
 * [displayRepairRequired] must reflect display-authority corruption only — soak compare
 * mismatches under LEGACY_SQL must not set repairRequired (see dual-write validator).
 */
object RecentsWarmPaintPolicy {

    private const val TAG = "recentsWarmPaint"

    fun evaluate(
        displayVersion: Long,
        displayRows: Int,
        displayDirty: Boolean,
        displayRepairRequired: Boolean,
        hasCallLogPermission: Boolean,
        rawRepairRequired: Boolean = false,
        rawDirty: Boolean = false,
        providerFallbackActive: Boolean = false,
    ): DisplayPaintDecision {
        if (!hasCallLogPermission) {
            return decision(false, DisplayPaintReason.PERMISSION_BLOCKED)
        }
        if (providerFallbackActive) {
            return decision(false, DisplayPaintReason.NO_DISPLAY_SNAPSHOT)
        }
        if (displayVersion <= 0L) {
            return decision(false, DisplayPaintReason.NO_DISPLAY_SNAPSHOT)
        }
        // Prefer painting a non-empty snapshot over a spinner while rebuild/repair runs.
        // Empty + dirty/repair still blocks — there is nothing useful to show yet.
        if (displayRows > 0) {
            return when {
                displayDirty || displayRepairRequired ->
                    decision(true, DisplayPaintReason.STALE_DISPLAY_CACHE)
                else -> decision(true, DisplayPaintReason.CLEAN_DISPLAY_CACHE)
            }
        }
        if (displayDirty) {
            return decision(false, DisplayPaintReason.DISPLAY_DIRTY)
        }
        if (displayRepairRequired) {
            return decision(false, DisplayPaintReason.DISPLAY_REPAIR_REQUIRED)
        }
        if (displayRows == 0 && displayVersion > 0L) {
            return decision(true, DisplayPaintReason.AUTHORITATIVE_EMPTY)
        }
        return decision(false, DisplayPaintReason.NO_DISPLAY_SNAPSHOT)
    }

    fun logDecision(
        decision: DisplayPaintDecision,
        rawState: RawCacheReadiness,
        displayReadiness: DisplayCacheReadiness,
        displayVersion: Long,
        displayRows: Int,
    ) {
        val msg =
            "recentsWarmPaintDecision allowed=${decision.allowed} reason=${decision.reason.name} " +
                "rawState=${rawState.name} displayState=${displayReadiness.name} " +
                "version=$displayVersion rows=$displayRows"
        try {
            Log.d(TAG, msg)
        } catch (_: RuntimeException) {
        }
        StartupSessionLogger.log(
            domain = "RECENTS",
            stage = "WARM_PAINT_DECISION",
            extra = msg,
        )
    }

    private fun decision(allowed: Boolean, reason: DisplayPaintReason): DisplayPaintDecision =
        DisplayPaintDecision(allowed = allowed, reason = reason)
}
