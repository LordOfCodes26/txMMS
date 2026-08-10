package com.goodwy.commons.providercache.grouping

/**
 * Phase 1 inventory of recents grouping paths (migration reference).
 *
 * | Location | Mode | Membership | Display metadata | Kind | Replacement |
 * |----------|------|------------|------------------|------|-------------|
 * | DefaultRecentGroupingEngine.build/rebuildAffected | both | ENGINE | ENRICHER | production | authority |
 * | RecentDisplayCacheBuilder.buildEngineDisplaySnapshot | both | ENGINE | ENRICHER | production | authority |
 * | RecentDisplayCacheBuilder.rebuildFullSwap ENGINE_AUTHORITATIVE | both | ENGINE | ENRICHER | production | authority |
 * | RecentDisplayCacheBuilder.rebuildFullSwap LEGACY_SQL | BY_NUMBER | SQL | SQL | emergency fallback only | clear forceLegacySqlFallback |
 * | RecentDisplayCacheBuilder.rebuildFullSwap ENGINE_COMPARE | BY_NUMBER | ENGINE | SQL visible | debug soak | ENGINE_AUTHORITATIVE |
 * | RecentDisplayCacheBuilder.buildSqlBaselineDisplayRows | both | SQL | SQL | compare/fallback | engine only |
 * | RecentGroupingPreviewPipeline | both | ENGINE | ENRICHER | production | authority |
 * | RecentsDisplayBridge.submitGroupedSnapshot | both | pre-grouped cache | cache bind | production | no regroup |
 * | RecentsPagingBridge | both | deprecated | deprecated | deprecated | RecentsDisplayBridge |
 * | CallLogDao.getGroupedEntries* | both | SQL digits | SQL | compare/fallback | raw input only |
 * | RecentsHelper.groupCalls* | both | LEGACY | LEGACY | ERROR deprecated | engine |
 * | RecentAffectedGroupRebuilder + checksum planner | both | ENGINE | ENRICHER | production | authority |
 * | RecentCallsAdapter | both | precomputed | bind only | production | no Room/provider |
 * | RecentRelativeTimeController | n/a | n/a | live time bind | production | payload-only refresh |
 */
object RecentGroupingPathInventory
