package com.goodwy.commons.providercache.display

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Debounces contact display-cache rebuilds and merges changed/deleted ID sets.
 */
class ContactDisplayCacheRebuildScheduler(
    private val scope: CoroutineScope,
    private val onComplete: (ContactDisplayRebuildRequest) -> Unit,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {
    var rebuildHandler: suspend (ContactDisplayRebuildRequest) -> Unit = {}
    private val mutex = Mutex()
    private var pendingJob: Job? = null
    @Volatile
    private var rebuildInProgress = false
    private var pendingChanged = mutableSetOf<Int>()
    private var pendingDeleted = mutableSetOf<Int>()
    private var pendingReason = DisplayCacheRebuildReason.CONTACT_SYNC_COMPLETED
    private var forceFull = false
    private var pendingMode = ContactDisplayRebuildMode.FAST

    fun isRebuildInProgress(): Boolean = rebuildInProgress || pendingJob?.isActive == true

    suspend fun awaitIdle() {
        while (isRebuildInProgress()) {
            delay(25)
        }
    }

    fun schedule(request: ContactDisplayRebuildRequest) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                mergeRequest(request)
                pendingJob?.cancel()
                pendingJob = scope.launch(Dispatchers.IO) {
                    delay(debounceMs)
                    runPendingRebuild()
                }
            }
        }
    }

    fun scheduleImmediate(request: ContactDisplayRebuildRequest) {
        scope.launch(Dispatchers.IO) {
            // Must release [mutex] before [runPendingRebuild] — Mutex is not reentrant.
            // Holding it here deadlocks (same bug as RecentDisplayCacheRebuildScheduler).
            mutex.withLock {
                mergeRequest(request)
                pendingJob?.cancel()
                pendingJob = null
            }
            runPendingRebuild()
        }
    }

    private fun mergeRequest(request: ContactDisplayRebuildRequest) {
        if (request.forceFull || request.reason.requiresFullContactRebuild) {
            forceFull = true
        }
        pendingChanged.addAll(request.changedContactIds)
        pendingDeleted.addAll(request.deletedContactIds)
        pendingReason = pickStrongerReason(pendingReason, request.reason)
        pendingMode = pickStrongerMode(pendingMode, request.mode)
    }

    private fun pickStrongerMode(
        current: ContactDisplayRebuildMode,
        incoming: ContactDisplayRebuildMode,
    ): ContactDisplayRebuildMode =
        if (incoming == ContactDisplayRebuildMode.ACCURATE) ContactDisplayRebuildMode.ACCURATE else current

    private suspend fun runPendingRebuild() {
        val reason: DisplayCacheRebuildReason
        val changed: Set<Int>
        val deleted: Set<Int>
        val full: Boolean
        val rebuildMode: ContactDisplayRebuildMode
        mutex.withLock {
            reason = resolveReason()
            changed = pendingChanged.toSet()
            deleted = pendingDeleted.toSet()
            full = forceFull
            rebuildMode = pendingMode
            pendingChanged.clear()
            pendingDeleted.clear()
            forceFull = false
            pendingReason = DisplayCacheRebuildReason.CONTACT_SYNC_COMPLETED
            pendingMode = ContactDisplayRebuildMode.FAST
        }
        val request = ContactDisplayRebuildRequest(
            reason = reason,
            changedContactIds = changed,
            deletedContactIds = deleted,
            forceFull = full,
            mode = rebuildMode,
        )
        rebuildInProgress = true
        try {
            rebuildHandler(request)
            onComplete(request)
        } finally {
            rebuildInProgress = false
        }
    }

    private fun resolveReason(): DisplayCacheRebuildReason {
        if (forceFull) return pendingReason
        return when {
            pendingDeleted.isNotEmpty() && pendingChanged.isNotEmpty() -> DisplayCacheRebuildReason.CONTACT_SYNC_COMPLETED
            pendingDeleted.isNotEmpty() -> DisplayCacheRebuildReason.DELETED_CONTACT_IDS
            pendingChanged.isNotEmpty() -> DisplayCacheRebuildReason.CHANGED_CONTACT_IDS
            else -> pendingReason
        }
    }

    private fun pickStrongerReason(
        current: DisplayCacheRebuildReason,
        incoming: DisplayCacheRebuildReason,
    ): DisplayCacheRebuildReason {
        val priority = listOf(
            DisplayCacheRebuildReason.MIGRATION,
            DisplayCacheRebuildReason.STARTUP_INVALID_CACHE,
            DisplayCacheRebuildReason.COLD_EMPTY_CACHE,
            DisplayCacheRebuildReason.SECURE_MODE_CHANGED,
            DisplayCacheRebuildReason.SOURCE_FILTER_CHANGED,
            DisplayCacheRebuildReason.DUPLICATE_MERGE_IDLE,
            DisplayCacheRebuildReason.MANUAL_DEBUG,
            DisplayCacheRebuildReason.CONTACT_SYNC_COMPLETED,
            DisplayCacheRebuildReason.DELETED_CONTACT_IDS,
            DisplayCacheRebuildReason.CHANGED_CONTACT_IDS,
        )
        val currentIdx = priority.indexOf(current).let { if (it < 0) priority.size else it }
        val incomingIdx = priority.indexOf(incoming).let { if (it < 0) priority.size else it }
        return if (incomingIdx <= currentIdx) incoming else current
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 800L
    }
}
