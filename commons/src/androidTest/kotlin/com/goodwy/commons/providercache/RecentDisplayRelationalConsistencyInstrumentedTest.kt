package com.goodwy.commons.providercache

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.display.RecentRelationalValidationDeferredState
import com.goodwy.commons.providercache.entities.CallLogEntity
import com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity
import com.goodwy.commons.providercache.entities.RecentGroupCallEntity
import com.goodwy.commons.providercache.entities.RecentGroupEntity
import com.goodwy.commons.providercache.validation.RecentDisplayRelationalConsistencyValidator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentDisplayRelationalConsistencyInstrumentedTest {

    private lateinit var database: ProviderCacheDatabase

    @Before
    fun setUp() {
        RecentRelationalValidationDeferredState.resetForDebug()
        database = ProviderCacheDatabase.createInMemory(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
    }

    @After
    fun tearDown() {
        RecentRelationalValidationDeferredState.resetForDebug()
        ProviderCacheDatabase.destroyInstance()
    }

    @Test
    fun deferredPhase_skipsMissingDisplayRow_untilDisplayCommitted() = runBlocking {
        seedCall(1, "5551000", 1000L)
        seedGroup(
            groupKey = "5551000",
            latestCallId = 1L,
            latestTimestamp = 1000L,
            callCount = 1,
            membership = listOf(1L),
        )
        RecentRelationalValidationDeferredState.markRelationalWritten(
            RecentGroupingMode.BY_NUMBER,
            "test_mid_rebuild",
        )
        val deferred = RecentDisplayRelationalConsistencyValidator.validate(
            database,
            RecentGroupingMode.BY_NUMBER,
        )
        assertTrue(deferred.deferred)
        assertTrue(deferred.valid)
        assertTrue(deferred.issues.isEmpty())

        RecentRelationalValidationDeferredState.markDisplayCommitted(
            RecentGroupingMode.BY_NUMBER,
            "test_post_commit",
        )
        val ready = RecentDisplayRelationalConsistencyValidator.validate(
            database,
            RecentGroupingMode.BY_NUMBER,
        )
        assertFalse(ready.deferred)
        assertFalse(ready.valid)
        assertTrue(
            ready.issues.any {
                it.reason == RecentDisplayRelationalConsistencyValidator.IssueReason.MISSING_DISPLAY_ROW
            },
        )
    }

    @Test
    fun missingDisplayRow_detected() = runBlocking {
        seedCall(1, "5551000", 1000L)
        seedGroup(
            groupKey = "5551000",
            latestCallId = 1L,
            latestTimestamp = 1000L,
            callCount = 1,
            membership = listOf(1L),
        )
        val result = RecentDisplayRelationalConsistencyValidator.validate(
            database,
            RecentGroupingMode.BY_NUMBER,
        )
        assertFalse(result.valid)
        assertTrue(
            result.issues.any {
                it.reason == RecentDisplayRelationalConsistencyValidator.IssueReason.MISSING_DISPLAY_ROW
            },
        )
    }

    @Test
    fun extraDisplayRow_detected() = runBlocking {
        seedCall(1, "5551000", 1000L)
        seedDisplay(callId = 1, phone = "5551000", groupKey = "5551000", count = 1, latestTs = 1000L)
        val result = RecentDisplayRelationalConsistencyValidator.validate(
            database,
            RecentGroupingMode.BY_NUMBER,
        )
        assertFalse(result.valid)
        assertTrue(
            result.issues.any {
                it.reason == RecentDisplayRelationalConsistencyValidator.IssueReason.EXTRA_DISPLAY_ROW
            },
        )
    }

    @Test
    fun callCountMismatch_detected() = runBlocking {
        seedCall(1, "5551000", 1000L)
        seedCall(2, "5551000", 2000L)
        seedGroup(
            groupKey = "5551000",
            latestCallId = 2L,
            latestTimestamp = 2000L,
            callCount = 1, // wrong — membership has 2
            membership = listOf(1L, 2L),
        )
        seedDisplay(callId = 2, phone = "5551000", groupKey = "5551000", count = 2, latestTs = 2000L)
        val result = RecentDisplayRelationalConsistencyValidator.validate(
            database,
            RecentGroupingMode.BY_NUMBER,
        )
        assertFalse(result.valid)
        assertTrue(
            result.issues.any {
                it.reason == RecentDisplayRelationalConsistencyValidator.IssueReason.CALL_COUNT_MISMATCH
            },
        )
    }

    @Test
    fun latestTimestampMismatch_detected() = runBlocking {
        seedCall(1, "5551000", 1000L)
        seedCall(2, "5551000", 2000L)
        seedGroup(
            groupKey = "5551000",
            latestCallId = 2L,
            latestTimestamp = 1500L, // wrong — max membership ts is 2000
            callCount = 2,
            membership = listOf(1L, 2L),
        )
        seedDisplay(callId = 2, phone = "5551000", groupKey = "5551000", count = 2, latestTs = 2000L)
        val result = RecentDisplayRelationalConsistencyValidator.validate(
            database,
            RecentGroupingMode.BY_NUMBER,
        )
        assertFalse(result.valid)
        assertTrue(
            result.issues.any {
                it.reason == RecentDisplayRelationalConsistencyValidator.IssueReason.LATEST_TIMESTAMP_MISMATCH
            },
        )
    }

    private suspend fun seedCall(id: Int, phone: String, ts: Long) {
        database.callLogDao().insertAll(
            listOf(
                CallLogEntity(
                    callId = id,
                    phoneNumber = phone,
                    cachedName = "",
                    cachedPhotoUri = "",
                    startTS = ts,
                    duration = 0,
                    type = 1,
                    simID = 0,
                    normalizedNumber = phone,
                    contactID = null,
                ),
            ),
        )
    }

    private suspend fun seedGroup(
        groupKey: String,
        latestCallId: Long,
        latestTimestamp: Long,
        callCount: Int,
        membership: List<Long>,
    ) {
        database.recentGroupDao().upsertAll(
            listOf(
                RecentGroupEntity(
                    groupingMode = 0,
                    groupKey = groupKey,
                    displayContactId = null,
                    latestCallId = latestCallId,
                    latestTimestamp = latestTimestamp,
                    callCount = callCount,
                    primaryNumber = groupKey,
                    displayOrder = 0,
                ),
            ),
        )
        database.recentGroupCallDao().insertAll(
            membership.map {
                RecentGroupCallEntity(groupingMode = 0, groupKey = groupKey, callId = it)
            },
        )
    }

    private suspend fun seedDisplay(
        callId: Int,
        phone: String,
        groupKey: String,
        count: Int,
        latestTs: Long,
    ) {
        database.recentDisplayCacheDao().insertAll(
            listOf(
                RecentDisplayCacheEntity(
                    callId = callId,
                    phoneNumber = phone,
                    cachedName = "",
                    photoUri = "",
                    startTS = latestTs,
                    duration = 0,
                    type = 1,
                    simID = 0,
                    simTypeID = 1,
                    simColor = 0,
                    contactID = null,
                    callCount = count,
                    groupedCallIds = callId.toString(),
                    normalizedNumber = phone,
                    groupKey = groupKey,
                    isUnknownNumber = false,
                    isVoiceMail = false,
                    blockReason = 0,
                    features = 0,
                    groupByContact = 0,
                    displayOrder = 0,
                ),
            ),
        )
    }
}
