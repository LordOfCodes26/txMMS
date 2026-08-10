package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger
import java.util.ArrayDeque

/**
 * Debug-only snapshot of one Recents authority compare failure.
 * Persisted in-memory (last [MAX_SNAPSHOTS]); no production behavior.
 */
data class RecentAuthorityMismatchSnapshot(
    val capturedAtMs: Long,
    val groupingMode: RecentGroupingMode,
    val semanticGroupKey: String,
    val legacyCallIds: Set<Long>,
    val relationalCallIds: Set<Long>,
    val legacyCount: Int,
    val relationalCount: Int,
    val legacyLatestCall: Long,
    val relationalLatestCall: Long,
    val legacyTimestamp: Long,
    val relationalTimestamp: Long,
    val normalizedNumbers: Set<String>,
    val legacyDisplayContactId: Long?,
    val relationalDisplayContactId: Long?,
    val mismatchReason: String,
) {
    fun toLogLine(): String =
        "recentAuthorityMismatchSnapshot mode=${groupingMode.name} key=$semanticGroupKey " +
            "reason=$mismatchReason legacyCount=$legacyCount relationalCount=$relationalCount " +
            "legacyLatest=$legacyLatestCall/$legacyTimestamp relationalLatest=$relationalLatestCall/$relationalTimestamp " +
            "legacyCalls=${legacyCallIds.sorted().joinToString(",")} " +
            "relationalCalls=${relationalCallIds.sorted().joinToString(",")} " +
            "numbers=${normalizedNumbers.sorted().joinToString(",")} " +
            "legacyContact=$legacyDisplayContactId relationalContact=$relationalDisplayContactId " +
            "at=$capturedAtMs"
}

object RecentAuthorityMismatchStore {

    private const val MAX_SNAPSHOTS = 20

    private val lock = Any()
    private val snapshots = ArrayDeque<RecentAuthorityMismatchSnapshot>(MAX_SNAPSHOTS)

    @Volatile
    private var lastConsistencyPass: Boolean? = null

    @Volatile
    private var lastConsistencyDeferred: Boolean = false

    @Volatile
    private var lastConsistencySummary: String = "NOT_RUN"

    fun captureFromCompare(
        mode: RecentGroupingMode,
        legacy: Map<String, ComparableRecentGroup>,
        relational: Map<String, ComparableRecentGroup>,
        mismatches: List<ComparableRecentGroupMismatch>,
        displayMismatches: List<ComparableDisplayMismatch> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (mismatches.isEmpty() && displayMismatches.isEmpty()) return
        val byKey = linkedMapOf<String, MutableList<String>>()
        mismatches.forEach { m ->
            byKey.getOrPut(m.semanticKey) { mutableListOf() }
                .add("${m.field.name}:${m.oldValue}->${m.newValue}")
        }
        displayMismatches.forEach { m ->
            byKey.getOrPut(m.semanticKey) { mutableListOf() }
                .add("DISPLAY_${m.field.name}:${m.oldValue}->${m.newValue}")
        }
        byKey.forEach { (key, reasons) ->
            val old = legacy[key]
            val neu = relational[key]
            record(
                RecentAuthorityMismatchSnapshot(
                    capturedAtMs = nowMs,
                    groupingMode = mode,
                    semanticGroupKey = key,
                    legacyCallIds = old?.callIds.orEmpty(),
                    relationalCallIds = neu?.callIds.orEmpty(),
                    legacyCount = old?.callCount ?: 0,
                    relationalCount = neu?.callCount ?: 0,
                    legacyLatestCall = old?.latestCallId ?: 0L,
                    relationalLatestCall = neu?.latestCallId ?: 0L,
                    legacyTimestamp = old?.latestTimestamp ?: 0L,
                    relationalTimestamp = neu?.latestTimestamp ?: 0L,
                    normalizedNumbers = (old?.normalizedNumbers.orEmpty() + neu?.normalizedNumbers.orEmpty()),
                    legacyDisplayContactId = old?.displayContactId,
                    relationalDisplayContactId = neu?.displayContactId,
                    mismatchReason = reasons.joinToString(";"),
                ),
            )
        }
    }

    fun record(snapshot: RecentAuthorityMismatchSnapshot) {
        synchronized(lock) {
            if (snapshots.size >= MAX_SNAPSHOTS) {
                snapshots.removeFirst()
            }
            snapshots.addLast(snapshot)
        }
        ProviderCacheDebugLogger.log(snapshot.toLogLine())
    }

    fun dump(): String = synchronized(lock) {
        if (snapshots.isEmpty()) {
            return@synchronized "recentAuthorityMismatches count=0"
        }
        buildString {
            appendLine("recentAuthorityMismatches count=${snapshots.size}")
            snapshots.toList().asReversed().forEach { snap ->
                appendLine(snap.toLogLine())
            }
        }.trimEnd()
    }

    fun clear() {
        synchronized(lock) {
            snapshots.clear()
        }
        ProviderCacheDebugLogger.log("recentAuthorityMismatches cleared")
    }

    fun lastOrNull(): RecentAuthorityMismatchSnapshot? = synchronized(lock) {
        snapshots.lastOrNull()
    }

    fun size(): Int = synchronized(lock) { snapshots.size }

    fun all(): List<RecentAuthorityMismatchSnapshot> = synchronized(lock) {
        snapshots.toList()
    }

    fun setLastConsistencyResult(pass: Boolean, summary: String) {
        lastConsistencyPass = pass
        lastConsistencyDeferred = false
        lastConsistencySummary = summary
    }

    fun setLastConsistencyDeferred(summary: String) {
        lastConsistencyPass = null
        lastConsistencyDeferred = true
        lastConsistencySummary = summary
    }

    fun lastConsistencyPass(): Boolean? = lastConsistencyPass

    fun lastConsistencyDeferred(): Boolean = lastConsistencyDeferred

    fun lastConsistencyLabel(): String = when {
        lastConsistencyDeferred -> "DEFERRED"
        lastConsistencyPass == true -> "PASS"
        lastConsistencyPass == false -> "FAIL"
        else -> "NOT_RUN"
    }

    fun lastConsistencySummary(): String = lastConsistencySummary

    fun resetForDebug() {
        clear()
        lastConsistencyPass = null
        lastConsistencyDeferred = false
        lastConsistencySummary = "NOT_RUN"
    }
}
