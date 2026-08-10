package com.goodwy.commons.providercache.repository

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.goodwy.commons.providercache.ProviderCache
import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.datasource.CallLogProviderDataSource
import com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity
import com.goodwy.commons.providercache.model.CallLogEntry
import com.goodwy.commons.providercache.coordinator.CacheMutationReason
import com.goodwy.commons.providercache.coordinator.DisplayCacheCoordinator
import com.goodwy.commons.providercache.debug.CacheMutationLogger
import com.goodwy.commons.providercache.debug.PagingInvalidationReason
import com.goodwy.commons.providercache.debug.ProviderCacheDataSource
import com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger
import com.goodwy.commons.providercache.model.ProviderCacheLoadState
import com.goodwy.commons.providercache.display.RecentDisplayListRow
import com.goodwy.commons.providercache.display.ContactDeletedRecentsResolution
import com.goodwy.commons.providercache.display.ContactDeletedRecentsResult
import com.goodwy.commons.providercache.display.ContactDeletedRecentsResolver
import com.goodwy.commons.providercache.display.ContactDisplayChanged
import com.goodwy.commons.providercache.display.ContactDisplayChangeResult
import com.goodwy.commons.providercache.display.ContactDisplayDeleted
import com.goodwy.commons.providercache.display.DisplayCacheRebuildReason
import com.goodwy.commons.providercache.display.PendingRecentDelta
import com.goodwy.commons.providercache.display.RecentDeltaMode
import com.goodwy.commons.providercache.display.RecentDisplayCacheRebuildResult
import com.goodwy.commons.providercache.display.RecentDisplayCacheRebuildScheduler
import com.goodwy.commons.providercache.display.RecentDisplayRebuildRequest
import com.goodwy.commons.providercache.display.RecentsDisplayState
import com.goodwy.commons.providercache.display.RecentGroupKey
import com.goodwy.commons.providercache.display.groupKeyFromDisplayEntity
import com.goodwy.commons.providercache.display.toCacheMutationReason
import com.goodwy.commons.providercache.display.recentsChangeNeedsFullReload
import com.goodwy.commons.providercache.sync.CallLogSyncChangeSet
import com.goodwy.commons.providercache.sync.CallLogSyncManager
import com.goodwy.commons.providercache.display.DisplayCacheReadiness
import com.goodwy.commons.providercache.display.DisplayCacheReadinessTracker
import com.goodwy.commons.providercache.display.CacheDomain
import com.goodwy.commons.providercache.display.StartupDomainOwner
import com.goodwy.commons.providercache.display.StartupDomainOwnerKind
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.display.RelationalRecentsGroupingFlags
import com.goodwy.commons.providercache.display.RelationalRecentDisplaySnapshotBuilder
import com.goodwy.commons.providercache.display.RelationalReadAuthorityGate
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.helpers.PERMISSION_READ_CALL_LOG
import com.goodwy.commons.providercache.startup.StartupOrchestrator
import com.goodwy.commons.providercache.transaction.ProviderCacheTransactions
import com.goodwy.commons.providercache.toDomain
import com.goodwy.commons.providercache.validation.CacheValidator
import com.goodwy.commons.providercache.validation.RecentDisplayCacheValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CallLogRepository(
    private val context: Context,
    private val database: ProviderCacheDatabase,
    private val providerDataSource: CallLogProviderDataSource,
    private val syncManager: CallLogSyncManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val enrichEntry: suspend (CallLogEntry) -> CallLogEntry = { it },
) {
    private val callLogDao get() = database.callLogDao()
    private val recentDisplayDao get() = database.recentDisplayCacheDao()

    /** App-layer hook to rebuild [recent_display_cache] after raw call-log sync. */
    var recentDisplayCacheRebuild: suspend (RecentDisplayRebuildRequest) -> RecentDisplayCacheRebuildResult = {
        RecentDisplayCacheRebuildResult.EMPTY
    }

    /** Set by [RecentsDisplayBridge] once the real rebuild handler is installed. */
    @Volatile
    var recentDisplayRebuildInstalled: Boolean = false
        private set

    fun markRecentDisplayRebuildInstalled() {
        recentDisplayRebuildInstalled = true
        if (pendingDisplayCatchUp) {
            pendingDisplayCatchUp = false
            scope.launch {
                ensureRecentsDisplayCacheWhenRawPresent()
                // Raw-empty sync that finished before install never reached ensureRecents…
                // (it returns when call_log count == 0). Settle READY_EMPTY / LOADING here.
                ensureEmptyDisplayRebuildAfterSync()
            }
        }
    }

    @Volatile
    private var pendingDisplayCatchUp = false

    /**
     * App-layer hook to patch [recent_display_cache] after a single-contact display edit.
     * Returns updated display-row call ids; empty list triggers a lightweight rebuild.
     */
    var recentDisplayContactChangeHandler: suspend (ContactDisplayChanged, Boolean) -> ContactDisplayChangeResult =
        { _, _ -> ContactDisplayChangeResult.EMPTY }

    /**
     * App-layer hook to clear [recent_display_cache] rows for deleted contacts.
     * Returns patch outcome including deltas; empty match means nothing to rebind.
     */
    var recentDisplayContactDeletedHandler: suspend (
        List<ContactDisplayDeleted>,
        ContactDeletedRecentsResolution,
    ) -> ContactDeletedRecentsResult = { _, _ ->
        ContactDeletedRecentsResult.EMPTY
    }

    /** App-layer hook to validate/repair [recent_display_cache] on warm startup. */
    var recentDisplayCacheStartupRepair: suspend (Boolean) -> Boolean = { false }

    @Volatile
    private var contactDisplayListenerWired = false

    private val recentDisplayCacheScheduler = RecentDisplayCacheRebuildScheduler(
        scope = scope,
        onComplete = { request, result -> handleDisplayCacheRebuildComplete(request, result) },
    ).also { scheduler ->
        scheduler.rebuildHandler = { request ->
            recentDisplayCacheRebuild(request)
        }
    }

    @Volatile
    private var startupDeepValidationCompleted = false

    @Volatile
    private var syncCallbacksWired = false

    @Volatile
    private var initialIncrementalSyncScheduled = false

    @Volatile
    private var recentsCacheVersion = 0L

    @Volatile
    private var needsFullReload = false

    private val pendingRecentDeltas = java.util.Collections.synchronizedList(mutableListOf<PendingRecentDelta>())

    @Volatile
    private var cachedRecentsDisplayRowCount = 0

    @Volatile
    private var displayCacheCoordinator: DisplayCacheCoordinator? = null

    fun wireDisplayCacheCoordinator(coordinator: DisplayCacheCoordinator) {
        displayCacheCoordinator = coordinator
        coordinator.onRecentsDisplayCommitted = onRecentsDisplayCommitted@{ result ->
            if (result.mutationId < latestHandledRecentsMutationId) return@onRecentsDisplayCommitted
            latestHandledRecentsMutationId = result.mutationId
            recentsCacheVersion = result.recentsVersion
            needsFullReload = result.recentsNeedFullReload
            if (result.recentsNeedFullReload) {
                pendingRecentDeltas.clear()
            } else if (result.recentDeltas.isNotEmpty()) {
                pendingRecentDeltas.clear()
                pendingRecentDeltas.addAll(result.recentDeltas)
            }
            val version = result.recentsVersion
            val reasonName = lastRecentsCommitReasonName
            scope.launch(Dispatchers.Main) {
                onRecentsDisplayVersionCommitted?.invoke(version, reasonName)
                // When coordinator owns reconcile, skip bridge self-reconcile callback.
                if (!com.goodwy.commons.providercache.pipeline.RecentsPipelineOwnershipCounters.coordinatorAttached) {
                    onRecentsDisplayStateChanged?.invoke()
                }
            }
        }
    }

    @Volatile
    private var latestHandledRecentsMutationId = 0L

    @Volatile
    private var lastRecentsCommitReasonName: String = "DISPLAY_COMMITTED"

    /** Monotonic version bumped on every [recent_display_cache] change. */
    fun recentsCacheVersion(): Long {
        val persisted = displayCacheCoordinator?.metadataStore?.peekRecentsDisplayVersion() ?: 0L
        return maxOf(recentsCacheVersion, persisted)
    }

    fun recentsDisplayCacheRowCount(): Int = cachedRecentsDisplayRowCount

    /**
     * Main-thread hint before Room purge finishes: treat display cache as empty so Recents
     * empty-placeholder logic does not wait on [applyExternalCallLogDeleteAll].
     */
    fun noteOptimisticCallLogDeleteAll() {
        cachedRecentsDisplayRowCount = 0
    }

    /** Latest display-cache version; mirrors [recentsCacheVersion]. */
    fun latestCacheVersion(): Long = recentsCacheVersion()

    /** Debug-only mirror of Recents UI visible version (bridge writes). */
    @Volatile
    var debugVisibleVersion: Long = 0L
        private set

    fun setDebugVisibleVersion(version: Long) {
        debugVisibleVersion = version
    }

    /** Repository-owned slice of [RecentsDisplayState] (bridge supplies visible version + adapter flag). */
    fun recentsCacheDisplayState(): RecentsDisplayState = RecentsDisplayState(
        cacheVersion = recentsCacheVersion(),
        lastVisibleVersion = debugVisibleVersion,
        adapterLoaded = false,
        pendingDeltas = peekPendingRecentDeltas(),
        needsFullReload = needsFullReload,
    )

    fun markNeedsFullReload() {
        needsFullReload = true
    }

    fun clearNeedsFullReload() {
        needsFullReload = false
    }

    /**
     * Bumps [recentsCacheVersion] after an out-of-band display-cache rewrite (e.g. mislink repair)
     * that did not go through [rebuild]. Notifies the pipeline via display-commit hooks.
     */
    suspend fun commitVersionAfterExternalDisplayWrite(
        reason: DisplayCacheRebuildReason = DisplayCacheRebuildReason.STARTUP_INVALID_CACHE,
    ): Long = commitRecentsDisplayVersion(
        reason = reason,
        forceFull = true,
        meaningfulChange = true,
    )

    fun hasPendingRecentDeltas(): Boolean = pendingRecentDeltas.isNotEmpty()

    fun peekPendingRecentDeltas(): List<PendingRecentDelta> = pendingRecentDeltas.toList()

    fun clearPendingDeltas() {
        pendingRecentDeltas.clear()
    }

    /** @deprecated Use [recentsCacheDisplayState] — kept for transitional callers. */
    @Deprecated("Use recentsCacheDisplayState().needsFullReload", ReplaceWith("recentsCacheDisplayState().needsFullReload"))
    fun pendingRecentsUiReload(): Boolean = needsFullReload || recentsCacheDisplayState().needsReconcile()

    /** @deprecated No-op — reconcile clears reload intent via [clearNeedsFullReload]. */
    @Deprecated("Reconcile clears reload via clearNeedsFullReload")
    fun clearPendingRecentsUiReload() {
        clearNeedsFullReload()
    }

    fun takePendingRecentDeltas(): List<PendingRecentDelta> {
        val copy = pendingRecentDeltas.toList()
        pendingRecentDeltas.clear()
        return copy
    }

    suspend fun loadDisplayRecentEntity(
        callId: Int,
        byContact: Boolean = groupByContact.value,
    ): RecentDisplayCacheEntity? = withContext(Dispatchers.IO) {
        val mode = if (byContact) 1 else 0
        recentDisplayDao.getByCallIds(listOf(callId)).firstOrNull { it.groupByContact == mode }
    }

    fun markRecentPhotoUriInvalid(
        rawContactId: Int,
        contactId: Int?,
        callId: Int,
        uri: String,
    ) {
        val versionOld = com.goodwy.commons.helpers.ContactAvatarPhotoVersionTracker.getSignature(rawContactId)
        com.goodwy.commons.helpers.AvatarBindLogger.photoLoadFailed(
            com.goodwy.commons.helpers.AvatarBindLogger.Surface.RECENTS,
            contactId ?: callId,
            uri,
        )
        com.goodwy.commons.helpers.ContactAvatarInvalidUriTracker.markInvalid(rawContactId, uri)
        com.goodwy.commons.helpers.ContactAvatarPhotoVersionTracker.bump(rawContactId)
        val versionNew = com.goodwy.commons.helpers.ContactAvatarPhotoVersionTracker.getSignature(rawContactId)
        com.goodwy.commons.helpers.AvatarBindLogger.invalidated(rawContactId, versionOld, versionNew)
        scope.launch(Dispatchers.IO) {
            val callIds = linkedSetOf(callId)
            if (contactId != null && contactId > 0) {
                recentDisplayDao.markPhotoUriInvalidByContactId(contactId)
                recentDisplayDao.getByContactId(contactId).forEach { callIds.add(it.callId) }
            } else {
                recentDisplayDao.markPhotoUriInvalid(listOf(callId))
            }
            val deltas = buildUpdateDeltasForCallIds(callIds.toList())
            recordRecentsCacheChanged(DisplayCacheRebuildReason.CONTACT_DISPLAY_CHANGED, deltas)
        }
    }

    /** Called on the main thread when [recent_display_cache] changed — bridge should [reconcileRecentsUi]. */
    var onRecentsDisplayStateChanged: (() -> Unit)? = null

    /** Notifies pending-outgoing-insert tracking about incremental sync deltas. */
    var onCallLogSyncChangeSet: ((CallLogSyncChangeSet) -> Unit)? = null

    /**
     * Fired after a successful Recents display-cache version commit.
     * The RecentsPipelineCoordinator should own reconcile from this callback.
     */
    var onRecentsDisplayVersionCommitted: ((version: Long, reason: String) -> Unit)? = null

    /** @deprecated Use [onRecentsDisplayStateChanged]. */
    @Deprecated("Use onRecentsDisplayStateChanged")
    var onRecentDisplayCacheUpdated: (() -> Unit)?
        get() = onRecentsDisplayStateChanged
        set(value) { onRecentsDisplayStateChanged = value }

    /** @deprecated Use [onRecentsDisplayStateChanged]. */
    @Deprecated("Use onRecentsDisplayStateChanged")
    var onRecentsCacheSilentUpdate: ((Long, List<PendingRecentDelta>) -> Unit)?
        get() = null
        set(_) { }

    // Persisted across launches so warmRecentsRoomCacheIfPresent can pre-start the paging bridge
    // on the main thread before the first IO round-trip, avoiding a 1+ s race with gotRecents.
    private val prefs by lazy {
        context.getSharedPreferences("provider_cache_meta", android.content.Context.MODE_PRIVATE)
    }
    private fun markRoomCachePresent() = prefs.edit().putBoolean("has_call_log_cache", true).apply()
    /** Synchronous — safe to call on the main thread without IO dispatch. */
    fun isRoomCacheKnownPresent(): Boolean = prefs.getBoolean("has_call_log_cache", false)

    private val useRoomCache = MutableStateFlow(false)
    private val pagingGeneration = MutableStateFlow(0)
    /** Mirrors the user's "group recents by contact" setting. Set by the paging bridge on start and on config change. */
    private val groupByContact = MutableStateFlow(true)

    private val _loadState = MutableStateFlow(ProviderCacheLoadState.LoadingFirstPage)
    val loadState: StateFlow<ProviderCacheLoadState> = _loadState.asStateFlow()

    private val pagingConfig = PagingConfig(
        pageSize = DISPLAY_PAGE_SIZE,
        initialLoadSize = DISPLAY_PAGE_SIZE,
        prefetchDistance = DISPLAY_PAGE_SIZE / 2,
        enablePlaceholders = false,
    )

    suspend fun refreshCacheState() {
        val mode = if (groupByContact.value) 1 else 0
        val hasRawCache = withContext(Dispatchers.IO) { callLogDao.getCount() > 0 }
        val displayRows = withContext(Dispatchers.IO) { recentDisplayDao.getCount(mode) }
        val hasDisplayCache = displayRows > 0
        cachedRecentsDisplayRowCount = displayRows
        val hadCache = useRoomCache.value
        useRoomCache.value = hasRawCache || hasDisplayCache
        val previousLoadState = _loadState.value
        val initialSyncComplete = StartupOrchestrator.callLogsSyncDone &&
            !StartupOrchestrator.displayCacheRebuildRunning
        _loadState.value = when {
            hasDisplayCache -> ProviderCacheLoadState.ShowingRoomCache
            hasRawCache -> ProviderCacheLoadState.ShowingRoomCache
            initialSyncComplete || previousLoadState == ProviderCacheLoadState.ShowingRoomCache ->
                ProviderCacheLoadState.ShowingRoomCache
            previousLoadState == ProviderCacheLoadState.RebuildingCache ->
                ProviderCacheLoadState.RebuildingCache
            else -> ProviderCacheLoadState.ShowingProviderFallback
        }
        val fallbackActive = _loadState.value == ProviderCacheLoadState.ShowingProviderFallback &&
            !hasDisplayCache
        DisplayCacheReadinessTracker.setRecentsProviderFallbackActive(fallbackActive)
        if (fallbackActive) {
            com.goodwy.commons.providercache.validation.legacy.LegacyCacheGate.logAuthority("RECENTS")
        }
        ProviderCacheDebugLogger.logRecentsSource(currentCallLogSource(), _loadState.value)
        if (!hadCache && (hasRawCache || hasDisplayCache)) {
            markRoomCachePresent()
            ProviderCacheDebugLogger.logRoomSwitch(
                ProviderCacheDataSource.PROVIDER_FALLBACK,
                ProviderCacheDataSource.ROOM,
            )
            invalidatePaging(PagingInvalidationReason.ROOM_CACHE_READY)
        }
    }

    /** Update the grouping mode; triggers display-cache reload for the new mode. */
    fun setGroupByContact(byContact: Boolean) {
        if (groupByContact.value != byContact) {
            groupByContact.value = byContact
            markNeedsFullReload()
            scheduleRecentDisplayCacheRebuild(
                RecentDisplayRebuildRequest(
                    reason = DisplayCacheRebuildReason.RECENTS_GROUPING_CHANGED,
                    groupByContact = byContact,
                    forceFull = true,
                    limit = DISPLAY_CACHE_LIMIT,
                ),
                immediate = true,
            )
        }
    }

    suspend fun hasRecentDisplayCache(byContact: Boolean = groupByContact.value): Boolean =
        withContext(Dispatchers.IO) {
            recentDisplayDao.getCount(if (byContact) 1 else 0) > 0
        }

    suspend fun loadDisplayRecentsEntities(
        byContact: Boolean = groupByContact.value,
        limit: Int = DISPLAY_CACHE_LIMIT,
    ): List<RecentDisplayCacheEntity> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val mode = if (byContact) 1 else 0
        if (recentDisplayDao.getCount(mode) == 0) {
            return@withContext emptyList()
        }
        val rows = recentDisplayDao.getOrdered(mode, limit)
        ProviderCacheDebugLogger.logRoomQuery(
            label = "recent_display_load",
            durationMs = System.currentTimeMillis() - start,
            rowCount = rows.size,
        )
        rows
    }

    fun scheduleRecentDisplayCacheRebuild(
        request: RecentDisplayRebuildRequest = RecentDisplayRebuildRequest(
            reason = DisplayCacheRebuildReason.CALL_LOG_SYNC_COMPLETED,
            groupByContact = groupByContact.value,
            limit = DISPLAY_CACHE_LIMIT,
        ),
        immediate: Boolean = false,
    ) {
        if (!recentDisplayRebuildInstalled) {
            pendingDisplayCatchUp = true
            // Do not leave a startup owner stuck: repair may have acquired METADATA_REPAIR before
            // the app-layer rebuild handler was installed, which previously blocked COLD_BOOTSTRAP
            // forever and left recent_display_cache empty (UI stuck on raw_preview).
            StartupDomainOwner.release(CacheDomain.RECENTS)
            ProviderCacheDebugLogger.log(
                "recentsDisplayRebuild deferred reason=${request.reason} pendingCatchUp=true",
            )
            return
        }
        DisplayCacheReadinessTracker.setRecents(DisplayCacheReadiness.DISPLAY_BUILDING)
        displayCacheCoordinator?.beginRecentsMutation()
        if (immediate) {
            recentDisplayCacheScheduler.scheduleImmediate(request)
        } else {
            recentDisplayCacheScheduler.schedule(request)
        }
    }

    /** Rebuild [recent_display_cache] immediately after address-book edits (e.g. contact deleted). */
    fun scheduleRecentDisplayCacheRebuildForContactChanges() {
        if (StartupOrchestrator.shouldDeferRecentsResyncAfterContacts()) {
            StartupOrchestrator.markPendingRecentsResyncAfterContacts()
            return
        }
        recentDisplayCacheScheduler.scheduleImmediate(
            RecentDisplayRebuildRequest(
                reason = DisplayCacheRebuildReason.CONTACTS_CHANGED,
                groupByContact = groupByContact.value,
                forceFull = true,
                limit = DISPLAY_CACHE_LIMIT,
            ),
        )
    }

    fun wireContactDisplayChangeListener(contactsRepository: ContactsRepository) {
        if (contactDisplayListenerWired) return
        contactDisplayListenerWired = true
        contactsRepository.onContactDisplayChanged = { change ->
            scope.launch {
                handleContactDisplayChanged(change)
            }
        }
        // Awaited (not fire-and-forget via scope.launch): the caller still holds the lookup-key /
        // phone-digits data it resolved pre-delete, and purges its own Room rows right after this
        // returns, so recents must finish matching/clearing before that data disappears.
        contactsRepository.onContactsDisplayDeleted = { deleted ->
            onContactDeleted(deleted)
        }
        contactsRepository.onAllContactsDisplayDeleted = {
            onAllContactsDeleted()
        }
        contactsRepository.onDisplayCacheReadyForRecentsResync = {
            scheduleRecentDisplayCacheRebuildForContactChanges()
        }
        StartupOrchestrator.setPendingRecentsResyncListener {
            scheduleRecentDisplayCacheRebuildForContactChanges()
        }
    }

    /**
     * Clears contact linkage from call logs and [recent_display_cache], then notifies the Recents
     * bridge via [recordRecentsCacheChanged]. Call after contact metadata is captured and before
     * or after contact Room purge — matching uses only the captured [ContactDisplayDeleted] payload.
     */
    /**
     * Delete-all: strip every contact label from call log + [recent_display_cache], then rebuild
     * recents from phone numbers only.
     */
    suspend fun onAllContactsDeleted() {
        withContext(Dispatchers.IO) {
            callLogDao.clearAllContactInfo()
            if (callLogDao.getCount() == 0) {
                syncManager.runIncrementalSyncAwaitable()
            }
        }
        val request = RecentDisplayRebuildRequest(
            reason = DisplayCacheRebuildReason.CONTACTS_CHANGED,
            groupByContact = groupByContact.value,
            forceFull = true,
            limit = DISPLAY_CACHE_LIMIT,
        )
        val result = recentDisplayCacheRebuild(request)
        android.util.Log.d(
            TAG_CONTACT_CHANGE,
            "recentContactUpdate matchedRows=0 action=cleared_all deltas=${result.deltas.size} " +
                "needsFullReload=${result.needsFullReload}",
        )
        handleDisplayCacheRebuildComplete(request, result)
    }

    suspend fun onContactDeleted(deleted: List<ContactDisplayDeleted>) {
        if (deleted.isEmpty()) return
        val deletedIds = deleted.map { it.contactId }.filter { it > 0 }.distinct()
        val phoneNumbers = deleted.flatMap { event ->
            event.normalizedNumbers + event.phoneDigits
        }.filter { it.isNotEmpty() }
            .map { RecentGroupKey.fromNormalizedNumber(it, it) }
            .distinct()
        val resolution = withContext(Dispatchers.IO) {
            ContactDeletedRecentsResolver.resolve(
                database = database,
                deleted = deleted,
                deletedContactIds = deletedIds,
                groupByContact = groupByContact.value,
            )
        }
        withContext(Dispatchers.IO) {
            if (deletedIds.isNotEmpty()) {
                callLogDao.clearContactInfoForContactIds(deletedIds)
            }
            if (phoneNumbers.isNotEmpty()) {
                callLogDao.clearContactInfoForPhoneNumbers(phoneNumbers)
            }
            resolution.replacementByNormalizedNumber.forEach { (phoneKey, replacement) ->
                if (replacement == null) return@forEach
                val phoneNumbers = buildList {
                    add(phoneKey)
                    deleted.flatMap { it.normalizedNumbers + it.phoneDigits }
                        .filter { candidate ->
                            resolution.replacementFor(candidate)?.contactId == replacement.contactId
                        }
                        .forEach { add(it) }
                }.filter { it.isNotEmpty() }.distinct()
                callLogDao.reassignContactInfoForPhoneNumber(
                    phoneNumbers = phoneNumbers,
                    contactId = replacement.contactId,
                    displayName = replacement.displayName,
                    photoUri = replacement.photoThumbUri,
                )
            }
        }
        val result = withContext(Dispatchers.IO) {
            recentDisplayContactDeletedHandler(deleted, resolution)
        }
        android.util.Log.d(
            TAG_CONTACT_CHANGE,
            "recentContactUpdate matchedRows=${result.updatedCallIds.size} action=cleared " +
                "deltas=${result.deltas.size} needsFullReload=${result.needsFullReload}",
        )
        if (result.updatedCallIds.isEmpty() && !result.needsFullReload) {
            rebuildRecentsFullAfterContactChange()
            return
        }
        recordRecentsCacheChanged(
            reason = DisplayCacheRebuildReason.CONTACT_DISPLAY_CHANGED,
            deltas = emptyList(),
            forceFull = true,
        )
    }

    private suspend fun handleContactDisplayChanged(change: ContactDisplayChanged) {
        if (change.contactId <= 0) return
        withContext(Dispatchers.IO) {
            callLogDao.backfillContactInfoForIds(listOf(change.contactId))
        }
        val result = withContext(Dispatchers.IO) {
            recentDisplayContactChangeHandler(change, groupByContact.value)
        }
        android.util.Log.d(
            TAG_CONTACT_CHANGE,
            "recentContactUpdate matchedRows=${result.updatedCallIds.size} action=updated " +
                "needsFullReload=${result.needsFullReload}",
        )
        if (result.needsFullReload) {
            recordRecentsCacheChanged(
                reason = DisplayCacheRebuildReason.CONTACT_DISPLAY_CHANGED,
                deltas = emptyList(),
                forceFull = true,
            )
            return
        }
        if (result.updatedCallIds.isNotEmpty()) {
            val deltas = buildUpdateDeltasForCallIds(result.updatedCallIds)
            recordRecentsCacheChanged(
                reason = DisplayCacheRebuildReason.CONTACT_DISPLAY_CHANGED,
                deltas = deltas,
                forceFull = false,
            )
            return
        }
        rebuildRecentsFullAfterContactChange()
    }

    private suspend fun rebuildRecentsFullAfterContactChange() {
        withContext(Dispatchers.IO) {
            if (callLogDao.getCount() == 0) {
                syncManager.runIncrementalSyncAwaitable()
            }
        }
        val request = RecentDisplayRebuildRequest(
            reason = DisplayCacheRebuildReason.CONTACTS_CHANGED,
            groupByContact = groupByContact.value,
            forceFull = true,
            limit = DISPLAY_CACHE_LIMIT,
        )
        val result = recentDisplayCacheRebuild(request)
        handleDisplayCacheRebuildComplete(request, result)
    }

    private suspend fun buildUpdateDeltasForCallIds(callIds: List<Int>): List<PendingRecentDelta> {
        if (callIds.isEmpty()) return emptyList()
        val mode = if (groupByContact.value) 1 else 0
        val groupByContact = groupByContact.value
        return withContext(Dispatchers.IO) {
            callIds.mapNotNull { callId ->
                val entity = recentDisplayDao.getByCallIds(listOf(callId))
                    .firstOrNull { it.groupByContact == mode } ?: return@mapNotNull null
                val groupKey = groupKeyFromDisplayEntity(entity)
                PendingRecentDelta(
                    groupKey = groupKey,
                    latestCallId = callId,
                    mode = RecentDeltaMode.UPDATE,
                    groupByContact = groupByContact,
                ).also {
                    android.util.Log.d(
                        TAG,
                        "recentDeltaQueued mode=UPDATE groupKey=$groupKey callId=$callId",
                    )
                }
            }
        }
    }

    private var coalescedRecentsCommitJob: Job? = null

    private suspend fun commitRecentsDisplayVersion(
        reason: DisplayCacheRebuildReason,
        deltas: List<PendingRecentDelta> = emptyList(),
        forceFull: Boolean = false,
        mutationId: Long? = null,
        meaningfulChange: Boolean = true,
    ): Long {
        val fullReload = reason.recentsChangeNeedsFullReload(
            hasDeltas = deltas.isNotEmpty(),
            forceFull = forceFull,
        )
        lastRecentsCommitReasonName = reason.name
        if (fullReload) {
            pendingRecentDeltas.clear()
            needsFullReload = true
        } else if (deltas.isNotEmpty()) {
            pendingRecentDeltas.addAll(deltas)
            deltas.forEach { delta ->
                android.util.Log.d(
                    TAG,
                    "recentDeltaQueued mode=${delta.mode} groupKey=${delta.groupKey}",
                )
            }
        } else {
            needsFullReload = true
        }
        val coordinator = displayCacheCoordinator
        val id = mutationId ?: coordinator?.peekInFlightRecentsMutationId() ?: coordinator?.allocateMutationId() ?: 0L
        val version = if (coordinator != null && id > 0L) {
            coordinator.commitRecentsDisplay(
                mutationId = id,
                reason = reason.toCacheMutationReason(),
                rowCount = cachedRecentsDisplayRowCount,
                meaningfulChange = meaningfulChange,
                recentDeltas = if (fullReload) emptyList() else deltas,
                needsFullReload = needsFullReload,
            )
        } else {
            recentsCacheVersion + 1L
        }
        recentsCacheVersion = version
        android.util.Log.d(
            TAG,
            "recentsCacheChanged version=$recentsCacheVersion reason=$reason " +
                "deltas=${deltas.size} needsFullReload=$needsFullReload",
        )
        // When DisplayCacheCoordinator is wired, version notification is delivered via
        // onRecentsDisplayCommitted. Without it, notify directly.
        if (displayCacheCoordinator == null) {
            val notifyVersion = version
            val notifyReason = reason.name
            scope.launch(Dispatchers.Main.immediate) {
                onRecentsDisplayVersionCommitted?.invoke(notifyVersion, notifyReason)
            }
        }
        return version
    }

    private fun recordRecentsCacheChanged(
        reason: DisplayCacheRebuildReason,
        deltas: List<PendingRecentDelta> = emptyList(),
        forceFull: Boolean = false,
    ) {
        val coalesce = reason == DisplayCacheRebuildReason.CONTACTS_CHANGED ||
            reason == DisplayCacheRebuildReason.CONTACT_DISPLAY_CHANGED
        if (coalesce) {
            coalescedRecentsCommitJob?.cancel()
            coalescedRecentsCommitJob = scope.launch(Dispatchers.IO) {
                delay(CONTACT_REBUILD_COALESCE_MS)
                commitRecentsDisplayVersion(reason, deltas, forceFull)
            }
            return
        }
        scope.launch(Dispatchers.IO) {
            commitRecentsDisplayVersion(reason, deltas, forceFull)
        }
    }

    private suspend fun applySyncChangesToDisplayCache(changes: CallLogSyncChangeSet) {
        if (changes.wasColdRebuild) {
            scheduleRecentDisplayCacheRebuild(
                RecentDisplayRebuildRequest(
                    reason = DisplayCacheRebuildReason.COLD_EMPTY_CACHE,
                    groupByContact = groupByContact.value,
                    forceFull = true,
                    limit = DISPLAY_CACHE_LIMIT,
                ),
            )
            return
        }
        if (!changes.hasChanges) return
        val reason = when {
            changes.deletedCallIds.isNotEmpty() && changes.insertedCallIds.isNotEmpty() ->
                DisplayCacheRebuildReason.CALL_LOG_SYNC_COMPLETED
            changes.deletedCallIds.isNotEmpty() ->
                DisplayCacheRebuildReason.CALL_LOG_DELETED
            else -> DisplayCacheRebuildReason.CALL_LOG_INSERTED
        }
        val request = RecentDisplayRebuildRequest(
            reason = reason,
            groupByContact = groupByContact.value,
            insertedCallIds = changes.insertedCallIds.toSet(),
            deletedCallIds = changes.deletedCallIds.toSet(),
            limit = DISPLAY_CACHE_LIMIT,
        )
        // During cold start only, merge insert deltas into the single COLD_EMPTY_CACHE rebuild
        // when the display cache is still empty. Never upgrade post-call inserts to a full cold
        // swap once authoritative display rows exist — that was adding ~1s per new call.
        if (reason == DisplayCacheRebuildReason.CALL_LOG_INSERTED &&
            changes.insertedCallIds.isNotEmpty() &&
            StartupOrchestrator.coldStart &&
            cachedRecentsDisplayRowCount == 0 &&
            (
                recentDisplayCacheScheduler.isRebuildInProgress() ||
                    recentDisplayCacheScheduler.hasPendingColdRebuild()
                )
        ) {
            scheduleRecentDisplayCacheRebuild(
                request.copy(
                    reason = DisplayCacheRebuildReason.COLD_EMPTY_CACHE,
                    forceFull = true,
                ),
            )
            return
        }
        if (reason == DisplayCacheRebuildReason.CALL_LOG_INSERTED &&
            changes.insertedCallIds.isNotEmpty()
        ) {
            // New call rows: always rebuild immediately. Waiting for the 150ms insert debounce
            // (or only skipping it for pending outgoing tokens) made every new recent feel laggy.
            scheduleRecentDisplayCacheRebuild(request, immediate = true)
            return
        }
        scheduleRecentDisplayCacheRebuild(request)
    }

    private fun handleDisplayCacheRebuildComplete(
        request: RecentDisplayRebuildRequest,
        result: RecentDisplayCacheRebuildResult = RecentDisplayCacheRebuildResult.EMPTY,
    ) {
        scope.launch(Dispatchers.IO) {
            val mode = if (request.groupByContact) 1 else 0
            val rows = recentDisplayDao.getCount(mode)
            val rawCount = callLogDao.getCount()
            cachedRecentsDisplayRowCount = rows
            val hasPermission = context.hasPermission(PERMISSION_READ_CALL_LOG)
            val rawSyncDone = StartupOrchestrator.callLogsSyncDone
            val readiness = DisplayCacheReadinessTracker.computeRecentsReadiness(
                rawCount = rawCount,
                displayRows = rows,
                rawSyncDone = rawSyncDone,
                displayBuilding = false,
                hasCallLogPermission = hasPermission,
            )
            // Empty Room after clear-all / warm sync is authoritative even when coldStart is false
            // (callLogsSyncDone may still be false until onCallLogsRawSyncComplete runs). During
            // cold start before raw sync finishes, keep RAW_SYNCING — do not claim READY_EMPTY.
            val canClaimAuthoritativeEmpty =
                rawCount == 0 &&
                    hasPermission &&
                    (rawSyncDone || !StartupOrchestrator.coldStart)
            val effectiveReadiness = when {
                rows > 0 -> DisplayCacheReadiness.READY_WITH_DATA
                canClaimAuthoritativeEmpty -> DisplayCacheReadiness.READY_EMPTY
                else -> readiness
            }
            com.goodwy.commons.providercache.debug.CacheReadinessAssertions.assertReadyEmptyRequiresRawSync(
                domain = "RECENTS",
                readiness = effectiveReadiness,
                rawSyncComplete = canClaimAuthoritativeEmpty ||
                    rawSyncDone ||
                    effectiveReadiness == DisplayCacheReadiness.READY_WITH_DATA,
            )
            DisplayCacheReadinessTracker.setRecents(effectiveReadiness)
            StartupOrchestrator.onRecentsDisplayCacheReady(rows)
            refreshCacheState()
            val meaningfulCommit = effectiveReadiness == DisplayCacheReadiness.READY_EMPTY ||
                effectiveReadiness == DisplayCacheReadiness.READY_WITH_DATA
            if (meaningfulCommit) {
                commitRecentsDisplayVersion(
                    reason = request.reason,
                    deltas = result.deltas,
                    forceFull = request.forceFull || result.needsFullReload,
                    meaningfulChange = true,
                )
                StartupDomainOwner.markCommitted(CacheDomain.RECENTS)
            } else {
                // Keep startup owner active so load-path does not loop COLD_EMPTY rebuilds;
                // sync completion forces a settle rebuild via ensureEmptyDisplayRebuildAfterSync.
                android.util.Log.d(
                    TAG,
                    "recentsCacheSkipVersionCommit readiness=$effectiveReadiness rows=$rows raw=$rawCount reason=${request.reason}",
                )
            }
        }
    }

    /**
     * Paged recents display rows straight out of `recent_display_cache`.
     *
     * Stage 3 of `docs/recents-remediation-plan.md`: the recents list currently loads the whole
     * table and hand-rolls windowing, diffing and invalidation. Note that even the Room branch of
     * [callLogEntries] wraps a full query in `PagingData.from(...)` — a single page containing
     * everything — so it is not really paged either. This is.
     *
     * Room invalidates the underlying [androidx.paging.PagingSource] on any write to the table,
     * which is what removes the need for the manual invalidation the recents pipeline coordinator
     * performs today. [pagingGeneration] is still honoured so grouping-mode changes and manual
     * refreshes rebuild the pager, matching [callLogEntries].
     *
     * Additive: nothing consumes this yet, so recents behaviour is unchanged.
     *
     * Rows are returned unfiltered. Call-type / blocked-number / protection filtering is per-row
     * and partly ContentProvider-backed, so it cannot go in the SQL; apply it with
     * `PagingData.filter { }` at the consumer, which also makes it cheaper than today because only
     * the loaded window is filtered rather than the entire list.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun recentDisplayPages(): Flow<PagingData<RecentDisplayListRow>> =
        pagingGeneration.flatMapLatest {
            val byContact = if (groupByContact.value) 1 else 0
            Pager(
                config = pagingConfig,
                pagingSourceFactory = { recentDisplayDao.orderedForListPagingSource(byContact) },
            ).flow
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun callLogEntries(): Flow<PagingData<CallLogEntry>> {
        // pagingGeneration drives all refreshes (sync complete, mode change, manual).
        // groupByContact changes call invalidatePaging() so they already increment pagingGeneration —
        // no need to flatMapLatest on groupByContact separately (that would double-emit).
        return useRoomCache.flatMapLatest { fromRoom ->
            pagingGeneration.flatMapLatest {
                val byContact = groupByContact.value
                if (fromRoom) {
                    kotlinx.coroutines.flow.flow {
                        val queryStart = System.currentTimeMillis()
                        val grouped = if (byContact) {
                            callLogDao.getGroupedEntries()
                        } else {
                            callLogDao.getGroupedEntriesByNumber()
                        }
                        ProviderCacheDebugLogger.logRoomQuery(
                            label = if (byContact) "call_log_grouped" else "call_log_grouped_by_number",
                            durationMs = System.currentTimeMillis() - queryStart,
                            rowCount = grouped.size,
                        )
                        emit(PagingData.from(grouped.map { it.toDomain() }))
                    }
                } else {
                    Pager(
                        config = pagingConfig,
                        pagingSourceFactory = { providerDataSource.pagingSource() },
                    ).flow.map { pagingData ->
                        pagingData.map { entry -> enrichEntry(entry) }
                    }
                }
            }
        }
    }

    suspend fun loadFirstPage(limit: Int = PAGE_SIZE): List<CallLogEntry> = withContext(Dispatchers.IO) {
        _loadState.value = ProviderCacheLoadState.LoadingFirstPage
        try {
            refreshCacheState()
            if (useRoomCache.value) {
                val dao = if (groupByContact.value) callLogDao.getGroupedEntries() else callLogDao.getGroupedEntriesByNumber()
                dao.map { it.toDomain() }
            } else {
                _loadState.value = ProviderCacheLoadState.ShowingProviderFallback
                providerDataSource.loadPage(0, limit).map { enrichEntry(it) }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "loadFirstPageFailed", e)
            _loadState.value = ProviderCacheLoadState.Error
            emptyList()
        }
    }

    /**
     * First-screen recents preview from Room when the call-log cache is warm.
     * Returns empty when Room has no rows (caller should fall back to provider call-log preview).
     */
    suspend fun loadGroupedPreview(limit: Int): List<CallLogEntry> = withContext(Dispatchers.IO) {
        refreshCacheState()
        if (!useRoomCache.value) return@withContext emptyList()
        val rows = if (groupByContact.value) {
            callLogDao.getGroupedEntries()
        } else {
            callLogDao.getGroupedEntriesByNumber()
        }
        rows.take(limit).map { it.toDomain() }
    }

    fun wireSyncCallbacksIfNeeded() {
        wireSyncCallbacks()
    }

    private fun wireSyncCallbacks() {
        if (syncCallbacksWired) return
        syncCallbacksWired = true
        syncManager.onSyncChanges = { changes ->
            DisplayCacheReadinessTracker.setRecents(DisplayCacheReadiness.RAW_SYNCING)
            onCallLogSyncChangeSet?.invoke(changes)
            if (changes.insertedCallIds.isNotEmpty()) {
                ProviderCacheDebugLogger.log(
                    "callLogDbUpdate callIds=${changes.insertedCallIds.size}",
                )
                android.util.Log.d(
                    TAG,
                    "callLogDbUpdate callIds=${changes.insertedCallIds.size}",
                )
            }
            if (changes.hasChanges) {
                val coordinator = displayCacheCoordinator
                val rawReason = when {
                    changes.wasColdRebuild -> CacheMutationReason.STARTUP_REPAIR
                    changes.deletedCallIds.isNotEmpty() -> CacheMutationReason.CALL_DELETED
                    else -> CacheMutationReason.CALL_INSERTED
                }
                coordinator?.commitRawRecents(
                    mutationId = coordinator.allocateMutationId(),
                    reason = rawReason,
                    rowCount = callLogDao.getCount(),
                )
            }
            applySyncChangesToDisplayCache(changes)
        }
        syncManager.onSyncCompleted = {
            scope.launch {
                refreshCacheState()
                val mode = if (groupByContact.value) 1 else 0
                if (recentDisplayDao.getCount(mode) == 0) {
                    invalidatePaging(PagingInvalidationReason.SYNC_COMPLETE)
                }
                // Settle empty/filter-empty after sync; forces rebuild when startup owner
                // is still active from a deferred early empty rebuild (mirrors Contacts).
                ensureEmptyDisplayRebuildAfterSync()
            }
        }
    }

    /**
     * After raw sync, empty display must rebuild even when the startup owner is already
     * active (early empty rebuild deferred READY_EMPTY). Skip when already settled so
     * warm NoChange syncs do not flash loading.
     */
    private fun ensureEmptyDisplayRebuildAfterSync() {
        if (!recentDisplayRebuildInstalled) {
            pendingDisplayCatchUp = true
            return
        }
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            DisplayCacheReadinessTracker.setRecents(DisplayCacheReadiness.ERROR_PERMISSION)
            return
        }
        val readiness = DisplayCacheReadinessTracker.recentsReadiness()
        if (readiness == DisplayCacheReadiness.READY_EMPTY ||
            readiness == DisplayCacheReadiness.READY_WITH_DATA
        ) {
            return
        }
        if (scheduleColdEmptyDisplayCacheRebuildIfNeeded()) return
        DisplayCacheReadinessTracker.setRecents(DisplayCacheReadiness.DISPLAY_BUILDING)
        scheduleRecentDisplayCacheRebuild(
            RecentDisplayRebuildRequest(
                reason = DisplayCacheRebuildReason.CALL_LOG_SYNC_COMPLETED,
                groupByContact = groupByContact.value,
                forceFull = true,
                limit = DISPLAY_CACHE_LIMIT,
            ),
            immediate = true,
        )
    }

    /** @return true when a startup-owned cold-empty rebuild was newly acquired and scheduled. */
    private fun scheduleColdEmptyDisplayCacheRebuildIfNeeded(): Boolean {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) return false
        if (!StartupDomainOwner.tryAcquire(
                CacheDomain.RECENTS,
                StartupDomainOwnerKind.COLD_BOOTSTRAP,
                DisplayCacheRebuildReason.COLD_EMPTY_CACHE.name,
            )
        ) {
            return false
        }
        DisplayCacheReadinessTracker.setRecents(DisplayCacheReadiness.DISPLAY_BUILDING)
        scheduleRecentDisplayCacheRebuild(
            RecentDisplayRebuildRequest(
                reason = DisplayCacheRebuildReason.COLD_EMPTY_CACHE,
                groupByContact = groupByContact.value,
                forceFull = true,
                limit = DISPLAY_CACHE_LIMIT,
            ),
            immediate = true,
        )
        return true
    }

    private fun scheduleInitialIncrementalSyncOnce() {
        wireSyncCallbacks()
        if (initialIncrementalSyncScheduled) return
        initialIncrementalSyncScheduled = true
        syncManager.scheduleIncrementalSync()
    }

    fun startBackgroundSync() {
        wireSyncCallbacks()
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            DisplayCacheReadinessTracker.setRecents(DisplayCacheReadiness.ERROR_PERMISSION)
            DisplayCacheReadinessTracker.setRecentsProviderFallbackActive(true)
            _loadState.value = ProviderCacheLoadState.ShowingProviderFallback
            ProviderCacheDebugLogger.logRecentsSource(currentCallLogSource(), _loadState.value)
            return
        }
        if (!DisplayCacheReadinessTracker.resumeRecentsAfterPermissionGranted()) {
            return
        }
        if (!isRoomCacheKnownPresent() && !DisplayCacheReadinessTracker.recentsDisplayReadinessIsSeeded()) {
            StartupOrchestrator.markColdStart()
        }
        scope.launch {
            activateRoomCacheIfPresent()
            ensureRecentsDisplayCacheWhenRawPresent()
        }
        _loadState.value = when {
            useRoomCache.value -> ProviderCacheLoadState.ShowingRoomCache
            StartupOrchestrator.callLogsSyncDone && !StartupOrchestrator.displayCacheRebuildRunning ->
                ProviderCacheLoadState.ShowingRoomCache
            _loadState.value == ProviderCacheLoadState.ShowingRoomCache ->
                ProviderCacheLoadState.ShowingRoomCache
            else -> ProviderCacheLoadState.RebuildingCache
        }
        ProviderCacheDebugLogger.logRecentsSource(currentCallLogSource(), _loadState.value)
        scheduleInitialIncrementalSyncOnce()
    }

    /** Ensures [recent_display_cache] is rebuilt when raw call-log rows exist but display rows do not. */
    suspend fun ensureRecentsDisplayCacheWhenRawPresent() {
        if (!recentDisplayRebuildInstalled) {
            pendingDisplayCatchUp = true
            return
        }
        withContext(Dispatchers.IO) {
            val hasCache = callLogDao.getCount() > 0
            if (!hasCache) return@withContext
            activateRoomCacheIfPresent()
            val mode = if (groupByContact.value) 1 else 0
            if (recentDisplayDao.getCount(mode) == 0 &&
                !recentDisplayCacheScheduler.isRebuildInProgress() &&
                !recentDisplayCacheScheduler.hasPendingColdRebuild()
            ) {
                // Reuse an already-held startup owner (e.g. METADATA_REPAIR deferred until install),
                // otherwise acquire COLD_BOOTSTRAP.
                val owned = StartupDomainOwner.hasActive(CacheDomain.RECENTS) ||
                    StartupDomainOwner.tryAcquire(
                        CacheDomain.RECENTS,
                        StartupDomainOwnerKind.COLD_BOOTSTRAP,
                        DisplayCacheRebuildReason.COLD_EMPTY_CACHE.name,
                    )
                if (!owned) return@withContext
                scheduleRecentDisplayCacheRebuild(
                    RecentDisplayRebuildRequest(
                        reason = DisplayCacheRebuildReason.COLD_EMPTY_CACHE,
                        groupByContact = groupByContact.value,
                        forceFull = true,
                        limit = DISPLAY_CACHE_LIMIT,
                    ),
                    immediate = true,
                )
            } else if (recentDisplayDao.getCount(mode) > 0) {
                if (com.goodwy.commons.providercache.startup.StartupFirstPaintGate.shouldDeferHeavyStartupWork()) {
                    val sourceVersion = recentsCacheVersion
                    com.goodwy.commons.providercache.startup.StartupFirstPaintGate.onRecentsFirstPaintOrTimeout {
                        scope.launch {
                            runDeferredStartupDisplayRepair(mode, sourceVersion)
                        }
                    }
                    return@withContext
                }
                runDeferredStartupDisplayRepair(mode, recentsCacheVersion)
            }
        }
    }

    private suspend fun runDeferredStartupDisplayRepair(mode: Int, sourceVersion: Long) {
        val light = com.goodwy.commons.providercache.validation.RecentRelationalLightCheck
            .evaluate(database, mode)
        if (light.needsRepair) {
            val repaired = com.goodwy.commons.providercache.grouping.RecentGroupingRepairCoordinator.requestRepair(
                mode = mode,
                reason = com.goodwy.commons.providercache.grouping.RepairReason.STARTUP_EMPTY_MEMBERSHIP,
                sourceVersion = sourceVersion,
                currentVersionProvider = { recentsCacheVersion },
                currentModeProvider = { if (groupByContact.value) 1 else 0 },
            ) {
                recentDisplayCacheStartupRepair(groupByContact.value)
            }
            if (repaired is com.goodwy.commons.providercache.grouping.RepairRequestResult.Started ||
                repaired is com.goodwy.commons.providercache.grouping.RepairRequestResult.Coalesced
            ) {
                recordRecentsCacheChanged(
                    reason = DisplayCacheRebuildReason.COLD_EMPTY_CACHE,
                    forceFull = true,
                )
            }
            return
        }
        val repaired = recentDisplayCacheStartupRepair(groupByContact.value)
        if (repaired) {
            recordRecentsCacheChanged(
                reason = DisplayCacheRebuildReason.COLD_EMPTY_CACHE,
                forceFull = true,
            )
        } else {
            maybeRecordAuthorityCompareOnWarmLoad(mode)
        }
    }

    /** Ensures COMPARE soak counters advance when Recents loads a warm display snapshot without rebuild. */
    private suspend fun maybeRecordAuthorityCompareOnWarmLoad(modeDb: Int) {
        if (!RelationalRecentsGroupingFlags.shouldCompareAuthority()) return
        val mode = RecentGroupingMode.fromDbValue(modeDb)

        // An empty relational side while raw has rows is "not built yet", not divergence — the same
        // condition `validateAndRepairOnStartup` treats as needing a rebuild. Comparing here reports
        // every group as GROUP_EXISTENCE:missing, and the result is not merely logged: it lands in
        // `RelationalReadAuthorityGate.lastCompareValid`, which gates relational reads via
        // `RelationalReadBlockReason.COMPARE_MISMATCH`.
        //
        // The window is real and short: a full sync clears the raw table and the relational tables
        // together, and the grouping rebuild that refills them starts a moment later. It is not
        // covered by `RecentRelationalValidationDeferredState`, because that only becomes
        // RELATIONAL_WRITTEN once the rebuild has begun — this fires before that.
        val relationalGroups = database.recentGroupDao().countGroups(mode.dbValue)
        if (relationalGroups == 0 && callLogDao.getCount() > 0) {
            ProviderCacheDebugLogger.log(
                "recentAuthorityCompareWarmLoad skipped mode=${mode.name} reason=relational_not_built",
            )
            return
        }

        val compare = RelationalRecentDisplaySnapshotBuilder.compareWithLegacyDisplay(
            database = database,
            mode = mode,
            includeDisplayFields = true,
        )
        RelationalReadAuthorityGate.markCompareResult(compare.valid)
        com.goodwy.commons.providercache.debug.CompareOnlySoakCounters.recordAuthorityCompare(
            valid = compare.valid,
            displayMismatchCount = compare.cosmeticMismatchCount + compare.authorityMismatchCount,
            cosmeticMismatchCount = compare.cosmeticMismatchCount,
        )
        ProviderCacheDebugLogger.log(
            "recentAuthorityCompareWarmLoad mode=${mode.name} " +
                "authorityMismatches=${compare.authorityMismatchCount} " +
                "cosmeticMismatches=${compare.cosmeticMismatchCount} valid=${compare.valid}",
        )
    }

    private suspend fun activateRoomCacheIfPresent() {
        if (useRoomCache.value) return
        val hasCache = callLogDao.getCount() > 0
        if (!hasCache) return
        markRoomCachePresent()
        useRoomCache.value = true
        _loadState.value = ProviderCacheLoadState.ShowingRoomCache
        invalidatePaging(PagingInvalidationReason.ROOM_CACHE_READY)
    }

    /**
     * Schedules incremental CallLog sync with an explicit ownership class.
     *
     * Only [CallLogSyncOwnership.RECENTS_UI] increments illegal-bypass counters when the
     * RecentsPipelineCoordinator is attached — startup/background/debug sync is allowed.
     */
    fun startIncrementalSync(
        ownership: com.goodwy.commons.providercache.pipeline.CallLogSyncOwnership =
            com.goodwy.commons.providercache.pipeline.CallLogSyncOwnership.BACKGROUND_MAINTENANCE,
    ) {
        if (ownership == com.goodwy.commons.providercache.pipeline.CallLogSyncOwnership.RECENTS_UI) {
            com.goodwy.commons.providercache.pipeline.RecentsPipelineOwnershipCounters.noteDirectSyncAttempt()
        }
        ProviderCacheDebugLogger.log(
            "callLogSync startIncrementalSync ownership=$ownership coordinatorAttached=" +
                com.goodwy.commons.providercache.pipeline.RecentsPipelineOwnershipCounters.coordinatorAttached,
        )
        wireSyncCallbacks()
        syncManager.scheduleIncrementalSync()
    }

    /**
     * Ensures Room call_log reflects the latest provider rows.
     * Coordinator awaits with [CallLogSyncOwnership.RECENTS_UI] (legal — no bypass counter).
     */
    suspend fun awaitCallLogIncrementalSync(
        ownership: com.goodwy.commons.providercache.pipeline.CallLogSyncOwnership =
            com.goodwy.commons.providercache.pipeline.CallLogSyncOwnership.RECENTS_UI,
    ) = withContext(Dispatchers.IO) {
        ProviderCacheDebugLogger.log("callLogSync awaitIncrementalSync ownership=$ownership")
        wireSyncCallbacks()
        syncManager.runIncrementalSyncAwaitable()
    }

    suspend fun callLogRowCount(): Int = withContext(Dispatchers.IO) {
        callLogDao.getCount()
    }

    private var invalidateJob: Job? = null

    fun invalidatePaging(reason: PagingInvalidationReason = PagingInvalidationReason.UNSPECIFIED) {
        // Debounce: coalesce rapid-fire invalidations (e.g. sync-complete + backfill within ~100ms)
        // into a single re-query so the bridge doesn't flash an intermediate result.
        invalidateJob?.cancel()
        invalidateJob = scope.launch {
            delay(80L)
            pagingGeneration.value = pagingGeneration.value + 1
            ProviderCacheDebugLogger.logPagingInvalidation(
                target = "recents",
                reason = reason,
                generation = pagingGeneration.value,
            )
        }
    }

    /**
     * Call after the system call log was cleared or rows were deleted outside paging
     * (e.g. clear history, swipe-to-delete). Drops stale Room rows and rebuilds from provider.
     */
    suspend fun clearRoomCacheOnly() = withContext(Dispatchers.IO) {
        callLogDao.clearAll()
        useRoomCache.value = false
        _loadState.value = ProviderCacheLoadState.RebuildingCache
        invalidatePaging(PagingInvalidationReason.MANUAL_REFRESH)
    }

    fun rebuildAfterExternalMutation() {
        scope.launch {
            syncManager.onSyncCompleted = {
                scope.launch {
                    refreshCacheState()
                    invalidatePaging(PagingInvalidationReason.SYNC_COMPLETE)
                }
            }
            syncManager.scheduleFullRebuild()
        }
    }

    fun onCallLogMutatedExternally() {
        scope.launch {
            clearRoomCacheOnly()
            rebuildAfterExternalMutation()
        }
    }

    private suspend fun recordRecentsDisplayPatched() {
        needsFullReload = false
        pendingRecentDeltas.clear()
        commitRecentsDisplayVersion(
            reason = DisplayCacheRebuildReason.CALL_LOG_DELETED,
            forceFull = false,
        )
    }

    /**
     * Resolves every call-log id that belongs to a Recents group row.
     *
     * Warm-path [RecentCall] shells no longer carry [groupedCalls]; membership lives in
     * [recent_group_calls]. Falls back to the display-cache compatibility column, then the
     * visible head call id.
     *
     * [callType] is the active recents call-type filter. Membership is keyed by number/contact and
     * spans every call type, but under a filter the visible row was built from calls of one type
     * only — so the ids are narrowed to match what the row actually represents. Without it, acting
     * on a row that reads "2 missed calls" would also take the outgoing callback sharing its key.
     */
    suspend fun resolveMembershipCallIds(
        groupKey: String,
        groupingModeDb: Int,
        fallbackCallId: Int = 0,
        callType: Int? = null,
    ): List<Int> = withContext(Dispatchers.IO) {
        if (groupKey.isEmpty()) {
            return@withContext listOfNotNull(fallbackCallId.takeIf { it > 0 })
        }
        val mode = when {
            groupingModeDb >= 0 -> groupingModeDb
            groupByContact.value -> 1
            else -> 0
        }
        // Never narrows to empty: the head call is always of the filtered type, so it survives.
        suspend fun ofFilteredType(ids: List<Int>): List<Int> = when {
            callType == null || ids.isEmpty() -> ids
            else -> callLogDao.getCallIdsOfTypeIn(ids, callType).filter { it > 0 }
        }

        val membershipIds = database.recentGroupCallDao()
            .getCallIdsForGroup(mode, groupKey)
            .map { it.toInt() }
            .filter { it > 0 }
            .distinct()
        if (membershipIds.isNotEmpty()) return@withContext ofFilteredType(membershipIds)

        val displayIds = if (fallbackCallId > 0) {
            recentDisplayDao.getByCallIds(listOf(fallbackCallId))
                .firstOrNull { it.groupByContact == mode }
                ?.groupedCallIds
                .orEmpty()
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it > 0 }
                .distinct()
        } else {
            emptyList()
        }
        if (displayIds.isNotEmpty()) return@withContext ofFilteredType(displayIds)

        listOfNotNull(fallbackCallId.takeIf { it > 0 })
    }

    /**
     * Targeted invalidation after the app deletes call-log rows (action mode, swipe, etc.).
     * Drops matching Room + display-cache rows without scheduling a full reconcile that can
     * reload stale cache rows over the adapter's local delete.
     */
    suspend fun applyExternalCallLogDeletes(
        callLogIds: List<Int>,
        displayRowCallIds: List<Int> = emptyList(),
    ) = withContext(Dispatchers.IO) {
        val ids = callLogIds.distinct().filter { it > 0 }
        if (ids.isEmpty()) return@withContext
        val mutationId = displayCacheCoordinator?.beginRecentsMutation()
        val groupKeys = ids.chunked(200).flatMap { chunk ->
            callLogDao.getGroupKeysForCallIds(chunk)
        }.distinct()
        val displayIds = (displayRowCallIds + ids).distinct().filter { it > 0 }
        ProviderCacheTransactions.purgeCallLogRoomCaches(
            database = database,
            callLogIds = ids,
            groupKeys = groupKeys,
            displayRowCallIds = displayIds,
            mutationId = mutationId ?: 0L,
        )
        recordRecentsDisplayPatched()
        invalidatePaging(PagingInvalidationReason.MANUAL_REFRESH)
    }

    /** Clears Room + display cache after the user deletes all call history. */
    suspend fun applyExternalCallLogDeleteAll() = withContext(Dispatchers.IO) {
        val mutationId = displayCacheCoordinator?.beginRecentsMutation()
        ProviderCacheTransactions.clearAllCallLogRoomCaches(
            database = database,
            mutationId = mutationId ?: 0L,
        )
        cachedRecentsDisplayRowCount = 0
        recordRecentsDisplayPatched()
        invalidatePaging(PagingInvalidationReason.MANUAL_REFRESH)
    }

    fun retryAfterError() {
        _loadState.value = ProviderCacheLoadState.LoadingFirstPage
        startBackgroundSync()
        invalidatePaging(PagingInvalidationReason.ERROR_RETRY)
    }

    fun debugPagingGeneration(): Int = pagingGeneration.value

    fun debugUseRoomCache(): Boolean = useRoomCache.value

    private fun currentCallLogSource(): ProviderCacheDataSource =
        if (useRoomCache.value) ProviderCacheDataSource.ROOM else ProviderCacheDataSource.PROVIDER_FALLBACK

    suspend fun hasRoomCache(): Boolean = withContext(Dispatchers.IO) {
        callLogDao.getCount() > 0
    }

    fun peekGroupByContact(): Boolean = groupByContact.value

    fun hasCallLogPermissionForStartup(): Boolean =
        context.hasPermission(PERMISSION_READ_CALL_LOG)

    /** Called when warm display cache is seeded from persisted metadata at process start. */
    fun noteWarmDisplayCacheSeeded(displayRows: Int, displayVersion: Long) {
        cachedRecentsDisplayRowCount = displayRows
        if (displayVersion > recentsCacheVersion) {
            recentsCacheVersion = displayVersion
        }
        if (displayRows > 0) {
            useRoomCache.value = true
            _loadState.value = ProviderCacheLoadState.ShowingRoomCache
            DisplayCacheReadinessTracker.setRecentsProviderFallbackActive(false)
        }
    }

    /**
     * Metadata-driven raw mirror repair — schedules a full provider resync.
     *
     * Takes RECENTS ownership first, like [repairDisplayCacheFromMetadata]. A full resync
     * rewrites call_log, and recent_group_calls.call_id is a CASCADE foreign key onto it, so
     * firing this while a display rebuild is mid-flight deletes the rows that rebuild is about
     * to reference — the insert then fails the constraint and takes the process down. The
     * repair flag is persisted, so standing down here just defers it to the next startup.
     */
    fun repairRawCacheFromMetadata(reason: String) {
        if (!StartupDomainOwner.tryAcquire(
                CacheDomain.RECENTS,
                StartupDomainOwnerKind.METADATA_REPAIR,
                reason,
            )
        ) {
            return
        }
        ProviderCacheDebugLogger.log("recentsRawRepair reason=$reason")
        syncManager.scheduleFullRebuild()
    }

    /** Metadata-driven display-cache repair after crash or failed mutation. */
    fun repairDisplayCacheFromMetadata(reason: String, groupByContact: Boolean) {
        if (!StartupDomainOwner.tryAcquire(
                CacheDomain.RECENTS,
                StartupDomainOwnerKind.METADATA_REPAIR,
                reason,
            )
        ) {
            return
        }
        ProviderCacheDebugLogger.log("recentsDisplayRepair reason=$reason")
        markNeedsFullReload()
        scheduleRecentDisplayCacheRebuild(
            RecentDisplayRebuildRequest(
                reason = DisplayCacheRebuildReason.STARTUP_INVALID_CACHE,
                groupByContact = groupByContact,
                forceFull = true,
                limit = DISPLAY_CACHE_LIMIT,
            ),
            immediate = true,
        )
    }

    /**
     * Deep recents display validation after light metadata recovery.
     * Repairs invalid phone-identity groups or schedules a full rebuild when needed.
     */
    suspend fun runStartupCacheRecoveryIfNeeded(
        lightReport: CacheValidator.ValidationReport,
    ) = withContext(Dispatchers.IO) {
        if (startupDeepValidationCompleted) return@withContext
        if (com.goodwy.commons.providercache.startup.StartupFirstPaintGate.shouldDeferDeepRecentsValidation()) {
            com.goodwy.commons.providercache.startup.StartupFirstPaintGate.logDeepValidationDeferred("RECENTS_DEEP")
            com.goodwy.commons.providercache.startup.StartupFirstPaintGate.onRecentsFirstPaintOrTimeout {
                scope.launch { executeStartupDeepValidation(lightReport) }
            }
            return@withContext
        }
        executeStartupDeepValidation(lightReport)
    }

    private suspend fun executeStartupDeepValidation(
        lightReport: CacheValidator.ValidationReport,
    ) {
        if (startupDeepValidationCompleted) return
        startupDeepValidationCompleted = true
        val mode = if (groupByContact.value) 1 else 0
        if (callLogDao.getCount() == 0 || recentDisplayDao.getCount(mode) == 0) return
        if (com.goodwy.commons.providercache.grouping.RecentGroupingRepairCoordinator.shouldSkipValidation()) {
            com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger.log(
                "recentGroupingValidation skipped reason=REPAIR_IN_FLIGHT",
            )
            return
        }
        val lightRelational = com.goodwy.commons.providercache.validation.RecentRelationalLightCheck
            .evaluate(database, mode)
        if (lightRelational.needsRepair) {
            val sourceVersion = recentsCacheVersion
            com.goodwy.commons.providercache.grouping.RecentGroupingRepairCoordinator.requestRepair(
                mode = mode,
                reason = com.goodwy.commons.providercache.grouping.RepairReason.STARTUP_DEEP_VALIDATION,
                sourceVersion = sourceVersion,
                currentVersionProvider = { recentsCacheVersion },
                currentModeProvider = { if (groupByContact.value) 1 else 0 },
            ) {
                recentDisplayCacheStartupRepair(groupByContact.value)
            }
            return
        }
        val deep = RecentDisplayCacheValidator.validate(
            database = database,
            groupByContact = mode,
        )
        if (deep.isValid) return
        ProviderCacheDebugLogger.log(
            "recentsDeepValidation invalid=${deep.issues.size} lightIssues=${lightReport.issues.size}",
        )
        val repaired = recentDisplayCacheStartupRepair(groupByContact.value)
        if (!repaired) {
            repairDisplayCacheFromMetadata("DEEP_VALIDATION", groupByContact.value)
        } else {
            recordRecentsCacheChanged(
                reason = DisplayCacheRebuildReason.STARTUP_INVALID_CACHE,
                forceFull = true,
            )
        }
    }

    companion object {
        const val PAGE_SIZE = 50
        const val DISPLAY_CACHE_LIMIT = 1000
        private const val DISPLAY_PAGE_SIZE = 500
        /** Shared tag for the ContactChangeCoordinator structured log lines (see app-layer coordinator). */
        private const val TAG = "CallLogRepository"
        private const val TAG_CONTACT_CHANGE = "ContactChangeCoordinator"
        private const val CONTACT_REBUILD_COALESCE_MS = 50L

        fun create(
            context: Context,
            syncManager: CallLogSyncManager,
            enrichEntry: suspend (CallLogEntry) -> CallLogEntry = { it },
        ): CallLogRepository {
            val appContext = context.applicationContext
            val db = ProviderCacheDatabase.getInstance(appContext)
            return CallLogRepository(
                context = appContext,
                database = db,
                providerDataSource = CallLogProviderDataSource(appContext),
                syncManager = syncManager,
                enrichEntry = enrichEntry,
            ).also { it.wireSyncCallbacksIfNeeded() }
        }
    }
}
