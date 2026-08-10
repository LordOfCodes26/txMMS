package com.goodwy.commons.providercache

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.goodwy.commons.providercache.display.RecentGroupBuildContextLoader
import com.goodwy.commons.providercache.display.RecentGroupDualWriteValidator
import com.goodwy.commons.providercache.display.RecentGroupRelationalBuilder
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.entities.CallLogEntity
import com.goodwy.commons.providercache.transaction.ProviderCacheTransactions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentGroupingIntegrationInstrumentedTest {

    private lateinit var database: ProviderCacheDatabase

    @Before
    fun setUp() {
        database = ProviderCacheDatabase.createInMemory(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
        )
    }

    @After
    fun tearDown() {
        ProviderCacheDatabase.destroyInstance()
    }

    @Test
    fun byNumber_sameNumberSeveralContacts_dualWriteValid() = runBlocking {
        ProviderCacheTestFixtures.seedContactGraph(database, contactId = 1, phone = "5551234")
        ProviderCacheTestFixtures.seedContactGraph(database, contactId = 2, rawId = 201, displayName = "Bob", phone = "5551234")
        database.callLogDao().insertAll(
            listOf(
                call(10, "5551234", ts = 3000L, contactId = 1),
                call(11, "5551234", ts = 2000L, contactId = 2),
            ),
        )
        rebuildBothModes()

        val result = RecentGroupDualWriteValidator.validateDualWrite(database, RecentGroupingMode.BY_NUMBER)
        assertTrue("dual-write: ${result.mismatches}", result.valid)
        assertEquals(1, database.recentGroupDao().countGroups(0))
    }

    @Test
    fun byContact_oneContactSeveralNumbers_singleGroup() = runBlocking {
        ProviderCacheTestFixtures.seedContactGraph(database, contactId = 42, phone = "5551111")
        database.contactPhoneIndexDao().insertAll(
            listOf(
                com.goodwy.commons.providercache.entities.ContactPhoneIndexEntity(
                    contactId = 42,
                    normalizedNumber = "5552222",
                    digits = "5552222",
                    phoneDigits = "5552222",
                ),
            ),
        )
        database.callLogDao().insertAll(
            listOf(
                call(1, "5551111", ts = 2000L, contactId = 42),
                call(2, "5552222", ts = 1000L, contactId = 42),
            ),
        )
        val context = RecentGroupBuildContextLoader.load(database)
        val calls = database.callLogDao().getFirstEntries(100)
        val relational = RecentGroupRelationalBuilder.build(calls, RecentGroupingMode.BY_CONTACT, context)
        ProviderCacheTransactions.replaceRecentGroupingTables(database, RecentGroupingMode.BY_CONTACT, relational)

        assertEquals(1, relational.groups.size)
        assertEquals("contact:42", relational.groups.single().groupKey)
        assertEquals(2, relational.calls.size)
    }

    @Test
    fun fkCascade_callDelete_removesMembership() = runBlocking {
        database.callLogDao().insertAll(listOf(call(99, "5559999", ts = 1000L)))
        val context = RecentGroupBuildContextLoader.load(database)
        val relational = RecentGroupRelationalBuilder.build(
            database.callLogDao().getFirstEntries(100),
            RecentGroupingMode.BY_NUMBER,
            context,
        )
        ProviderCacheTransactions.replaceRecentGroupingTables(database, RecentGroupingMode.BY_NUMBER, relational)
        assertEquals(1, database.recentGroupCallDao().countCallsForGroup(0, relational.groups.single().groupKey))

        database.callLogDao().deleteByIds(listOf(99))
        assertEquals(0, database.recentGroupCallDao().countCallsForGroup(0, relational.groups.single().groupKey))
    }

    private suspend fun rebuildBothModes() {
        val calls = RecentGroupBuildContextLoader.loadCalls(database, 1000)
        val context = RecentGroupBuildContextLoader.load(database)
        listOf(RecentGroupingMode.BY_NUMBER, RecentGroupingMode.BY_CONTACT).forEach { mode ->
            val relational = RecentGroupRelationalBuilder.build(calls, mode, context)
            ProviderCacheTransactions.replaceRecentGroupingTables(database, mode, relational)
            val displayRows = com.goodwy.commons.providercache.display.RelationalRecentDisplaySnapshotBuilder
                .buildDisplayRowsFromRelationalGroups(
                    database = database,
                    mode = mode,
                    groupByContactFlag = mode == RecentGroupingMode.BY_CONTACT,
                )
            database.recentDisplayCacheDao().insertAll(displayRows)
        }
    }

    private fun call(id: Int, number: String, ts: Long, contactId: Int? = null) = CallLogEntity(
        callId = id,
        phoneNumber = number,
        cachedName = "",
        cachedPhotoUri = "",
        startTS = ts,
        duration = 0,
        type = 1,
        simID = 0,
        normalizedNumber = number,
        contactID = contactId,
    )
}
