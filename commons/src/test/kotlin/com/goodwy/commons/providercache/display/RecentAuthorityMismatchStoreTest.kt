package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecentAuthorityMismatchStoreTest {

    @Before
    fun setUp() {
        ProviderCacheDebugLogger.isEnabled = true
        RecentAuthorityMismatchStore.resetForDebug()
    }

    @After
    fun tearDown() {
        RecentAuthorityMismatchStore.resetForDebug()
        ProviderCacheDebugLogger.isEnabled = false
    }

    @Test
    fun mismatchSnapshot_generatedFromCompare() {
        val legacy = mapOf(
            "number:5551000" to ComparableRecentGroup(
                semanticKey = "number:5551000",
                callIds = setOf(1L, 2L),
                displayContactId = 10L,
                normalizedNumbers = setOf("5551000"),
                callCount = 2,
                latestCallId = 2L,
                latestTimestamp = 2000L,
                primaryNumber = "5551000",
            ),
        )
        val relational = mapOf(
            "number:5551000" to ComparableRecentGroup(
                semanticKey = "number:5551000",
                callIds = setOf(1L, 2L, 3L),
                displayContactId = 10L,
                normalizedNumbers = setOf("5551000"),
                callCount = 3,
                latestCallId = 3L,
                latestTimestamp = 3000L,
                primaryNumber = "5551000",
            ),
        )
        val mismatches = listOf(
            ComparableRecentGroupMismatch(
                mode = RecentGroupingMode.BY_NUMBER,
                semanticKey = "number:5551000",
                field = ComparableRecentGroupField.COUNT,
                oldValue = "2",
                newValue = "3",
            ),
            ComparableRecentGroupMismatch(
                mode = RecentGroupingMode.BY_NUMBER,
                semanticKey = "number:5551000",
                field = ComparableRecentGroupField.LATEST_CALL,
                oldValue = "2",
                newValue = "3",
            ),
        )

        RecentAuthorityMismatchStore.captureFromCompare(
            mode = RecentGroupingMode.BY_NUMBER,
            legacy = legacy,
            relational = relational,
            mismatches = mismatches,
            nowMs = 1_700_000_000_000L,
        )

        assertEquals(1, RecentAuthorityMismatchStore.size())
        val snap = RecentAuthorityMismatchStore.lastOrNull()!!
        assertEquals(RecentGroupingMode.BY_NUMBER, snap.groupingMode)
        assertEquals("number:5551000", snap.semanticGroupKey)
        assertEquals(setOf(1L, 2L), snap.legacyCallIds)
        assertEquals(setOf(1L, 2L, 3L), snap.relationalCallIds)
        assertEquals(2, snap.legacyCount)
        assertEquals(3, snap.relationalCount)
        assertEquals(2L, snap.legacyLatestCall)
        assertEquals(3L, snap.relationalLatestCall)
        assertEquals(2000L, snap.legacyTimestamp)
        assertEquals(3000L, snap.relationalTimestamp)
        assertTrue(snap.mismatchReason.contains("COUNT"))
        assertTrue(snap.mismatchReason.contains("LATEST_CALL"))
        assertTrue(RecentAuthorityMismatchStore.dump().contains("recentAuthorityMismatchSnapshot"))
    }

    @Test
    fun ringBuffer_keepsLast20() {
        repeat(25) { i ->
            RecentAuthorityMismatchStore.record(
                RecentAuthorityMismatchSnapshot(
                    capturedAtMs = i.toLong(),
                    groupingMode = RecentGroupingMode.BY_NUMBER,
                    semanticGroupKey = "number:$i",
                    legacyCallIds = emptySet(),
                    relationalCallIds = emptySet(),
                    legacyCount = 0,
                    relationalCount = 0,
                    legacyLatestCall = 0L,
                    relationalLatestCall = 0L,
                    legacyTimestamp = 0L,
                    relationalTimestamp = 0L,
                    normalizedNumbers = emptySet(),
                    legacyDisplayContactId = null,
                    relationalDisplayContactId = null,
                    mismatchReason = "TEST",
                ),
            )
        }
        assertEquals(20, RecentAuthorityMismatchStore.size())
        assertEquals("number:24", RecentAuthorityMismatchStore.lastOrNull()!!.semanticGroupKey)
        assertEquals("number:5", RecentAuthorityMismatchStore.all().first().semanticGroupKey)
    }
}
