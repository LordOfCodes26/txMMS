package com.goodwy.commons.providercache.display

/**
 * Consistent warm Recents display snapshot loaded from Room in one IO pass.
 */
data class LoadedRecentWarmSnapshot(
    val version: Long,
    val readiness: DisplayCacheReadiness,
    val mode: Int,
    val rows: List<RecentDisplayListRow>,
)
