package com.goodwy.commons.providercache

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goodwy.commons.providercache.coordinator.CacheMutationReason
import com.goodwy.commons.providercache.coordinator.DisplayCacheCoordinator
import com.goodwy.commons.providercache.entities.ContactDisplayCacheEntity
import com.goodwy.commons.providercache.identity.ContactIdentityResolver
import com.goodwy.commons.providercache.metadata.CacheMetadataStore
import com.goodwy.commons.providercache.transaction.ProviderCacheTransactions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactRecentsIntegrationInstrumentedTest {

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
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun contactDelete_capturesIdentityBeforePurge() = runBlocking {
        ProviderCacheTestFixtures.seedContactGraph(database, contactId = 42, rawId = 142)
        ProviderCacheTestFixtures.seedCallLogWithDisplay(database, callId = 5000, contactId = 42)
        val identity = ContactIdentityResolver(database).resolveFromAggregateId(42L)
        assertNotNull(identity)

        ProviderCacheTransactions.purgeContactRoomCaches(
            database = database,
            rawIds = listOf(142),
            contactIds = listOf(42),
        )

        assertNull(ContactIdentityResolver(database).resolveFromAggregateId(42L))
        assertEquals(1, database.callLogDao().getCount())
    }

    @Test
    fun contactRename_updatesDisplayCacheRow() = runBlocking {
        ProviderCacheTestFixtures.seedContactGraph(database, displayName = "Old Name")
        val updated = ContactDisplayCacheEntity(
            rawId = 200,
            contactId = 100,
            displayName = "New Name",
            thumbnailUri = "",
            photoUri = "",
            source = "Google",
            accountType = "",
            firstPhone = "+15551234567",
            firstEmail = "",
            sectionLetter = "N",
            sortKey = "new name",
            searchName = "new name",
            t9Key = "new name",
            phoneDigits = "5551234567",
        )
        database.contactDisplayCacheDao().insertAll(listOf(updated))
        coordinator.commitContactsDisplay(1L, CacheMutationReason.CONTACT_UPDATED, rowCount = 1)
        coordinator.commitRecentsDisplay(2L, CacheMutationReason.CONTACT_UPDATED, rowCount = 1)

        val row = database.contactDisplayCacheDao().getCount()
        assertEquals(1, row)
        assertTrue(metadataStore.peekContactsDisplayVersion() >= 1L)
        assertTrue(metadataStore.peekRecentsDisplayVersion() >= 1L)
    }

    @Test
    fun deleteAllContacts_callLogsRemain() = runBlocking {
        ProviderCacheTestFixtures.seedContactGraph(database)
        ProviderCacheTestFixtures.seedCallLogWithDisplay(database, callId = 6000, contactId = 100)

        ProviderCacheTransactions.purgeContactRoomCaches(
            database = database,
            rawIds = listOf(200),
            contactIds = listOf(100),
        )

        assertEquals(0, database.contactDisplayCacheDao().getCount())
        assertEquals(1, database.callLogDao().getCount())
    }
}
