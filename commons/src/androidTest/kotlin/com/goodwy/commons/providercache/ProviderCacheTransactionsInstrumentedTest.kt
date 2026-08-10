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
import com.goodwy.commons.providercache.transaction.ProviderCacheTransactions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderCacheTransactionsInstrumentedTest {

    private lateinit var database: ProviderCacheDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = ProviderCacheDatabase.createInMemory(context)
    }

    @After
    fun tearDown() {
        database.close()
        CacheFailureInjector.clear()
    }

    @Test
    fun purgeContactRoomCaches_removesAllRelatedRows() = runBlocking {
        ProviderCacheTestFixtures.seedContactGraph(database)
        ProviderCacheTestFixtures.seedCallLogWithDisplay(database, callId = 2000, contactId = 100)

        ProviderCacheTransactions.purgeContactRoomCaches(
            database = database,
            rawIds = listOf(200),
            contactIds = listOf(100),
            mutationId = 1L,
        )

        assertEquals(0, database.contactDao().getSummaryCount())
        assertEquals(0, database.contactDisplayCacheDao().getCount())
        assertEquals(1, database.callLogDao().getCount())
    }

    @Test
    fun purgeCallLogRoomCaches_removesRawAndDisplayRows() = runBlocking {
        ProviderCacheTestFixtures.seedCallLogWithDisplay(database, callId = 3000)
        val groupKey = "5551234567"

        ProviderCacheTransactions.purgeCallLogRoomCaches(
            database = database,
            callLogIds = listOf(3000),
            groupKeys = listOf(groupKey),
            displayRowCallIds = listOf(3000),
            mutationId = 2L,
        )

        assertEquals(0, database.callLogDao().getCount())
        assertEquals(0, database.recentDisplayCacheDao().getCount(0))
    }

    @Test
    fun clearAllCallLogRoomCaches_clearsBothModes() = runBlocking {
        ProviderCacheTestFixtures.seedCallLogWithDisplay(database, callId = 4000)
        ProviderCacheTransactions.clearAllCallLogRoomCaches(database, mutationId = 3L)
        assertEquals(0, database.callLogDao().getCount())
        assertEquals(0, database.recentDisplayCacheDao().getCount(0))
        assertEquals(0, database.recentDisplayCacheDao().getCount(1))
    }

    @Test
    fun failureInjectionBeforeCommit_rollsBackTransaction() = runBlocking {
        ProviderCacheTestFixtures.seedContactGraph(database)
        CacheFailureInjector.arm(CacheFailureDomain.CONTACTS, CacheFailurePoint.AFTER_RAW_WRITE)
        try {
            ProviderCacheTransactions.purgeContactRoomCaches(
                database = database,
                rawIds = listOf(200),
                contactIds = listOf(100),
            )
            assertFalse("expected failure injection", true)
        } catch (_: Exception) {
            // expected
        }
        assertEquals(1, database.contactDao().getSummaryCount())
        assertEquals(1, database.contactDisplayCacheDao().getCount())
    }
}
