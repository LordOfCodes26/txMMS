package com.goodwy.commons.providercache.display

/**
 * Central decision for which source commits authoritative Recents display rows on full build.
 */
enum class RecentDisplayBuildAuthority {
    /** SQL-built display rows are visible; engine runs in parallel for dual-write/compare. */
    LEGACY_SQL,
    /** SQL rows visible; engine snapshot compared but not committed as display authority. */
    ENGINE_COMPARE,
    /** Engine-enriched rows commit relational + display tables atomically. */
    ENGINE_AUTHORITATIVE,
}

object RecentDisplayBuildAuthorityResolver {

    /**
     * Production default ([LEGACY_ONLY] + enabled flag): engine is authoritative for both modes.
     * [COMPARE_ONLY] / [RELATIONAL_DEBUG] debug read modes take precedence over the production flag
     * so soak tooling still works. [forceLegacySqlFallback] reverts BY_NUMBER to [LEGACY_SQL]
     * after a validation failure until the next clean engine commit.
     */
    fun resolveForFullBuild(mode: RecentGroupingMode): RecentDisplayBuildAuthority {
        if (RelationalRecentsGroupingFlags.forceLegacySqlFallback &&
            mode == RecentGroupingMode.BY_NUMBER
        ) {
            return RecentDisplayBuildAuthority.LEGACY_SQL
        }
        return when (RelationalRecentsGroupingFlags.readMode) {
            RelationalRecentsReadMode.COMPARE_ONLY ->
                RecentDisplayBuildAuthority.ENGINE_COMPARE
            RelationalRecentsReadMode.RELATIONAL_DEBUG ->
                RecentDisplayBuildAuthority.ENGINE_AUTHORITATIVE
            RelationalRecentsReadMode.LEGACY_ONLY ->
                // Production target: engine owns both modes. LEGACY_ONLY name retained for
                // tooling; emergency rollback sets RELATIONAL_RECENTS_GROUPING_ENABLED=false
                // and would need an explicit LEGACY_SQL path — use forceLegacySqlFallback instead.
                if (RelationalRecentsGroupingFlags.buildFromRelational()) {
                    RecentDisplayBuildAuthority.ENGINE_AUTHORITATIVE
                } else {
                    // Emergency rollback: BY_CONTACT stays engine (already migrated); BY_NUMBER SQL.
                    when (mode) {
                        RecentGroupingMode.BY_CONTACT ->
                            RecentDisplayBuildAuthority.ENGINE_AUTHORITATIVE
                        RecentGroupingMode.BY_NUMBER ->
                            RecentDisplayBuildAuthority.LEGACY_SQL
                    }
                }
        }
    }

    fun shouldBuildSqlBaseline(authority: RecentDisplayBuildAuthority): Boolean =
        authority == RecentDisplayBuildAuthority.LEGACY_SQL ||
            authority == RecentDisplayBuildAuthority.ENGINE_COMPARE

    fun commitsEngineDisplay(authority: RecentDisplayBuildAuthority): Boolean =
        authority == RecentDisplayBuildAuthority.ENGINE_AUTHORITATIVE
}
