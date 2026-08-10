package com.goodwy.commons.providercache.display

/**
 * Debug-only read authority modes for relational recents rollout.
 */
enum class RelationalRecentsReadMode {
    /** Production default — engine builds display cache for both grouping modes. */
    LEGACY_ONLY,
    /** Build/compare both snapshots; UI reads legacy display cache. */
    COMPARE_ONLY,
    /** Relational tables are authority for display-cache construction (debug only). */
    RELATIONAL_DEBUG,
}

object RelationalRecentsGroupingFlags {

    /**
     * When true, builders treat [recent_groups] + membership as grouping truth.
     * Production default is true after BY_NUMBER engine flip; set false only for emergency rollback.
     */
    @Volatile
    var RELATIONAL_RECENTS_GROUPING_ENABLED: Boolean = true

    /**
     * When true, dual-write validation runs after each relational rebuild.
     * Mismatches only mark [repairRequired] when display authority is [ENGINE_AUTHORITATIVE]
     * (see [RecentGroupDualWriteValidator.markRepairRequiredIfInvalid]).
     */
    const val DUAL_WRITE_VALIDATION_ENABLED: Boolean = true

    /**
     * When true, post-commit authority compare runs and increments [CompareOnlySoakCounters.compareTotal]
     * without changing [readMode]. Production default remains [RelationalRecentsReadMode.LEGACY_ONLY].
     * Soak must remain observe-only for LEGACY_SQL / ENGINE_COMPARE display authority.
     */
    const val AUTHORITY_COMPARE_SOAK_ENABLED: Boolean = true

    /** Debug read/compare mode — never RELATIONAL_DEBUG in release without explicit opt-in. */
    @Volatile
    var readMode: RelationalRecentsReadMode = RelationalRecentsReadMode.LEGACY_ONLY

    /**
     * Staged fallback: after engine validation/checksum failure on BY_NUMBER, force [LEGACY_SQL]
     * until cleared by a successful engine-authoritative commit.
     */
    @Volatile
    var forceLegacySqlFallback: Boolean = false

    fun setReadModeForDebug(mode: RelationalRecentsReadMode) {
        readMode = mode
    }

    fun shouldCompareAuthority(): Boolean =
        AUTHORITY_COMPARE_SOAK_ENABLED ||
            readMode == RelationalRecentsReadMode.COMPARE_ONLY ||
            readMode == RelationalRecentsReadMode.RELATIONAL_DEBUG

    fun buildFromRelational(): Boolean =
        RELATIONAL_RECENTS_GROUPING_ENABLED ||
            readMode == RelationalRecentsReadMode.RELATIONAL_DEBUG

    fun activateLegacySqlFallback(reason: String) {
        forceLegacySqlFallback = true
        android.util.Log.w(
            "recentDisplayBuildAuthority",
            "forceLegacySqlFallback=true reason=$reason",
        )
    }

    fun clearLegacySqlFallback() {
        if (forceLegacySqlFallback) {
            forceLegacySqlFallback = false
            android.util.Log.i(
                "recentDisplayBuildAuthority",
                "forceLegacySqlFallback=false after successful engine commit",
            )
        }
    }
}
