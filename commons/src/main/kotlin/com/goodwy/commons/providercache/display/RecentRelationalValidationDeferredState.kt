package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger

/**
 * Explicit repair/rebuild lifecycle for relational Recents grouping.
 *
 * Validation and repairRequired clearing must run only after [COMMITTED].
 * Does not change production authority flags.
 */
enum class RecentRelationalRepairState {
    IDLE,
    /** recent_groups / membership / numbers written (or about to be in the same txn). */
    RELATIONAL_WRITTEN,
    /** recent_display_cache rows prepared. */
    DISPLAY_WRITTEN,
    /** Transaction committed (relational + display durable). */
    COMMITTED,
    /** Post-commit dual-write / consistency / authority compare running. */
    VALIDATING,
    /** Validation finished for this cycle. */
    COMPLETE,
}

object RecentRelationalValidationDeferredState {

    @Volatile
    private var state: RecentRelationalRepairState = RecentRelationalRepairState.IDLE

    @Volatile
    private var deferredMode: RecentGroupingMode? = null

    @Volatile
    private var deferredReason: String = ""

    fun phase(): RecentRelationalRepairState = state

    /** @deprecated Use [phase]; kept for call sites that checked DEFERRED. */
    fun isDeferred(): Boolean =
        state == RecentRelationalRepairState.RELATIONAL_WRITTEN ||
            state == RecentRelationalRepairState.DISPLAY_WRITTEN

    fun markRelationalWritten(mode: RecentGroupingMode, reason: String = "relational_write") {
        transition(RecentRelationalRepairState.RELATIONAL_WRITTEN, mode, reason)
    }

    fun markDisplayWritten(mode: RecentGroupingMode, reason: String = "display_written") {
        transition(RecentRelationalRepairState.DISPLAY_WRITTEN, mode, reason)
    }

    fun markDisplayCommitted(mode: RecentGroupingMode, reason: String = "display_commit") {
        transition(RecentRelationalRepairState.COMMITTED, mode, reason)
    }

    fun markValidating(mode: RecentGroupingMode, reason: String = "validating") {
        transition(RecentRelationalRepairState.VALIDATING, mode, reason)
    }

    fun markComplete(mode: RecentGroupingMode? = deferredMode, reason: String = "complete") {
        transition(RecentRelationalRepairState.COMPLETE, mode, reason)
    }

    fun markIdle() {
        state = RecentRelationalRepairState.IDLE
        deferredMode = null
        deferredReason = ""
    }

    /** Skip alignment until the display cache transaction has committed. */
    fun shouldSkipAlignmentValidation(): Boolean =
        state == RecentRelationalRepairState.RELATIONAL_WRITTEN ||
            state == RecentRelationalRepairState.DISPLAY_WRITTEN

    fun dump(): String =
        "recentRelationalRepairState state=$state mode=${deferredMode?.name ?: "none"} " +
            "reason=$deferredReason"

    fun resetForDebug() {
        markIdle()
    }

    private fun transition(
        next: RecentRelationalRepairState,
        mode: RecentGroupingMode?,
        reason: String,
    ) {
        state = next
        if (mode != null) deferredMode = mode
        deferredReason = reason
        ProviderCacheDebugLogger.log(
            "recentRelationalRepairState state=$next mode=${deferredMode?.name ?: "none"} reason=$reason",
        )
    }
}

/** @deprecated Use [RecentRelationalRepairState]. */
typealias RecentRelationalValidationPhase = RecentRelationalRepairState
