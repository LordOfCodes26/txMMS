package com.goodwy.commons.providercache.startup

/**
 * Cold-start Contacts list paint policy after clear-storage / empty cache.
 *
 * The Contacts tab must show one complete snapshot (not a growing partial page).
 * Secondary indexes (phone/search) and call-log backfill must not gate first paint.
 */
object ContactsColdStartPaintPolicy {

    /**
     * Display-cache rebuild is allowed only after progressive provider→Room summary sync
     * has finished writing every page.
     */
    fun allowDisplayRebuild(progressiveSyncInProgress: Boolean): Boolean =
        !progressiveSyncInProgress

    /**
     * First paint is complete only when display rows match the full Room summary count
     * (empty device → both zero is also complete).
     */
    fun isCompleteSnapshotForFirstPaint(summaryCount: Int, displayRowCount: Int): Boolean {
        if (summaryCount < 0 || displayRowCount < 0) return false
        return displayRowCount == summaryCount
    }

    /** Work that must finish before the Contacts tab dismisses rebuild progress. */
    val criticalPathBeforeFirstPaint: List<String> = listOf(
        "raw_summaries",
        "display_cache",
    )

    /** Work deferred until after [criticalPathBeforeFirstPaint] so the full list paints sooner. */
    val deferredAfterFirstPaint: List<String> = listOf(
        "phone_index",
        "search_index",
        "call_log_backfill",
    )
}
