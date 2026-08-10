package com.goodwy.commons.providercache.pending

/**
 * One-shot token for a single outgoing CallLog insert expected by Recents optimistic preview.
 * Never reconstruct pending state from sticky [lastDialedNumber].
 *
 * Baseline is immutable after registration — never re-captured after the token is published.
 */
data class PendingRecentsCallInsert(
    val tokenId: Long,
    val normalizedNumber: String,
    val placedAtMillis: Long,
    val callAttemptId: String,
    val baselineSnapshotVersion: Long,
    val baselineQuality: BaselineQuality,
    val baselineMaxCallId: Long?,
    val baselineLatestTimestamp: Long?,
    val baselineMatchingCallIds: Set<Long>,
    val baselineRowCount: Int?,
    val state: PendingInsertState,
    val confirmedCallId: Long? = null,
    val confirmedTimestamp: Long? = null,
)

enum class BaselineQuality {
    /** Max id, latest timestamp, matching ids, and row count captured atomically. */
    COMPLETE,
    /** Partial fields only (e.g. max id + timestamp without matching set). */
    PARTIAL,
    /** No Room snapshot available; novelty must use sync delta or timestamp after placedAt. */
    UNAVAILABLE,
}

enum class PendingInsertState {
    PENDING,
    PREVIEW_PUBLISHED,
    INSERT_CONFIRMED,
    AUTHORITATIVE_PAINTED,
    EXPIRED,
    CANCELLED,
}

enum class CallLogChangeKind {
    POSSIBLE_INSERT,
    DELETE,
    CLEAR_ALL,
    UPDATE,
    UNKNOWN,
    NO_CHANGE,
}

enum class EvidenceSource {
    SYNC_DELTA,
    NEW_CALL_ID,
    NEW_TIMESTAMP,
    ROW_COUNT_GROWTH,
}

data class CallLogInsertEvidence(
    val callId: Long,
    val normalizedNumber: String,
    val startTimestamp: Long,
    val source: EvidenceSource,
)

data class CallLogInsertBaseline(
    val maxCallId: Long?,
    val latestTimestamp: Long?,
    val matchingCallIds: Set<Long>,
    val rowCount: Int,
    val snapshotVersion: Long = 0L,
    val quality: BaselineQuality = BaselineQuality.COMPLETE,
)

data class ProvisionalRecentMutation(
    val tokenId: Long,
    val groupKey: String,
    val provisionalTimestamp: Long,
    val baselineCallCount: Int,
)

enum class RecentsCatchUpReason {
    CALL_LOG_CHANGED,
    APP_RESUME,
    NEED_UPDATE_RECENTS,
    TAB_ENTER,
}

enum class PendingInsertCancelReason(val logName: String) {
    INSERT_NOT_FOUND("INSERT_NOT_FOUND"),
    CALL_CANCELLED("CALL_CANCELLED"),
    TIMEOUT("TIMEOUT"),
    PROCESS_RESTART_STALE("PROCESS_RESTART_STALE"),
    NUMBER_MISMATCH("NUMBER_MISMATCH"),
    CLEAR_ALL("CLEAR_ALL"),
    SUPERSEDED("SUPERSEDED"),
    AUTHORITATIVE_MISMATCH("AUTHORITATIVE_MISMATCH"),
}

sealed class PendingInsertRegistrationResult {
    data class Created(val insert: PendingRecentsCallInsert) : PendingInsertRegistrationResult()
    data class Reused(val insert: PendingRecentsCallInsert) : PendingInsertRegistrationResult()
    data class Replaced(
        val previousTokenId: Long,
        val insert: PendingRecentsCallInsert,
    ) : PendingInsertRegistrationResult()

    val token: PendingRecentsCallInsert
        get() = when (this) {
            is Created -> insert
            is Reused -> insert
            is Replaced -> insert
        }
}
