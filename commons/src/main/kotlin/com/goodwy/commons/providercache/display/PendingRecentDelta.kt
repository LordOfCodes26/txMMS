package com.goodwy.commons.providercache.display

/**
 * Row-level Recents UI change produced after [recent_display_cache] updates.
 * Consumed by [RecentsDisplayBridge.reconcileRecentsUi] when applying GROUP_DELTA is safe.
 */
enum class RecentDeltaMode {
    INSERT,
    UPDATE,
    MOVE,
}

data class PendingRecentDelta(
    val groupKey: String,
    val latestCallId: Int,
    /** Previous representative call id when the group already existed in display cache. */
    val previousCallId: Int? = null,
    /** Adapter call-row index before apply; -1 when unknown or group not visible. */
    val oldPosition: Int = -1,
    /** Target call-row index after apply (0 = top). */
    val newPosition: Int = 0,
    val mode: RecentDeltaMode,
    val groupByContact: Boolean,
)

data class RecentDisplayCacheRebuildResult(
    val deltas: List<PendingRecentDelta> = emptyList(),
    val needsFullReload: Boolean = false,
) {
    companion object {
        val EMPTY = RecentDisplayCacheRebuildResult()
    }
}
