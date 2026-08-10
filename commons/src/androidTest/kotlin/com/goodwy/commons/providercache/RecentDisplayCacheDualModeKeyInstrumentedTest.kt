package com.goodwy.commons.providercache

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression: both grouping modes may store a row with the same latest call_id.
 * Composite PK (call_id, group_by_contact) must keep both modes.
 */
@RunWith(AndroidJUnit4::class)
class RecentDisplayCacheDualModeKeyInstrumentedTest {

    private lateinit var database: ProviderCacheDatabase

    @Before
    fun setUp() {
        database = ProviderCacheDatabase.createInMemory(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
    }

    @After
    fun tearDown() {
        ProviderCacheDatabase.destroyInstance()
    }

    @Test
    fun insertSameCallId_bothModesPersist() = runBlocking {
        val callId = 42
        database.recentDisplayCacheDao().insertAll(
            listOf(
                row(callId, RecentGroupingMode.BY_NUMBER, "number:5551000"),
                row(callId, RecentGroupingMode.BY_CONTACT, "contact:1"),
            ),
        )
        val byNumber = database.recentDisplayCacheDao().getOrdered(RecentGroupingMode.BY_NUMBER.dbValue, 10)
        val byContact = database.recentDisplayCacheDao().getOrdered(RecentGroupingMode.BY_CONTACT.dbValue, 10)
        assertEquals(1, byNumber.size)
        assertEquals(1, byContact.size)
        assertEquals("number:5551000", byNumber.single().groupKey)
        assertEquals("contact:1", byContact.single().groupKey)
    }

    private fun row(callId: Int, mode: RecentGroupingMode, groupKey: String) = RecentDisplayCacheEntity(
        callId = callId,
        phoneNumber = "5551000",
        cachedName = "Alice",
        photoUri = "",
        startTS = 1L,
        duration = 0,
        type = 1,
        simID = -1,
        simTypeID = 1,
        simColor = 0,
        contactID = 1,
        callCount = 1,
        groupedCallIds = callId.toString(),
        normalizedNumber = "5551000",
        groupKey = groupKey,
        isUnknownNumber = false,
        isVoiceMail = false,
        blockReason = null,
        features = null,
        groupByContact = mode.dbValue,
        displayOrder = 0,
    )
}
