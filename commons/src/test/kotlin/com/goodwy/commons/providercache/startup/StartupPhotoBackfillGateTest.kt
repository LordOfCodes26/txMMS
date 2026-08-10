package com.goodwy.commons.providercache.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StartupPhotoBackfillGateTest {

    @Before
    fun resetGate() {
        StartupPhotoBackfillGate.paused.set(false)
        StartupPhotoBackfillGate.retryScheduled.set(false)
        StartupPhotoBackfillGate.scheduledRetryCount.set(0)
        StartupPhotoBackfillGate.resumeBackfill()
    }

    @Test
    fun pauseAndResume_togglesPausedState() {
        StartupPhotoBackfillGate.pauseBackfill("STARTUP_BUSY", remaining = 100)
        assertTrue(StartupPhotoBackfillGate.paused.get())
        StartupPhotoBackfillGate.resumeBackfill()
        assertFalse(StartupPhotoBackfillGate.paused.get())
    }

    @Test
    fun allowProviderPhotoProbe_returnsFalseWhenPaused() {
        StartupPhotoBackfillGate.pauseBackfill("CONTACTS_FIRST_PAINT", remaining = 100)
        try {
            assertFalse(StartupPhotoBackfillGate.allowProviderPhotoProbe(logOnSkip = false))
        } finally {
            StartupPhotoBackfillGate.resumeBackfill()
        }
    }

    @Test
    fun scheduleRetryWhenIdle_recordsRetryRequest() {
        val before = StartupPhotoBackfillGate.scheduledRetryCount.get()
        StartupPhotoBackfillGate.scheduleRetryWhenIdle(delayMs = 50L) {}
        assertEquals(before + 1, StartupPhotoBackfillGate.scheduledRetryCount.get())
        // Without ProviderCache the flag is cleared so another request is accepted.
        StartupPhotoBackfillGate.scheduleRetryWhenIdle(delayMs = 50L) {}
        assertTrue(StartupPhotoBackfillGate.scheduledRetryCount.get() >= before + 1)
    }

    @Test
    fun allowPhotoBackfill_returnsFalseWhenPaused() {
        StartupPhotoBackfillGate.pauseBackfill("STARTUP_BUSY", remaining = -1)
        try {
            assertFalse(StartupPhotoBackfillGate.allowPhotoBackfill())
        } finally {
            StartupPhotoBackfillGate.resumeBackfill()
        }
    }
}
