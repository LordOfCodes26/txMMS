package com.goodwy.commons.providercache.startup

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupFirstPaintGateTest {

    @After
    fun tearDown() {
        StartupFirstPaintGate.resetForTests()
        StartupWorkPriorityCoordinator.resetForTests()
    }

    @Test
    fun contactsDataWorkNeverDeferredBehindRecents() {
        StartupFirstPaintGate.markWarmRecentsExpected()
        StartupWorkPriorityCoordinator.setVisibleSurface(
            StartupWorkPriorityCoordinator.VisibleSurface.RECENTS,
        )
        assertFalse(StartupFirstPaintGate.shouldDeferContactsHeavyWork())
        assertFalse(StartupFirstPaintGate.shouldDeferContactsWarmPreload())
        assertFalse(StartupFirstPaintGate.shouldDeferContactsFullDisplaySnapshot(recentsTabVisible = true))
        assertFalse(StartupWorkPriorityCoordinator.shouldDeferHiddenContactsWork())
        assertFalse(StartupWorkPriorityCoordinator.shouldDeferHiddenContactsHeavyWork())
        assertFalse(StartupWorkPriorityCoordinator.shouldDeferRecentsOnlyAssumptions())
        StartupFirstPaintGate.markSurfaceRequested(StartupSurface.RECENTS)
        assertFalse(StartupFirstPaintGate.shouldDeferContactsWarmPreload())
        StartupFirstPaintGate.markAdapterSubmitAccepted(rowCount = 2)
        assertFalse(StartupFirstPaintGate.shouldDeferContactsWarmPreload())
        assertFalse(StartupFirstPaintGate.shouldDeferContactsHeavyWork())
        // Recents-adjacent heavy work (media / raw repair) still waits for frame.
        assertTrue(StartupFirstPaintGate.shouldDeferHeavyStartupWork())
        StartupFirstPaintGate.markFrameWithRows(rowCount = 2)
        assertFalse(StartupFirstPaintGate.shouldDeferHeavyStartupWork())
    }

    @Test
    fun doesNotDeferContactsWhenContactsVisible() {
        StartupFirstPaintGate.markWarmRecentsExpected()
        StartupWorkPriorityCoordinator.setVisibleSurface(
            StartupWorkPriorityCoordinator.VisibleSurface.CONTACTS,
        )
        assertFalse(StartupFirstPaintGate.shouldDeferContactsWarmPreload())
        assertFalse(StartupFirstPaintGate.shouldDeferContactsHeavyWork())
        assertFalse(StartupWorkPriorityCoordinator.shouldDeferHiddenContactsWork())
        assertFalse(StartupWorkPriorityCoordinator.shouldDeferHiddenContactsHeavyWork())
        assertFalse(StartupWorkPriorityCoordinator.shouldDeferRecentsOnlyAssumptions())
        assertTrue(StartupFirstPaintGate.shouldDeferPhotoBackfill())
        assertTrue(StartupFirstPaintGate.shouldDeferMediaInit())
        StartupFirstPaintGate.markContactsUiFirstPainted(rowCount = 10)
        assertFalse(StartupFirstPaintGate.shouldDeferPhotoBackfill())
        assertFalse(StartupFirstPaintGate.shouldDeferMediaInit())
    }

    @Test
    fun offTabContactsPaintDoesNotPermanentlyPausePhotoProbes() {
        StartupFirstPaintGate.markWarmRecentsExpected()
        StartupWorkPriorityCoordinator.setVisibleSurface(
            StartupWorkPriorityCoordinator.VisibleSurface.RECENTS,
        )
        StartupPhotoBackfillGate.paused.set(false)
        StartupPhotoBackfillGate.retryScheduled.set(false)
        StartupFirstPaintGate.markSurfaceRequested(StartupSurface.RECENTS)
        StartupFirstPaintGate.markAdapterSubmitAccepted(rowCount = 2)
        StartupFirstPaintGate.markFrameWithRows(rowCount = 2)
        assertFalse(StartupPhotoBackfillGate.paused.get())
        // Off-tab Contacts pre-bind must not re-pause after Recents already resumed probes.
        StartupFirstPaintGate.markContactsUiFirstPainted(rowCount = 50)
        assertFalse(StartupPhotoBackfillGate.paused.get())
    }

    @Test
    fun bridgeAttachDoesNotMarkPainted() {
        StartupFirstPaintGate.markSurfaceRequested(StartupSurface.RECENTS)
        assertTrue(StartupFirstPaintGate.shouldDeferHeavyStartupWork())
        assertFalse(StartupFirstPaintGate.recentsFirstPaintCompleted())
    }

    @Test
    fun submitAcceptedDoesNotMarkPainted() {
        StartupFirstPaintGate.markWarmRecentsExpected()
        StartupWorkPriorityCoordinator.setVisibleSurface(
            StartupWorkPriorityCoordinator.VisibleSurface.RECENTS,
        )
        StartupFirstPaintGate.markSurfaceRequested(StartupSurface.RECENTS)
        StartupFirstPaintGate.markAdapterSubmitAccepted(rowCount = 2)
        assertTrue(StartupFirstPaintGate.shouldDeferHeavyStartupWork())
        assertFalse(StartupFirstPaintGate.recentsFirstPaintCompleted())
        assertFalse(StartupFirstPaintGate.shouldDeferContactsWarmPreload())
        assertTrue(StartupFirstPaintGate.adapterSubmitAccepted())
    }

    @Test
    fun onlyFrameWithRowsMarksPainted() {
        StartupFirstPaintGate.markSurfaceRequested(StartupSurface.RECENTS)
        StartupFirstPaintGate.markAdapterSubmitAccepted(rowCount = 2)
        StartupFirstPaintGate.markFrameWithRows(rowCount = 2)
        assertFalse(StartupFirstPaintGate.shouldDeferHeavyStartupWork())
        assertTrue(StartupFirstPaintGate.recentsFirstPaintCompleted())
    }

    @Test
    fun frameEmptyReleasesHeavyWork() {
        StartupFirstPaintGate.markWarmRecentsExpected()
        StartupWorkPriorityCoordinator.setVisibleSurface(
            StartupWorkPriorityCoordinator.VisibleSurface.RECENTS,
        )
        StartupFirstPaintGate.markSurfaceRequested(StartupSurface.RECENTS)
        StartupFirstPaintGate.markAdapterSubmitAccepted(rowCount = 0)
        StartupFirstPaintGate.markFrameEmpty()
        assertFalse(StartupFirstPaintGate.shouldDeferHeavyStartupWork())
        assertFalse(StartupFirstPaintGate.shouldDeferContactsHeavyWork())
        assertTrue(StartupFirstPaintGate.recentsFirstPaintCompleted())
    }

    @Test
    fun deferRecentsRawRepairWhenWarmDisplayAvailable() {
        StartupFirstPaintGate.markSurfaceRequested(StartupSurface.RECENTS)
        assertTrue(StartupFirstPaintGate.shouldDeferRecentsRawRepair(warmDisplayAvailable = true))
        StartupFirstPaintGate.markFrameWithRows(rowCount = 2)
        assertFalse(StartupFirstPaintGate.shouldDeferRecentsRawRepair(warmDisplayAvailable = true))
    }

    @Test
    fun frameTimeoutWaitsForAdapterSubmit() {
        StartupFirstPaintGate.markSurfaceRequested(StartupSurface.RECENTS)
        assertTrue(StartupFirstPaintGate.shouldDeferHeavyStartupWork())
        assertFalse(StartupFirstPaintGate.recentsFirstPaintCompleted())
        // Surface requested but submit not accepted — gate must stay in defer state.
        assertTrue(StartupFirstPaintGate.shouldDeferHeavyStartupWork())
    }

    @Test
    fun onRecentsSubmitDispatchedRunsImmediately() {
        StartupFirstPaintGate.markWarmRecentsExpected()
        StartupWorkPriorityCoordinator.setVisibleSurface(
            StartupWorkPriorityCoordinator.VisibleSurface.RECENTS,
        )
        var ran = false
        StartupFirstPaintGate.onRecentsSubmitDispatched { ran = true }
        assertTrue(ran)
    }
}
