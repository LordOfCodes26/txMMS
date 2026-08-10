package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.display.RecentGroupingMode.BY_CONTACT
import com.goodwy.commons.providercache.display.RecentGroupingMode.BY_NUMBER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecentDisplayBuildAuthorityTest {

    @Before
    fun resetFlags() {
        RelationalRecentsGroupingFlags.RELATIONAL_RECENTS_GROUPING_ENABLED = true
        RelationalRecentsGroupingFlags.forceLegacySqlFallback = false
        RelationalRecentsGroupingFlags.readMode = RelationalRecentsReadMode.LEGACY_ONLY
    }

    @Test
    fun legacyOnly_byContact_isEngineAuthoritative() {
        assertEquals(
            RecentDisplayBuildAuthority.ENGINE_AUTHORITATIVE,
            RecentDisplayBuildAuthorityResolver.resolveForFullBuild(BY_CONTACT),
        )
    }

    @Test
    fun legacyOnly_byNumber_isEngineAuthoritative() {
        assertEquals(
            RecentDisplayBuildAuthority.ENGINE_AUTHORITATIVE,
            RecentDisplayBuildAuthorityResolver.resolveForFullBuild(BY_NUMBER),
        )
    }

    @Test
    fun byNumber_fallsBackToLegacySql_whenForceFallbackActive() {
        RelationalRecentsGroupingFlags.forceLegacySqlFallback = true
        assertEquals(
            RecentDisplayBuildAuthority.LEGACY_SQL,
            RecentDisplayBuildAuthorityResolver.resolveForFullBuild(BY_NUMBER),
        )
        // BY_CONTACT stays on engine even during BY_NUMBER fallback.
        assertEquals(
            RecentDisplayBuildAuthority.ENGINE_AUTHORITATIVE,
            RecentDisplayBuildAuthorityResolver.resolveForFullBuild(BY_CONTACT),
        )
    }

    @Test
    fun compareOnly_byNumber_isEngineCompare_evenWhenRelationalFlagOn() {
        RelationalRecentsGroupingFlags.RELATIONAL_RECENTS_GROUPING_ENABLED = true
        RelationalRecentsGroupingFlags.readMode = RelationalRecentsReadMode.COMPARE_ONLY
        assertEquals(
            RecentDisplayBuildAuthority.ENGINE_COMPARE,
            RecentDisplayBuildAuthorityResolver.resolveForFullBuild(BY_NUMBER),
        )
    }

    @Test
    fun emergencyRollback_byNumber_isLegacySql_whenFlagOff() {
        RelationalRecentsGroupingFlags.RELATIONAL_RECENTS_GROUPING_ENABLED = false
        RelationalRecentsGroupingFlags.readMode = RelationalRecentsReadMode.LEGACY_ONLY
        assertEquals(
            RecentDisplayBuildAuthority.LEGACY_SQL,
            RecentDisplayBuildAuthorityResolver.resolveForFullBuild(BY_NUMBER),
        )
        assertEquals(
            RecentDisplayBuildAuthority.ENGINE_AUTHORITATIVE,
            RecentDisplayBuildAuthorityResolver.resolveForFullBuild(BY_CONTACT),
        )
    }

    @Test
    fun activateAndClearLegacySqlFallback() {
        RelationalRecentsGroupingFlags.activateLegacySqlFallback("test")
        assertTrue(RelationalRecentsGroupingFlags.forceLegacySqlFallback)
        RelationalRecentsGroupingFlags.clearLegacySqlFallback()
        assertFalse(RelationalRecentsGroupingFlags.forceLegacySqlFallback)
    }

    @Test
    fun commitsEngineDisplay_onlyAuthoritative() {
        assertEquals(
            true,
            RecentDisplayBuildAuthorityResolver.commitsEngineDisplay(
                RecentDisplayBuildAuthority.ENGINE_AUTHORITATIVE,
            ),
        )
        assertEquals(
            false,
            RecentDisplayBuildAuthorityResolver.commitsEngineDisplay(
                RecentDisplayBuildAuthority.LEGACY_SQL,
            ),
        )
        assertEquals(
            false,
            RecentDisplayBuildAuthorityResolver.commitsEngineDisplay(
                RecentDisplayBuildAuthority.ENGINE_COMPARE,
            ),
        )
    }
}
