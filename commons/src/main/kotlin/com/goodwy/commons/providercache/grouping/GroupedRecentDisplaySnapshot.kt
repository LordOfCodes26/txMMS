package com.goodwy.commons.providercache.grouping

import com.goodwy.commons.providercache.display.RecentDisplayListRow
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity

/**
 * Authoritative grouped display snapshot consumed by RecentsDisplayBridge.
 * Prefer [listRows] for warm first paint; [rows] remains for rebuild/engine paths.
 *
 * Stable adapter identity: groupingMode + groupKey (via RecentCall.groupingModeDb + groupKey).
 */
data class GroupedRecentDisplaySnapshot(
    val mode: RecentGroupingMode,
    val displayVersion: Long,
    val rows: List<RecentDisplayCacheEntity> = emptyList(),
    val listRows: List<RecentDisplayListRow> = emptyList(),
    val provisional: Boolean = false,
    val contentChecksum: Long = 0L,
) {
    val rowCount: Int get() = if (listRows.isNotEmpty()) listRows.size else rows.size
}
