package com.goodwy.commons.providercache.validation.legacy

import com.goodwy.commons.providercache.display.DisplayCacheReadiness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCacheGateTest {

    @Test
    fun evaluateAuthority_requiresReadyStateVersionAndCleanFlags() {
        val none = LegacyCacheGate.evaluateAuthority(
            contactsReadiness = DisplayCacheReadiness.NOT_STARTED,
            contactsDisplayVersion = 0L,
            contactsDirty = true,
            contactsRepairRequired = true,
            contactsFallbackActive = false,
            recentsReadiness = DisplayCacheReadiness.NOT_STARTED,
            recentsDisplayVersion = 0L,
            recentsDirty = true,
            recentsRepairRequired = true,
            recentsFallbackActive = false,
        )
        assertFalse(none.contactsRoomAuthoritative)
        assertFalse(none.recentsRoomAuthoritative)

        val ready = LegacyCacheGate.evaluateAuthority(
            contactsReadiness = DisplayCacheReadiness.READY_WITH_DATA,
            contactsDisplayVersion = 3L,
            contactsDirty = false,
            contactsRepairRequired = false,
            contactsFallbackActive = false,
            recentsReadiness = DisplayCacheReadiness.READY_EMPTY,
            recentsDisplayVersion = 1L,
            recentsDirty = false,
            recentsRepairRequired = false,
            recentsFallbackActive = false,
        )
        assertTrue(ready.contactsRoomAuthoritative)
        assertTrue(ready.recentsRoomAuthoritative)
    }

    @Test
    fun evaluateAuthority_versionWithoutReadyState_notAuthoritative() {
        val snap = LegacyCacheGate.evaluateAuthority(
            contactsReadiness = DisplayCacheReadiness.DISPLAY_BUILDING,
            contactsDisplayVersion = 5L,
            contactsDirty = false,
            contactsRepairRequired = false,
            contactsFallbackActive = false,
            recentsReadiness = DisplayCacheReadiness.RAW_SYNCING,
            recentsDisplayVersion = 5L,
            recentsDirty = false,
            recentsRepairRequired = false,
            recentsFallbackActive = false,
        )
        assertFalse(snap.contactsRoomAuthoritative)
        assertFalse(snap.recentsRoomAuthoritative)
    }

    @Test
    fun evaluateAuthority_providerFallbackBlocksEvictionAuthority() {
        val snap = LegacyCacheGate.evaluateAuthority(
            contactsReadiness = DisplayCacheReadiness.READY_WITH_DATA,
            contactsDisplayVersion = 1L,
            contactsDirty = false,
            contactsRepairRequired = false,
            contactsFallbackActive = true,
            recentsReadiness = DisplayCacheReadiness.READY_WITH_DATA,
            recentsDisplayVersion = 1L,
            recentsDirty = false,
            recentsRepairRequired = false,
            recentsFallbackActive = true,
        )
        assertFalse(snap.contactsRoomAuthoritative)
        assertFalse(snap.recentsRoomAuthoritative)
    }

    @Test
    fun legacyCounters_trackReadsWritesAndPaintBlocks() {
        LegacyCacheCounters.reset()
        LegacyCacheCounters.recordRead()
        LegacyCacheCounters.recordWrite()
        LegacyCacheCounters.recordPaintAttempt(blocked = true)
        assertTrue(LegacyCacheCounters.dump().contains("legacyReadCount=1"))
        assertTrue(LegacyCacheCounters.dump().contains("legacyWriteCount=1"))
        assertTrue(LegacyCacheCounters.dump().contains("legacyPaintBlockedCount=1"))
    }
}
