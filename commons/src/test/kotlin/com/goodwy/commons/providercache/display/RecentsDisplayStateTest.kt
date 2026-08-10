package com.goodwy.commons.providercache.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentsDisplayStateTest {

    @Test
    fun needsReconcile_versionMatchNoDeltas_noOp() {
        val state = RecentsDisplayState(
            cacheVersion = 8L,
            lastVisibleVersion = 8L,
            adapterLoaded = true,
        )
        assertFalse(state.needsReconcile())
    }

    @Test
    fun effectiveRecentsVisibleVersion_prefersPendingFrameCommit() {
        assertEquals(12L, effectiveRecentsVisibleVersion(10L, 12L))
        assertEquals(10L, effectiveRecentsVisibleVersion(10L, -1L))
        assertEquals(10L, effectiveRecentsVisibleVersion(10L, 0L))
        assertEquals(5L, effectiveRecentsVisibleVersion(3L, 5L))
    }

    @Test
    fun needsReconcile_pendingFrameMakesVersionMatch_noOp() {
        val effective = effectiveRecentsVisibleVersion(
            lastVisibleVersion = 7L,
            pendingFrameCommitVersion = 9L,
        )
        val state = RecentsDisplayState(
            cacheVersion = 9L,
            lastVisibleVersion = effective,
            adapterLoaded = true,
        )
        assertFalse(state.needsReconcile())
    }

    @Test
    fun needsReconcile_pendingDeltas() {
        val state = RecentsDisplayState(
            cacheVersion = 8L,
            lastVisibleVersion = 8L,
            adapterLoaded = true,
            pendingDeltas = listOf(
                PendingRecentDelta(
                    groupKey = "5551234",
                    latestCallId = 1,
                    mode = RecentDeltaMode.INSERT,
                    groupByContact = false,
                ),
            ),
        )
        assertTrue(state.needsReconcile())
    }

    @Test
    fun pickRecentsReconcileReason_prefersManualRefresh() {
        val picked = pickRecentsReconcileReason(
            setOf(
                RecentsReconcileReason.STARTUP,
                RecentsReconcileReason.SYNC_COMPLETE,
                RecentsReconcileReason.MANUAL_REFRESH,
            ),
        )
        assertEquals(RecentsReconcileReason.MANUAL_REFRESH, picked)
    }

    @Test
    fun pickRecentsReconcileReason_sameVersionReasonsCollapse() {
        val picked = pickRecentsReconcileReason(
            setOf(
                RecentsReconcileReason.STARTUP,
                RecentsReconcileReason.SYNC_COMPLETE,
                RecentsReconcileReason.APP_RESUME,
                RecentsReconcileReason.TAB_ENTER,
            ),
        )
        assertEquals(RecentsReconcileReason.SYNC_COMPLETE, picked)
    }

    @Test
    fun recentsChangeNeedsFullReload_insertWithDelta_safe() {
        assertFalse(
            DisplayCacheRebuildReason.CALL_LOG_INSERTED.recentsChangeNeedsFullReload(
                hasDeltas = true,
                forceFull = false,
            ),
        )
    }

    @Test
    fun recentsChangeNeedsFullReload_deleteRequiresFull() {
        assertTrue(
            DisplayCacheRebuildReason.CALL_LOG_DELETED.recentsChangeNeedsFullReload(
                hasDeltas = true,
                forceFull = false,
            ),
        )
    }

    @Test
    fun shouldAdvanceRecentsVisibleVersion_onlyOnFullSuccess() {
        assertTrue(shouldAdvanceRecentsVisibleVersion(3, 3, adapterPublishSucceeded = true))
        assertFalse(shouldAdvanceRecentsVisibleVersion(2, 3, adapterPublishSucceeded = true))
        assertFalse(shouldAdvanceRecentsVisibleVersion(3, 3, adapterPublishSucceeded = false))
    }

    @Test
    fun shouldDeferRecentsReconcile_searchOrDialpad() {
        assertTrue(shouldDeferRecentsReconcile(uiStateAllowsReconcile = false))
        assertFalse(shouldDeferRecentsReconcile(uiStateAllowsReconcile = true))
    }

    @Test
    fun shouldForceRecentsReconcileAfterBlockedMislinkRepair_onlyWhenBlockedWithRows() {
        assertTrue(
            shouldForceRecentsReconcileAfterBlockedMislinkRepair(
                uiBlocked = true,
                repairedRowCount = 3,
            ),
        )
        assertFalse(
            shouldForceRecentsReconcileAfterBlockedMislinkRepair(
                uiBlocked = false,
                repairedRowCount = 3,
            ),
        )
        assertFalse(
            shouldForceRecentsReconcileAfterBlockedMislinkRepair(
                uiBlocked = true,
                repairedRowCount = 0,
            ),
        )
    }
}
