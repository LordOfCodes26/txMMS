package com.goodwy.commons.providercache.sync

import android.content.Context
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.helpers.PERMISSION_READ_CONTACTS
import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.datasource.ContactsMetadataLoader
import com.goodwy.commons.providercache.datasource.ContactsProviderDataSource
import com.goodwy.commons.providercache.entities.ContactDetailEntity
import com.goodwy.commons.providercache.entities.ContactSummaryEntity
import com.goodwy.commons.providercache.entities.ContactEmailEntity
import com.goodwy.commons.providercache.entities.ContactPhoneEntity
import com.goodwy.commons.helpers.ContactAvatarInvalidUriTracker
import com.goodwy.commons.helpers.ContactAvatarPhotoVersionTracker
import com.goodwy.commons.helpers.ContactListPhotoUriResolver
import com.goodwy.commons.helpers.ContactsHelper
import com.goodwy.commons.providercache.ProviderCacheUserInteractionGate
import com.goodwy.commons.providercache.startup.StartupOrchestrator
import com.goodwy.commons.providercache.startup.StartupPhotoBackfillGate
import android.util.Log
import com.goodwy.commons.providercache.debug.CacheMutationLogger
import com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger
import com.goodwy.commons.providercache.filter.ContactPagingMapper
import com.goodwy.commons.providercache.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ContactsSyncManager(
    private val context: Context,
    private val database: ProviderCacheDatabase,
    private val providerDataSource: ContactsProviderDataSource,
    private val scope: CoroutineScope,
) {
    private val contactDao get() = database.contactDao()
    private val displayCacheDao get() = database.contactDisplayCacheDao()
    private val phoneIndexDao get() = database.contactPhoneIndexDao()
    private val searchIndexDao get() = database.contactSearchIndexDao()
    private val phoneIndexSync = ContactPhoneIndexSync(context, database)
    private val searchIndexSync = ContactSearchIndexSync(context, database)
    private val metadataLoader = ContactsMetadataLoader(context)
    // Mutex serialises all sync work — no separate AtomicBoolean needed.
    private val syncMutex = Mutex()
    @Volatile
    private var incrementalSyncPending = false
    private var incrementalSyncJob: Job? = null
    @Volatile
    private var photoThumbnailBackfillCompleted = false

    @Volatile
    private var photoBackfillDeferredForInteraction = false

    /** Cursor so empty-photo probes advance past already-checked ids (negative cache alone is not enough). */
    @Volatile
    private var photoBackfillAfterContactId = 0

    @Volatile
    private var consecutiveEmptyPhotoChunks = 0

    /** When true, incremental sync and provider fallback rebuilds are suppressed (delete-all). */
    @Volatile
    var suppressIncrementalSync: Boolean = false

    /** True while [rebuildCacheProgressively] is streaming provider pages into Room. */
    @Volatile
    private var progressiveSyncInProgress = false

    fun isProgressiveSyncInProgress(): Boolean = progressiveSyncInProgress

    /** True once idle photo backfill has finished (or was skipped because thumbs were already present). */
    fun isPhotoThumbnailBackfillCompleted(): Boolean = photoThumbnailBackfillCompleted

    var onSyncCompleted: (() -> Unit)? = null
    /** Called with the list of contact IDs that were updated during an incremental sync. */
    var onContactsPartiallyUpdated: (suspend (List<Int>) -> Unit)? = null
    /** Delivers changed/deleted IDs for incremental display-cache rebuilds. */
    var onSyncChanges: ((ContactSyncChangeSet) -> Unit)? = null

    fun scheduleFullRebuild() {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) return
        scope.launch(Dispatchers.IO) {
            syncMutex.withLock {
                runFullSyncLocked(includeSecondaryIndexes = true)
            }
        }
    }

    /**
     * Full provider → Room summary sync. Does not rebuild phone/search indexes or call-log
     * contact backfill — callers that need those should invoke [rebuildSecondaryIndexes] after
     * the contacts display cache is ready so the full list can paint sooner.
     */
    suspend fun fullSyncContacts() = withContext(Dispatchers.IO) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) return@withContext
        syncMutex.withLock {
            runFullSyncLocked(includeSecondaryIndexes = false)
        }
    }

    /**
     * Rebuilds phone/search indexes and call-log contact labels. Safe to run after the contacts
     * display cache is ready; not required for the Contacts tab list itself.
     */
    suspend fun rebuildSecondaryIndexes() = withContext(Dispatchers.IO) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) return@withContext
        syncMutex.withLock {
            runSecondaryIndexesLocked()
        }
    }

    private suspend fun runFullSyncLocked(includeSecondaryIndexes: Boolean) {
        try {
            ProviderCacheDebugLogger.logSyncStart("full", "contacts")
            val started = System.currentTimeMillis()
            rebuildCacheProgressively()
            val summaryCount = contactDao.getSummaryCount()
            if (includeSecondaryIndexes) {
                runSecondaryIndexesLocked()
            }
            val phoneCount = database.contactPhoneIndexDao().getCount()
            val searchCount = database.contactSearchIndexDao().getCount()
            ProviderCacheDebugLogger.logSyncEnd(
                kind = "full",
                entity = "contacts",
                durationMs = System.currentTimeMillis() - started,
                syncedCount = summaryCount,
                extra = "phoneIndex=$phoneCount searchIndex=$searchCount " +
                    "secondaryIndexes=$includeSecondaryIndexes",
            )
            if (includeSecondaryIndexes) {
                ProviderCacheDebugLogger.logIndexSync("contact_phone_index", phoneCount)
                ProviderCacheDebugLogger.logIndexSync("contact_search_index", searchCount)
            }
        } catch (e: Exception) {
            Log.w(TAG, "contactsFullSyncFailed stage=${SyncStage.RAW_WRITE}", e)
            CacheMutationLogger.mutationFailed(0L, CacheMutationLogger.Stage.RAW_WRITE, e.message ?: "full_sync")
        }
    }

    private suspend fun runSecondaryIndexesLocked() {
        phoneIndexSync.rebuildIfNeeded()
        searchIndexSync.rebuildIfNeeded()
        // Detach before attaching. `backfillContactInfo` is fill-only, so on its own it can never
        // undo a contact link — edit or remove a number and the call-log row keeps the old
        // `cached_name` forever, and Recents keeps showing the old contact name.
        //
        // This is the right place for the unscoped sweep: the phone index has just been rebuilt, so
        // the match test is evaluated against current data, and it runs on every contacts sync
        // rather than only when a repair happens to be triggered.
        val detached = database.callLogDao().clearStaleContactInfo()
        if (detached > 0) {
            ProviderCacheDebugLogger.log("callLogContactInfoDetached rows=$detached reason=contacts_sync")
        }
        database.callLogDao().backfillContactInfo()
        val phoneCount = database.contactPhoneIndexDao().getCount()
        val searchCount = database.contactSearchIndexDao().getCount()
        ProviderCacheDebugLogger.logIndexSync("contact_phone_index", phoneCount)
        ProviderCacheDebugLogger.logIndexSync("contact_search_index", searchCount)
    }

    /** Fire-and-forget secondary indexes after a cold empty-cache sync so display paint is not blocked. */
    private fun scheduleSecondaryIndexesAfterColdSync() {
        scope.launch(Dispatchers.IO) {
            syncMutex.withLock {
                runSecondaryIndexesLocked()
            }
        }
    }

    fun scheduleIncrementalSync() {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) return
        if (suppressIncrementalSync) {
            ProviderCacheDebugLogger.log("syncStart skipped (bulk delete in progress) entity=contacts")
            return
        }
        if (incrementalSyncJob?.isActive == true) {
            incrementalSyncPending = true
            ProviderCacheDebugLogger.log("syncStart coalesced (incremental in flight) entity=contacts")
            return
        }
        incrementalSyncJob = scope.launch(Dispatchers.IO) {
            syncMutex.withLock {
                do {
                    incrementalSyncPending = false
                    runIncrementalSyncLocked(forceFullIdReconcile = false)
                } while (incrementalSyncPending)
            }
        }
    }

    /**
     * Runs incremental sync synchronously (fully awaited) and returns the resulting
     * [ContactSyncChangeSet], for callers (e.g. `ContactChangeCoordinator.handleProviderChanged`)
     * that need to react deterministically to exactly what changed instead of relying solely on the
     * debounced [onSyncChanges] / [onSyncCompleted] reactive callbacks (which may not be wired yet,
     * e.g. if the Contacts tab was never opened this session). Still invokes those callbacks as usual
     * for other subscribers (Recents caller-name patching, paging invalidation, etc.).
     */
    /** Result of the most recently completed [runIncrementalSyncLocked] pass, for [runIncrementalSyncAwaitable]. */
    @Volatile
    private var lastCompletedChangeSet: ContactSyncChangeSet = ContactSyncChangeSet()

    suspend fun runIncrementalSyncAwaitable(
        forceFullIdReconcile: Boolean = true,
    ): ContactSyncChangeSet {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) return ContactSyncChangeSet()
        if (suppressIncrementalSync) return ContactSyncChangeSet()
        // If a sync triggered elsewhere (e.g. the raw ContentObserver's own direct
        // scheduleIncrementalSync() call) is already in flight, wait for it instead of racing it for
        // the mutex — a second pass starting right after would find no remaining delta and report an
        // empty change set even though the real changes just landed via the in-flight job.
        incrementalSyncJob?.takeIf { it.isActive }?.let { inFlight ->
            inFlight.join()
            return lastCompletedChangeSet
        }
        return syncMutex.withLock {
            var result = ContactSyncChangeSet()
            do {
                incrementalSyncPending = false
                result = runIncrementalSyncLocked(forceFullIdReconcile = forceFullIdReconcile)
            } while (incrementalSyncPending)
            result
        }
    }

    private suspend fun runIncrementalSyncLocked(
        forceFullIdReconcile: Boolean = true,
    ): ContactSyncChangeSet {
        var dataChanged = false
        var uiNotified = false
        var result = ContactSyncChangeSet()
        try {
            ProviderCacheDebugLogger.logSyncStart("incremental", "contacts")
            val started = System.currentTimeMillis()
            val count = contactDao.getSummaryCount()
            var updatedCount = 0
            var staleCount = 0
            var backfilledPhotoIds = emptyList<Int>()
            val startupCacheValid = isStartupCacheValidSafe()
            val displayCacheWarm = displayCacheDao.getCount() > 0
            // Only skip photo backfill when summaries already have thumbs — a warm display cache
            // can still have empty photo_thumb_uri after probes were deferred at rebuild time.
            if (startupCacheValid && displayCacheWarm && !photoThumbnailBackfillCompleted) {
                val missingThumbs = contactDao.getContactIdsMissingPhotoThumbnail(limit = 1)
                if (missingThumbs.isEmpty()) {
                    markPhotoThumbnailBackfillCompleted("summaries already have thumbs")
                }
            }
            if (count > 0 && !photoThumbnailBackfillCompleted) {
                if (!StartupPhotoBackfillGate.allowPhotoBackfill()) {
                    photoBackfillDeferredForInteraction = true
                    ProviderCacheUserInteractionGate.deferPhotoBackfill()
                    ProviderCacheDebugLogger.log("photoThumbnailBackfill deferred (startup busy)")
                } else {
                    backfilledPhotoIds = backfillMissingPhotoThumbnails()
                }
                if (backfilledPhotoIds.isNotEmpty()) {
                    dataChanged = true
                    result = ContactSyncChangeSet(
                        updatedContactIds = backfilledPhotoIds,
                        wasFullRebuild = false,
                        hasProvenProviderDelta = true,
                    )
                    onSyncChanges?.invoke(result)
                }
            }
            if (count == 0) {
                if (suppressIncrementalSync) {
                    ProviderCacheDebugLogger.log("sync skipped rebuild (bulk delete in progress) entity=contacts")
                    return ContactSyncChangeSet()
                }
                StartupOrchestrator.beginRawSync()
                rebuildCacheProgressively()
                // Notify display rebuild with the complete Room snapshot before secondary indexes
                // so the Contacts tab can paint the full list without waiting on phone/search/call-log work.
                dataChanged = true
                result = ContactSyncChangeSet(wasFullRebuild = true)
                onSyncChanges?.invoke(result)
                StartupOrchestrator.onContactsRawSyncComplete()
                uiNotified = true
                scheduleSecondaryIndexesAfterColdSync()
            } else {
                // Startup validation already proved count/hash parity — skip loadAllSummaryIds prune
                // unless the caller is ContentObserver (forceFullIdReconcile) needing delete catch-up.
                val skipFullId = !forceFullIdReconcile && startupCacheValid
                if (skipFullId) {
                    ProviderCacheDebugLogger.log(
                        "syncIncremental cheapOnly reason=startup_cache_valid",
                    )
                }
                val missingOutcome = if (skipFullId) {
                    IncrementalSyncOutcome(emptyList(), emptyList())
                } else {
                    syncMissingProviderContactsIfNeeded()
                }
                val syncOutcome = if (missingOutcome.changedContactIds.isNotEmpty()) {
                    missingOutcome
                } else {
                    incrementalSync()
                }
                val updatedIds = syncOutcome.changedContactIds
                updatedCount = updatedIds.size

                val cleanup = if (skipFullId) {
                    ContactCleanupResult(emptyList(), providerEmpty = false)
                } else {
                    cleanupDeletedContacts()
                }
                val staleIds = cleanup.deletedIds
                staleCount = staleIds.size
                dataChanged = updatedCount > 0 || staleCount > 0

                if (dataChanged) {
                    result = ContactSyncChangeSet(
                        updatedContactIds = updatedIds,
                        deletedContactIds = staleIds,
                        wasFullRebuild = false,
                        hasProvenProviderDelta = true,
                        insertedContactIds = syncOutcome.insertedContactIds,
                    )
                    onSyncChanges?.invoke(result)
                }

                if (dataChanged) {
                    onSyncCompleted?.invoke()
                    uiNotified = true
                }

                if (updatedIds.isNotEmpty()) {
                    searchIndexSync.updateForContactIds(updatedIds)
                    phoneIndexSync.rebuildForContactIds(updatedIds)
                    database.callLogDao().backfillContactInfoForIds(updatedIds)
                    onContactsPartiallyUpdated?.invoke(updatedIds)
                }
                if (staleIds.isNotEmpty() && cleanup.providerEmpty) {
                    phoneIndexSync.rebuildAll()
                    searchIndexSync.rebuildAll()
                }
                searchIndexSync.rebuildIfNeeded()
            }
            if (backfilledPhotoIds.isNotEmpty() && result.updatedContactIds !== backfilledPhotoIds) {
                // Merge photo-only backfill ids into the returned set so a caller acting on the
                // return value (rather than the onSyncChanges callback) still sees them.
                result = result.copy(
                    updatedContactIds = (result.updatedContactIds + backfilledPhotoIds).distinct(),
                )
            }
            ProviderCacheDebugLogger.logSyncEnd(
                kind = "incremental",
                entity = "contacts",
                durationMs = System.currentTimeMillis() - started,
                syncedCount = contactDao.getSummaryCount(),
                extra = "updated=$updatedCount stale=$staleCount backfilledPhotos=${backfilledPhotoIds.size}",
            )
            return result
        } finally {
            if (dataChanged && !uiNotified) onSyncCompleted?.invoke()
            lastCompletedChangeSet = result
        }
    }

    /**
     * Streams provider contacts into Room in pages. Display-cache rebuild must wait until this
     * returns so the Contacts tab paints one complete list (not a growing partial page).
     * [onSyncCompleted] may fire after the first batch for paging bookkeeping only; callers must
     * keep skipping display rebuild while [isProgressiveSyncInProgress] is true.
     */
    private suspend fun rebuildCacheProgressively() {
        progressiveSyncInProgress = true
        try {
            var offset = 0
            val pageSize = 500
            var firstBatchNotified = false
            val helper = ContactsHelper(context)
            while (true) {
                val page = providerDataSource.loadPage(offset, pageSize)
                if (page.isEmpty()) break
                val meta = metadataLoader.loadForSummaries(page)
                val enrichedPage = page.map { summary ->
                    val rawId = meta[summary.contactId]?.primaryRawId?.takeIf { it > 0 } ?: summary.contactId
                    enrichSummaryPhotoUri(helper, summary, rawId)
                }
                val entities = enrichedPage.map { summary ->
                    val m = meta[summary.contactId]
                    if (m != null) ContactPagingMapper.entityToSummaryEntity(summary, m)
                    else summary.toEntity()
                }
                contactDao.insertSummaries(withoutRecentlyDeleted(entities))
                offset += page.size
                // Paging bookkeeping only — display-cache rebuild stays blocked until all pages land.
                if (!firstBatchNotified) {
                    firstBatchNotified = true
                    onSyncCompleted?.invoke()
                }
                if (page.size < pageSize) break
            }
        } finally {
            progressiveSyncInProgress = false
        }
    }

    /**
     * Drops rows the app has just deleted.
     *
     * Every write into `contact_summaries` goes through here. A sync that began before a delete is
     * carrying a provider snapshot that still contains the contact, and without this its write
     * lands after the purge and resurrects the row. See [RecentlyDeletedContacts].
     */
    private fun withoutRecentlyDeleted(
        entities: List<ContactSummaryEntity>,
    ): List<ContactSummaryEntity> {
        if (entities.isEmpty()) return entities
        val kept = entities.filterNot {
            RecentlyDeletedContacts.isSuppressed(it.contactId, it.primaryRawId)
        }
        if (kept.size != entities.size) {
            ProviderCacheDebugLogger.log(
                "syncSkippedRecentlyDeleted skipped=${entities.size - kept.size} kept=${kept.size}",
            )
        }
        return kept
    }

    private data class IncrementalSyncOutcome(
        val changedContactIds: List<Int>,
        /** Subset of [changedContactIds] that had no cached summary before this sync (e.g. an import). */
        val insertedContactIds: List<Int>,
    )

    private suspend fun syncMissingProviderContactsIfNeeded(): IncrementalSyncOutcome {
        val roomIds = contactDao.getAllSummaryIds().toSet()
        if (roomIds.isEmpty()) return IncrementalSyncOutcome(emptyList(), emptyList())
        val missingIds = providerDataSource.loadAllSummaryIds().filter { it !in roomIds }
        if (missingIds.isEmpty()) return IncrementalSyncOutcome(emptyList(), emptyList())
        ProviderCacheDebugLogger.log("syncMissingProviderContacts count=${missingIds.size}")
        val summaries = providerDataSource.loadSummariesForContactIds(missingIds)
        if (summaries.isEmpty()) return IncrementalSyncOutcome(emptyList(), emptyList())
        return persistChangedSummaries(summaries)
    }

    private suspend fun incrementalSync(): IncrementalSyncOutcome {
        // Subtract 1 ms so the contact whose timestamp equals MAX is also included — otherwise a
        // just-edited contact (timestamp == MAX) is invisible to the strict > query.
        val since = (contactDao.getMaxSummaryTimestamp() ?: 0L) - 1L
        val candidates = providerDataSource.loadSummariesUpdatedSince(since, limit = 500)
        if (candidates.isEmpty()) return IncrementalSyncOutcome(emptyList(), emptyList())

        val cachedById = contactDao.getSummariesByIds(candidates.map { it.contactId })
            .associateBy { it.contactId }
        val changedSummaries = candidates.filter { summary ->
            val cached = cachedById[summary.contactId] ?: return@filter true
            !summaryMatchesCached(summary, cached)
        }
        if (changedSummaries.isEmpty()) {
            ProviderCacheDebugLogger.log(
                "syncIncremental skipped providerDelta=0 candidates=${candidates.size}",
            )
            return IncrementalSyncOutcome(emptyList(), emptyList())
        }
        return persistChangedSummaries(changedSummaries)
    }

    private suspend fun persistChangedSummaries(
        changedSummaries: List<com.goodwy.commons.providercache.model.ContactSummary>,
    ): IncrementalSyncOutcome {
        val cachedById = contactDao.getSummariesByIds(changedSummaries.map { it.contactId })
            .associateBy { it.contactId }
        val insertedIds = changedSummaries.filter { cachedById[it.contactId] == null }.map { it.contactId }

        val meta = metadataLoader.loadForSummaries(changedSummaries)
        val helper = ContactsHelper(context)
        val enriched = changedSummaries.map { summary ->
            val rawId = meta[summary.contactId]?.primaryRawId?.takeIf { it > 0 } ?: summary.contactId
            val cachedThumb = cachedById[summary.contactId]?.photoThumbnailUri.orEmpty()
            val providerThumb = summary.photoThumbnailUri
            val resolvedThumb = when {
                providerThumb.isNotEmpty() -> providerThumb
                cachedThumb.isNotEmpty() -> cachedThumb
                else -> ContactListPhotoUriResolver.resolveForList(
                    helper, summary.contactId, rawId, providerThumb,
                    allowProviderProbe = ProviderCacheUserInteractionGate.allowProviderPhotoProbe(),
                )
            }
            if (resolvedThumb != cachedThumb) {
                ProviderCacheDebugLogger.log(
                    "avatarCacheUpdate contactId=${summary.contactId} oldUri=${cachedThumb.take(80)} newUri=${resolvedThumb.take(80)}",
                )
                if (resolvedThumb.isNotEmpty()) {
                    ContactAvatarInvalidUriTracker.removeRawId(rawId)
                }
                ContactAvatarPhotoVersionTracker.bump(rawId)
            }
            summary.copy(photoThumbnailUri = resolvedThumb)
        }
        val entities = withoutRecentlyDeleted(
            enriched.map { summary ->
                val m = meta[summary.contactId]
                if (m != null) ContactPagingMapper.entityToSummaryEntity(summary, m)
                else summary.toEntity()
            },
        )
        contactDao.insertSummaries(entities)
        // Report only what was actually written, so callers do not raise display rebuilds for
        // rows this sync deliberately refused.
        val writtenIds = entities.map { it.contactId }.toSet()
        return IncrementalSyncOutcome(
            changedContactIds = changedSummaries.map { it.contactId }.filter { it in writtenIds },
            insertedContactIds = insertedIds.filter { it in writtenIds },
        )
    }

    /** Runs a small idle photo-backfill chunk when previously deferred. */
    fun scheduleIdlePhotoBackfillIfNeeded() {
        if (!StartupPhotoBackfillGate.allowPhotoBackfill()) {
            photoBackfillDeferredForInteraction = true
            StartupPhotoBackfillGate.scheduleRetryWhenIdle(delayMs = PHOTO_BACKFILL_RETRY_MS) {
                scheduleIdlePhotoBackfillIfNeeded()
            }
            return
        }
        photoBackfillDeferredForInteraction = false
        scope.launch(Dispatchers.IO) {
            // Re-open backfill when summaries still need probes, or display rows are empty while
            // summaries already have thumbs (list-policy strip race).
            if (photoThumbnailBackfillCompleted) {
                val summariesMissing =
                    contactDao.getContactIdsMissingPhotoThumbnail(limit = 1).isNotEmpty()
                val displayMissingWithSummaryThumb = if (!summariesMissing) {
                    val displayMissing = displayCacheDao.getContactIdsMissingListPhoto(limit = 32)
                    displayMissing.isNotEmpty() &&
                        contactDao.getSummariesByIds(displayMissing)
                            .any { it.photoThumbnailUri.isNotEmpty() }
                } else {
                    false
                }
                if (!summariesMissing && !displayMissingWithSummaryThumb) return@launch
                photoThumbnailBackfillCompleted = false
                ProviderCacheDebugLogger.log("photoThumbnailBackfill reopened (list photos still missing)")
            }
            syncMutex.withLock {
                if (photoThumbnailBackfillCompleted) return@withLock
                val backfilledPhotoIds = backfillMissingPhotoThumbnails()
                if (backfilledPhotoIds.isNotEmpty()) {
                    onSyncChanges?.invoke(
                        ContactSyncChangeSet(
                            updatedContactIds = backfilledPhotoIds,
                            wasFullRebuild = false,
                            hasProvenProviderDelta = true,
                        ),
                    )
                    onSyncCompleted?.invoke()
                    // Push avatar URIs into the visible Contacts list without waiting for a full reload.
                    onContactsPartiallyUpdated?.invoke(backfilledPhotoIds)
                }
            }
        }
    }

    private fun isStartupCacheValidSafe(): Boolean =
        try {
            com.goodwy.commons.providercache.ProviderCache.isInitialized() &&
                com.goodwy.commons.providercache.ProviderCache.contactsRepository.isStartupCacheValid()
        } catch (_: Exception) {
            false
        }

    /**
     * Provider photo probes are expensive and ANR telephony when run for thousands of rows.
     * Process a small chunk per idle wake; list UI already uses contact_display_cache avatars.
     */
    private suspend fun backfillMissingPhotoThumbnails(): List<Int> {
        if (suppressIncrementalSync) return emptyList()
        if (!StartupPhotoBackfillGate.allowPhotoBackfill()) {
            photoBackfillDeferredForInteraction = true
            StartupPhotoBackfillGate.pauseBackfill("STARTUP_BUSY", remaining = -1)
            StartupPhotoBackfillGate.scheduleRetryWhenIdle(delayMs = PHOTO_BACKFILL_RETRY_MS) {
                scheduleIdlePhotoBackfillIfNeeded()
            }
            return emptyList()
        }
        val updatedIds = mutableListOf<Int>()
        // Display cache may be missing photos while summaries already have them (policy strip race).
        val displayMissing = displayCacheDao.getContactIdsMissingListPhoto(limit = PHOTO_BACKFILL_MAX_PROBES_PER_RUN)
        if (displayMissing.isNotEmpty()) {
            val summariesWithThumbs = contactDao.getSummariesByIds(displayMissing)
                .filter { it.photoThumbnailUri.isNotEmpty() }
            summariesWithThumbs.forEach { entity ->
                displayCacheDao.updatePhotoFieldsByContactId(
                    contactId = entity.contactId,
                    photoThumbUri = entity.photoThumbnailUri,
                    thumbnailUri = entity.photoThumbnailUri,
                    photoUri = entity.photoThumbnailUri,
                    usePhotoAvatar = 1,
                    hasValidPhotoUri = 1,
                )
                val rawId = entity.primaryRawId.takeIf { it > 0 } ?: entity.contactId
                ContactAvatarInvalidUriTracker.removeRawId(rawId)
                ContactAvatarPhotoVersionTracker.bump(rawId)
                updatedIds.add(entity.contactId)
            }
        }
        // Over-fetch then drop known-empty so we do not burn the probe budget on negatives.
        val candidates = contactDao
            .getContactIdsMissingPhotoThumbnailAfter(
                afterId = photoBackfillAfterContactId,
                limit = PHOTO_BACKFILL_CANDIDATE_FETCH,
            )
            .filterNot { StartupPhotoBackfillGate.hasNegativePhotoResult(it) }
            .take(PHOTO_BACKFILL_MAX_PROBES_PER_RUN)
        if (candidates.isEmpty()) {
            val nextMissing = contactDao.getContactIdsMissingPhotoThumbnailAfter(
                afterId = photoBackfillAfterContactId,
                limit = PHOTO_BACKFILL_CANDIDATE_FETCH,
            )
            if (nextMissing.isEmpty()) {
                if (updatedIds.isEmpty() &&
                    displayCacheDao.getContactIdsMissingListPhoto(limit = 1).isEmpty()
                ) {
                    markPhotoThumbnailBackfillCompleted("no pending probes")
                }
            } else {
                // Entire window already negative-cached — advance cursor past it.
                photoBackfillAfterContactId = nextMissing.maxOrNull() ?: photoBackfillAfterContactId
                photoBackfillDeferredForInteraction = true
                StartupPhotoBackfillGate.scheduleRetryWhenIdle(delayMs = PHOTO_BACKFILL_RETRY_MS) {
                    scheduleIdlePhotoBackfillIfNeeded()
                }
            }
            return updatedIds
        }
        val started = System.currentTimeMillis()
        StartupPhotoBackfillGate.logPhotoBackfillStart(candidates.size)
        val helper = ContactsHelper(context)
        var skippedNoPhoto = 0
        var queried = 0
        candidates.chunked(PHOTO_BACKFILL_BATCH).forEach { idChunk ->
            if (!StartupPhotoBackfillGate.allowProviderPhotoProbe(logOnSkip = false)) {
                photoBackfillDeferredForInteraction = true
                StartupPhotoBackfillGate.pauseBackfill(
                    "STARTUP_BUSY",
                    remaining = candidates.size - queried,
                )
                StartupPhotoBackfillGate.scheduleRetryWhenIdle(delayMs = PHOTO_BACKFILL_RETRY_MS) {
                    scheduleIdlePhotoBackfillIfNeeded()
                }
                return updatedIds
            }
            val batch = contactDao.getSummariesByIds(idChunk)
            val enriched = batch.map { entity ->
                if (entity.photoThumbnailUri.isNotEmpty()) return@map entity
                if (StartupPhotoBackfillGate.hasNegativePhotoResult(entity.contactId)) {
                    skippedNoPhoto++
                    return@map entity
                }
                queried++
                val rawId = entity.primaryRawId.takeIf { it > 0 } ?: entity.contactId
                val resolved = ContactListPhotoUriResolver.resolveForList(
                    helper,
                    entity.contactId,
                    rawId,
                    entity.photoThumbnailUri,
                    allowProviderProbe = true,
                )
                if (resolved.isEmpty()) {
                    StartupPhotoBackfillGate.recordNoPhoto(entity.contactId)
                    skippedNoPhoto++
                }
                if (resolved.isEmpty()) entity else entity.copy(photoThumbnailUri = resolved)
            }
            val changed = withoutRecentlyDeleted(enriched.filter { it.photoThumbnailUri.isNotEmpty() })
            if (changed.isEmpty()) return@forEach
            contactDao.insertSummaries(changed)
            changed.forEach { entity ->
                val rawId = entity.primaryRawId.takeIf { it > 0 } ?: entity.contactId
                ContactAvatarInvalidUriTracker.removeRawId(rawId)
                ContactAvatarPhotoVersionTracker.bump(rawId)
                updatedIds.add(entity.contactId)
                // Keep list/recents display cache in sync — do not wait on a separate rebuild.
                runCatching {
                    displayCacheDao.updatePhotoFieldsByContactId(
                        contactId = entity.contactId,
                        photoThumbUri = entity.photoThumbnailUri,
                        thumbnailUri = entity.photoThumbnailUri,
                        photoUri = entity.photoThumbnailUri,
                        usePhotoAvatar = 1,
                        hasValidPhotoUri = 1,
                    )
                }
            }
            delay(PHOTO_BACKFILL_BATCH_YIELD_MS)
        }
        photoBackfillAfterContactId = maxOf(photoBackfillAfterContactId, candidates.maxOrNull() ?: 0)
        if (updatedIds.isNotEmpty()) {
            consecutiveEmptyPhotoChunks = 0
            ProviderCacheDebugLogger.log(
                "photoThumbnailBackfill updated=${updatedIds.size}",
            )
        } else if (queried > 0) {
            consecutiveEmptyPhotoChunks++
        }
        StartupPhotoBackfillGate.logPhotoBackfillEnd(
            durationMs = System.currentTimeMillis() - started,
            queried = queried,
            skippedNoPhoto = skippedNoPhoto,
        )
        // Datasets with no contact photos: stop after a few empty chunks instead of probing forever.
        if (consecutiveEmptyPhotoChunks >= PHOTO_BACKFILL_EMPTY_CHUNK_ABORT) {
            markPhotoThumbnailBackfillCompleted(
                "no photos after $consecutiveEmptyPhotoChunks empty chunks",
            )
            return updatedIds
        }
        // More rows may remain after the cursor — schedule another small idle chunk.
        val morePending = contactDao.getContactIdsMissingPhotoThumbnailAfter(
            afterId = photoBackfillAfterContactId,
            limit = 1,
        ).isNotEmpty()
        if (morePending) {
            photoBackfillDeferredForInteraction = true
            StartupPhotoBackfillGate.pauseBackfill("CHUNK_YIELD", remaining = -1)
            StartupPhotoBackfillGate.scheduleRetryWhenIdle(delayMs = PHOTO_BACKFILL_RETRY_MS) {
                scheduleIdlePhotoBackfillIfNeeded()
            }
        } else {
            markPhotoThumbnailBackfillCompleted("all candidates processed")
        }
        return updatedIds
    }

    private fun markPhotoThumbnailBackfillCompleted(reason: String) {
        photoThumbnailBackfillCompleted = true
        photoBackfillAfterContactId = 0
        ProviderCacheDebugLogger.log("photoThumbnailBackfill completed ($reason)")
        StartupOrchestrator.markStartupComplete()
    }

    private fun enrichSummaryPhotoUri(
        helper: ContactsHelper,
        summary: com.goodwy.commons.providercache.model.ContactSummary,
        rawId: Int,
    ): com.goodwy.commons.providercache.model.ContactSummary {
        if (summary.photoThumbnailUri.isNotEmpty()) return summary
        if (!StartupPhotoBackfillGate.allowProviderPhotoProbe()) return summary
        val resolved = ContactListPhotoUriResolver.resolveForList(
            helper,
            summary.contactId,
            rawId,
            summary.photoThumbnailUri,
        )
        return if (resolved.isEmpty()) summary else summary.copy(photoThumbnailUri = resolved)
    }

    private fun summaryMatchesCached(provider: com.goodwy.commons.providercache.model.ContactSummary, cached: ContactSummaryEntity): Boolean =
        provider.contactId == cached.contactId &&
            provider.lookupKey == cached.lookupKey &&
            provider.displayName == cached.displayName &&
            provider.photoThumbnailUri == cached.photoThumbnailUri &&
            provider.hasPhoneNumber == cached.hasPhoneNumber &&
            provider.lastUpdatedTimestamp == cached.lastUpdatedTimestamp

    private data class ContactCleanupResult(
        val deletedIds: List<Int>,
        val providerEmpty: Boolean,
    )

    /**
     * Compares Room [contact_summaries] IDs with the live provider and removes any contacts
     * that no longer exist (including when the provider has zero contacts).
     */
    private suspend fun cleanupDeletedContacts(): ContactCleanupResult {
        val cachedIds = contactDao.getAllSummaryIds()
        if (cachedIds.isEmpty()) return ContactCleanupResult(emptyList(), providerEmpty = false)
        val providerIds = providerDataSource.loadAllSummaryIds().toSet()
        val staleIds = cachedIds.filter { it !in providerIds }
        if (staleIds.isEmpty()) return ContactCleanupResult(emptyList(), providerEmpty = providerIds.isEmpty())
        purgeDeletedContactsFromRoom(staleIds, providerIds.isEmpty())
        ProviderCacheDebugLogger.log(
            "contactPrune deleted=${staleIds.size} providerCount=${providerIds.size} roomBefore=${cachedIds.size}",
        )
        return ContactCleanupResult(staleIds, providerEmpty = providerIds.isEmpty())
    }

    private suspend fun purgeDeletedContactsFromRoom(deletedIds: List<Int>, providerEmpty: Boolean) {
        if (providerEmpty) {
            contactDao.clearSummaries()
            contactDao.clearDetails()
            displayCacheDao.clearAll()
            phoneIndexDao.clearAll()
            searchIndexDao.clearAll()
            ContactAvatarInvalidUriTracker.clearAll()
            ContactAvatarPhotoVersionTracker.clearAll()
            return
        }
        deletedIds.chunked(PURGE_CHUNK).forEach { chunk ->
            val displayRows = displayCacheDao.getByContactIds(chunk)
            displayRows.forEach { row -> ContactAvatarInvalidUriTracker.removeRawId(row.rawId) }
            contactDao.deleteSummariesByIds(chunk)
            contactDao.deleteDetailsByContactIds(chunk)
            displayCacheDao.deleteByContactIds(chunk)
            searchIndexDao.deleteForContacts(chunk)
            phoneIndexDao.deleteForContacts(chunk)
        }
    }

    suspend fun cacheContactDetail(contactId: Int) = withContext(Dispatchers.IO) {
        val loaded = providerDataSource.loadContactDetail(contactId) ?: return@withContext
        val summary = loaded.summary
        val summaryEntities = withoutRecentlyDeleted(listOf(summary.toEntity()))
        if (summaryEntities.isEmpty()) return@withContext
        contactDao.insertSummaries(summaryEntities)
        contactDao.replaceContactDetail(
            detail = ContactDetailEntity(
                contactId = summary.contactId,
                lookupKey = summary.lookupKey,
                displayName = summary.displayName,
                prefix = loaded.prefix,
                firstName = loaded.firstName,
                middleName = loaded.middleName,
                surname = loaded.surname,
                suffix = loaded.suffix,
                nickname = loaded.nickname,
                photoUri = "",
                photoThumbnailUri = summary.photoThumbnailUri,
                notes = loaded.notes,
                company = loaded.company,
                jobPosition = loaded.jobPosition,
                starred = 0,
                lastUpdatedTimestamp = summary.lastUpdatedTimestamp,
            ),
            phones = loaded.phones.map {
                ContactPhoneEntity(
                    contactId = contactId,
                    number = it.number,
                    normalizedNumber = it.normalizedNumber,
                    type = it.type,
                    label = it.label,
                    isPrimary = it.isPrimary,
                )
            },
            emails = loaded.emails.map {
                ContactEmailEntity(
                    contactId = contactId,
                    value = it.value,
                    type = it.type,
                    label = it.label,
                )
            },
        )
    }

    companion object {
        private const val TAG = "ContactsSyncManager"
        private const val PURGE_CHUNK = 200
        private const val PHOTO_BACKFILL_BATCH = 10
        /** Hard cap per idle wake — avoids telephony ANR from thousands of provider photo probes. */
        private const val PHOTO_BACKFILL_MAX_PROBES_PER_RUN = 20
        private const val PHOTO_BACKFILL_CANDIDATE_FETCH = 80
        private const val PHOTO_BACKFILL_BATCH_YIELD_MS = 50L
        private const val PHOTO_BACKFILL_RETRY_MS = 15_000L
        /** Abort when several idle chunks find zero provider photos (common for synthetic datasets). */
        private const val PHOTO_BACKFILL_EMPTY_CHUNK_ABORT = 3

        fun create(context: Context, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)): ContactsSyncManager {
            val db = ProviderCacheDatabase.getInstance(context)
            return ContactsSyncManager(
                context = context.applicationContext,
                database = db,
                providerDataSource = ContactsProviderDataSource(context.applicationContext),
                scope = scope,
            )
        }
    }
}
