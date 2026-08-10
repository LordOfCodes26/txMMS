package com.goodwy.commons.providercache.display

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Debounces recent display-cache rebuild requests from call-log sync.
 */
class RecentDisplayCacheRebuildScheduler(
    private val scope: CoroutineScope,
    private val onComplete: (RecentDisplayRebuildRequest, RecentDisplayCacheRebuildResult) -> Unit = { _, _ -> },
    private val debounceMs: Long = ContactDisplayCacheRebuildScheduler.DEFAULT_DEBOUNCE_MS,
) {
    private val mutex = Mutex()
    private var pendingJob: Job? = null
    private var pendingInserted = mutableSetOf<Int>()
    private var pendingDeleted = mutableSetOf<Int>()
    private var pendingReason = DisplayCacheRebuildReason.CALL_LOG_SYNC_COMPLETED
    private var pendingGroupByContact = true
    private var forceFull = false
    private var pendingLimit = 1000
    @Volatile
    private var rebuildInProgress = false
    @Volatile
    private var pendingColdRebuild = false

    var rebuildHandler: suspend (RecentDisplayRebuildRequest) -> RecentDisplayCacheRebuildResult = {
        RecentDisplayCacheRebuildResult.EMPTY
    }

    fun isRebuildInProgress(): Boolean = rebuildInProgress

    fun hasPendingColdRebuild(): Boolean = pendingColdRebuild || pendingJob?.isActive == true &&
        (pendingReason == DisplayCacheRebuildReason.COLD_EMPTY_CACHE || forceFull)

    fun schedule(request: RecentDisplayRebuildRequest) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                mergeRequest(request)
                val delayMs = effectiveDebounceMs()
                pendingJob?.cancel()
                pendingJob = scope.launch(Dispatchers.IO) {
                    delay(delayMs)
                    runPendingRebuild()
                }
            }
        }
    }

    private fun effectiveDebounceMs(): Long {
        if (pendingColdRebuild || forceFull) return debounceMs
        if (pendingInserted.isNotEmpty() && pendingDeleted.isEmpty() &&
            pendingReason == DisplayCacheRebuildReason.CALL_LOG_INSERTED
        ) {
            return INSERT_DEBOUNCE_MS
        }
        return debounceMs
    }

    fun scheduleImmediate(request: RecentDisplayRebuildRequest) {
        scope.launch(Dispatchers.IO) {
            // Must release [mutex] before [runPendingRebuild] — Mutex is not reentrant, and
            // runPendingRebuild acquires it again. Holding it here deadlocked startup repair and
            // left recent_display_cache empty forever (UI stuck on raw_preview).
            mutex.withLock {
                mergeRequest(request)
                pendingJob?.cancel()
                pendingJob = null
            }
            runPendingRebuild()
        }
    }

    private fun mergeRequest(request: RecentDisplayRebuildRequest) {
        if (request.reason == DisplayCacheRebuildReason.COLD_EMPTY_CACHE) {
            pendingColdRebuild = true
            forceFull = true
            pendingReason = DisplayCacheRebuildReason.COLD_EMPTY_CACHE
        } else if (request.forceFull &&
            request.reason.requiresFullRecentRebuild &&
            request.reason != DisplayCacheRebuildReason.RECENTS_GROUPING_CHANGED
        ) {
            pendingColdRebuild = true
            forceFull = true
            pendingReason = DisplayCacheRebuildReason.COLD_EMPTY_CACHE
        }
        if (request.forceFull || request.reason.requiresFullRecentRebuild) {
            forceFull = true
        }
        pendingInserted.addAll(request.insertedCallIds)
        pendingDeleted.addAll(request.deletedCallIds)
        pendingGroupByContact = request.groupByContact
        pendingLimit = request.limit
        if (request.reason == DisplayCacheRebuildReason.RECENTS_GROUPING_CHANGED) {
            pendingReason = request.reason
        } else if (pendingReason != DisplayCacheRebuildReason.RECENTS_GROUPING_CHANGED &&
            !pendingColdRebuild
        ) {
            pendingReason = request.reason
        }
    }

    private suspend fun runPendingRebuild() {
        val request: RecentDisplayRebuildRequest
        mutex.withLock {
            val inserted = pendingInserted.toSet()
            val deleted = pendingDeleted.toSet()
            // Preserve a pending full/cold rebuild even when inserts coalesce into the same tick.
            val pendingFull = pendingColdRebuild || forceFull
            val insertOnly = inserted.isNotEmpty() && deleted.isEmpty() && !pendingFull
            val reason = when {
                pendingColdRebuild -> DisplayCacheRebuildReason.COLD_EMPTY_CACHE
                pendingReason == DisplayCacheRebuildReason.RECENTS_GROUPING_CHANGED && pendingFull ->
                    DisplayCacheRebuildReason.RECENTS_GROUPING_CHANGED
                pendingFull -> DisplayCacheRebuildReason.COLD_EMPTY_CACHE
                deleted.isNotEmpty() && inserted.isNotEmpty() ->
                    DisplayCacheRebuildReason.CALL_LOG_SYNC_COMPLETED
                deleted.isNotEmpty() -> DisplayCacheRebuildReason.CALL_LOG_DELETED
                insertOnly -> DisplayCacheRebuildReason.CALL_LOG_INSERTED
                pendingReason == DisplayCacheRebuildReason.RECENTS_GROUPING_CHANGED ->
                    DisplayCacheRebuildReason.RECENTS_GROUPING_CHANGED
                else -> pendingReason
            }
            request = RecentDisplayRebuildRequest(
                reason = reason,
                groupByContact = pendingGroupByContact,
                insertedCallIds = inserted,
                deletedCallIds = deleted,
                forceFull = pendingFull || reason.requiresFullRecentRebuild,
                limit = pendingLimit,
            )
            pendingInserted.clear()
            pendingDeleted.clear()
            forceFull = false
            pendingColdRebuild = false
            pendingReason = DisplayCacheRebuildReason.CALL_LOG_SYNC_COMPLETED
        }
        rebuildInProgress = true
        com.goodwy.commons.providercache.startup.StartupOrchestrator.onDisplayCacheRebuildStarted()
        try {
            val result = rebuildHandler(request)
            onComplete(request, result)
        } finally {
            rebuildInProgress = false
            com.goodwy.commons.providercache.startup.StartupOrchestrator.onDisplayCacheRebuildEnded()
        }
    }

    private companion object {
        /** Match call-log observer debounce so new-call counts appear quickly. */
        const val INSERT_DEBOUNCE_MS = 150L
    }
}
