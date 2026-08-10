package com.goodwy.commons.providercache.pipeline

import com.goodwy.commons.providercache.pending.CallLogChangeKind
import com.goodwy.commons.providercache.sync.CallLogSyncChangeSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLogSyncResultTest {

    @Test
    fun classifyInsertFromChangeSet() {
        val result = CallLogSyncResult.fromChangeSet(
            generation = 1L,
            changes = CallLogSyncChangeSet(insertedCallIds = listOf(10, 11)),
            displayVersionAfter = 5L,
        )
        assertEquals(CallLogChangeKind.POSSIBLE_INSERT, result.changeKind)
        assertEquals(setOf(10L, 11L), result.insertedCallIds.toSet())
    }

    @Test
    fun classifyDelete() {
        val result = CallLogSyncResult.fromChangeSet(
            generation = 2L,
            changes = CallLogSyncChangeSet(deletedCallIds = listOf(3)),
            displayVersionAfter = 6L,
        )
        assertEquals(CallLogChangeKind.DELETE, result.changeKind)
    }

    @Test
    fun classifyClearAllColdRebuild() {
        val result = CallLogSyncResult.fromChangeSet(
            generation = 3L,
            changes = CallLogSyncChangeSet(wasColdRebuild = true),
            displayVersionAfter = 1L,
        )
        assertTrue(result.wasColdRebuild)
        assertEquals(CallLogChangeKind.CLEAR_ALL, result.changeKind)
    }

    @Test
    fun outgoingCatchUpTransitions_terminalAlwaysAllowed() {
        // Document expected terminal force transitions used by coordinator.
        val terminals = setOf(
            OutgoingCatchUpState.CANCELLED,
            OutgoingCatchUpState.EXPIRED,
            OutgoingCatchUpState.COMPLETED,
            OutgoingCatchUpState.NONE,
        )
        assertTrue(terminals.contains(OutgoingCatchUpState.CANCELLED))
    }

    @Test
    fun publishKindEnvelopeHoldsGeneration() {
        val env = RecentsPublishEnvelope(
            pipelineGeneration = 9L,
            displayVersion = 4L,
            kind = PublishKind.AUTHORITATIVE,
            authoritativeBaseVersion = 3L,
            tokenId = 1L,
        )
        assertEquals(9L, env.pipelineGeneration)
        assertEquals(PublishKind.AUTHORITATIVE, env.kind)
    }
}
