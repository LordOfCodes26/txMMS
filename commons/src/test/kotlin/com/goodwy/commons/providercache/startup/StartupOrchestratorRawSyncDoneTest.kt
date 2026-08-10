package com.goodwy.commons.providercache.startup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clear-all rebuild claims READY_EMPTY; that requires callLogsSyncDone after warm sync too.
 */
class StartupOrchestratorRawSyncDoneTest {

    @Test
    fun onCallLogsRawSyncComplete_marksDoneOutsideColdStart() {
        // Warm session: coldStart is false; clear-all still runs empty Room sync.
        assertFalse(StartupOrchestrator.coldStart)
        StartupOrchestrator.onCallLogsRawSyncComplete()
        assertTrue(StartupOrchestrator.callLogsSyncDone)
    }
}
