package com.goodwy.commons.providercache.debug

import com.goodwy.commons.providercache.display.CacheDomain
import com.goodwy.commons.providercache.display.DisplayCacheReadiness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StartupTimelineAndSoakInstrumentationTest {

    @Before
    fun setUp() {
        ProviderCacheDebugLogger.isEnabled = true
        StartupTimeline.resetForDebug()
        CompareOnlySoakCounters.reset()
    }

    @After
    fun tearDown() {
        ProviderCacheDebugLogger.isEnabled = false
        StartupTimeline.resetForDebug()
        CompareOnlySoakCounters.reset()
    }

    @Test
    fun startupTimeline_recordsProcessStartAndAuthorityReady() {
        StartupTimeline.markProcessStart()
        StartupTimeline.markRawSyncStart(CacheDomain.RECENTS)
        StartupTimeline.markRawSyncEnd(CacheDomain.RECENTS)
        StartupTimeline.markDisplayBuildStart(CacheDomain.RECENTS)
        StartupTimeline.markDisplayBuildEnd(CacheDomain.RECENTS)
        StartupTimeline.markAuthorityReady(CacheDomain.RECENTS, "READY_EMPTY")
        StartupTimeline.markFirstUiPublish(CacheDomain.RECENTS, rows = 0)
        StartupTimeline.markFirstUiPublish(CacheDomain.RECENTS, rows = 5) // ignored

        assertEquals(StartupTimeline.State.FIRST_UI_PUBLISH, StartupTimeline.lastState(CacheDomain.RECENTS))
        assertTrue(StartupTimeline.elapsedMs() >= 0L)
        assertTrue(StartupTimeline.dump().contains("recentsState=FIRST_UI_PUBLISH"))
    }

    @Test
    fun compareOnlySoakCounters_accumulate() {
        CompareOnlySoakCounters.recordAuthorityCompare(valid = true, displayMismatchCount = 0)
        CompareOnlySoakCounters.recordAuthorityCompare(valid = false, displayMismatchCount = 2)
        CompareOnlySoakCounters.recordDualWrite(valid = false, mismatchCount = 3)

        val snap = CompareOnlySoakCounters.snapshot()
        assertEquals(2L, snap.compareTotal)
        assertEquals(1L, snap.compareMismatch)
        assertEquals(2L, snap.displayMismatch)
        assertEquals(1L, snap.dualWriteTotal)
        assertEquals(3L, snap.dualWriteMismatch)
        assertTrue(CompareOnlySoakCounters.dump().contains("compareTotal=2"))
        assertTrue(CompareOnlySoakCounters.dump().contains("dualWriteTotal=1"))
    }

    @Test
    fun readyEmptyAssertion_passesWhenRawSyncComplete() {
        CacheReadinessAssertions.assertReadyEmptyRequiresRawSync(
            domain = "RECENTS",
            readiness = DisplayCacheReadiness.READY_EMPTY,
            rawSyncComplete = true,
        )
    }

    @Test(expected = IllegalStateException::class)
    fun readyEmptyAssertion_failsWhenRawSyncIncomplete() {
        CacheReadinessAssertions.assertReadyEmptyRequiresRawSync(
            domain = "RECENTS",
            readiness = DisplayCacheReadiness.READY_EMPTY,
            rawSyncComplete = false,
        )
    }

    @Test(expected = IllegalStateException::class)
    fun roomAuthorityAssertion_failsWhenFallbackActive() {
        CacheReadinessAssertions.assertRoomAuthoritativeInvariants(
            domain = "RECENTS",
            allowed = true,
            readiness = DisplayCacheReadiness.READY_WITH_DATA,
            fallbackActive = true,
            repairRequired = false,
        )
    }
}
