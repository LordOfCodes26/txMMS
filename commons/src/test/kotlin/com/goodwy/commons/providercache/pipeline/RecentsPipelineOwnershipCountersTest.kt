package com.goodwy.commons.providercache.pipeline

import com.goodwy.commons.providercache.pipeline.CallLogSyncOwnership
import com.goodwy.commons.providercache.pipeline.ObserverSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecentsPipelineOwnershipCountersTest {

    @Before
    fun reset() {
        RecentsPipelineOwnershipCounters.reset()
        RecentsPipelineOwnershipCounters.coordinatorAttached = false
    }

    @Test
    fun directSyncNotCountedWhenCoordinatorAbsent() {
        RecentsPipelineOwnershipCounters.noteDirectSyncAttempt()
        assertEquals(0, RecentsPipelineOwnershipCounters.directSyncWhileCoordinatorAttached.get())
    }

    @Test
    fun directSyncCountedWhenCoordinatorAttached() {
        RecentsPipelineOwnershipCounters.coordinatorAttached = true
        RecentsPipelineOwnershipCounters.noteDirectSyncAttempt()
        assertEquals(1, RecentsPipelineOwnershipCounters.directSyncWhileCoordinatorAttached.get())
    }

    @Test
    fun observerSourceMapsToSyncReason() {
        assertEquals(RecentsSyncReason.GLOBAL_OBSERVER, ObserverSource.GLOBAL.toSyncReason())
        assertEquals(RecentsSyncReason.UI_OBSERVER, ObserverSource.UI.toSyncReason())
        assertEquals(RecentsSyncReason.APP_RESUME, ObserverSource.RESUME.toSyncReason())
    }

    @Test
    fun snapshotIncludesOwnershipKeys() {
        val snap = RecentsPipelineOwnershipCounters.snapshot()
        assertTrue(snap.containsKey("directSyncWhileCoordinatorAttached"))
        assertTrue(snap.containsKey("bridgeSyncStartAttempt"))
        assertFalse(snap.containsKey("unknown"))
    }

    @Test
    fun syncOwnershipEnumHasExpectedValues() {
        assertEquals(
            setOf("RECENTS_UI", "STARTUP_CACHE", "BACKGROUND_MAINTENANCE", "DEBUG"),
            CallLogSyncOwnership.entries.map { it.name }.toSet(),
        )
    }
}
