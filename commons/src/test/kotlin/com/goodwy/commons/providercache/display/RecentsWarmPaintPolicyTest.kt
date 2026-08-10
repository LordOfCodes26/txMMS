package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.startup.StartupOrchestrator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentsWarmPaintPolicyTest {

    @After
    fun tearDown() {
        DisplayCacheReadinessTracker.resetForColdStart()
        StartupDomainOwner.reset()
    }

    @Test
    fun rawDirty_displayCleanWithRows_allowsWarmPaint() {
        val decision = RecentsWarmPaintPolicy.evaluate(
            displayVersion = 5L,
            displayRows = 2,
            displayDirty = false,
            displayRepairRequired = false,
            hasCallLogPermission = true,
            rawRepairRequired = true,
            rawDirty = true,
        )
        assertTrue(decision.allowed)
        assertEquals(DisplayPaintReason.CLEAN_DISPLAY_CACHE, decision.reason)
    }

    @Test
    fun displayDirty_withRows_allowsStaleWarmPaint() {
        val decision = RecentsWarmPaintPolicy.evaluate(
            displayVersion = 5L,
            displayRows = 2,
            displayDirty = true,
            displayRepairRequired = false,
            hasCallLogPermission = true,
        )
        assertTrue(decision.allowed)
        assertEquals(DisplayPaintReason.STALE_DISPLAY_CACHE, decision.reason)
    }

    @Test
    fun displayRepairRequired_withRows_allowsStaleWarmPaint() {
        val decision = RecentsWarmPaintPolicy.evaluate(
            displayVersion = 5L,
            displayRows = 2,
            displayDirty = false,
            displayRepairRequired = true,
            hasCallLogPermission = true,
        )
        assertTrue(decision.allowed)
        assertEquals(DisplayPaintReason.STALE_DISPLAY_CACHE, decision.reason)
    }

    @Test
    fun displayDirty_withZeroRows_blocksWarmPaint() {
        val decision = RecentsWarmPaintPolicy.evaluate(
            displayVersion = 5L,
            displayRows = 0,
            displayDirty = true,
            displayRepairRequired = false,
            hasCallLogPermission = true,
        )
        assertFalse(decision.allowed)
        assertEquals(DisplayPaintReason.DISPLAY_DIRTY, decision.reason)
    }

    @Test
    fun displayRepairRequired_withZeroRows_blocksWarmPaint() {
        val decision = RecentsWarmPaintPolicy.evaluate(
            displayVersion = 5L,
            displayRows = 0,
            displayDirty = false,
            displayRepairRequired = true,
            hasCallLogPermission = true,
        )
        assertFalse(decision.allowed)
        assertEquals(DisplayPaintReason.DISPLAY_REPAIR_REQUIRED, decision.reason)
    }

    @Test
    fun providerFallbackActive_blocksWarmPaint() {
        val decision = RecentsWarmPaintPolicy.evaluate(
            displayVersion = 5L,
            displayRows = 2,
            displayDirty = false,
            displayRepairRequired = false,
            hasCallLogPermission = true,
            providerFallbackActive = true,
        )
        assertFalse(decision.allowed)
        assertEquals(DisplayPaintReason.NO_DISPLAY_SNAPSHOT, decision.reason)
    }

    @Test
    fun cleanEmptyWithVersion_isAuthoritativeEmpty() {
        val decision = RecentsWarmPaintPolicy.evaluate(
            displayVersion = 3L,
            displayRows = 0,
            displayDirty = false,
            displayRepairRequired = false,
            hasCallLogPermission = true,
            rawRepairRequired = true,
        )
        assertTrue(decision.allowed)
        assertEquals(DisplayPaintReason.AUTHORITATIVE_EMPTY, decision.reason)
    }

    @Test
    fun seedRecentsDisplay_preservesReadinessThroughColdStart() {
        DisplayCacheReadinessTracker.seedRecentsDisplay(DisplayCacheReadiness.READY_WITH_DATA)
        DisplayCacheReadinessTracker.setRecentsRawReadiness(RawCacheReadiness.REPAIR_REQUIRED)
        StartupOrchestrator.markColdStart()
        assertEquals(DisplayCacheReadiness.READY_WITH_DATA, DisplayCacheReadinessTracker.recentsReadiness())
        assertEquals(RawCacheReadiness.NOT_STARTED, DisplayCacheReadinessTracker.recentsRawReadiness())
        DisplayCacheReadinessTracker.resetForColdStart()
    }
}
