package com.goodwy.commons.providercache.sync

import android.content.Context
import androidx.room.withTransaction
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.helpers.PERMISSION_READ_CALL_LOG
import com.goodwy.commons.providercache.ProviderCache
import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.datasource.CallLogProviderDataSource
import com.goodwy.commons.providercache.entities.CacheMetadataDomain
import com.goodwy.commons.providercache.debug.CacheMutationLogger
import com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.startup.StartupOrchestrator
import com.goodwy.commons.providercache.toEntity
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CallLogSyncManager(
    private val context: Context,
    private val database: ProviderCacheDatabase,
    private val providerDataSource: CallLogProviderDataSource,
    private val scope: CoroutineScope,
    private val maxCachedEntries: Int = DEFAULT_MAX_CACHED_ENTRIES,
) {
    private val callLogDao get() = database.callLogDao()
    // Mutex serialises all sync work — no separate AtomicBoolean needed.
    private val syncMutex = Mutex()
    private var incrementalSyncJob: Job? = null
    private var incrementalSyncPending = false

    var onSyncCompleted: (() -> Unit)? = null
    var onSyncChanges: (suspend (CallLogSyncChangeSet) -> Unit)? = null

    fun scheduleFullRebuild() {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) return
        scope.launch(Dispatchers.IO) {
            syncMutex.withLock {
                var dataChanged = false
                try {
                    ProviderCacheDebugLogger.logSyncStart("full", "call_log")
                    val started = System.currentTimeMillis()
                    rebuildCacheProgressively()
                    detachStaleContactInfo("full_sync")
                    callLogDao.backfillContactInfo()
                    dataChanged = true
                    val count = callLogDao.getCount()
                    acknowledgeRawMirrorHealthy("FULL_REBUILD")
                    ProviderCacheDebugLogger.logSyncEnd(
                        kind = "full",
                        entity = "call_log",
                        durationMs = System.currentTimeMillis() - started,
                        syncedCount = count,
                    )
                } finally {
                    if (dataChanged) onSyncCompleted?.invoke()
                }
            }
        }
    }

    /**
     * A full rebuild replaces the entire window from the provider, so on success the raw mirror
     * *is* the provider's state — exactly what `CacheMetadataStore.acknowledgeHealthy` is for.
     *
     * Nothing cleared RECENTS_RAW `repairRequired`. `ensureSeeded` writes every domain with
     * `repairRequired = true` on first install, and the two commit paths then diverge:
     * `commitDisplayMutation` clears `dirty` *and* `repairRequired`, while `commitRawMutation`
     * clears only `dirty`. RECENTS_DISPLAY therefore heals on its first commit and RECENTS_RAW
     * never does — the seed value outlives every sync, every repair, and every process.
     *
     * So each launch saw `rawRepairRequired=true`, ran a full sync, and that sync calls
     * [clearRawAndRelationalCaches] — wiping the grouping tables and forcing a regroup of all
     * 1000 calls plus a re-enrich of all 304 display rows. Three consecutive startups paid ~20s
     * for that while `callLogIdDiff` reported `inserted=0 deleted=0` on the mirror it was
     * "repairing".
     *
     * Acknowledging here rather than symmetrising `commitRawMutation` is deliberate: an
     * incremental commit reconciles an id-set diff, which is weaker evidence than "the whole
     * window was just re-read". Only a wholesale rebuild earns the acknowledgement, and only the
     * raw domain gets it — a display-side problem still has its own flag and still repairs.
     */
    private suspend fun acknowledgeRawMirrorHealthy(reason: String) {
        try {
            ProviderCache.cacheMetadataStore.acknowledgeHealthy(
                CacheMetadataDomain.RECENTS_RAW,
                reason,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sync can precede ProviderCache.init in tests and cold-start races; a missed
            // acknowledgement costs one redundant rebuild, an escaping throw loses the sync.
            Log.w(TAG, "acknowledgeRawMirrorHealthy skipped reason=$reason", e)
        }
    }

    fun scheduleIncrementalSync() {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) return
        if (incrementalSyncJob?.isActive == true) {
            incrementalSyncPending = true
            ProviderCacheDebugLogger.log("syncStart coalesced (incremental in flight) entity=call_log")
            return
        }
        incrementalSyncJob = scope.launch(Dispatchers.IO) {
            syncMutex.withLock {
                do {
                    incrementalSyncPending = false
                    runIncrementalSyncLocked()
                } while (incrementalSyncPending)
            }
        }
    }

    /** Runs call-log sync synchronously; used before recents rebuild when Room cache may be empty. */
    suspend fun runIncrementalSyncAwaitable() {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) return
        incrementalSyncJob?.takeIf { it.isActive }?.let { inFlight ->
            inFlight.join()
            return
        }
        syncMutex.withLock {
            do {
                incrementalSyncPending = false
                runIncrementalSyncLocked()
            } while (incrementalSyncPending)
        }
    }

    private suspend fun runIncrementalSyncLocked() {
        var syncSucceeded = false
        try {
            ProviderCacheDebugLogger.logSyncStart("incremental", "call_log")
            val started = System.currentTimeMillis()
            val count = callLogDao.getCount()
            if (count == 0) {
                StartupOrchestrator.beginRawSync()
                rebuildCacheProgressively()
                detachStaleContactInfo("cold_rebuild")
                callLogDao.backfillContactInfo()
                syncSucceeded = true
                // Same wholesale replacement as scheduleFullRebuild, so the same acknowledgement.
                acknowledgeRawMirrorHealthy("COLD_REBUILD")
                StartupOrchestrator.onCallLogsRawSyncComplete()
                onSyncChanges?.invoke(CallLogSyncChangeSet(wasColdRebuild = true))
            } else {
                when (val result = incrementalSync()) {
                    is SyncResult.Success -> {
                        val changes = result.value
                        syncSucceeded = true
                        if (changes.hasChanges) {
                            val inserted = changes.insertedCallIds
                            if (inserted.isNotEmpty()) {
                                inserted.chunked(200).forEach { chunk ->
                                    callLogDao.backfillContactInfoForCallIds(chunk)
                                }
                            }
                            callLogDao.trimToMostRecent(maxCachedEntries)
                            onSyncChanges?.invoke(changes)
                        }
                    }
                    is SyncResult.NoChange -> {
                        // Warm Room with no provider drift still completes raw sync — Recents
                        // READY_EMPTY / known-empty UI depends on callLogsSyncDone.
                        syncSucceeded = true
                    }
                    is SyncResult.Failure -> {
                        Log.w(TAG, "callLogSyncFailed stage=${result.stage} retryable=${result.retryable}", result.throwable)
                    }
                }
                if (syncSucceeded) {
                    StartupOrchestrator.onCallLogsRawSyncComplete()
                }
            }
            ProviderCacheDebugLogger.logSyncEnd(
                kind = "incremental",
                entity = "call_log",
                durationMs = System.currentTimeMillis() - started,
                syncedCount = callLogDao.getCount(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "callLogSyncFailed stage=${SyncStage.UNKNOWN}", e)
        } finally {
            // Always notify on successful sync (including NoChange) so empty display can settle.
            if (syncSucceeded) onSyncCompleted?.invoke()
        }
    }

    /**
     * Clears the raw call log *and* the relational grouping tables together.
     *
     * `recent_group_calls` has `onDelete = CASCADE` on `call_log_entries.call_id`, but
     * `recent_groups` / `recent_group_numbers` have no foreign key. Clearing only the raw table
     * therefore wipes every membership row while leaving groups behind with their `call_count`
     * intact — the exact shape `RecentDisplayCacheValidator` rejects unconditionally
     * (`rawEligibleCalls > 0 && membershipRows == 0`). The result was a guaranteed full grouping
     * repair after every full sync, escalating to `reason=prefixed_or_empty_membership`.
     *
     * Clearing all four together leaves the relational side consistently empty, so the following
     * rebuild is a deliberate one rather than a repair of self-inflicted damage.
     */
    /**
     * Undoes contact links that no longer hold, before the fill-only backfill re-attaches the ones
     * that do. Without this a number edited or removed on a contact keeps its old `cached_name` on
     * the call-log row indefinitely, and Recents keeps rendering the stale contact name.
     */
    private suspend fun detachStaleContactInfo(reason: String) {
        val detached = callLogDao.clearStaleContactInfo()
        if (detached > 0) {
            ProviderCacheDebugLogger.log("callLogContactInfoDetached rows=$detached reason=$reason")
        }
    }

    private suspend fun clearRawAndRelationalCaches() {
        database.withTransaction {
            RECENT_GROUPING_MODES.forEach { mode ->
                database.recentGroupCallDao().deleteMode(mode)
                database.recentGroupNumberDao().deleteMode(mode)
                database.recentGroupDao().deleteMode(mode)
            }
            callLogDao.clearAll()
        }
    }

    /**
     * Rebuilds the raw mirror from the provider.
     *
     * Reads the whole window in one call, then swaps it in atomically. Two reasons, both learned
     * the hard way from the paged clear-then-fill this replaces:
     *
     * - The provider ignores `QUERY_ARG_OFFSET` (see [CallLogProviderDataSource.queryEntries]), so
     *   offset paging re-fetched the newest rows every iteration and the loop terminated on a
     *   miscount — leaving the mirror short of [maxCachedEntries].
     * - Clearing outside the fill left a window where readers saw a partially populated call_log.
     *   The display-cache rebuild runs concurrently and groups whatever it finds, and
     *   recent_group_calls.call_id cascades off call_log, so that window produced either a foreign
     *   key failure on commit or a display cache silently built from a torn snapshot.
     */
    private suspend fun rebuildCacheProgressively() {
        val rows = providerDataSource
            .loadPage(offset = 0, limit = maxCachedEntries)
            .map { it.toEntity() }
        if (rows.isEmpty()) {
            clearRawAndRelationalCaches()
            return
        }
        database.withTransaction {
            clearRawAndRelationalCaches()
            rows.chunked(INSERT_CHUNK_SIZE).forEach { callLogDao.insertAll(it) }
            callLogDao.trimToMostRecent(maxCachedEntries)
        }
    }

    private suspend fun incrementalSync(): SyncResult<CallLogSyncChangeSet> {
        if (callLogDao.getCount() == 0) return SyncResult.NoChange

        // Provider was cleared (e.g. "clear call history") but Room still has stale rows.
        if (providerDataSource.loadPage(0, 1).isEmpty()) {
            clearRawAndRelationalCaches()
            return SyncResult.Success(
                CallLogSyncChangeSet(deletedCallIds = emptyList(), wasColdRebuild = true),
            )
        }

        val roomIds = callLogDao.getAllCallIds().toSet()
        val providerIds = providerDataSource.loadRecentIds(maxCachedEntries).toSet()
        val drift = CallLogSyncDelta.computeIdDrift(roomIds, providerIds)

        CacheMutationLogger.callLogIdDiff(
            provider = providerIds.size,
            room = roomIds.size,
            inserted = drift.insertedIds.size,
            deleted = drift.deletedIds.size,
            equalCountDifferentIds = drift.equalCountDifferentIds,
        )

        val deleted = drift.deletedIds.toMutableList()
        if (deleted.isNotEmpty()) {
            CacheMutationLogger.callLogDeleteDetected(deleted.size, "ID_SET_DIFF")
            deleted.chunked(200).forEach { chunk -> callLogDao.deleteByIds(chunk) }
        }

        val inserted = mutableListOf<Int>()
        if (drift.insertedIds.isNotEmpty()) {
            drift.insertedIds.chunked(INCREMENTAL_PAGE_SIZE).forEach { chunk ->
                val page = providerDataSource.loadByIds(chunk)
                if (page.isNotEmpty()) {
                    callLogDao.insertAll(page.map { it.toEntity() })
                    inserted.addAll(page.map { it.callId })
                }
            }
        }

        // Also pick up same-timestamp new rows not yet in the ID window diff (belt-and-suspenders).
        val sinceMs = (callLogDao.getMaxStartTimestamp() ?: 0L) * 1000
        var insertOffset = 0
        while (true) {
            val page = providerDataSource.loadNewerThan(sinceMs, limit = INCREMENTAL_PAGE_SIZE, offset = insertOffset)
            if (page.isEmpty()) break
            val newIds = page.map { it.callId }.filter { it !in roomIds && it !in inserted }
            if (newIds.isNotEmpty()) {
                callLogDao.insertAll(page.filter { it.callId in newIds }.map { it.toEntity() })
                inserted.addAll(newIds)
            }
            insertOffset += page.size
            if (page.size < INCREMENTAL_PAGE_SIZE) break
        }

        val changes = CallLogSyncChangeSet(
            insertedCallIds = inserted.distinct(),
            deletedCallIds = deleted.distinct(),
            wasColdRebuild = false,
        )
        return if (changes.hasChanges) {
            SyncResult.Success(changes)
        } else {
            SyncResult.NoChange
        }
    }

    companion object {
        private const val TAG = "CallLogSyncManager"
        const val DEFAULT_MAX_CACHED_ENTRIES = 1000
        private const val INCREMENTAL_PAGE_SIZE = 200

        /** Insert batch inside the full-rebuild transaction — bounds statement size, not the read. */
        private const val INSERT_CHUNK_SIZE = 200

        /** Both `grouping_mode` values — the raw table is shared, so both modes must be cleared. */
        private val RECENT_GROUPING_MODES = RecentGroupingMode.entries.map { it.dbValue }

        fun create(
            context: Context,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ): CallLogSyncManager {
            val db = ProviderCacheDatabase.getInstance(context)
            return CallLogSyncManager(
                context = context.applicationContext,
                database = db,
                providerDataSource = CallLogProviderDataSource(context.applicationContext),
                scope = scope,
            )
        }
    }
}
