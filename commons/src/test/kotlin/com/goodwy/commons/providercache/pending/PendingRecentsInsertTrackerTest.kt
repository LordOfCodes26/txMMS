package com.goodwy.commons.providercache.pending

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingRecentsInsertTrackerTest {

    private fun tracker(now: Long = 1_000_000L) = PendingRecentsInsertTracker(
        callLogDaoProvider = { null },
        nowMs = { now },
        log = {},
    )

    @Test
    fun stickyHistoryWithoutToken_peekIsNull() {
        val t = tracker()
        assertNull(t.peekPendingInsert())
    }

    @Test
    fun registerOutgoingDial_createsOneShotPendingWithImmutableBaseline() = runBlocking {
        val t = tracker()
        val token = t.registerOutgoingDial("5551234")
        assertEquals(PendingInsertState.PENDING, token.state)
        assertTrue(token.normalizedNumber.contains("5551234") || token.normalizedNumber == "5551234")
        assertEquals(BaselineQuality.UNAVAILABLE, token.baselineQuality)
        assertEquals(token.tokenId, t.peekPendingInsert()?.tokenId)
        assertNotNull(token.callAttemptId)
    }

    @Test
    fun attachBaseline_isNoOpAfterImmutableRegister() = runBlocking {
        val t = tracker()
        val token = t.registerOutgoingDial("111")
        t.attachBaseline(token.tokenId)
        assertEquals(BaselineQuality.UNAVAILABLE, t.peekPendingInsert()?.baselineQuality)
    }

    @Test
    fun previewPublished_idempotent() = runBlocking {
        val t = tracker()
        val token = t.registerOutgoingDial("111")
        t.markPreviewPublished(
            token.tokenId,
            ProvisionalRecentMutation(
                tokenId = token.tokenId,
                groupKey = "number:111",
                provisionalTimestamp = 1L,
                baselineCallCount = 2,
            ),
        )
        assertEquals(PendingInsertState.PREVIEW_PUBLISHED, t.peekPendingInsert()?.state)
        t.markPreviewPublished(
            token.tokenId,
            ProvisionalRecentMutation(
                tokenId = token.tokenId,
                groupKey = "number:111",
                provisionalTimestamp = 2L,
                baselineCallCount = 99,
            ),
        )
        assertEquals(2, t.getProvisionalMutation()?.baselineCallCount)
        assertEquals(PendingInsertState.PREVIEW_PUBLISHED, t.peekPendingInsert()?.state)
    }

    @Test
    fun authoritativePaint_consumesToken() = runBlocking {
        val t = tracker()
        val token = t.registerOutgoingDial("222")
        t.markPreviewPublished(
            token.tokenId,
            ProvisionalRecentMutation(token.tokenId, "number:222", 1L, 0),
        )
        t.markInsertConfirmed(token.tokenId, callId = 9L, timestamp = 100L)
        t.markAuthoritativePainted(token.tokenId, version = 3L)
        assertNull(t.peekPendingInsert())
        assertNull(t.getProvisionalMutation())
    }

    @Test
    fun expirePendingInsert_clearsAfterTtl() = runBlocking {
        var now = 1_000_000L
        val t = PendingRecentsInsertTracker(
            callLogDaoProvider = { null },
            nowMs = { now },
            log = {},
        )
        t.registerOutgoingDial("333")
        assertNotNull(t.peekPendingInsert())
        now += PendingRecentsInsertTracker.TOKEN_TTL_MS + 1
        t.expirePendingInsert(now)
        assertNull(t.peekPendingInsert())
    }

    @Test
    fun supersede_cancelsPreviousToken() = runBlocking {
        val t = tracker()
        val first = t.registerOutgoingDial("123")
        val second = t.registerOutgoingDial("456")
        assertEquals(second.tokenId, t.peekPendingInsert()?.tokenId)
        assertTrue(first.tokenId != second.tokenId)
    }

    @Test
    fun cancel_clearsPending() = runBlocking {
        val t = tracker()
        val token = t.registerOutgoingDial("999")
        t.cancelPendingInsert(token.tokenId, PendingInsertCancelReason.TIMEOUT)
        assertNull(t.peekPendingInsert())
    }

    @Test
    fun maskDigits_hidesPrefix() {
        assertEquals("****1234", PendingRecentsInsertTracker.maskDigits("5551234"))
        assertEquals("****", PendingRecentsInsertTracker.maskDigits("12"))
    }
}
