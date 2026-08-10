package com.goodwy.commons.providercache.pending

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLogInsertEvidenceEvaluatorTest {

    private fun pending(
        matchingIds: Set<Long> = setOf(1L, 2L, 3L),
        maxId: Long? = 5L,
        latestTs: Long? = 1_000L,
        placedAt: Long = 2_000_000L,
        rowCount: Int? = 10,
        number: String = "123",
        quality: BaselineQuality = BaselineQuality.COMPLETE,
    ) = PendingRecentsCallInsert(
        tokenId = 1L,
        normalizedNumber = number,
        placedAtMillis = placedAt,
        callAttemptId = "attempt-1",
        baselineSnapshotVersion = 0L,
        baselineQuality = quality,
        baselineMaxCallId = maxId,
        baselineLatestTimestamp = latestTs,
        baselineMatchingCallIds = matchingIds,
        baselineRowCount = rowCount,
        state = PendingInsertState.PENDING,
    )

    private fun row(id: Long, digits: String = "123", tsSec: Long) =
        CallLogInsertEvidenceEvaluator.CandidateRow(id, digits, tsSec)

    @Test
    fun historicalPresenceAloneNeverConfirms() {
        val p = pending()
        // Existing IDs for 123 already in baseline — not novel.
        val evidence = CallLogInsertEvidenceEvaluator.findEvidence(
            pending = p,
            syncInsertedCandidates = emptyList(),
            roomMatchingCandidates = listOf(row(1, tsSec = 900), row(2, tsSec = 950), row(3, tsSec = 1000)),
            previousRowCount = 10,
            currentRowCount = 10,
        )
        assertNull(evidence)
    }

    @Test
    fun newCallIdConfirmsInsert() {
        val p = pending()
        val evidence = CallLogInsertEvidenceEvaluator.findEvidence(
            pending = p,
            syncInsertedCandidates = emptyList(),
            roomMatchingCandidates = listOf(row(6, tsSec = 2_000)),
            previousRowCount = 10,
            currentRowCount = 11,
        )
        assertNotNull(evidence)
        assertEquals(6L, evidence!!.callId)
        assertEquals(EvidenceSource.NEW_CALL_ID, evidence.source)
    }

    @Test
    fun syncDeltaIsPreferredSource() {
        val p = pending()
        val evidence = CallLogInsertEvidenceEvaluator.findEvidence(
            pending = p,
            syncInsertedCandidates = listOf(row(6, tsSec = 2_000)),
            roomMatchingCandidates = listOf(row(6, tsSec = 2_000)),
            previousRowCount = 10,
            currentRowCount = 11,
        )
        assertNotNull(evidence)
        assertEquals(EvidenceSource.SYNC_DELTA, evidence!!.source)
    }

    @Test
    fun mismatchedNumberDoesNotConfirm() {
        val p = pending(number = "123")
        val evidence = CallLogInsertEvidenceEvaluator.findEvidence(
            pending = p,
            syncInsertedCandidates = listOf(row(6, digits = "456", tsSec = 2_000)),
            roomMatchingCandidates = listOf(row(6, digits = "456", tsSec = 2_000)),
            previousRowCount = 10,
            currentRowCount = 11,
        )
        assertNull(evidence)
    }

    @Test
    fun missingBaselineDoesNotTreatHistoryAsNovelById() {
        val p = pending(
            matchingIds = emptySet(),
            maxId = null,
            latestTs = null,
            rowCount = null,
            quality = BaselineQuality.UNAVAILABLE,
        )
        val evidence = CallLogInsertEvidenceEvaluator.findEvidence(
            pending = p,
            syncInsertedCandidates = emptyList(),
            roomMatchingCandidates = listOf(row(3, tsSec = 500)), // old history
            previousRowCount = null,
            currentRowCount = 10,
        )
        assertNull(evidence)
    }

    @Test
    fun missingBaselineConfirmsViaTimestampNearPlacedAt() {
        val placedAt = 2_000_000L
        val p = pending(
            matchingIds = emptySet(),
            maxId = null,
            latestTs = null,
            rowCount = null,
            placedAt = placedAt,
            quality = BaselineQuality.UNAVAILABLE,
        )
        val evidence = CallLogInsertEvidenceEvaluator.findEvidence(
            pending = p,
            syncInsertedCandidates = emptyList(),
            roomMatchingCandidates = listOf(row(99, tsSec = placedAt / 1000)),
            previousRowCount = 10,
            currentRowCount = 11,
        )
        assertNotNull(evidence)
        assertEquals(EvidenceSource.NEW_TIMESTAMP, evidence!!.source)
    }

    @Test
    fun classifySyncDelta() {
        assertEquals(
            CallLogChangeKind.CLEAR_ALL,
            CallLogInsertEvidenceEvaluator.classifySyncDelta(
                insertedCallIds = emptyList(),
                deletedCallIds = emptyList(),
                clearedAll = true,
            ),
        )
        assertEquals(
            CallLogChangeKind.DELETE,
            CallLogInsertEvidenceEvaluator.classifySyncDelta(
                insertedCallIds = emptyList(),
                deletedCallIds = listOf(1L),
                clearedAll = false,
            ),
        )
        assertEquals(
            CallLogChangeKind.POSSIBLE_INSERT,
            CallLogInsertEvidenceEvaluator.classifySyncDelta(
                insertedCallIds = listOf(9L),
                deletedCallIds = emptyList(),
                clearedAll = false,
            ),
        )
        assertEquals(
            CallLogChangeKind.UPDATE,
            CallLogInsertEvidenceEvaluator.classifySyncDelta(
                insertedCallIds = emptyList(),
                deletedCallIds = emptyList(),
                clearedAll = false,
                updatedCallIds = listOf(2L),
            ),
        )
    }

    @Test
    fun isNovelCallIdRequiresBaselineMembershipCapture() {
        val withBaseline = pending(matchingIds = setOf(1L, 2L), maxId = 5L, rowCount = 5)
        assertTrue(CallLogInsertEvidenceEvaluator.isNovelCallId(withBaseline, 6L))
        assertFalse(CallLogInsertEvidenceEvaluator.isNovelCallId(withBaseline, 2L))

        val noBaseline = pending(
            matchingIds = emptySet(),
            maxId = null,
            rowCount = null,
            quality = BaselineQuality.UNAVAILABLE,
        )
        assertFalse(CallLogInsertEvidenceEvaluator.isNovelCallId(noBaseline, 2L))
    }
}
