package com.goodwy.commons.providercache.pending

import android.util.Log
import com.goodwy.commons.providercache.dao.CallLogDao
import com.goodwy.commons.providercache.display.CanonicalPhoneNumberResolver
import com.goodwy.commons.providercache.sync.CallLogSyncChangeSet
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-scoped owner of one-shot outgoing CallLog insert tokens for Recents optimistic preview.
 * Sticky last-dialed history must never reconstruct a pending token.
 *
 * Baseline is captured exactly once at registration and never mutated afterward.
 */
class PendingRecentsInsertTracker(
    private val callLogDaoProvider: () -> CallLogDao?,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = { msg -> Log.d(TAG, msg) },
    private val snapshotVersionProvider: () -> Long = { 0L },
) {
    private val tokenSeq = AtomicLong(1L)
    private val attemptSeq = AtomicLong(1L)
    private val lock = Any()

    @Volatile
    private var pending: PendingRecentsCallInsert? = null

    @Volatile
    private var lastSyncChangeSet: CallLogSyncChangeSet? = null

    @Volatile
    private var provisionalMutation: ProvisionalRecentMutation? = null

    fun peekPendingInsert(): PendingRecentsCallInsert? {
        expirePendingInsert(nowMs())
        return synchronized(lock) { pending?.takeIf { CallLogInsertEvidenceEvaluator.isActiveState(it.state) } }
    }

    fun getProvisionalMutation(): ProvisionalRecentMutation? = provisionalMutation

    fun lastSyncChangeSet(): CallLogSyncChangeSet? = lastSyncChangeSet

    /**
     * Registers a pending outgoing insert with an immutable baseline captured in this call.
     * Same [callAttemptId] reuses the existing active token (placeCall + onCallAdded dedupe).
     */
    suspend fun registerOutgoingDial(
        number: String,
        callAttemptId: String? = null,
    ): PendingRecentsCallInsert = registerOutgoingDialResult(number, callAttemptId).token

    suspend fun registerOutgoingDialResult(
        number: String,
        callAttemptId: String? = null,
    ): PendingInsertRegistrationResult {
        val digits = CanonicalPhoneNumberResolver.canonicalDigits(number, number)
        val placedAt = nowMs()
        val attempt = callAttemptId ?: "attempt-${attemptSeq.getAndIncrement()}"

        synchronized(lock) {
            val existing = pending
            if (existing != null &&
                CallLogInsertEvidenceEvaluator.isActiveState(existing.state) &&
                existing.callAttemptId == attempt
            ) {
                log(
                    "pendingRecentsInsert register action=REUSED attempt=$attempt " +
                        "token=${existing.tokenId} numberMasked=${maskDigits(digits)}",
                )
                return PendingInsertRegistrationResult.Reused(existing)
            }
        }

        val baseline = captureBaselineOnce(digits)
        val token = PendingRecentsCallInsert(
            tokenId = tokenSeq.getAndIncrement(),
            normalizedNumber = digits,
            placedAtMillis = placedAt,
            callAttemptId = attempt,
            baselineSnapshotVersion = baseline.snapshotVersion,
            baselineQuality = baseline.quality,
            baselineMaxCallId = baseline.maxCallId,
            baselineLatestTimestamp = baseline.latestTimestamp,
            baselineMatchingCallIds = baseline.matchingCallIds,
            baselineRowCount = baseline.rowCount,
            state = PendingInsertState.PENDING,
        )
        var replacedId: Long? = null
        var keepConfirmed = false
        synchronized(lock) {
            pending?.let { old ->
                if (CallLogInsertEvidenceEvaluator.isActiveState(old.state)) {
                    if (old.state == PendingInsertState.INSERT_CONFIRMED) {
                        keepConfirmed = true
                        log(
                            "pendingRecentsInsert register action=SKIP_KEEP_CONFIRMED attempt=$attempt " +
                                "token=${old.tokenId} ignoredNewNumber=${maskDigits(digits)}",
                        )
                    } else {
                        replacedId = old.tokenId
                        transitionLocked(
                            old.copy(state = PendingInsertState.CANCELLED),
                            PendingInsertState.CANCELLED,
                            PendingInsertCancelReason.SUPERSEDED.logName,
                        )
                    }
                }
            }
            if (keepConfirmed) {
                // Keep confirmed token; return reused existing for mapping purposes.
                return@synchronized
            }
            pending = token
            provisionalMutation = null
        }
        if (keepConfirmed) {
            val existing = synchronized(lock) { pending }!!
            return PendingInsertRegistrationResult.Reused(existing)
        }
        val action = if (replacedId != null) "REPLACED" else "CREATED"
        log(
            "pendingRecentsInsert register action=$action attempt=$attempt " +
                "token=${token.tokenId} numberMasked=${maskDigits(digits)} placedAt=$placedAt " +
                "baselineQuality=${baseline.quality} baselineMaxId=${baseline.maxCallId} " +
                "baselineTs=${baseline.latestTimestamp} matchingIds=${baseline.matchingCallIds.size} " +
                "rowCount=${baseline.rowCount}" +
                (replacedId?.let { " previousToken=$it" } ?: ""),
        )
        return if (replacedId != null) {
            PendingInsertRegistrationResult.Replaced(replacedId!!, token)
        } else {
            PendingInsertRegistrationResult.Created(token)
        }
    }

    /**
     * @deprecated Baseline is immutable at registration. No-op retained for compatibility.
     */
    @Deprecated("Baseline is captured once at registerOutgoingDial; do not re-attach")
    suspend fun attachBaseline(tokenId: Long) {
        // Intentionally no-op: mutating baseline after registration races short calls.
        val current = synchronized(lock) { pending }
        if (current?.tokenId == tokenId) {
            log("pendingRecentsInsert attachBaseline ignored token=$tokenId quality=${current.baselineQuality}")
        }
    }

    suspend fun captureBaselineOnce(
        normalizedNumber: String,
        dao: CallLogDao? = callLogDaoProvider(),
    ): CallLogInsertBaseline {
        if (dao == null) {
            return CallLogInsertBaseline(
                maxCallId = null,
                latestTimestamp = null,
                matchingCallIds = emptySet(),
                rowCount = 0,
                snapshotVersion = snapshotVersionProvider(),
                quality = BaselineQuality.UNAVAILABLE,
            )
        }
        return runCatching {
            val maxCallId = dao.getMaxCallId()?.toLong()
            val latestTs = dao.getMaxStartTimestamp()
            val matching = if (normalizedNumber.isNotEmpty()) {
                dao.getCallIdsByPhoneNumbers(listOf(normalizedNumber)).map { it.toLong() }.toSet()
            } else {
                emptySet()
            }
            val count = dao.getCount()
            CallLogInsertBaseline(
                maxCallId = maxCallId,
                latestTimestamp = latestTs,
                matchingCallIds = matching,
                rowCount = count,
                snapshotVersion = snapshotVersionProvider(),
                quality = BaselineQuality.COMPLETE,
            )
        }.getOrElse {
            CallLogInsertBaseline(
                maxCallId = null,
                latestTimestamp = null,
                matchingCallIds = emptySet(),
                rowCount = 0,
                snapshotVersion = snapshotVersionProvider(),
                quality = BaselineQuality.UNAVAILABLE,
            )
        }
    }

    /** Kept for tests; prefers [captureBaselineOnce]. */
    suspend fun captureBaseline(
        normalizedNumber: String,
        dao: CallLogDao = requireNotNull(callLogDaoProvider()),
    ): CallLogInsertBaseline = captureBaselineOnce(normalizedNumber, dao)

    fun onSyncChangeSet(changes: CallLogSyncChangeSet) {
        lastSyncChangeSet = changes
    }

    fun markPreviewPublished(tokenId: Long, mutation: ProvisionalRecentMutation) {
        synchronized(lock) {
            val current = pending ?: return
            if (current.tokenId != tokenId) return
            if (current.state == PendingInsertState.PREVIEW_PUBLISHED ||
                current.state == PendingInsertState.INSERT_CONFIRMED ||
                current.state == PendingInsertState.AUTHORITATIVE_PAINTED
            ) {
                return
            }
            provisionalMutation = mutation
            transitionLocked(
                current.copy(state = PendingInsertState.PREVIEW_PUBLISHED),
                PendingInsertState.PREVIEW_PUBLISHED,
                "preview",
            )
        }
    }

    fun markInsertConfirmed(tokenId: Long, callId: Long, timestamp: Long) {
        synchronized(lock) {
            val current = pending ?: return
            if (current.tokenId != tokenId) return
            if (current.state == PendingInsertState.INSERT_CONFIRMED ||
                current.state == PendingInsertState.AUTHORITATIVE_PAINTED
            ) {
                return
            }
            transitionLocked(
                current.copy(
                    state = PendingInsertState.INSERT_CONFIRMED,
                    confirmedCallId = callId,
                    confirmedTimestamp = timestamp,
                ),
                PendingInsertState.INSERT_CONFIRMED,
                "callId=$callId",
            )
        }
    }

    fun markAuthoritativePainted(tokenId: Long, version: Long) {
        synchronized(lock) {
            val current = pending ?: return
            if (current.tokenId != tokenId) return
            transitionLocked(
                current.copy(state = PendingInsertState.AUTHORITATIVE_PAINTED),
                PendingInsertState.AUTHORITATIVE_PAINTED,
                "version=$version",
            )
            log("pendingRecentsInsert consumed token=$tokenId version=$version")
            pending = null
            provisionalMutation = null
        }
    }

    fun cancelPendingInsert(tokenId: Long, reason: PendingInsertCancelReason) {
        synchronized(lock) {
            val current = pending ?: return
            if (current.tokenId != tokenId) return
            transitionLocked(
                current.copy(state = PendingInsertState.CANCELLED),
                PendingInsertState.CANCELLED,
                reason.logName,
            )
            pending = null
            provisionalMutation = null
        }
    }

    fun expirePendingInsert(now: Long = nowMs()) {
        synchronized(lock) {
            val current = pending ?: return
            if (!CallLogInsertEvidenceEvaluator.isActiveState(current.state)) {
                pending = null
                return
            }
            val age = now - current.placedAtMillis
            if (age <= TOKEN_TTL_MS) return
            transitionLocked(
                current.copy(state = PendingInsertState.EXPIRED),
                PendingInsertState.EXPIRED,
                "ageMs=$age",
            )
            log("pendingRecentsInsert expired token=${current.tokenId} ageMs=$age")
            pending = null
            provisionalMutation = null
        }
    }

    fun clearProvisionalMutation() {
        provisionalMutation = null
    }

    /** Test / debug helper. */
    fun replacePendingForTest(value: PendingRecentsCallInsert?) {
        synchronized(lock) {
            pending = value
            if (value == null) provisionalMutation = null
        }
    }

    private fun transitionLocked(
        next: PendingRecentsCallInsert,
        to: PendingInsertState,
        reason: String,
    ) {
        val from = pending?.state
        pending = next
        log("pendingRecentsInsert state token=${next.tokenId} from=$from to=$to reason=$reason")
    }

    companion object {
        private const val TAG = "PendingRecentsInsert"
        const val TOKEN_TTL_MS = 5 * 60 * 1000L

        fun maskDigits(digits: String): String {
            if (digits.length <= 4) return "****"
            return "****${digits.takeLast(4)}"
        }
    }
}
