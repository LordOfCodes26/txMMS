package com.goodwy.commons.providercache.pending

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingRecentsInsertDedupeTest {

    @Test
    fun sameCallAttemptId_reusesToken() = runBlocking {
        val t = PendingRecentsInsertTracker(callLogDaoProvider = { null }, log = {})
        val first = t.registerOutgoingDialResult("5551000", callAttemptId = "attempt-A")
        val second = t.registerOutgoingDialResult("5551000", callAttemptId = "attempt-A")
        assertTrue(first is PendingInsertRegistrationResult.Created)
        assertTrue(second is PendingInsertRegistrationResult.Reused)
        assertEquals(first.token.tokenId, second.token.tokenId)
    }

    @Test
    fun differentAttempts_replaceUnconfirmed() = runBlocking {
        val t = PendingRecentsInsertTracker(callLogDaoProvider = { null }, log = {})
        val first = t.registerOutgoingDialResult("111", callAttemptId = "a1")
        val second = t.registerOutgoingDialResult("222", callAttemptId = "a2")
        assertTrue(second is PendingInsertRegistrationResult.Replaced)
        assertEquals(first.token.tokenId, (second as PendingInsertRegistrationResult.Replaced).previousTokenId)
        assertEquals(second.token.tokenId, t.peekPendingInsert()?.tokenId)
    }
}
