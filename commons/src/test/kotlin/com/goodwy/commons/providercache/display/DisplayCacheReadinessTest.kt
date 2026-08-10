package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.validation.legacy.LegacyCacheGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayCacheReadinessTest {

    @After
    fun tearDown() {
        DisplayCacheReadinessTracker.resetForColdStart()
        StartupDomainOwner.reset()
    }

    @Test
    fun temporaryEmptyDuringBuild_isNotReadyEmpty() {
        val readiness = DisplayCacheReadinessTracker.computeRecentsReadiness(
            rawCount = 10,
            displayRows = 0,
            rawSyncDone = true,
            displayBuilding = true,
            hasCallLogPermission = true,
        )
        assertEquals(DisplayCacheReadiness.DISPLAY_BUILDING, readiness)
    }

    @Test
    fun validatedZeroSourceRows_isReadyEmpty() {
        val readiness = DisplayCacheReadinessTracker.computeRecentsReadiness(
            rawCount = 0,
            displayRows = 0,
            rawSyncDone = true,
            displayBuilding = false,
            hasCallLogPermission = true,
        )
        assertEquals(DisplayCacheReadiness.READY_EMPTY, readiness)
    }

    @Test
    fun providerFallbackBlocksRoomAuthority() {
        val allowed = LegacyCacheGate.evaluateRecentsAuthority(
            readiness = DisplayCacheReadiness.READY_WITH_DATA,
            displayVersion = 2L,
            dirty = false,
            repairRequired = false,
            providerFallbackActive = true,
        )
        assertFalse(allowed)
    }

    @Test
    fun readyEmptyWithVersion_isAuthoritativeWhenClean() {
        val allowed = LegacyCacheGate.evaluateRecentsAuthority(
            readiness = DisplayCacheReadiness.READY_EMPTY,
            displayVersion = 1L,
            dirty = false,
            repairRequired = false,
            providerFallbackActive = false,
        )
        assertTrue(allowed)
    }

    @Test
    fun contacts_rawRowsWithZeroDisplay_isDisplayBuildingUntilRebuildSettles() {
        val readiness = DisplayCacheReadinessTracker.computeContactsReadiness(
            rawCount = 10,
            displayRows = 0,
            rawSyncDone = true,
            displayBuilding = false,
            hasContactsPermission = true,
        )
        // Pre-rebuild / mid-recovery: empty display with Room rows means still building.
        assertEquals(DisplayCacheReadiness.DISPLAY_BUILDING, readiness)
    }

    @Test
    fun contacts_completedRebuildWithFilterEmpty_isReadyEmptyWhenWarm() {
        val computed = DisplayCacheReadinessTracker.computeContactsReadiness(
            rawCount = 10,
            displayRows = 0,
            rawSyncDone = true,
            displayBuilding = false,
            hasContactsPermission = true,
        )
        val effective = ContactsDisplayReadiness.effectiveAfterRebuild(
            computed = computed,
            displayRows = 0,
            rawCount = 10,
            hasPermission = true,
            contactsSyncDone = false,
            coldStart = false,
        )
        assertEquals(DisplayCacheReadiness.READY_EMPTY, effective)
    }

    @Test
    fun contacts_completedRebuildWithFilterEmpty_isReadyEmptyAfterColdSync() {
        val computed = DisplayCacheReadinessTracker.computeContactsReadiness(
            rawCount = 10,
            displayRows = 0,
            rawSyncDone = true,
            displayBuilding = false,
            hasContactsPermission = true,
        )
        val effective = ContactsDisplayReadiness.effectiveAfterRebuild(
            computed = computed,
            displayRows = 0,
            rawCount = 10,
            hasPermission = true,
            contactsSyncDone = true,
            coldStart = true,
        )
        assertEquals(DisplayCacheReadiness.READY_EMPTY, effective)
    }

    @Test
    fun contacts_earlyColdRebuildEmpty_staysBuildingUntilSyncDone() {
        val computed = DisplayCacheReadinessTracker.computeContactsReadiness(
            rawCount = 10,
            displayRows = 0,
            rawSyncDone = true,
            displayBuilding = false,
            hasContactsPermission = true,
        )
        val effective = ContactsDisplayReadiness.effectiveAfterRebuild(
            computed = computed,
            displayRows = 0,
            rawCount = 10,
            hasPermission = true,
            contactsSyncDone = false,
            coldStart = true,
        )
        assertEquals(DisplayCacheReadiness.DISPLAY_BUILDING, effective)
    }

    /**
     * Same inputs as [contacts_earlyColdRebuildEmpty_staysBuildingUntilSyncDone] except that the
     * rebuild came from a user visibility action — protecting every contact. The rows are in Room
     * and the user just hid them, so the empty display is final and must be claimed; otherwise the
     * Contacts tab keeps MProgressDialog up over a list that will never change.
     */
    @Test
    fun contacts_filterEmptyFromUserAction_isReadyEmptyEvenDuringColdStart() {
        val computed = DisplayCacheReadinessTracker.computeContactsReadiness(
            rawCount = 10,
            displayRows = 0,
            rawSyncDone = true,
            displayBuilding = false,
            hasContactsPermission = true,
        )
        val effective = ContactsDisplayReadiness.effectiveAfterRebuild(
            computed = computed,
            displayRows = 0,
            rawCount = 10,
            hasPermission = true,
            contactsSyncDone = false,
            coldStart = true,
            filterDrivenEmpty = true,
        )
        assertEquals(DisplayCacheReadiness.READY_EMPTY, effective)
    }

    /** No Room rows means nothing was hidden, so the cold-start settle rule still applies. */
    @Test
    fun contacts_filterEmptyWithNoRoomRows_stillDefersToColdStart() {
        val computed = DisplayCacheReadinessTracker.computeContactsReadiness(
            rawCount = 0,
            displayRows = 0,
            rawSyncDone = false,
            displayBuilding = false,
            hasContactsPermission = true,
        )
        val effective = ContactsDisplayReadiness.effectiveAfterRebuild(
            computed = computed,
            displayRows = 0,
            rawCount = 0,
            hasPermission = true,
            contactsSyncDone = false,
            coldStart = true,
            filterDrivenEmpty = true,
        )
        assertEquals(computed, effective)
    }

    @Test
    fun contacts_trueEmptyAfterColdSync_isReadyEmpty() {
        val computed = DisplayCacheReadinessTracker.computeContactsReadiness(
            rawCount = 0,
            displayRows = 0,
            rawSyncDone = true,
            displayBuilding = false,
            hasContactsPermission = true,
        )
        val effective = ContactsDisplayReadiness.effectiveAfterRebuild(
            computed = computed,
            displayRows = 0,
            rawCount = 0,
            hasPermission = true,
            contactsSyncDone = true,
            coldStart = true,
        )
        assertEquals(DisplayCacheReadiness.READY_EMPTY, effective)
    }

    @Test
    fun contacts_trueEmptyDuringColdSync_keepsRawSyncing() {
        val computed = DisplayCacheReadinessTracker.computeContactsReadiness(
            rawCount = 0,
            displayRows = 0,
            rawSyncDone = false,
            displayBuilding = false,
            hasContactsPermission = true,
        )
        assertEquals(DisplayCacheReadiness.RAW_SYNCING, computed)
        val effective = ContactsDisplayReadiness.effectiveAfterRebuild(
            computed = computed,
            displayRows = 0,
            rawCount = 0,
            hasPermission = true,
            contactsSyncDone = false,
            coldStart = true,
        )
        assertEquals(DisplayCacheReadiness.RAW_SYNCING, effective)
    }

    @Test
    fun missingPermission_isErrorPermission() {
        val readiness = DisplayCacheReadinessTracker.computeContactsReadiness(
            rawCount = 0,
            displayRows = 0,
            rawSyncDone = false,
            displayBuilding = false,
            hasContactsPermission = false,
        )
        assertEquals(DisplayCacheReadiness.ERROR_PERMISSION, readiness)
    }

    @Test
    fun startupDomainOwner_coalescesDuplicateRepair() {
        assertTrue(
            StartupDomainOwner.tryAcquire(
                CacheDomain.CONTACTS,
                StartupDomainOwnerKind.CACHE_REPAIR,
                "STARTUP_REPAIR",
            ),
        )
        assertFalse(
            StartupDomainOwner.tryAcquire(
                CacheDomain.CONTACTS,
                StartupDomainOwnerKind.CACHE_REPAIR,
                "STARTUP_REPAIR",
            ),
        )
        StartupDomainOwner.markCommitted(CacheDomain.CONTACTS)
        assertFalse(
            StartupDomainOwner.tryAcquire(
                CacheDomain.CONTACTS,
                StartupDomainOwnerKind.CACHE_REPAIR,
                "STARTUP_REPAIR",
            ),
        )
    }

    @Test
    fun startupDomainOwner_reasonsDoNotCreateSeparateKeys() {
        assertTrue(
            StartupDomainOwner.tryAcquire(
                CacheDomain.RECENTS,
                StartupDomainOwnerKind.COLD_BOOTSTRAP,
                "COLD_EMPTY_CACHE",
            ),
        )
        assertFalse(
            StartupDomainOwner.tryAcquire(
                CacheDomain.RECENTS,
                StartupDomainOwnerKind.CACHE_REPAIR,
                "STARTUP_REPAIR",
            ),
        )
        assertFalse(
            StartupDomainOwner.tryAcquire(
                CacheDomain.RECENTS,
                StartupDomainOwnerKind.METADATA_REPAIR,
                "METADATA_REPAIR",
            ),
        )
        assertEquals(
            "RECENTS:${StartupDomainOwner.currentStartupGeneration()}",
            StartupDomainOwner.mutationKey(CacheDomain.RECENTS),
        )
    }

    @Test
    fun startupDomainOwner_newGenerationAllowsReacquire() {
        assertTrue(
            StartupDomainOwner.tryAcquire(
                CacheDomain.RECENTS,
                StartupDomainOwnerKind.COLD_BOOTSTRAP,
                "COLD_EMPTY_CACHE",
            ),
        )
        StartupDomainOwner.markCommitted(CacheDomain.RECENTS)
        val gen = StartupDomainOwner.nextStartupGeneration()
        assertTrue(gen > 0L)
        assertTrue(
            StartupDomainOwner.tryAcquire(
                CacheDomain.RECENTS,
                StartupDomainOwnerKind.METADATA_REPAIR,
                "METADATA_REPAIR",
            ),
        )
    }

    @Test
    fun contactsAndRecentsOwnersAreIndependent() {
        assertTrue(
            StartupDomainOwner.tryAcquire(
                CacheDomain.CONTACTS,
                StartupDomainOwnerKind.COLD_BOOTSTRAP,
                "COLD_EMPTY_CACHE",
            ),
        )
        assertTrue(
            StartupDomainOwner.tryAcquire(
                CacheDomain.RECENTS,
                StartupDomainOwnerKind.COLD_BOOTSTRAP,
                "COLD_EMPTY_CACHE",
            ),
        )
    }

    @Test
    fun permissionResume_runsBootstrapOnce() {
        DisplayCacheReadinessTracker.setContacts(DisplayCacheReadiness.ERROR_PERMISSION)
        assertTrue(DisplayCacheReadinessTracker.resumeContactsAfterPermissionGranted())
        assertEquals(DisplayCacheReadiness.NOT_STARTED, DisplayCacheReadinessTracker.contactsReadiness())
        DisplayCacheReadinessTracker.setContacts(DisplayCacheReadiness.ERROR_PERMISSION)
        assertFalse(DisplayCacheReadinessTracker.resumeContactsAfterPermissionGranted())
    }
}
