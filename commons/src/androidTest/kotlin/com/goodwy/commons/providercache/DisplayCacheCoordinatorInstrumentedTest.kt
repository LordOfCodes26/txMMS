package com.goodwy.commons.providercache

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goodwy.commons.providercache.coordinator.CacheMutationReason
import com.goodwy.commons.providercache.coordinator.DisplayCacheCoordinator
import com.goodwy.commons.providercache.debug.CacheFailureDomain
import com.goodwy.commons.providercache.debug.CacheFailureInjector
import com.goodwy.commons.providercache.debug.CacheFailurePoint
import com.goodwy.commons.providercache.entities.CacheMetadataDomain
import com.goodwy.commons.providercache.metadata.CacheMetadataStore
import com.goodwy.commons.providercache.validation.CacheRepairOrchestrator
import com.goodwy.commons.providercache.validation.CacheValidator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DisplayCacheCoordinatorInstrumentedTest {

    private lateinit var database: ProviderCacheDatabase
    private lateinit var metadataStore: CacheMetadataStore
    private lateinit var coordinator: DisplayCacheCoordinator

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = ProviderCacheDatabase.createInMemory(context)
        metadataStore = CacheMetadataStore(database)
        metadataStore.ensureSeeded()
        coordinator = DisplayCacheCoordinator(database, metadataStore)
        CacheRepairOrchestrator.resetStartupRecoveryForDebug()
        CacheFailureInjector.clear()
    }

    @After
    fun tearDown() {
        database.close()
        CacheFailureInjector.clear()
    }

    @Test
    fun olderMutationCommitIgnored_afterNewerCommit() = runBlocking {
        val notifyCount = AtomicInteger(0)
        coordinator.onContactsDisplayCommitted = { notifyCount.incrementAndGet() }

        coordinator.commitContactsDisplay(5L, CacheMutationReason.CONTACT_UPDATED, rowCount = 10)
        val versionAfterNew = metadataStore.peekContactsDisplayVersion()

        coordinator.commitContactsDisplay(3L, CacheMutationReason.CONTACT_UPDATED, rowCount = 10)
        assertEquals(versionAfterNew, metadataStore.peekContactsDisplayVersion())
        assertEquals(1, notifyCount.get())
    }

    @Test
    fun failedMutationBeforeCommit_doesNotAdvanceCommittedId() = runBlocking {
        metadataStore.markDirty(CacheMetadataDomain.CONTACTS_DISPLAY, 1L, "test")
        val beforeVersion = metadataStore.peekContactsDisplayVersion()
        CacheFailureInjector.arm(CacheFailureDomain.CONTACTS, CacheFailurePoint.BEFORE_VERSION_COMMIT)
        try {
            coordinator.commitContactsDisplay(1L, CacheMutationReason.CONTACT_UPDATED, rowCount = 5)
            assertFalse("expected injection", true)
        } catch (_: Exception) {
            // expected
        }
        assertEquals(beforeVersion, metadataStore.peekContactsDisplayVersion())
        assertEquals(0L, coordinator.peekLatestCommittedMutationId())
    }

    @Test
    fun successfulCommit_clearsDirtyAndBumpsVersionOnce() = runBlocking {
        metadataStore.markDirty(CacheMetadataDomain.CONTACTS_DISPLAY, 1L, "test")
        coordinator.commitContactsDisplay(2L, CacheMutationReason.CONTACT_UPDATED, rowCount = 3)
        val entity = metadataStore.getEntity(CacheMetadataDomain.CONTACTS_DISPLAY)!!
        assertFalse(entity.dirty)
        assertFalse(entity.repairRequired)
        assertTrue(entity.displayVersion >= 1L)
    }

    @Test
    fun uiNotification_onlyAfterSuccessfulCommit() = runBlocking {
        val notifyCount = AtomicInteger(0)
        coordinator.onRecentsDisplayCommitted = { notifyCount.incrementAndGet() }
        CacheFailureInjector.arm(CacheFailureDomain.RECENTS, CacheFailurePoint.AFTER_VERSION_COMMIT_BEFORE_NOTIFY)
        try {
            coordinator.commitRecentsDisplay(10L, CacheMutationReason.CALL_INSERTED, rowCount = 1)
            assertFalse("expected injection before notify completes", true)
        } catch (_: Exception) {
            // version may have committed — notify must not fire
        }
        assertEquals(0, notifyCount.get())
    }
}
