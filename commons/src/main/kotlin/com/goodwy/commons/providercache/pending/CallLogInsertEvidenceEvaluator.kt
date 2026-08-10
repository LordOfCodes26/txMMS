package com.goodwy.commons.providercache.pending

/**
 * Pure rules for confirming a genuinely new CallLog row for a pending outgoing dial.
 * Historical presence of [PendingRecentsCallInsert.normalizedNumber] alone never confirms.
 */
object CallLogInsertEvidenceEvaluator {

    /** Provider DATE / start_ts skew relative to [PendingRecentsCallInsert.placedAtMillis]. */
    const val ALLOWED_PROVIDER_SKEW_MS = 15_000L

    data class CandidateRow(
        val callId: Long,
        val normalizedDigits: String,
        /** Room [call_log_entries.start_ts] — seconds since epoch. */
        val startTsSeconds: Long,
    )

    fun isActiveState(state: PendingInsertState): Boolean =
        state == PendingInsertState.PENDING ||
            state == PendingInsertState.PREVIEW_PUBLISHED ||
            state == PendingInsertState.INSERT_CONFIRMED

    fun classifySyncDelta(
        insertedCallIds: Collection<Long>,
        deletedCallIds: Collection<Long>,
        clearedAll: Boolean,
        updatedCallIds: Collection<Long> = emptyList(),
    ): CallLogChangeKind {
        if (clearedAll) return CallLogChangeKind.CLEAR_ALL
        if (deletedCallIds.isNotEmpty() && insertedCallIds.isEmpty()) return CallLogChangeKind.DELETE
        if (insertedCallIds.isNotEmpty()) return CallLogChangeKind.POSSIBLE_INSERT
        if (updatedCallIds.isNotEmpty()) return CallLogChangeKind.UPDATE
        return CallLogChangeKind.UNKNOWN
    }

    /**
     * Returns evidence for [pending] from sync-delta candidates first, then Room candidates.
     * [previousRowCount] / [currentRowCount] enable ROW_COUNT_GROWTH only with a matching novel row.
     */
    fun findEvidence(
        pending: PendingRecentsCallInsert,
        syncInsertedCandidates: List<CandidateRow>,
        roomMatchingCandidates: List<CandidateRow>,
        previousRowCount: Int?,
        currentRowCount: Int?,
    ): CallLogInsertEvidence? {
        if (!isActiveState(pending.state)) return null

        findAmong(pending, syncInsertedCandidates, EvidenceSource.SYNC_DELTA)?.let { return it }

        val novelById = roomMatchingCandidates.filter { isNovelCallId(pending, it.callId) }
        findAmong(pending, novelById, EvidenceSource.NEW_CALL_ID)?.let { return it }

        val novelByTs = roomMatchingCandidates.filter { isNovelTimestamp(pending, it.startTsSeconds) }
        findAmong(pending, novelByTs, EvidenceSource.NEW_TIMESTAMP)?.let { return it }

        if (previousRowCount != null &&
            currentRowCount != null &&
            currentRowCount > previousRowCount
        ) {
            val grown = roomMatchingCandidates.filter {
                isNovelCallId(pending, it.callId) || isNovelTimestamp(pending, it.startTsSeconds)
            }
            findAmong(pending, grown, EvidenceSource.ROW_COUNT_GROWTH)?.let { return it }
        }
        return null
    }

    fun isNovelCallId(pending: PendingRecentsCallInsert, callId: Long): Boolean {
        val baselineMax = pending.baselineMaxCallId
        if (baselineMax != null && callId > baselineMax) return true
        // Membership novelty only when a baseline snapshot was captured.
        // Without baseline, "not in empty matching set" would treat every historical
        // row as novel — never use that fallback.
        if (pending.baselineQuality != BaselineQuality.UNAVAILABLE &&
            pending.baselineRowCount != null
        ) {
            return callId !in pending.baselineMatchingCallIds
        }
        return false
    }

    fun isNovelTimestamp(pending: PendingRecentsCallInsert, startTsSeconds: Long): Boolean {
        val startMs = startTsSeconds * 1000L
        // Reject history that predates the place-call moment (historical presence ≠ insert).
        if (startMs < pending.placedAtMillis - ALLOWED_PROVIDER_SKEW_MS) return false
        val baselineTs = pending.baselineLatestTimestamp
        if (baselineTs != null && startTsSeconds <= baselineTs) return false
        return true
    }

    fun numbersMatch(pendingDigits: String, candidateDigits: String): Boolean {
        if (pendingDigits.isEmpty() || candidateDigits.isEmpty()) return false
        if (pendingDigits == candidateDigits) return true
        if (pendingDigits.length >= 7 && candidateDigits.length >= 7 &&
            pendingDigits.takeLast(10) == candidateDigits.takeLast(10)
        ) {
            return true
        }
        return false
    }

    private fun findAmong(
        pending: PendingRecentsCallInsert,
        candidates: List<CandidateRow>,
        preferredSource: EvidenceSource,
    ): CallLogInsertEvidence? {
        val match = candidates.firstOrNull { numbersMatch(pending.normalizedNumber, it.normalizedDigits) }
            ?: return null
        if (!isNovelCallId(pending, match.callId) && !isNovelTimestamp(pending, match.startTsSeconds)) {
            // SYNC_DELTA inserted IDs are inherently novel; allow when source is SYNC_DELTA.
            if (preferredSource != EvidenceSource.SYNC_DELTA) return null
        }
        return CallLogInsertEvidence(
            callId = match.callId,
            normalizedNumber = pending.normalizedNumber,
            startTimestamp = match.startTsSeconds,
            source = preferredSource,
        )
    }
}
