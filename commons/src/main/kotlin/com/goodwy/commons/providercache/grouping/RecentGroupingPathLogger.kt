package com.goodwy.commons.providercache.grouping

import android.util.Log
import com.goodwy.commons.providercache.display.RecentGroupingMode

/**
 * Temporary path inventory logs for recents grouping migration (Phase 1).
 */
object RecentGroupingPathLogger {
    private const val TAG = "RecentGroupingPath"

    enum class Engine {
        LEGACY_HELPER,
        RELATIONAL,
        DEFAULT_RECENT_GROUPING_ENGINE,
        SQL,
        ADAPTER,
        DISPLAY_CACHE,
        BRIDGE_REGROUP,
    }

    enum class PathKind {
        PRODUCTION,
        DEBUG,
        MIGRATION,
    }

    fun logPath(
        source: String,
        engine: Engine,
        mode: RecentGroupingMode,
        inputRows: Int,
        alreadyGrouped: Boolean,
        kind: PathKind = PathKind.PRODUCTION,
    ) {
        Log.d(
            TAG,
            "recentGroupingPath source=$source engine=$engine mode=${mode.name} kind=$kind " +
                "rows=$inputRows alreadyGrouped=$alreadyGrouped",
        )
    }

    fun logInput(
        source: String,
        rows: Int,
        alreadyGrouped: Boolean,
    ) {
        Log.d(TAG, "recentGroupingInput source=$source rows=$rows alreadyGrouped=$alreadyGrouped")
    }

    fun logOutput(
        source: String,
        engine: Engine,
        mode: RecentGroupingMode,
        groups: Int,
    ) {
        Log.d(TAG, "recentGroupingOutput source=$source engine=$engine mode=${mode.name} groups=$groups")
    }
}
