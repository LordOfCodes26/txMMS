package com.goodwy.commons.providercache.grouping

import com.goodwy.commons.providercache.display.AffectedRecentGroups
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.entities.CallLogEntity

/**
 * Single production authority for recents call membership and group identity.
 *
 * Does not produce UI text, drawables, section headers, or relative timestamps.
 */
interface RecentGroupingEngine {

    suspend fun build(
        mode: RecentGroupingMode,
        calls: List<CallLogEntity>,
        contactSnapshot: RecentContactResolutionSnapshot,
        limit: Int = Int.MAX_VALUE,
    ): RecentGroupingResult

    suspend fun rebuildAffected(
        mode: RecentGroupingMode,
        affected: AffectedRecentGroups,
        calls: List<CallLogEntity>,
        contactSnapshot: RecentContactResolutionSnapshot,
        limit: Int = Int.MAX_VALUE,
    ): RecentGroupingResult
}
