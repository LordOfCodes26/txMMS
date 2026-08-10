package com.goodwy.commons.providercache.repository

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingSource.LoadParams
import androidx.paging.PagingSource.LoadResult
import androidx.paging.PagingState
import androidx.paging.map
import com.goodwy.commons.providercache.ProviderCacheUserInteractionGate
import com.goodwy.commons.providercache.display.ContactsDisplayReadiness
import com.goodwy.commons.providercache.display.DisplayCacheReadiness
import com.goodwy.commons.providercache.display.DisplayCacheReadinessTracker
import com.goodwy.commons.providercache.display.CacheDomain
import com.goodwy.commons.providercache.display.StartupDomainOwner
import com.goodwy.commons.providercache.display.StartupDomainOwnerKind
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.helpers.PERMISSION_READ_CONTACTS
import com.goodwy.commons.providercache.validation.legacy.LegacyCacheGate
import com.goodwy.commons.providercache.startup.ContactsColdStartPaintPolicy
import com.goodwy.commons.providercache.startup.ContactsStartupDecision
import com.goodwy.commons.providercache.startup.StartupOrchestrator
import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.datasource.ContactsMetadataLoader
import com.goodwy.commons.providercache.datasource.ContactsProviderDataSource
import com.goodwy.commons.helpers.ContactAvatarInvalidUriTracker
import com.goodwy.commons.helpers.AvatarIdentityResolver
import com.goodwy.commons.helpers.ContactAvatarPhotoVersionTracker
import com.goodwy.commons.helpers.ContactListPhotoUriPolicy
import com.goodwy.commons.helpers.ContactListPhotoUriResolver
import com.goodwy.commons.helpers.ContactProtectionHelper
import com.goodwy.commons.helpers.ContactsHelper
import com.goodwy.commons.providercache.display.ContactDisplayChanged
import com.goodwy.commons.providercache.display.ContactDisplayDeleted
import com.goodwy.commons.providercache.display.ContactDisplayBindComputer
import com.goodwy.commons.providercache.display.ContactDisplayCacheBuilder
import com.goodwy.commons.providercache.display.ContactDisplayCacheMapper
import com.goodwy.commons.providercache.sync.ContactSearchIndexSync
import com.goodwy.commons.providercache.sync.ContactPhoneIndexSync
import com.goodwy.commons.models.PhoneNumber
import com.goodwy.commons.providercache.entities.ContactDisplayCacheEntity
import com.goodwy.commons.providercache.entities.ContactSummaryEntity
import android.os.SystemClock
import com.goodwy.commons.providercache.display.ContactDisplayLoadHelper
import com.goodwy.commons.providercache.display.ContactDisplayLoadReason
import com.goodwy.commons.providercache.display.ContactDisplayLoadResult
import com.goodwy.commons.providercache.display.ContactsDisplaySnapshot
import com.goodwy.commons.providercache.display.ContactsFastScrollSections
import com.goodwy.commons.providercache.display.ContactDisplayCacheRebuildScheduler
import com.goodwy.commons.providercache.debug.ContactsFirstPaintLogger
import com.goodwy.commons.providercache.coordinator.CacheMutationReason
import com.goodwy.commons.providercache.coordinator.DisplayCacheCoordinator
import com.goodwy.commons.providercache.display.ContactDisplayRebuildMode
import com.goodwy.commons.providercache.display.toCacheMutationReason
import com.goodwy.commons.providercache.transaction.ProviderCacheTransactions
import com.goodwy.commons.providercache.display.ContactDisplayRebuildRequest
import com.goodwy.commons.providercache.display.DisplayCacheRebuildReason
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.providercache.filter.ContactListPagingFilters
import com.goodwy.commons.providercache.filter.ContactPageFilter
import com.goodwy.commons.providercache.filter.ContactPagingMapper
import com.goodwy.commons.providercache.filter.ContactRoomQueryFilters
import com.goodwy.commons.providercache.filter.ContactSecureSqlParams
import com.goodwy.commons.providercache.filter.T9Mapper
import com.goodwy.commons.providercache.search.DialpadContactSearch
import com.goodwy.commons.providercache.search.SearchPagingConfig
import com.goodwy.commons.providercache.search.ToolbarContactSearch
import com.goodwy.commons.providercache.model.ContactSummary
import com.goodwy.commons.providercache.model.ProviderCacheLoadState
import com.goodwy.commons.providercache.debug.PagingInvalidationReason
import com.goodwy.commons.providercache.debug.ProviderCacheDataSource
import com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger
import com.goodwy.commons.providercache.paging.ContactPageFilterPagingSource
import com.goodwy.commons.providercache.paging.EntityMappingPagingSource
import com.goodwy.commons.providercache.paging.MetadataEnrichedContactPagingSource
import com.goodwy.commons.providercache.sync.ContactsBulkDeleteManager
import com.goodwy.commons.providercache.sync.ContactsSyncManager
import com.goodwy.commons.providercache.sync.RecentlyDeletedContacts
import com.goodwy.commons.providercache.toDomain
import com.goodwy.commons.providercache.toListContact
import com.goodwy.commons.providercache.validation.ContactsCacheMetadataStore
import com.goodwy.commons.providercache.validation.ContactsCacheValidator
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.models.contacts.Email
import com.goodwy.commons.models.contacts.Organization
import com.goodwy.commons.extensions.formatPhoneNumber
import com.goodwy.commons.models.contacts.ContactDisplayBind
import com.goodwy.commons.models.normalizeSingleDefaultPhoneFlag
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.os.Looper
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class ContactsRepository(
    private val context: Context,
    private val database: ProviderCacheDatabase,
    private val providerDataSource: ContactsProviderDataSource,
    private val syncManager: ContactsSyncManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val contactDao get() = database.contactDao()
    private val displayCacheDao get() = database.contactDisplayCacheDao()
    private val phoneIndexDao get() = database.contactPhoneIndexDao()
    private val metadataLoader = ContactsMetadataLoader(context)
    private val cacheMetadataStore = ContactsCacheMetadataStore(context)
    private val searchIndexSync = ContactSearchIndexSync(context, database)
    private val phoneIndexSync = ContactPhoneIndexSync(context, database)
    private val startupValidationMutex = Mutex()

    /** Serializes full blank-list QUERY+MAP so early row-warm and submit-lane load share one flight. */
    private val fullDisplayListMutex = Mutex()

    @Volatile
    private var startupValidationCompleted = false

    @Volatile
    private var lastStartupValidationResult: ContactsCacheValidator.ValidationResult? = null

    @Volatile
    private var lastDisplayRebuildReason: DisplayCacheRebuildReason? = null

    private var displayCacheBuilder: ContactDisplayCacheBuilder = ContactDisplayCacheBuilder(
        context = context,
        database = database,
        secureFilterProvider = { contacts -> contacts },
    )

    @Volatile
    private var syncCallbacksWired = false

    @Volatile
    private var initialIncrementalSyncScheduled = false

    private var idlePhotoBackfillJob: Job? = null
    private var deferredStartupSyncJob: Job? = null

    /**
     * Every assignment marks the count as known, so [peekHasContactDisplayCache] can tell
     * "not seeded yet" apart from "genuinely empty" without a blocking Room read.
     */
    @Volatile
    private var cachedDisplayCacheRowCount = 0
        set(value) {
            field = value
            displayCacheRowCountKnown = true
        }

    @Volatile
    private var displayCacheRowCountKnown = false

    @Volatile
    private var displayCacheRowCountRefreshInFlight = false

    /** Reused mapped UI rows when [contactDisplayCacheVersion] and query are unchanged. */
    private var cachedMappedDisplayList: List<Contact>? = null
    private var cachedMappedDisplayListVersion = -1L
    private var cachedMappedDisplayListQuery: String? = null
    private var cachedMappedDisplayContentHash = 0L
    private var cachedMappedDisplaySnapshot: ContactsDisplaySnapshot? = null

    private var lastLoggedContactsSource: ProviderCacheDataSource? = null
    private var lastLoggedContactsLoadState: ProviderCacheLoadState? = null
    private var lastLoggedSearchHintSource: String? = null
    private var lastLoggedSearchHintCount = -1

    private val bulkDeleteManager = ContactsBulkDeleteManager(
        context = context,
        database = database,
        providerDataSource = providerDataSource,
    )

    private val contactDisplayCacheScheduler = ContactDisplayCacheRebuildScheduler(
        scope = scope,
        onComplete = { request -> handleDisplayCacheRebuildComplete(request) },
    ).also { scheduler ->
        scheduler.rebuildHandler = { request ->
            StartupOrchestrator.onDisplayCacheRebuildStarted()
            try {
                displayCacheBuilder.rebuild(
                    reason = request.reason,
                    changedContactIds = request.changedContactIds,
                    deletedContactIds = request.deletedContactIds,
                    forceFull = request.forceFull,
                    mode = request.mode,
                )
            } finally {
                StartupOrchestrator.onDisplayCacheRebuildEnded()
            }
        }
    }

    private var idleDuplicateMergeJob: Job? = null

    /** True while the main Contacts tab is visible; suppresses full list reload when on Recents. */
    @Volatile
    var contactsTabVisible: Boolean = false

    /** While true, suppresses display-cache reloads, sync, and provider fallback during delete-all. */
    @Volatile
    var contactsBulkDeleteInProgress: Boolean = false
        private set

    /**
     * After optimistic delete-all succeeds, block provider fallback repaints until the user explicitly
     * refreshes or [releaseContactsProviderFallbackHold] is called.
     */
    @Volatile
    var contactsJustBulkDeleted: Boolean = false
        private set

    fun shouldBlockContactsProviderFallback(): Boolean =
        contactsBulkDeleteInProgress || contactsJustBulkDeleted

    fun beginBulkDeleteAll() {
        contactsBulkDeleteInProgress = true
        contactsJustBulkDeleted = false
        cachedSearchHintCount = 0
        syncManager.suppressIncrementalSync = true
    }

    /** Main-thread optimistic hint update before Room/display caches catch up. */
    fun clearSearchHintContactCountImmediate() {
        cachedSearchHintCount = 0
    }

    fun adjustSearchHintContactCountImmediate(delta: Int) {
        if (delta == 0) return
        cachedSearchHintCount = (cachedSearchHintCount + delta).coerceAtLeast(0)
    }

    fun endBulkDeleteAll(success: Boolean) {
        contactsBulkDeleteInProgress = false
        syncManager.suppressIncrementalSync = false
        if (success) {
            contactsJustBulkDeleted = true
        }
    }

    fun releaseContactsProviderFallbackHold() {
        contactsJustBulkDeleted = false
    }

    /** Called on the main thread before Room clear — paint empty list and empty placeholder. */
    var onOptimisticDeleteAllStarted: (() -> Unit)? = null

    /** Called on the main thread when delete-all failed and UI should resync from provider. */
    var onBulkDeleteAllFailed: (() -> Unit)? = null

    /** Called on the main thread when delete-all has cleared caches and the adapter should show empty. */
    var onBulkDeleteAllCompleted: (() -> Unit)? = null

    /** Called on the main thread when [contact_display_cache] is rebuilt and UI needs a full reload. */
    var onContactDisplayCacheUpdated: ((ContactDisplayLoadReason) -> Unit)? = null

    /** Called on the main thread when [contact_display_cache] has rows after a rebuild (always, not avatar-gated). */
    var onDisplayCacheBecameReady: ((cacheVersion: Long, loadReason: ContactDisplayLoadReason) -> Unit)? = null

    /**
     * Called after a cold-start display-cache rebuild finishes with rows so recents can re-resolve
     * call-log labels that were built while the address book was still empty.
     */
    var onDisplayCacheReadyForRecentsResync: (() -> Unit)? = null

    /** Called on the main thread when specific contacts were deleted from the display cache. */
    var onContactsDeletedFromDisplay: ((Set<Int>) -> Unit)? = null

    /**
     * Called (and awaited) right before a contact's rows are purged from Room, while lookup key /
     * phone digits are still resolvable. Lets recents display cache clear the cached name/photo for
     * that contact instead of keeping a stale name after the contact is gone.
     */
    var onContactsDisplayDeleted: (suspend (List<ContactDisplayDeleted>) -> Unit)? = null

    /**
     * Called after delete-all succeeds: clears every call-log / recents display linkage to contacts.
     */
    var onAllContactsDisplayDeleted: (suspend () -> Unit)? = null

    /**
     * True when the main Contacts tab adapter has no real contact rows (headers only or not bound).
     * Wired by [ContactsTabDisplayController]; used to upgrade partial cache updates to full load.
     */
    var contactsDisplayAdapterNeedsFullLoad: (() -> Boolean)? = null

    @Volatile
    private var contactDisplayCacheVersion = 0L

    @Volatile
    private var displayCacheCoordinator: DisplayCacheCoordinator? = null

    fun wireDisplayCacheCoordinator(coordinator: DisplayCacheCoordinator) {
        displayCacheCoordinator = coordinator
        scope.launch(Dispatchers.IO) {
            coordinator.metadataStore.refreshFlowsFromRoom()
            contactDisplayCacheVersion = coordinator.metadataStore.peekContactsDisplayVersion()
        }
        coordinator.onContactsDisplayCommitted = onContactsDisplayCommitted@{ result ->
            if (result.mutationId < latestHandledContactsMutationId) return@onContactsDisplayCommitted
            latestHandledContactsMutationId = result.mutationId
            contactDisplayCacheVersion = result.contactsVersion
            scope.launch(Dispatchers.Main) {
                val loadReason = if (result.contactsNeedFullReload) {
                    ContactDisplayLoadReason.CACHE_REBUILD
                } else {
                    ContactDisplayLoadReason.CACHE_REBUILD
                }
                onContactDisplayCacheUpdated?.invoke(loadReason)
            }
        }
    }

    @Volatile
    private var latestHandledContactsMutationId = 0L

    private suspend fun commitContactsDisplayVersion(
        reason: DisplayCacheRebuildReason,
        rowCount: Int,
        needsFullReload: Boolean = false,
        mutationId: Long? = null,
        meaningfulChange: Boolean = true,
    ): Long {
        val coordinator = displayCacheCoordinator
        val id = mutationId ?: coordinator?.peekInFlightContactsMutationId() ?: coordinator?.allocateMutationId() ?: 0L
        val version = if (coordinator != null && id > 0L) {
            coordinator.commitContactsDisplay(
                mutationId = id,
                reason = reason.toCacheMutationReason(),
                rowCount = rowCount,
                meaningfulChange = meaningfulChange,
                needsFullReload = needsFullReload,
            )
        } else {
            contactDisplayCacheVersion + 1L
        }
        contactDisplayCacheVersion = version
        invalidateMappedDisplayListCache()
        return version
    }

    @Volatile
    private var cachedSearchHintCount = 0

    @Volatile
    private var lastAvatarBindSignature = 0L

    @Volatile
    private var lastPagingInvalidationMs = 0L

    @Volatile
    private var lastPagingInvalidationReason: PagingInvalidationReason? = null

    /** True while the main Contacts list waits for [contact_display_cache] to be built. */
    @Volatile
    var contactDisplayCacheBuilding: Boolean = false
        private set(value) {
            field = value
            // Clearing on completion is what keeps [inFlightDisplayRebuildReason] honest: the
            // bootstrap paths raise this flag directly without a request, and a reason left over
            // from the previous rebuild would make the next one look user-driven.
            if (!value) inFlightDisplayRebuildReason = null
        }

    /**
     * Reason for the rebuild currently running, or null when none is or it was raised without one.
     *
     * [lastDisplayRebuildReason] cannot answer this: it is assigned *after*
     * `displayCacheBuilder.rebuild(...)` returns, so while a rebuild is in flight it still holds
     * the previous one. Anything that has to distinguish rebuild kinds *during* the rebuild — the
     * Contacts progress dialog — needs the reason recorded when the work is scheduled instead.
     */
    @Volatile
    private var inFlightDisplayRebuildReason: DisplayCacheRebuildReason? = null

    /**
     * True while a rebuild raised by the user flipping a visibility control is running.
     *
     * Such a rebuild must not put a progress dialog over the Contacts tab. The list has already
     * been updated optimistically — protecting contacts removes the rows from the adapter before
     * the rebuild is even scheduled — so the dialog would cover a list that is already final. Same
     * reasoning as [com.goodwy.commons.providercache.display.ContactsDisplayReadiness], which
     * treats a filter-driven empty as authoritative rather than as a cache that has not settled.
     */
    fun isUserVisibilityRebuildInFlight(): Boolean =
        contactDisplayCacheBuilding &&
            inFlightDisplayRebuildReason?.reflectsUserVisibilityAction == true

    fun peekDisplayCacheVersion(): Long {
        val persisted = displayCacheCoordinator?.metadataStore?.peekContactsDisplayVersion() ?: 0L
        return maxOf(contactDisplayCacheVersion, persisted)
    }

    fun peekDisplayCacheRowCount(): Int = cachedDisplayCacheRowCount

    /**
     * Non-blocking counterpart of [hasContactDisplayCache] for main-thread callers.
     *
     * Returns `null` while the count has not been seeded yet (before
     * [runStartupCacheValidationIfNeeded] has run). Callers on the main thread must treat `null`
     * as "not ready" and use [refreshDisplayCacheRowCountAsync] instead of blocking on Room.
     */
    fun peekHasContactDisplayCache(): Boolean? =
        if (displayCacheRowCountKnown) cachedDisplayCacheRowCount > 0 else null

    /** Populates [peekHasContactDisplayCache] off the caller's thread. Coalesces concurrent calls. */
    fun refreshDisplayCacheRowCountAsync() {
        if (displayCacheRowCountRefreshInFlight) return
        displayCacheRowCountRefreshInFlight = true
        scope.launch {
            try {
                cachedDisplayCacheRowCount = displayCacheDao.getCount()
            } catch (_: Exception) {
                // Leave the previous value; a later mutation or startup validation will seed it.
            } finally {
                displayCacheRowCountRefreshInFlight = false
            }
        }
    }

    fun isStartupCacheValid(): Boolean = lastStartupValidationResult?.isValid == true

    /**
     * Skips a redundant full display reload when startup validation already proved the cache warm
     * and the adapter already shows every display-cache row at the current version.
     */
    fun shouldSkipStartupDisplayReload(loadedCacheVersion: Long, adapterRows: Int): Boolean {
        if (!isStartupCacheValid()) return false
        if (loadedCacheVersion < 0L) return false
        val displayRows = cachedDisplayCacheRowCount
        val effectiveTotal = maxOf(displayRows, cachedSearchHintCount)
        return loadedCacheVersion == contactDisplayCacheVersion &&
            adapterRows > 0 &&
            effectiveTotal > 0 &&
            adapterRows == effectiveTotal
    }

    fun isContactDisplayCacheBuilding(): Boolean = contactDisplayCacheBuilding

    fun peekSearchHintContactCount(): Int = cachedSearchHintCount

    fun activeSearchQueryForDisplay(): String = activeSearchQuery.value

    /**
     * True when a completed display rebuild left zero visible rows under the active filters
     * (e.g. all contacts moved to Private space / Secure box) while Room may still hold rows.
     */
    private fun isDisplayCacheAuthoritativelyEmpty(): Boolean =
        !contactDisplayCacheBuilding &&
            !contactDisplayCacheScheduler.isRebuildInProgress() &&
            DisplayCacheReadinessTracker.contactsReadiness() == DisplayCacheReadiness.READY_EMPTY

    /** Total contacts for search hint — display cache when warm, else Room/provider total. */
    suspend fun refreshSearchHintContactCount(): Int = withContext(Dispatchers.IO) {
        if (shouldBlockContactsProviderFallback()) {
            cachedSearchHintCount = 0
            ProviderCacheDebugLogger.logSearchHintCount("blocked", 0)
            return@withContext 0
        }
        refreshCacheState()
        val displayRows = displayCacheDao.getCount()
        val roomRows = contactDao.getSummaryCount()
        val (source, count) = when {
            displayRows > 0 -> "display_cache" to displayRows
            // Settled filter-empty display must not fall back to Room (protected contacts remain).
            isDisplayCacheAuthoritativelyEmpty() -> "display_cache_empty" to 0
            roomRows > 0 -> "room_total" to roomRows
            else -> {
                val providerRows = providerDataSource.getCount()
                if (providerRows > 0) "provider_total" to providerRows else "none" to 0
            }
        }
        cachedSearchHintCount = count
        if (source == "display_cache" || source == "display_cache_empty") {
            cachedDisplayCacheRowCount = count
        }
        if (source != lastLoggedSearchHintSource || count != lastLoggedSearchHintCount) {
            lastLoggedSearchHintSource = source
            lastLoggedSearchHintCount = count
            ProviderCacheDebugLogger.logSearchHintCount(source, count)
        }
        count
    }

    private suspend fun commitContactsDisplayVersionFromMutation(
        reason: CacheMutationReason,
        rowCount: Int,
    ): Long {
        val coordinator = displayCacheCoordinator ?: return run {
            contactDisplayCacheVersion++
            invalidateMappedDisplayListCache()
            contactDisplayCacheVersion
        }
        val mutationId = coordinator.allocateMutationId()
        contactDisplayCacheVersion = coordinator.commitContactsDisplay(
            mutationId = mutationId,
            reason = reason,
            rowCount = rowCount,
        )
        invalidateMappedDisplayListCache()
        return contactDisplayCacheVersion
    }

    private fun invalidateMappedDisplayListCache() {
        cachedMappedDisplayList = null
        cachedMappedDisplayListVersion = -1L
        cachedMappedDisplayListQuery = null
        cachedMappedDisplayContentHash = 0L
        cachedMappedDisplaySnapshot = null
    }

    private val useRoomCache = MutableStateFlow(false)
    private val pagingGeneration = MutableStateFlow(0)
    private val activeSearchQuery = MutableStateFlow("")

    private var securePageFilter: ContactPageFilter? = null
    private var secureSqlParamsProvider: (() -> ContactSecureSqlParams?)? = null
    private val pagingFilters = ContactListPagingFilters(context) { securePageFilter }

    private val _loadState = MutableStateFlow(ProviderCacheLoadState.LoadingFirstPage)
    val loadState: StateFlow<ProviderCacheLoadState> = _loadState.asStateFlow()

    /**
     * Called on the IO thread with the updated [Contact] objects when an incremental sync
     * modifies specific contacts. Use this to call [notifyItemChanged] on the adapter for
     * instant in-place updates without waiting for the full paging pipeline.
     */
    var onContactsPartiallyUpdated: ((List<Contact>) -> Unit)? = null

    /**
     * Called on the main thread after [refreshSingleContactDisplay] updates contact/display cache.
     * Recents display cache listens to patch matching rows without a full rebuild.
     */
    var onContactDisplayChanged: ((ContactDisplayChanged) -> Unit)? = null

    private val _phoneIndexReady = MutableStateFlow(false)
    val phoneIndexReady: StateFlow<Boolean> = _phoneIndexReady.asStateFlow()

    private val pagingConfig = PagingConfig(
        pageSize = DISPLAY_PAGE_SIZE,
        initialLoadSize = DISPLAY_PAGE_SIZE,
        prefetchDistance = DISPLAY_PAGE_SIZE,
        enablePlaceholders = false,
    )

    fun setSecurePageFilter(filter: ContactPageFilter?, scheduleDisplayRebuild: Boolean = true) {
        securePageFilter = filter
        if (!scheduleDisplayRebuild) return
        invalidatePaging(PagingInvalidationReason.SECURE_FILTER)
        scheduleContactDisplayCacheRebuild(
            ContactDisplayRebuildRequest(
                reason = DisplayCacheRebuildReason.SECURE_MODE_CHANGED,
                forceFull = true,
                mode = ContactDisplayRebuildMode.ACCURATE,
            ),
        )
    }

    fun setDisplayCacheSecureFilter(filter: suspend (List<Contact>) -> List<Contact>) {
        displayCacheBuilder = ContactDisplayCacheBuilder(context, database, filter)
    }

    fun setSearchQuery(query: String) {
        activeSearchQuery.value = query.trim()
        ProviderCacheDebugLogger.logSearch(
            query = activeSearchQuery.value,
            fromRoom = useRoomCache.value,
            phoneIndexReady = _phoneIndexReady.value,
            firstPageCount = -1,
        )
        invalidatePaging(PagingInvalidationReason.SEARCH_QUERY)
    }

    suspend fun hasContactDisplayCache(): Boolean = withContext(Dispatchers.IO) {
        displayCacheDao.getCount() > 0
    }

    suspend fun displayCacheRowCount(): Int = withContext(Dispatchers.IO) {
        displayCacheDao.getCount()
    }

    private fun adapterNeedsFullDisplayLoad(): Boolean =
        contactsDisplayAdapterNeedsFullLoad?.invoke() ?: false

    suspend fun loadDisplayContacts(
        reason: ContactDisplayLoadReason = ContactDisplayLoadReason.CACHE_REBUILD,
        searchQuery: String = activeSearchQuery.value,
    ): ContactDisplayLoadResult = loadDisplayContactChunk(
        limit = Int.MAX_VALUE,
        offset = 0,
        reason = reason,
        searchQuery = searchQuery,
    )

    /**
     * True when Room list rows are already mapped into RAM (thin Contact + sections) for the
     * current display-cache version — Contacts tab open can skip QUERY+MAP.
     */
    fun isMappedDisplayWarm(): Boolean {
        val snapshot = cachedMappedDisplaySnapshot ?: return false
        val list = cachedMappedDisplayList ?: return false
        return cachedMappedDisplayListQuery == "" &&
            cachedMappedDisplayListVersion == contactDisplayCacheVersion &&
            snapshot.displayVersion == contactDisplayCacheVersion &&
            list.isNotEmpty()
    }

    /**
     * Fire-and-forget: warm [cachedMappedDisplaySnapshot] from contact_display_cache after Recents
     * QUERY so submit-lane / tab-open loads hit the reuse path. Safe to call multiple times.
     */
    fun scheduleEnsureDisplayRowsWarmed() {
        if (isMappedDisplayWarm()) return
        scope.launch {
            runCatching { ensureDisplayRowsWarmed() }
        }
    }

    /** Single-flight Room → light list rows → thin Contact + FastScroll sections into RAM. */
    suspend fun ensureDisplayRowsWarmed(): ContactDisplayLoadResult =
        loadDisplayContacts(
            reason = ContactDisplayLoadReason.STARTUP_HIDDEN_ROW_WARM,
            searchQuery = "",
        )

    suspend fun loadDisplayContactChunk(
        limit: Int,
        offset: Int = 0,
        reason: ContactDisplayLoadReason = ContactDisplayLoadReason.INITIAL,
        searchQuery: String = activeSearchQuery.value,
    ): ContactDisplayLoadResult = withContext(Dispatchers.IO) {
        val queryStart = System.currentTimeMillis()
        if (shouldBlockContactsProviderFallback()) {
            ProviderCacheDebugLogger.logContactDisplayLoad(
                reason = reason.name,
                queryMs = 0,
                mapMs = 0,
                rowCount = 0,
                cacheVersion = contactDisplayCacheVersion,
                skipped = true,
            )
            return@withContext ContactDisplayLoadResult(
                contacts = emptyList(),
                cacheVersion = contactDisplayCacheVersion,
                queryMs = 0,
                mapMs = 0,
                rowCount = 0,
            )
        }
        if (displayCacheDao.getCount() == 0) {
            if (offset == 0) {
                refreshSearchHintContactCount()
            }
            val rawRows = if (searchQuery.isBlank()) contactDao.getSummaryCount() else 0
            if (offset == 0 && rawRows > 0) {
                // Secure/source filters can intentionally empty the display while Room still has
                // protected contacts — do not re-enter "building" or the loading dialog sticks.
                if (isDisplayCacheAuthoritativelyEmpty()) {
                    contactDisplayCacheBuilding = false
                    ProviderCacheDebugLogger.logDisplayCacheEmpty(rawRows, "filtered_empty")
                } else {
                    contactDisplayCacheBuilding = true
                    ProviderCacheDebugLogger.logDisplayCacheEmpty(rawRows, "show_loading")
                    if (startupValidationCompleted &&
                        !contactDisplayCacheScheduler.isRebuildInProgress()
                    ) {
                        scheduleColdEmptyDisplayCacheRebuildIfNeeded()
                    }
                }
            } else if (offset == 0 && rawRows == 0) {
                contactDisplayCacheBuilding = false
            }
            val queryMs = System.currentTimeMillis() - queryStart
            ProviderCacheDebugLogger.logContactDisplayLoad(
                reason = reason.name,
                queryMs = queryMs,
                mapMs = 0,
                rowCount = 0,
                cacheVersion = contactDisplayCacheVersion,
            )
            return@withContext ContactDisplayLoadResult(
                contacts = emptyList(),
                cacheVersion = contactDisplayCacheVersion,
                queryMs = queryMs,
                mapMs = 0,
                rowCount = 0,
                totalRowCount = 0,
                hasMore = false,
            )
        }
        val totalRowCount = if (searchQuery.isBlank()) {
            displayCacheDao.getCount()
        } else {
            -1
        }
        if (offset == 0 && totalRowCount > 0) {
            cachedDisplayCacheRowCount = totalRowCount
        }
        // Cheap checksum for first-paint skip guards — avoid full-table avatar SUM until idle.
        val contentHash = if (searchQuery.isBlank()) {
            contactDisplayCacheVersion * 31L + totalRowCount
        } else {
            0L
        }
        val isFullBlankList = searchQuery.isBlank() && offset == 0 && limit == Int.MAX_VALUE
        if (isFullBlankList) {
            // Single-flight: early Recents-QUERY warm and submit-lane load share one QUERY+MAP.
            return@withContext fullDisplayListMutex.withLock {
                reuseOrLoadFullBlankDisplayList(
                    reason = reason,
                    queryStart = queryStart,
                    totalRowCount = totalRowCount,
                    contentHash = contentHash,
                )
            }
        }
        reuseMappedDisplayListOrNull(
            reason = reason,
            queryStart = queryStart,
            searchQuery = searchQuery,
            offset = offset,
            limit = limit,
            totalRowCount = totalRowCount,
            contentHash = contentHash,
        )?.let { return@withContext it }

        val queryWallStart = SystemClock.elapsedRealtime()
        val mapStart = System.currentTimeMillis()
        android.util.Log.d("searchEngine", "tab=CONTACTS source=DISPLAY_CACHE mode=CONTACTS_TAB query=$searchQuery")
        val aligned = searchToolbarAlignedContacts(searchQuery, Int.MAX_VALUE)
        val page = when {
            offset > 0 -> aligned.drop(offset).take(limit)
            limit == Int.MAX_VALUE -> aligned
            else -> aligned.take(limit)
        }
        val mapMs = System.currentTimeMillis() - mapStart
        val queryMs = System.currentTimeMillis() - queryStart
        val effectiveTotal = if (totalRowCount >= 0) totalRowCount else page.size + offset
        if (offset == 0 && limit == Int.MAX_VALUE) {
            cachedMappedDisplayList = page
            cachedMappedDisplayListVersion = contactDisplayCacheVersion
            cachedMappedDisplayListQuery = searchQuery
            cachedMappedDisplayContentHash = contentHash
            cachedMappedDisplaySnapshot = null
        }
        ProviderCacheDebugLogger.logContactDisplayLoad(
            reason = reason.name,
            queryMs = queryMs,
            mapMs = mapMs,
            rowCount = page.size,
            cacheVersion = contactDisplayCacheVersion,
        )
        ContactDisplayLoadResult(
            contacts = page,
            cacheVersion = contactDisplayCacheVersion,
            contentHash = contentHash,
            queryMs = queryMs,
            mapMs = mapMs,
            rowCount = page.size,
            totalRowCount = effectiveTotal,
            hasMore = offset + page.size < effectiveTotal,
            snapshot = null,
            sectionsMs = 0,
        ).also {
            if (offset == 0) {
                contactDisplayCacheBuilding = false
                ProviderCacheDebugLogger.logContactsDisplayLoad("display_cache", page.size)
            }
        }
    }

    private fun reuseMappedDisplayListOrNull(
        reason: ContactDisplayLoadReason,
        queryStart: Long,
        searchQuery: String,
        offset: Int,
        limit: Int,
        totalRowCount: Int,
        contentHash: Long,
    ): ContactDisplayLoadResult? {
        val canReuseMappedList = offset == 0 &&
            limit == Int.MAX_VALUE &&
            searchQuery == cachedMappedDisplayListQuery &&
            contactDisplayCacheVersion == cachedMappedDisplayListVersion &&
            contentHash == cachedMappedDisplayContentHash
        val cachedList = cachedMappedDisplayList
        val cachedSnapshot = cachedMappedDisplaySnapshot
        // Blank-list reuse requires snapshot (rows + sections). Search reuse is list-only.
        if (!canReuseMappedList || cachedList == null) return null
        if (searchQuery.isBlank() && cachedSnapshot == null) return null
        val queryMs = System.currentTimeMillis() - queryStart
        ProviderCacheDebugLogger.logContactDisplayLoad(
            reason = reason.name,
            queryMs = queryMs,
            mapMs = 0,
            rowCount = cachedList.size,
            cacheVersion = contactDisplayCacheVersion,
            skipped = true,
        )
        val effectiveTotal = if (totalRowCount >= 0) totalRowCount else cachedList.size
        return ContactDisplayLoadResult(
            contacts = cachedList,
            cacheVersion = contactDisplayCacheVersion,
            contentHash = contentHash,
            queryMs = queryMs,
            mapMs = 0,
            rowCount = cachedList.size,
            totalRowCount = effectiveTotal,
            hasMore = offset + cachedList.size < effectiveTotal,
            snapshot = cachedSnapshot,
        )
    }

    private suspend fun reuseOrLoadFullBlankDisplayList(
        reason: ContactDisplayLoadReason,
        queryStart: Long,
        totalRowCount: Int,
        contentHash: Long,
    ): ContactDisplayLoadResult {
        reuseMappedDisplayListOrNull(
            reason = reason,
            queryStart = queryStart,
            searchQuery = "",
            offset = 0,
            limit = Int.MAX_VALUE,
            totalRowCount = totalRowCount,
            contentHash = contentHash,
        )?.let { return it }

        ContactsFirstPaintLogger.beginSession()
        ContactsFirstPaintLogger.stage("QUERY_START")
        val queryWallStart = SystemClock.elapsedRealtime()
        val mapStart = System.currentTimeMillis()
        val rows = queryDisplayListRows(Int.MAX_VALUE, 0)
        ContactsFirstPaintLogger.stage(
            "QUERY_END",
            "rows=${rows.size} durationMs=${ContactsFirstPaintLogger.elapsedMs(queryWallStart)}",
        )
        val (contacts, mapTimings) = ContactDisplayLoadHelper.mapListRows(rows)
        val mapMs = System.currentTimeMillis() - mapStart
        ContactsFirstPaintLogger.stage("MAP_END", "durationMs=$mapMs rows=${contacts.size}")
        val sectionsStart = SystemClock.elapsedRealtime()
        val sections = if (rows.isNotEmpty()) {
            ContactsFastScrollSections.buildFromRows(rows)
        } else {
            ContactsFastScrollSections.buildFromContacts(contacts)
        }
        val sectionsMs = ContactsFirstPaintLogger.elapsedMs(sectionsStart)
        ContactsFirstPaintLogger.stage(
            "SECTIONS_END",
            "sections=${sections.size} durationMs=$sectionsMs",
        )
        val snapshot = ContactsDisplaySnapshot(
            displayVersion = contactDisplayCacheVersion,
            rows = rows,
            contacts = contacts,
            sections = sections,
            contentChecksum = contentHash,
        )
        val queryMs = System.currentTimeMillis() - queryStart
        cachedMappedDisplayList = contacts
        cachedMappedDisplayListVersion = contactDisplayCacheVersion
        cachedMappedDisplayListQuery = ""
        cachedMappedDisplayContentHash = contentHash
        cachedMappedDisplaySnapshot = snapshot
        val effectiveTotal = if (totalRowCount >= 0) totalRowCount else contacts.size
        ProviderCacheDebugLogger.logContactDisplayLoad(
            reason = reason.name,
            queryMs = queryMs,
            mapMs = mapMs,
            rowCount = contacts.size,
            cacheVersion = contactDisplayCacheVersion,
            mapEntityMs = mapTimings.entityMs,
            mapTextMs = mapTimings.textMs,
            mapAvatarMs = mapTimings.avatarMs,
            mapSectionMs = mapTimings.sectionMs,
        )
        return ContactDisplayLoadResult(
            contacts = contacts,
            cacheVersion = contactDisplayCacheVersion,
            contentHash = contentHash,
            queryMs = queryMs,
            mapMs = mapMs,
            rowCount = contacts.size,
            totalRowCount = effectiveTotal,
            hasMore = contacts.size < effectiveTotal,
            snapshot = snapshot,
            sectionsMs = sectionsMs,
        ).also {
            contactDisplayCacheBuilding = false
            ProviderCacheDebugLogger.logContactsDisplayLoad("display_cache", contacts.size)
            // Defer identity registration + avatar signature until after first paint (idle).
            if (contacts.isNotEmpty()) {
                val toRegister = contacts
                scope.launch(Dispatchers.IO) {
                    ContactDisplayLoadHelper.registerAvatarIdentities(toRegister)
                    lastAvatarBindSignature = displayCacheDao.getAvatarBindSignature()
                }
            }
        }
    }

    private suspend fun queryDisplayListRows(
        limit: Int,
        offset: Int,
    ): List<com.goodwy.commons.providercache.display.ContactDisplayListRow> =
        if (limit == Int.MAX_VALUE && offset == 0) {
            displayCacheDao.getAllOrderedForList()
        } else {
            displayCacheDao.getOrderedForListChunk(limit, offset)
        }

    fun markContactPhotoUriInvalid(rawId: Int, uri: String) {
        val versionOld = ContactAvatarPhotoVersionTracker.getSignature(rawId)
        ContactAvatarInvalidUriTracker.markInvalid(rawId, uri)
        ContactAvatarPhotoVersionTracker.bump(rawId)
        val versionNew = ContactAvatarPhotoVersionTracker.getSignature(rawId)
        com.goodwy.commons.helpers.AvatarBindLogger.invalidated(rawId, versionOld, versionNew)
        scope.launch(Dispatchers.IO) {
            displayCacheDao.markPhotoUriInvalid(rawId)
            lastAvatarBindSignature = displayCacheDao.getAvatarBindSignature()
        }
    }

    /**
     * Re-reads display fields from the Contacts Provider, updates Room summaries/display cache,
     * and notifies the list adapter for the affected row when visible.
     *
     * @param force When true (e.g. returning from edit), always bust Glide/invalid-uri caches even if URIs are unchanged.
     * @return the [Job] doing the actual work, so callers (e.g. ContactChangeCoordinator) can await completion for logging.
     */
    fun refreshSingleContactDisplay(rawId: Int, force: Boolean = false): Job {
        if (rawId <= 0) return Job().apply { complete() }
        return scope.launch(Dispatchers.IO) {
            if (shouldBlockContactsProviderFallback()) return@launch
            val started = SystemClock.elapsedRealtime()

            coroutineScope {
                val oldDisplayRow = displayCacheDao.getByRawId(rawId)
                val providerContactId = providerDataSource.resolveContactIdFromRawId(rawId)
                if (providerContactId == null) {
                    // Contact was deleted (or never existed). Do not keep/rebuild from a stale
                    // display-cache row — that is what left deleted names searchable after purge.
                    if (oldDisplayRow != null) {
                        removeContactsFromCachesImmediately(listOf(rawId)).join()
                    }
                    return@coroutineScope
                }
                val contactId = providerContactId
                val fieldsDeferred = async { providerDataSource.loadEditRefreshFields(contactId) }
                val summaryDeferred = async { contactDao.getSummariesByIds(listOf(contactId)).firstOrNull() }
                val fields = fieldsDeferred.await()
                if (fields == null) {
                    if (oldDisplayRow != null) {
                        removeContactsFromCachesImmediately(listOf(rawId)).join()
                    }
                    return@coroutineScope
                }
                val cachedSummary = summaryDeferred.await()
                val oldName = oldDisplayRow?.displayName.orEmpty()
                val oldSortKey = oldDisplayRow?.sortKey.orEmpty()
                val newName = fields.displayName.ifEmpty { oldName }

                Log.d(
                    TAG_AVATAR_REFRESH,
                    "contactNameAfterEdit contactId=$contactId rawId=$rawId oldName=$oldName newName=$newName",
                )

                val listThumbUri = ContactListPhotoUriPolicy.resolveListPhotoThumbUri(
                    fields.thumbnailUri,
                    fields.photoUri,
                ).ifEmpty {
                    ContactListPhotoUriResolver.resolveForList(
                        ContactsHelper(context),
                        fields.contactId,
                        rawId,
                        fields.thumbnailUri,
                    )
                }
                val oldThumbUri = cachedSummary?.photoThumbnailUri.orEmpty()
                val photoChanged = force ||
                    listThumbUri != oldThumbUri ||
                    fields.photoId != ContactAvatarPhotoVersionTracker.getPhotoId(rawId)
                if (photoChanged) {
                    Log.d(
                        TAG_AVATAR_REFRESH,
                        "avatarCacheUpdate contactId=$contactId force=$force " +
                            "oldUri=${oldThumbUri.take(80)} newUri=${listThumbUri.take(80)} " +
                            "photoId=${fields.photoId}",
                    )
                    ContactAvatarInvalidUriTracker.removeRawId(rawId)
                    val versionOld = ContactAvatarPhotoVersionTracker.getSignature(rawId)
                    ContactAvatarPhotoVersionTracker.record(rawId, fields.photoId)
                    val versionNew = ContactAvatarPhotoVersionTracker.getSignature(rawId)
                    com.goodwy.commons.helpers.AvatarBindLogger.photoEditApplied(
                        contactId = contactId,
                        uriChanged = listThumbUri != oldThumbUri,
                        version = versionNew,
                    )
                    if (versionNew != versionOld) {
                        com.goodwy.commons.helpers.AvatarBindLogger.invalidated(rawId, versionOld, versionNew)
                    }
                }

                val contact = buildContactForDisplayRefresh(
                    rawId = rawId,
                    fields = fields,
                    oldDisplayRow = oldDisplayRow,
                ).apply {
                    if (oldDisplayRow == null) {
                        ContactsHelper(context).getContactWithId(rawId)?.source?.let { source = it }
                    }
                    if (listThumbUri.isNotEmpty()) {
                        thumbnailUri = listThumbUri
                        if (photoUri.isEmpty()) {
                            photoUri = listThumbUri
                        }
                    }
                }
                val updatedRow = if (oldDisplayRow != null) {
                    ContactDisplayCacheMapper.fromContact(
                        context = context,
                        contact = contact,
                        displayOrder = oldDisplayRow.displayOrder,
                    )
                } else {
                    ContactDisplayCacheMapper.fromContact(
                        context = context,
                        contact = contact,
                        displayOrder = displayCacheDao.getCount(),
                    )
                }
                val displayContact = ContactDisplayCacheMapper.toContact(updatedRow)
                AvatarIdentityResolver.register(
                    contactId = contact.contactId,
                    rawId = rawId,
                    displayBind = displayContact.displayBind,
                )

                displayCacheDao.insertAll(listOf(updatedRow))
                Log.d(TAG_CONTACT_CHANGE, "contactDisplayUpdate rows=1")
                cachedDisplayCacheRowCount = displayCacheDao.getCount()
                if (oldDisplayRow == null) {
                    adjustSearchHintContactCountImmediate(+1)
                    syncManager.scheduleIncrementalSync()
                }
                if (photoChanged) {
                    lastAvatarBindSignature = displayCacheDao.getAvatarBindSignature()
                }
                if (oldDisplayRow != null) {
                    Log.d(
                        TAG_AVATAR_REFRESH,
                        "displayCacheNameUpdate contactId=$contactId oldName=$oldName newName=${updatedRow.displayName} " +
                            "oldSortKey=$oldSortKey newSortKey=${updatedRow.sortKey}",
                    )
                    if (photoChanged) {
                        Log.d(
                            TAG_AVATAR_REFRESH,
                            "displayCacheAvatarUpdate contactId=$contactId photoThumbUri=${updatedRow.photoThumbUri.take(80)}",
                        )
                    }
                } else {
                    Log.d(
                        TAG_AVATAR_REFRESH,
                        "displayCacheInsertNew contactId=$contactId rawId=$rawId name=${updatedRow.displayName}",
                    )
                }

                withContext(Dispatchers.Main) {
                    if (shouldBlockContactsProviderFallback()) return@withContext
                    onContactsPartiallyUpdated?.invoke(listOf(displayContact))
                }

                // Always write/refresh the contact_summaries row — for existing contacts this keeps
                // it current, and for brand-new contacts (cachedSummary == null, e.g. right after
                // insert) this is what previously left contact_summaries empty until the next
                // timestamp-based incremental sync happened to run.
                val isNewSummary = cachedSummary == null
                val resolvedThumb = when {
                    force -> listThumbUri
                    listThumbUri.isNotEmpty() -> listThumbUri
                    else -> cachedSummary?.photoThumbnailUri.orEmpty()
                }
                val nameChanged = isNewSummary || cachedSummary!!.displayName != newName
                val oldFirstPhoneNormalized = cachedSummary?.firstPhoneNormalized.orEmpty()
                val baseSummary = cachedSummary ?: ContactSummaryEntity(
                    contactId = contactId,
                    lookupKey = fields.lookupKey,
                    displayName = newName,
                    photoThumbnailUri = resolvedThumb,
                    hasPhoneNumber = fields.firstPhoneNormalized.isNotEmpty() || fields.firstPhoneRaw.isNotEmpty(),
                    lastUpdatedTimestamp = fields.lastUpdatedTimestamp,
                    primaryRawId = rawId,
                )
                contactDao.insertSummaries(
                    listOf(
                        baseSummary.copy(
                            displayName = newName,
                            photoThumbnailUri = resolvedThumb,
                            lastUpdatedTimestamp = maxOf(baseSummary.lastUpdatedTimestamp, fields.lastUpdatedTimestamp),
                            firstPhoneNormalized = fields.firstPhoneNormalized,
                            firstEmail = fields.firstEmail,
                        ),
                    ),
                )

                // Keep contact_phone_index current immediately — an edited/new phone number must not
                // wait for the next timestamp-based incremental sync to be searchable/matchable in
                // dialpad search or recents caller-name resolution.
                val preRefreshPhoneIndex = phoneIndexDao.getByContactIds(listOf(contactId))
                val oldPhoneDigits = preRefreshPhoneIndex.map { entry ->
                    entry.phoneDigits.ifEmpty { entry.digits }
                }.filter { it.isNotEmpty() }
                val oldNormalizedNumbers = preRefreshPhoneIndex.map { it.normalizedNumber }.filter { it.isNotEmpty() }
                phoneIndexSync.rebuildForContactIds(listOf(contactId))
                if (nameChanged) {
                    searchIndexSync.updateForContactIds(listOf(contactId))
                }
                Log.d(TAG_CONTACT_CHANGE, "contactCacheUpdate rows=1 contactId=$contactId")

                Log.d(
                    TAG_AVATAR_REFRESH,
                    "refreshSingleContactDisplay contactId=$contactId rawId=$rawId " +
                        "ms=${SystemClock.elapsedRealtime() - started}",
                )

                val refreshedPhoneIndex = phoneIndexDao.getByContactIds(listOf(contactId))
                val phoneDigits = refreshedPhoneIndex.map { entry ->
                    entry.phoneDigits.ifEmpty { entry.digits }
                }.filter { it.isNotEmpty() }
                val normalizedNumbers = refreshedPhoneIndex.map { it.normalizedNumber }.filter { it.isNotEmpty() }
                val phoneChanged = oldFirstPhoneNormalized != fields.firstPhoneNormalized ||
                    oldPhoneDigits.toSet() != phoneDigits.toSet() ||
                    oldNormalizedNumbers.toSet() != normalizedNumbers.toSet()
                val lookupKeyChanged = cachedSummary?.lookupKey.orEmpty().isNotEmpty() &&
                    cachedSummary!!.lookupKey != fields.lookupKey
                val nameOrPhotoChanged = isNewSummary || newName != oldName || photoChanged || phoneChanged
                if (nameOrPhotoChanged) {
                    val change = ContactDisplayChanged(
                        contactId = contactId,
                        lookupKey = fields.lookupKey,
                        oldName = oldName,
                        newName = newName,
                        oldPhotoThumbUri = oldThumbUri,
                        newPhotoThumbUri = listThumbUri,
                        phoneDigits = phoneDigits,
                        normalizedNumbers = normalizedNumbers,
                        oldPhoneDigits = oldPhoneDigits,
                        oldNormalizedNumbers = oldNormalizedNumbers,
                        lookupKeyChanged = lookupKeyChanged,
                    )
                    Log.d(
                        TAG_AVATAR_REFRESH,
                        "contactDisplayChanged contactId=$contactId oldName=$oldName newName=$newName",
                    )
                    withContext(Dispatchers.Main) {
                        onContactDisplayChanged?.invoke(change)
                    }
                }
            }
        }
    }

    private fun buildContactForDisplayRefresh(
        rawId: Int,
        fields: ContactsProviderDataSource.ContactEditRefreshFields,
        oldDisplayRow: ContactDisplayCacheEntity?,
    ): Contact {
        val contact = Contact(
            id = rawId,
            contactId = fields.contactId,
            firstName = fields.displayName,
            photoUri = fields.photoUri,
            thumbnailUri = fields.thumbnailUri,
            source = oldDisplayRow?.source.orEmpty(),
            starred = fields.starred,
        )
        if (fields.firstPhoneRaw.isNotEmpty() || fields.firstPhoneNormalized.isNotEmpty()) {
            contact.phoneNumbers.add(
                PhoneNumber(
                    value = fields.firstPhoneRaw.ifEmpty { fields.firstPhoneNormalized },
                    normalizedNumber = fields.firstPhoneNormalized.ifEmpty { fields.firstPhoneRaw },
                    type = 0,
                    label = "",
                    isPrimary = true,
                ),
            )
        }
        if (fields.firstEmail.isNotEmpty()) {
            contact.emails.add(
                Email(value = fields.firstEmail, type = 0, label = ""),
            )
        }
        return contact
    }

    /** @see refreshSingleContactDisplay */
    fun refreshContactAvatar(rawId: Int, force: Boolean = false): Job = refreshSingleContactDisplay(rawId, force)

    private suspend fun notifyDisplayCacheBecameReadyIfPopulated(
        cacheVersion: Long,
        rebuildReason: DisplayCacheRebuildReason,
    ) {
        contactDisplayCacheBuilding = false
        val loadReason = if (rebuildReason == DisplayCacheRebuildReason.STARTUP_INVALID_CACHE) {
            ContactDisplayLoadReason.STARTUP_CACHE_REBUILT
        } else {
            ContactDisplayLoadReason.CACHE_REBUILD
        }
        withContext(Dispatchers.Main) {
            onDisplayCacheBecameReady?.invoke(cacheVersion, loadReason)
        }
    }

    private suspend fun notifyDisplayCacheUpdatedIfAvatarChanged(
        force: Boolean = false,
        reason: ContactDisplayLoadReason = ContactDisplayLoadReason.CACHE_REBUILD,
    ) {
        if (shouldBlockContactsProviderFallback()) {
            ProviderCacheDebugLogger.log(
                "contact_display_reload skipped (bulk delete hold)",
            )
            return
        }
        val newSig = displayCacheDao.getAvatarBindSignature()
        val avatarChanged = force || newSig != lastAvatarBindSignature
        if (!avatarChanged) {
            ProviderCacheDebugLogger.log(
                "contact_display_reload skipped (avatar bind signature unchanged sig=$newSig)",
            )
            return
        }
        lastAvatarBindSignature = newSig
        withContext(Dispatchers.Main) {
            onContactDisplayCacheUpdated?.invoke(reason)
        }
    }

    fun scheduleContactDisplayCacheRebuild(
        request: ContactDisplayRebuildRequest = ContactDisplayRebuildRequest(
            reason = DisplayCacheRebuildReason.CONTACT_SYNC_COMPLETED,
        ),
    ) {
        if (contactsBulkDeleteInProgress || contactsJustBulkDeleted) {
            ProviderCacheDebugLogger.log("displayCacheRebuild skipped (bulk delete hold)")
            return
        }
        inFlightDisplayRebuildReason = request.reason
        displayCacheCoordinator?.beginContactsMutation()
        contactDisplayCacheScheduler.schedule(request)
    }

    /** @return true when a startup-owned cold-empty rebuild was scheduled. */
    private fun scheduleColdEmptyDisplayCacheRebuildIfNeeded(): Boolean {
        if (shouldBlockContactsProviderFallback()) return false
        if (!StartupDomainOwner.tryAcquire(
                CacheDomain.CONTACTS,
                StartupDomainOwnerKind.COLD_BOOTSTRAP,
                DisplayCacheRebuildReason.COLD_EMPTY_CACHE.name,
            )
        ) {
            return false
        }
        contactDisplayCacheBuilding = true
        DisplayCacheReadinessTracker.setContacts(DisplayCacheReadiness.DISPLAY_BUILDING)
        scheduleContactDisplayCacheRebuildImmediate(
            ContactDisplayRebuildRequest(
                reason = DisplayCacheRebuildReason.COLD_EMPTY_CACHE,
                forceFull = true,
                mode = ContactDisplayRebuildMode.FAST,
            ),
        )
        return true
    }

    /**
     * After raw sync, empty display must rebuild even when the startup owner is already
     * active (early empty rebuild deferred READY_EMPTY). Skip when already settled so
     * filter-empty warm sessions do not flash loading on every sync complete.
     */
    private fun ensureEmptyDisplayRebuildAfterSync() {
        if (shouldBlockContactsProviderFallback()) return
        if (scheduleColdEmptyDisplayCacheRebuildIfNeeded()) return
        val readiness = DisplayCacheReadinessTracker.contactsReadiness()
        if (readiness == DisplayCacheReadiness.READY_EMPTY ||
            readiness == DisplayCacheReadiness.READY_WITH_DATA
        ) {
            return
        }
        contactDisplayCacheBuilding = true
        DisplayCacheReadinessTracker.setContacts(DisplayCacheReadiness.DISPLAY_BUILDING)
        scheduleContactDisplayCacheRebuildImmediate(
            ContactDisplayRebuildRequest(
                reason = DisplayCacheRebuildReason.CONTACT_SYNC_COMPLETED,
                forceFull = true,
                mode = ContactDisplayRebuildMode.FAST,
            ),
        )
    }

    fun scheduleAccurateContactDisplayCacheRebuild(
        reason: DisplayCacheRebuildReason = DisplayCacheRebuildReason.MANUAL_DEBUG,
    ) {
        idleDuplicateMergeJob?.cancel()
        scheduleContactDisplayCacheRebuild(
            ContactDisplayRebuildRequest(
                reason = reason,
                forceFull = true,
                mode = ContactDisplayRebuildMode.ACCURATE,
            ),
        )
    }

    private fun handleDisplayCacheRebuildComplete(request: ContactDisplayRebuildRequest) {
        scope.launch(Dispatchers.IO) {
            if (displayCacheDao.getCount() > 0) {
                cacheMetadataStore.setCacheSchemaVersion(ContactsCacheValidator.CURRENT_DISPLAY_CACHE_VERSION)
            }
            val count = displayCacheDao.getCount()
            contactDisplayCacheBuilding = false
            val roomCount = contactDao.getSummaryCount()
            useRoomCache.value = roomCount > 0
            _loadState.value = if (roomCount > 0) {
                ProviderCacheLoadState.ShowingRoomCache
            } else {
                ProviderCacheLoadState.ShowingProviderFallback
            }
            val fallbackActive = _loadState.value == ProviderCacheLoadState.ShowingProviderFallback
            DisplayCacheReadinessTracker.setContactsProviderFallbackActive(fallbackActive)
            cachedDisplayCacheRowCount = count
            lastDisplayRebuildReason = request.reason
            val needsFull = request.forceFull || request.reason.requiresFullContactRebuild
            val hasPermission = context.hasPermission(PERMISSION_READ_CONTACTS)
            val contactsSyncDone = StartupOrchestrator.contactsSyncDone
            val coldStart = StartupOrchestrator.coldStart
            // Room rows imply a usable raw mirror for compute(); sync flag still gates READY_EMPTY.
            val rawSyncDone = contactsSyncDone || roomCount > 0
            val readiness = DisplayCacheReadinessTracker.computeContactsReadiness(
                rawCount = roomCount,
                displayRows = count,
                rawSyncDone = rawSyncDone,
                displayBuilding = false,
                hasContactsPermission = hasPermission,
            )
            val effectiveReadiness = ContactsDisplayReadiness.effectiveAfterRebuild(
                computed = readiness,
                displayRows = count,
                rawCount = roomCount,
                hasPermission = hasPermission,
                contactsSyncDone = contactsSyncDone,
                coldStart = coldStart,
                filterDrivenEmpty = request.reason.reflectsUserVisibilityAction,
            )
            val canClaimAuthoritativeEmpty =
                effectiveReadiness == DisplayCacheReadiness.READY_EMPTY
            com.goodwy.commons.providercache.debug.CacheReadinessAssertions.assertReadyEmptyRequiresRawSync(
                domain = "CONTACTS",
                readiness = effectiveReadiness,
                rawSyncComplete = canClaimAuthoritativeEmpty ||
                    contactsSyncDone ||
                    !coldStart ||
                    effectiveReadiness == DisplayCacheReadiness.READY_WITH_DATA,
            )
            DisplayCacheReadinessTracker.setContacts(effectiveReadiness)
            // Hint after readiness so filter-empty display is not mistaken for a cold Room fallback.
            refreshSearchHintContactCount()
            // A user visibility action commits regardless of readiness: it cannot loop the load
            // path the way a bootstrap rebuild can, and no settle rebuild is guaranteed to carry
            // it. Without this, protecting a contact while the cache is still DISPLAY_BUILDING
            // writes the new rows but leaves the version untouched, and the list never repaints.
            val readinessSettled = effectiveReadiness == DisplayCacheReadiness.READY_EMPTY ||
                effectiveReadiness == DisplayCacheReadiness.READY_WITH_DATA
            val meaningfulCommit = readinessSettled || request.reason.reflectsUserVisibilityAction
            if (meaningfulCommit) {
                val cacheVersion = commitContactsDisplayVersion(
                    reason = request.reason,
                    rowCount = count,
                    needsFullReload = needsFull,
                    meaningfulChange = true,
                )
                notifyDisplayCacheBecameReadyIfPopulated(cacheVersion, request.reason)
                if (readinessSettled) {
                    StartupDomainOwner.markCommitted(CacheDomain.CONTACTS)
                } else {
                    // Version bumped so the UI repaints, but the startup owner stays active for the
                    // same reason the skip branch below keeps it: releasing it mid-build lets the
                    // load path loop COLD_EMPTY rebuilds. Sync completion still drives the settle.
                    android.util.Log.d(
                        "ContactsRepository",
                        "contactsCacheUserActionCommit readiness=$effectiveReadiness " +
                            "version=$cacheVersion rows=$count reason=${request.reason}",
                    )
                }
            } else {
                // Keep startup owner active so load-path does not loop COLD_EMPTY rebuilds;
                // sync completion forces a settle rebuild via ensureEmptyDisplayRebuildAfterSync.
                android.util.Log.d(
                    "ContactsRepository",
                    "contactsCacheSkipVersionCommit readiness=$effectiveReadiness rows=$count raw=$roomCount reason=${request.reason}",
                )
            }
            StartupOrchestrator.onContactsDisplayCacheReady(count)
            if (count > 0 &&
                (
                    request.reason == DisplayCacheRebuildReason.COLD_EMPTY_CACHE ||
                        request.reason == DisplayCacheRebuildReason.STARTUP_INVALID_CACHE ||
                        request.reason == DisplayCacheRebuildReason.CONTACT_SYNC_COMPLETED
                    ) &&
                !StartupOrchestrator.shouldDeferRecentsResyncAfterContacts()
            ) {
                onDisplayCacheReadyForRecentsResync?.invoke()
            }
            if (fallbackActive) {
                LegacyCacheGate.logAuthority("CONTACTS")
            }
        }
        maybeScheduleIdleDuplicateMerge(request)
        if (activeSearchQuery.value.isNotBlank()) {
            scope.launch {
                notifyDisplayCacheUpdatedIfAvatarChanged(force = true)
            }
            return
        }
        val changedCount = request.changedContactIds.size
        val deletedCount = request.deletedContactIds.size
        val totalChanges = changedCount + deletedCount
        if (totalChanges == 0) {
            if (request.forceFull || request.reason.requiresFullContactRebuild) {
                scope.launch {
                    notifyDisplayCacheUpdatedIfAvatarChanged()
                }
            }
            return
        }

        scope.launch {
            if (shouldBlockContactsProviderFallback()) return@launch
            val totalRows = displayCacheDao.getCount()
            if (deletedCount > 0 && totalRows == 0) {
                notifyDisplayCacheUpdatedIfAvatarChanged(force = true)
                return@launch
            }
            val partial = canUsePartialDisplayUpdate(changedCount, deletedCount, totalRows, request) &&
                !adapterNeedsFullDisplayLoad()
            if (partial) {
                if (deletedCount > 0) {
                    withContext(Dispatchers.Main) {
                        onContactsDeletedFromDisplay?.invoke(request.deletedContactIds)
                    }
                }
                if (changedCount > 0) {
                    val contacts = loadDisplayContactsByIds(request.changedContactIds.toList())
                    if (contacts.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            onContactsPartiallyUpdated?.invoke(contacts)
                        }
                    }
                }
                ProviderCacheDebugLogger.logContactDisplayUpdate(
                    kind = "partial",
                    changedCount = changedCount,
                    deletedCount = deletedCount,
                    reboundCount = changedCount,
                )
                return@launch
            }
            if (changedCount > 0 || deletedCount > 0) {
                val upgradeReason = if (adapterNeedsFullDisplayLoad()) "adapter_empty" else "partial_threshold"
                ProviderCacheDebugLogger.logContactDisplayUpdateUpgraded(
                    reason = upgradeReason,
                    changedCount = changedCount,
                    deletedCount = deletedCount,
                )
            }
            notifyDisplayCacheUpdatedIfAvatarChanged(force = true)
        }
    }

    private fun canUsePartialDisplayUpdate(
        changedCount: Int,
        deletedCount: Int,
        totalRows: Int,
        request: ContactDisplayRebuildRequest,
    ): Boolean {
        val totalChanges = changedCount + deletedCount
        if (totalChanges == 0) return false
        if (request.forceFull || request.mode == ContactDisplayRebuildMode.ACCURATE) return false
        if (request.reason.requiresFullContactRebuild) return false
        if (totalChanges <= PARTIAL_DISPLAY_UPDATE_MAX_IDS) return true
        if (totalRows > 0 && totalChanges <= (totalRows / PARTIAL_UPDATE_RATIO_DIVISOR).coerceAtLeast(1)) {
            return true
        }
        return false
    }

    suspend fun loadDisplayContactsByIds(contactIds: List<Int>): List<Contact> = withContext(Dispatchers.IO) {
        if (contactIds.isEmpty() || displayCacheDao.getCount() == 0) return@withContext emptyList()
        contactIds.chunked(200).flatMap { chunk ->
            ContactDisplayLoadHelper.mapListRows(displayCacheDao.getListRowsByContactIds(chunk)).first
        }
    }

    /**
     * Indexed SQL search on [contact_display_cache] for toolbar / recents-tab search.
     * Returns null when the display cache is not built yet (caller should fall back to in-memory search).
     */
    suspend fun searchToolbarContacts(query: String, limit: Int): List<Contact>? = withContext(Dispatchers.IO) {
        if (query.isBlank() || limit <= 0) return@withContext emptyList()
        if (displayCacheDao.getCount() == 0) return@withContext null
        android.util.Log.d("searchEngine", "tab=RECENTS source=ROOM mode=TOOLBAR query=$query")
        searchToolbarAlignedContacts(query, limit)
    }

    /**
     * Indexed display-cache search for dialpad contact matching using [DialpadContactSearch] semantics.
     * Returns null when the display cache is cold (caller should fall back to in-memory dialpad search).
     */
    suspend fun searchDialpadContacts(
        query: String,
        limit: Int,
        params: DialpadContactSearch.MatchParams,
    ): List<Contact>? = withContext(Dispatchers.IO) {
        if (query.isBlank() || limit <= 0) return@withContext emptyList()
        if (displayCacheDao.getCount() == 0) return@withContext null
        android.util.Log.d("searchEngine", "tab=DIALPAD source=ROOM mode=DIALPAD_T9 query=$query")
        searchDialpadAlignedContacts(query, limit, params)
    }

    /**
     * Paged toolbar search on [contact_display_cache]. Returns null when cache is cold.
     * [offset] is the number of verified matches to skip (not raw SQL offset for mixed/dialpad modes).
     */
    suspend fun searchToolbarContactsPage(
        query: String,
        limit: Int,
        offset: Int,
    ): List<Contact>? = withContext(Dispatchers.IO) {
        if (query.isBlank() || limit <= 0) return@withContext emptyList()
        if (displayCacheDao.getCount() == 0) return@withContext null
        val mode = ToolbarContactSearch.classifyMode(query)
        ToolbarContactSearch.logQuery(query, mode)
        // Name OR phone SQL is authoritative; no post-filter verify (that path used AND semantics).
        val rows = queryToolbarDisplayListRows(query = query, limit = limit, offset = offset)
        val contacts = applySecureSqlFilter(ContactDisplayLoadHelper.mapListRows(rows).first)
        val digitQuery = ToolbarContactSearch.digitQueryText(query)
        if (digitQuery.isEmpty()) return@withContext contacts
        // Attach the matching phone onto each row so the Contacts list can show it under the name.
        enrichDialpadPhoneMatches(contacts, query, digitQuery, contacts.size)
    }

    /**
     * Paged dialpad search on [contact_display_cache]. Returns null when cache is cold.
     */
    suspend fun searchDialpadContactsPage(
        query: String,
        limit: Int,
        offset: Int,
        params: DialpadContactSearch.MatchParams,
    ): List<Contact>? = withContext(Dispatchers.IO) {
        if (query.isBlank() || limit <= 0) return@withContext emptyList()
        if (displayCacheDao.getCount() == 0) return@withContext null
        val sessionKey = dialpadSearchSessionKey(query, params)
        val session = dialpadSearchSessions.getOrPut(sessionKey) {
            DialpadSearchPageSession(query, params)
        }
        if (session.query != query) {
            dialpadSearchSessions.remove(sessionKey)
            return@withContext searchDialpadContactsPage(query, limit, offset, params)
        }
        ensureDialpadVerified(session, offset + limit)
        val ids = session.verifiedContactIds.drop(offset).take(limit)
        mapSearchContactsByIds(ids, query, params)
    }

    /** Clears progressive search page caches when the active query changes. */
    fun invalidateSearchPageCaches() {
        toolbarVerifiedSessions.clear()
        dialpadSearchSessions.clear()
    }

    private val toolbarVerifiedSessions = ConcurrentHashMap<String, ToolbarVerifiedPageSession>()
    private val dialpadSearchSessions = ConcurrentHashMap<String, DialpadSearchPageSession>()

    private data class ToolbarVerifiedPageSession(
        val query: String,
        val mode: ToolbarContactSearch.Mode,
        val verifiedContactIds: MutableList<Int> = mutableListOf(),
        val seenContactIds: MutableSet<Int> = mutableSetOf(),
        var sqlOffset: Int = 0,
        var exhausted: Boolean = false,
    )

    private data class DialpadSearchPageSession(
        val query: String,
        val params: DialpadContactSearch.MatchParams,
        val verifiedContactIds: MutableList<Int> = mutableListOf(),
        val seenContactIds: MutableSet<Int> = mutableSetOf(),
        var sqlOffset: Int = 0,
        var exhausted: Boolean = false,
    )

    private fun dialpadSearchSessionKey(query: String, params: DialpadContactSearch.MatchParams): String =
        "dialpad:$query:${params.language}:${params.inputMethod}:${params.queryLower}"

    private suspend fun searchToolbarContactsVerifiedPage(
        query: String,
        mode: ToolbarContactSearch.Mode,
        limit: Int,
        offset: Int,
    ): List<Contact> {
        val sessionKey = "toolbar:$query:$mode"
        val session = toolbarVerifiedSessions.getOrPut(sessionKey) {
            ToolbarVerifiedPageSession(query, mode)
        }
        ensureToolbarVerified(session, query, mode, offset + limit)
        val ids = session.verifiedContactIds.drop(offset).take(limit)
        return mapSearchContactsByIds(ids, query, null)
    }

    private suspend fun ensureToolbarVerified(
        session: ToolbarVerifiedPageSession,
        query: String,
        mode: ToolbarContactSearch.Mode,
        targetCount: Int,
    ) {
        if (session.exhausted || session.verifiedContactIds.size >= targetCount) return
        val letters = ToolbarContactSearch.letterQueryText(query)
        val digits = ToolbarContactSearch.digitQueryText(query)
        while (session.verifiedContactIds.size < targetCount && !session.exhausted) {
            val batch = displayCacheDao.searchToolbarMixedAndPage(
                lettersContains = ToolbarContactSearch.likeContains(letters),
                digitsContains = ToolbarContactSearch.likeContains(digits),
                limit = SEARCH_SQL_SCAN_BATCH,
                offset = session.sqlOffset,
            )
            session.sqlOffset += batch.size
            if (batch.isEmpty()) {
                session.exhausted = true
                break
            }
            var contacts = ContactDisplayLoadHelper.mapListRows(batch).first
            contacts = verifyAndEnrichToolbarPhoneMatches(contacts, query, mode, Int.MAX_VALUE)
            for (contact in contacts) {
                if (session.seenContactIds.add(contact.contactId)) {
                    session.verifiedContactIds.add(contact.contactId)
                }
            }
            if (batch.size < SEARCH_SQL_SCAN_BATCH) {
                session.exhausted = true
            }
        }
    }

    private suspend fun ensureDialpadVerified(
        session: DialpadSearchPageSession,
        targetCount: Int,
    ) {
        if (session.exhausted || session.verifiedContactIds.size >= targetCount) return
        val query = session.query
        val params = session.params
        val digitQuery = DialpadContactSearch.digitQueryText(query)
        val enableNameSearch = DialpadContactSearch.enableNameSearch(query)
        val letterPart = query.filter { it.isLetter() }
        val t9Digits = T9Mapper.toT9Digits(query)
        val phonePattern = when {
            digitQuery.length >= 2 -> ToolbarContactSearch.likePrefix(digitQuery)
            digitQuery.isNotEmpty() -> ToolbarContactSearch.likeContains(digitQuery)
            else -> ""
        }
        val namePattern = if (letterPart.isNotEmpty() && enableNameSearch) {
            ToolbarContactSearch.likeContains(letterPart.lowercase())
        } else {
            ""
        }
        val t9Pattern = when {
            !enableNameSearch -> ""
            t9Digits.isNotEmpty() -> ToolbarContactSearch.likePrefix(t9Digits)
            digitQuery.length >= 2 -> ToolbarContactSearch.likePrefix(digitQuery)
            else -> ""
        }
        while (session.verifiedContactIds.size < targetCount && !session.exhausted) {
            val batch = displayCacheDao.searchDialpadCandidatesPage(
                phonePattern = phonePattern,
                namePattern = namePattern,
                t9Pattern = t9Pattern,
                limit = SEARCH_SQL_SCAN_BATCH,
                offset = session.sqlOffset,
            )
            session.sqlOffset += batch.size
            if (batch.isEmpty()) {
                session.exhausted = true
                break
            }
            val contacts = ContactDisplayLoadHelper.mapListRows(batch).first
            // Display-cache rows have no phoneNumbers; verify digits via phone_index.
            val phonesByContactId = contacts
                .map { it.contactId }
                .distinct()
                .chunked(200)
                .flatMap { phoneIndexDao.getByContactIds(it) }
                .groupBy { it.contactId }
            for (contact in contacts) {
                if (!session.seenContactIds.add(contact.contactId)) continue
                val phoneFields = buildToolbarPhoneSearchFields(phonesByContactId[contact.contactId].orEmpty())
                if (!matchesDialpadDisplayContact(
                        contact = contact,
                        query = query,
                        digitQuery = digitQuery,
                        enableNameSearch = enableNameSearch,
                        params = params,
                        phoneDigits = phoneFields.phoneDigits,
                        displayNumberDigits = phoneFields.displayNumberDigits,
                    )
                ) {
                    continue
                }
                session.verifiedContactIds.add(contact.contactId)
            }
            if (batch.size < SEARCH_SQL_SCAN_BATCH) {
                session.exhausted = true
            }
        }
    }

    private suspend fun mapSearchContactsByIds(
        contactIds: List<Int>,
        query: String,
        dialpadParams: DialpadContactSearch.MatchParams?,
    ): List<Contact> {
        if (contactIds.isEmpty()) return emptyList()
        val rows = contactIds.chunked(200).flatMap { chunk ->
            displayCacheDao.getListRowsByContactIds(chunk)
        }
        val byId = rows.associateBy { it.contactId }
        var contacts = contactIds.mapNotNull { byId[it] }
            .let { ContactDisplayLoadHelper.mapListRows(it).first }
        if (dialpadParams != null) {
            val digitQuery = DialpadContactSearch.digitQueryText(query)
            if (digitQuery.isNotEmpty()) {
                contacts = enrichDialpadPhoneMatches(contacts, query, digitQuery, contacts.size)
            }
        } else {
            val mode = ToolbarContactSearch.classifyMode(query)
            if (mode == ToolbarContactSearch.Mode.MIXED || ToolbarContactSearch.digitQueryText(query).isNotEmpty()) {
                contacts = verifyAndEnrichToolbarPhoneMatches(contacts, query, mode, contacts.size)
            }
        }
        return applySecureSqlFilter(contacts)
    }

    /**
     * Shared display-cache search for the contacts tab and toolbar.
     * Matches display name contains OR phone digits contains (no T9).
     */
    private suspend fun searchToolbarAlignedContacts(query: String, limit: Int): List<Contact> {
        if (query.isBlank()) return emptyList()
        val mode = ToolbarContactSearch.classifyMode(query)
        ToolbarContactSearch.logQuery(query, mode)
        val rows = queryToolbarDisplayListRows(query = query, limit = limit)
        val contacts = applySecureSqlFilter(ContactDisplayLoadHelper.mapListRows(rows).first)
        val digitQuery = ToolbarContactSearch.digitQueryText(query)
        val enriched = if (digitQuery.isEmpty()) {
            contacts
        } else {
            enrichDialpadPhoneMatches(contacts, query, digitQuery, contacts.size)
        }
        return if (limit == Int.MAX_VALUE) enriched else enriched.take(limit)
    }

    private suspend fun searchDialpadAlignedContacts(
        query: String,
        limit: Int,
        params: DialpadContactSearch.MatchParams,
    ): List<Contact> {
        val fetchLimit = (limit * 4).coerceAtMost(200)
        val digitQuery = DialpadContactSearch.digitQueryText(query)
        val enableNameSearch = DialpadContactSearch.enableNameSearch(query)
        val seenContactIds = LinkedHashSet<Int>()
        val candidateRows = ArrayList<com.goodwy.commons.providercache.display.ContactDisplayListRow>()

        fun addRows(rows: List<com.goodwy.commons.providercache.display.ContactDisplayListRow>) {
            for (row in rows) {
                if (seenContactIds.add(row.contactId)) {
                    candidateRows.add(row)
                }
            }
        }

        if (digitQuery.isNotEmpty()) {
            val phoneRows = if (digitQuery.length >= 2) {
                displayCacheDao.searchToolbarNumericPrefix(
                    digitsPrefix = ToolbarContactSearch.likePrefix(digitQuery),
                    limit = fetchLimit,
                )
            } else {
                displayCacheDao.searchToolbarNumericViaPhoneIndex(
                    digitsContains = ToolbarContactSearch.likeContains(digitQuery),
                    limit = fetchLimit,
                )
            }
            addRows(phoneRows)
        }

        if (enableNameSearch) {
            val letterPart = query.filter { it.isLetter() }
            val t9Digits = T9Mapper.toT9Digits(query)
            if (letterPart.isNotEmpty()) {
                addRows(
                    displayCacheDao.searchDialpadByName(
                        searchPrefix = ToolbarContactSearch.likeContains(letterPart.lowercase()),
                        limit = fetchLimit,
                    ),
                )
            }
            val t9Prefix = when {
                t9Digits.isNotEmpty() -> ToolbarContactSearch.likePrefix(t9Digits)
                digitQuery.length >= 2 -> ToolbarContactSearch.likePrefix(digitQuery)
                else -> null
            }
            if (t9Prefix != null) {
                addRows(displayCacheDao.searchDialpadT9Prefix(t9Prefix = t9Prefix, limit = fetchLimit))
            }
        }

        var contacts = ContactDisplayLoadHelper.mapListRows(candidateRows).first
        val phonesByContactId = contacts
            .map { it.contactId }
            .distinct()
            .chunked(200)
            .flatMap { phoneIndexDao.getByContactIds(it) }
            .groupBy { it.contactId }
        contacts = contacts.filter { contact ->
            val phoneFields = buildToolbarPhoneSearchFields(phonesByContactId[contact.contactId].orEmpty())
            matchesDialpadDisplayContact(
                contact = contact,
                query = query,
                digitQuery = digitQuery,
                enableNameSearch = enableNameSearch,
                params = params,
                phoneDigits = phoneFields.phoneDigits,
                displayNumberDigits = phoneFields.displayNumberDigits,
            )
        }
        if (digitQuery.isNotEmpty()) {
            contacts = enrichDialpadPhoneMatches(contacts, query, digitQuery, limit, phonesByContactId)
        }
        val result = applySecureSqlFilter(contacts).take(limit)
        android.util.Log.d(
            DialpadContactSearch.LOG_TAG,
            "dialpadSearchConsistency cache=warm resultIds=${result.joinToString(",") { it.id.toString() }}",
        )
        return result
    }

    /**
     * Applies [secureSqlParamsProvider] include/exclude rules to in-memory search hits.
     * Dialpad/toolbar DAO queries do not push secure predicates into SQL; this is the filter.
     */
    private fun applySecureSqlFilter(contacts: List<Contact>): List<Contact> {
        if (contacts.isEmpty()) return contacts
        val secure = secureSqlParamsProvider?.invoke() ?: return contacts
        return secure.filterByRawId(contacts) { it.id }
    }

    /**
     * Dialpad match against display-cache contacts.
     * Prefer [phoneDigits]/[displayNumberDigits] from [contact_phone_index] — list rows do not
     * populate [Contact.phoneNumbers], so [Contact.doesContainPhoneNumber] alone always fails.
     */
    private fun matchesDialpadDisplayContact(
        contact: Contact,
        query: String,
        digitQuery: String,
        enableNameSearch: Boolean,
        params: DialpadContactSearch.MatchParams,
        phoneDigits: String = "",
        displayNumberDigits: String = "",
    ): Boolean {
        if (digitQuery.isNotEmpty()) {
            if (phoneDigits.contains(digitQuery) || displayNumberDigits.contains(digitQuery)) {
                return true
            }
            val displayDigits = contact.displayBind?.formattedPhone.orEmpty().filter { it.isDigit() || it == '+' }
            if (displayDigits.contains(digitQuery)) {
                return true
            }
            if (contact.doesContainPhoneNumber(digitQuery, convertLetters = false, search = true)) {
                return true
            }
        }
        if (contact.doesContainPhoneNumber(query, convertLetters = true, search = true)) {
            return true
        }
        if (!enableNameSearch) return false
        return DialpadContactSearch.matchesName(contact.getNameToDisplay(), params)
    }

    private suspend fun enrichDialpadPhoneMatches(
        contacts: List<Contact>,
        query: String,
        digitQuery: String,
        limit: Int,
        phonesByContactId: Map<Int, List<com.goodwy.commons.providercache.entities.ContactPhoneIndexEntity>>? = null,
    ): List<Contact> {
        if (contacts.isEmpty() || digitQuery.isEmpty()) return contacts
        val indexed = phonesByContactId ?: contacts
            .map { it.contactId }
            .distinct()
            .chunked(200)
            .flatMap { phoneIndexDao.getByContactIds(it) }
            .groupBy { it.contactId }
        val formatNumbers = context.baseConfig.formatPhoneNumbers
        return contacts.map { contact ->
            val indexEntries = indexed[contact.contactId].orEmpty()
            if (indexEntries.isEmpty()) return@map contact
            enrichToolbarContactPhones(contact, indexEntries, query, formatNumbers)
        }.take(limit)
    }

    private suspend fun verifyAndEnrichToolbarPhoneMatches(
        contacts: List<Contact>,
        query: String,
        mode: ToolbarContactSearch.Mode,
        limit: Int,
    ): List<Contact> {
        if (contacts.isEmpty()) return contacts
        val phonesByContactId = contacts
            .map { it.contactId }
            .distinct()
            .chunked(200)
            .flatMap { phoneIndexDao.getByContactIds(it) }
            .groupBy { it.contactId }
        val formatNumbers = context.baseConfig.formatPhoneNumbers
        return contacts.mapNotNull { contact ->
            val indexEntries = phonesByContactId[contact.contactId].orEmpty()
            val phoneFields = if (indexEntries.isNotEmpty()) {
                buildToolbarPhoneSearchFields(indexEntries)
            } else {
                val displayDigits = contact.displayBind?.formattedPhone.orEmpty().filter { it.isDigit() }
                ToolbarPhoneSearchFields(phoneDigits = displayDigits, displayNumberDigits = displayDigits)
            }
            val nameOk = when (mode) {
                ToolbarContactSearch.Mode.NUMERIC_DIGITS_ONLY -> true
                ToolbarContactSearch.Mode.MIXED -> {
                    val letters = ToolbarContactSearch.letterQueryText(query)
                    letters.isEmpty() || contact.getNameToDisplay().contains(letters, ignoreCase = true)
                }
                ToolbarContactSearch.Mode.LETTERS -> false
            }
            if (!nameOk) return@mapNotNull null
            if (!ToolbarContactSearch.matchesPhoneFields(
                    phoneFields.phoneDigits,
                    normalizedNumbers = "",
                    phoneFields.displayNumberDigits,
                    query,
                    mode,
                )
            ) {
                return@mapNotNull null
            }
            enrichToolbarContactPhones(contact, indexEntries, query, formatNumbers)
        }.take(limit)
    }

    private data class ToolbarPhoneSearchFields(
        val phoneDigits: String,
        val displayNumberDigits: String,
    )

    private fun buildToolbarPhoneSearchFields(
        entries: List<com.goodwy.commons.providercache.entities.ContactPhoneIndexEntity>,
    ): ToolbarPhoneSearchFields {
        val phoneDigits = StringBuilder()
        val displayNumberDigits = StringBuilder()
        for (entry in entries) {
            val digits = entry.phoneDigits.ifEmpty { entry.digits }
            phoneDigits.append(digits)
            displayNumberDigits.append(entry.digits)
        }
        return ToolbarPhoneSearchFields(
            phoneDigits = phoneDigits.toString(),
            displayNumberDigits = displayNumberDigits.toString(),
        )
    }

    private fun enrichToolbarContactPhones(
        contact: Contact,
        indexEntries: List<com.goodwy.commons.providercache.entities.ContactPhoneIndexEntity>,
        query: String,
        formatNumbers: Boolean,
    ): Contact {
        if (indexEntries.isEmpty()) return contact
        val digitQuery = ToolbarContactSearch.digitQueryText(query)
        contact.phoneNumbers = ArrayList(
            indexEntries.map { entry ->
                PhoneNumber(
                    value = entry.normalizedNumber,
                    type = 0,
                    label = "",
                    normalizedNumber = entry.normalizedNumber,
                    isPrimary = false,
                )
            },
        )
        contact.phoneNumbers.normalizeSingleDefaultPhoneFlag()
        val matchingEntry = indexEntries.firstOrNull { entry ->
            val digits = entry.digits.ifEmpty { entry.phoneDigits }
            digitQuery.isNotEmpty() && digits.contains(digitQuery)
        } ?: return contact
        val bind = contact.displayBind ?: return contact
        val rawPhone = matchingEntry.normalizedNumber
        val formattedPhone = if (formatNumbers && rawPhone.isNotEmpty()) {
            rawPhone.formatPhoneNumber()
        } else {
            rawPhone
        }
        // Prefer the matched number over the display-cache primary/first phone.
        contact.displayBind = bind.copy(
            formattedPhone = formattedPhone,
            showPhoneNumber = formattedPhone.isNotEmpty(),
        )
        return contact
    }

    private suspend fun queryToolbarDisplayListRows(
        query: String,
        limit: Int,
        offset: Int = 0,
    ): List<com.goodwy.commons.providercache.display.ContactDisplayListRow> {
        // Contacts tab / toolbar: name contains full query OR (when appropriate) phone digits.
        // Letter+digit queries (e.g. "123a") must not OR phone LIKE '%123%' — that floods the list
        // with unrelated number hits and hides the name match.
        val mode = ToolbarContactSearch.classifyMode(query)
        val namePattern = ToolbarContactSearch.likeContains(query.lowercase())
        val digitsPattern = if (ToolbarContactSearch.shouldMatchPhoneDigits(query, mode)) {
            ToolbarContactSearch.likeContains(ToolbarContactSearch.digitQueryText(query))
        } else {
            ""
        }
        return displayCacheDao.searchToolbarNameOrPhonePage(
            namePattern = namePattern,
            digitsPattern = digitsPattern,
            limit = limit,
            offset = offset,
        )
    }

    private fun wireSyncCallbacks() {
        if (syncCallbacksWired) return
        syncCallbacksWired = true
        syncManager.onSyncChanges = { changes ->
            scheduleDisplayCacheFromSyncChanges(changes)
        }
        syncManager.onSyncCompleted = {
            scope.launch {
                refreshCacheState()
                refreshSearchHintContactCount()
                val displayRows = displayCacheDao.getCount()
                if (displayRows == 0) {
                    invalidatePagingDebounced(PagingInvalidationReason.SYNC_COMPLETE)
                    // Repaint is deferred until contact_display_cache rebuild completes so we do not
                    // paint from the cold-start provider fallback and then again from display cache.
                    // Do not rebuild from a partial Room snapshot while progressive sync is still
                    // streaming provider pages — that leaves a truncated display cache on cold start.
                    if (ContactsColdStartPaintPolicy.allowDisplayRebuild(
                            syncManager.isProgressiveSyncInProgress(),
                        )
                    ) {
                        // Settle empty/filter-empty after sync; forces rebuild when startup owner
                        // is still active from a deferred early empty rebuild.
                        ensureEmptyDisplayRebuildAfterSync()
                    }
                } else {
                    ProviderCacheDebugLogger.log(
                        "invalidate skipped reason=SYNC_COMPLETE (display cache warm rows=$displayRows)",
                    )
                }
            }
        }
        syncManager.onContactsPartiallyUpdated = { contactIds ->
            scope.launch {
                if (shouldBlockContactsProviderFallback()) return@launch
                if (displayCacheDao.getCount() == 0) return@launch
                val fromDisplay = loadDisplayContactsByIds(contactIds)
                if (fromDisplay.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (shouldBlockContactsProviderFallback()) return@withContext
                        onContactsPartiallyUpdated?.invoke(fromDisplay)
                    }
                    return@launch
                }
                val contacts = contactDao.getSummariesByIds(contactIds).map { it.toDomain().toListContact() }
                if (contacts.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (shouldBlockContactsProviderFallback()) return@withContext
                        onContactsPartiallyUpdated?.invoke(contacts)
                    }
                }
            }
        }
    }

    private fun shouldSkipDisplayRebuildForValidStartupCache(
        changes: com.goodwy.commons.providercache.sync.ContactSyncChangeSet,
    ): Boolean {
        if (!isStartupCacheValid()) return false
        if (changes.wasFullRebuild || changes.deletedContactIds.isNotEmpty()) return false
        if (!changes.hasProvenProviderDelta) return true
        if (changes.updatedContactIds.isEmpty()) return true
        return false
    }

    private fun scheduleInitialIncrementalSyncOnce() {
        wireSyncCallbacks()
        if (initialIncrementalSyncScheduled) return
        initialIncrementalSyncScheduled = true
        syncManager.scheduleIncrementalSync()
    }

    private fun scheduleDeferredStartupSyncWhenValidCache() {
        wireSyncCallbacks()
        if (initialIncrementalSyncScheduled) return
        initialIncrementalSyncScheduled = true
        ProviderCacheDebugLogger.logSkipStartupSync("valid_cache")
        // Do not schedule deferred full-ID incremental sync. Startup validation already proved
        // provider/Room/display parity; the 8s reconcile (loadAllSummaryIds × N) caused GC and
        // telephony ANRs. Real changes arrive via ContentObserver → runIncrementalSyncAwaitable.
        deferredStartupSyncJob?.cancel()
        deferredStartupSyncJob = null
    }

    /** Cancels the delayed startup sync when the provider [ContentObserver] already scheduled one. */
    fun cancelDeferredStartupSyncIfScheduled() {
        if (deferredStartupSyncJob?.isActive == true) {
            deferredStartupSyncJob?.cancel()
            deferredStartupSyncJob = null
        }
    }

    private suspend fun seedDisplayCacheStateFromRoom() {
        val displayRows = displayCacheDao.getCount()
        // Assign before the early return so an empty cache still counts as "known" and
        // main-thread callers get a definitive false instead of null.
        cachedDisplayCacheRowCount = displayRows
        if (displayRows <= 0) return
        if (contactDisplayCacheVersion == 0L) {
            contactDisplayCacheVersion = 1L
            lastAvatarBindSignature = displayCacheDao.getAvatarBindSignature()
        }
    }

    private fun maybeScheduleIdleDuplicateMerge(request: ContactDisplayRebuildRequest) {
        if (request.mode != ContactDisplayRebuildMode.FAST) return
        if (!context.baseConfig.mergeDuplicateContacts) return
        if (request.reason == DisplayCacheRebuildReason.DUPLICATE_MERGE_IDLE) return
        idleDuplicateMergeJob?.cancel()
        idleDuplicateMergeJob = scope.launch(Dispatchers.IO) {
            delay(IDLE_DUPLICATE_MERGE_DELAY_MS)
            scheduleContactDisplayCacheRebuild(
                ContactDisplayRebuildRequest(
                    reason = DisplayCacheRebuildReason.DUPLICATE_MERGE_IDLE,
                    forceFull = true,
                    mode = ContactDisplayRebuildMode.ACCURATE,
                ),
            )
        }
    }

    fun scheduleContactDisplayCacheRebuildImmediate(
        request: ContactDisplayRebuildRequest,
    ) {
        if (contactsBulkDeleteInProgress || contactsJustBulkDeleted) {
            ProviderCacheDebugLogger.log("displayCacheRebuild skipped immediate (bulk delete hold)")
            return
        }
        inFlightDisplayRebuildReason = request.reason
        displayCacheCoordinator?.beginContactsMutation()
        contactDisplayCacheScheduler.scheduleImmediate(request)
    }

    /**
     * After [ContactProtectionHelper.unlockAllWithPin], pull unlocked protected contacts into
     * Room. Incremental sync never sees them while locked, so Private space / Secure box
     * display-cache rebuilds would otherwise filter an empty set and leave the normal list.
     */
    suspend fun cacheUnlockedSessionContacts() = withContext(Dispatchers.IO) {
        if (shouldBlockContactsProviderFallback()) return@withContext
        ContactProtectionHelper.ensureUnlockedForThread(context)
        val rawIds = ContactProtectionHelper.getUnlockedRawContactIds() ?: return@withContext
        if (rawIds.isEmpty()) return@withContext
        val helper = ContactsHelper(context)
        val summaries = ArrayList<ContactSummaryEntity>(rawIds.size)
        val contactIdsForPhoneIndex = ArrayList<Int>(rawIds.size)
        for (rawIdLong in rawIds) {
            val rawId = rawIdLong.toInt()
            if (rawId <= 0) continue
            val contact = helper.getContactWithId(rawId) ?: continue
            val contactId = contact.contactId.takeIf { it > 0 } ?: rawId
            val firstPhone = contact.phoneNumbers.firstOrNull()?.normalizedNumber
                ?.ifEmpty { null }
                ?: contact.phoneNumbers.firstOrNull()?.value.orEmpty()
            val firstEmail = contact.emails.firstOrNull()?.value.orEmpty()
            summaries.add(
                ContactSummaryEntity(
                    contactId = contactId,
                    lookupKey = "",
                    displayName = contact.getNameToDisplay(),
                    photoThumbnailUri = contact.thumbnailUri.ifEmpty { contact.photoUri },
                    hasPhoneNumber = contact.phoneNumbers.isNotEmpty(),
                    lastUpdatedTimestamp = System.currentTimeMillis(),
                    primaryRawId = rawId,
                    accountName = contact.source,
                    accountType = "",
                    firstPhoneNormalized = firstPhone,
                    firstEmail = firstEmail,
                ),
            )
            contactIdsForPhoneIndex.add(contactId)
        }
        if (summaries.isEmpty()) {
            ProviderCacheDebugLogger.log(
                "cacheUnlockedSessionContacts inserted=0 requested=${rawIds.size}",
            )
            return@withContext
        }
        contactDao.insertSummaries(summaries)
        phoneIndexSync.rebuildForContactIds(contactIdsForPhoneIndex.distinct())
        ProviderCacheDebugLogger.log(
            "cacheUnlockedSessionContacts inserted=${summaries.size} requested=${rawIds.size}",
        )
    }

    /**
     * Blocks until Room has unlocked contacts and [contact_display_cache] is fully swapped for
     * the active secure-mode filter. Call after setting the UI cipher / session PIN.
     */
    suspend fun rebuildContactDisplayCacheForSecureMode(): ContactDisplayLoadResult =
        withContext(Dispatchers.IO) {
            wireSyncCallbacks()
            if (shouldBlockContactsProviderFallback()) {
                ProviderCacheDebugLogger.log("secureMode display rebuild skipped (bulk delete hold)")
                return@withContext loadDisplayContacts(reason = ContactDisplayLoadReason.FORCED)
            }
            cacheUnlockedSessionContacts()
            val mutationId = displayCacheCoordinator?.beginContactsMutation()
            contactDisplayCacheBuilding = true
            DisplayCacheReadinessTracker.setContacts(DisplayCacheReadiness.DISPLAY_BUILDING)
            val request = ContactDisplayRebuildRequest(
                reason = DisplayCacheRebuildReason.SECURE_MODE_CHANGED,
                forceFull = true,
                mode = ContactDisplayRebuildMode.ACCURATE,
            )
            displayCacheBuilder.rebuild(
                reason = request.reason,
                forceFull = true,
                mode = request.mode,
            )
            lastDisplayRebuildReason = request.reason
            val count = displayCacheDao.getCount()
            cachedDisplayCacheRowCount = count
            contactDisplayCacheBuilding = false
            refreshSearchHintContactCount()
            val cacheVersion = commitContactsDisplayVersion(
                reason = request.reason,
                rowCount = count,
                needsFullReload = true,
                mutationId = mutationId,
                meaningfulChange = true,
            )
            notifyDisplayCacheBecameReadyIfPopulated(cacheVersion, request.reason)
            withContext(Dispatchers.Main) {
                onContactDisplayCacheUpdated?.invoke(ContactDisplayLoadReason.FORCED)
            }
            invalidatePaging(PagingInvalidationReason.SECURE_FILTER)
            loadDisplayContacts(reason = ContactDisplayLoadReason.FORCED)
        }

    suspend fun needsDisplayCacheCatchUpAfterSync(): Boolean = withContext(Dispatchers.IO) {
        val summaries = contactDao.getSummaryCount()
        val display = displayCacheDao.getCount()
        if (summaries > 0 && display < summaries) return@withContext true
        val providerCount = providerDataSource.loadValidationSnapshot().contactsCount
        providerCount > summaries
    }

    /**
     * Synchronously (fully awaited) ensures [contact_display_cache] reflects [changes], then reloads
     * and republishes the full display list. Used by `ContactChangeCoordinator.handleProviderChanged`
     * so an external bulk write (e.g. a VCF import) is guaranteed to update the visible Contacts list
     * even if the debounced [scheduleContactDisplayCacheRebuild] pipeline hasn't fired for this
     * session yet (e.g. the Contacts tab was never opened, so [wireSyncCallbacks] never ran).
     *
     * Unlike the debounced path this bypasses [ContactDisplayCacheRebuildScheduler] entirely — it is
     * meant for the rare "explicit external change" call site, not the hot per-keystroke/edit path.
     */
    suspend fun applySyncChangesToDisplayCacheBlocking(
        changes: com.goodwy.commons.providercache.sync.ContactSyncChangeSet,
        reason: DisplayCacheRebuildReason = DisplayCacheRebuildReason.PROVIDER_CHANGED_IMPORT,
        loadReason: ContactDisplayLoadReason = ContactDisplayLoadReason.PROVIDER_CHANGED_IMPORT,
    ): ContactDisplayLoadResult = withContext(Dispatchers.IO) {
        wireSyncCallbacks()
        if (changes.updatedContactIds.isEmpty() && changes.deletedContactIds.isEmpty() && !changes.wasFullRebuild) {
            return@withContext loadDisplayContacts(reason = loadReason)
        }
        if (shouldBlockContactsProviderFallback()) {
            ProviderCacheDebugLogger.log("displayCacheRebuild skipped (bulk delete hold) reason=$reason")
            return@withContext loadDisplayContacts(reason = loadReason)
        }
        val mutationId = displayCacheCoordinator?.beginContactsMutation()
        displayCacheBuilder.rebuild(
            reason = reason,
            changedContactIds = changes.updatedContactIds.toSet(),
            deletedContactIds = changes.deletedContactIds.toSet(),
            forceFull = changes.wasFullRebuild,
            mode = ContactDisplayRebuildMode.FAST,
        )
        lastDisplayRebuildReason = reason
        cachedDisplayCacheRowCount = displayCacheDao.getCount()
        contactDisplayCacheBuilding = false
        refreshSearchHintContactCount()
        commitContactsDisplayVersion(
            reason = reason,
            rowCount = cachedDisplayCacheRowCount,
            needsFullReload = changes.wasFullRebuild,
            mutationId = mutationId,
        )
        val result = loadDisplayContacts(reason = loadReason)
        withContext(Dispatchers.Main) {
            if (displayCacheCoordinator == null) {
                onContactDisplayCacheUpdated?.invoke(loadReason)
            }
        }
        result
    }

    private fun scheduleDisplayCacheFromSyncChanges(changes: com.goodwy.commons.providercache.sync.ContactSyncChangeSet) {
        if (shouldSkipDisplayRebuildForValidStartupCache(changes)) {
            ProviderCacheDebugLogger.logSkipDisplayRebuild("valid_cache")
            return
        }
        if (changes.wasFullRebuild) {
            // Immediate: cold empty-cache sync notifies before secondary indexes; do not wait on
            // the 800ms debounce before the full contact list can paint.
            scheduleContactDisplayCacheRebuildImmediate(
                ContactDisplayRebuildRequest(
                    reason = DisplayCacheRebuildReason.CONTACT_SYNC_COMPLETED,
                    forceFull = true,
                    mode = ContactDisplayRebuildMode.FAST,
                ),
            )
            // Secondary indexes may still be running (or scheduled) in ContactsSyncManager.
            // Recents may already have painted bare numbers during an earlier COLD_EMPTY rebuild.
            onDisplayCacheReadyForRecentsResync?.invoke()
            return
        }
        if (changes.updatedContactIds.isEmpty() && changes.deletedContactIds.isEmpty()) return
        val reason = when {
            changes.deletedContactIds.isNotEmpty() && changes.updatedContactIds.isNotEmpty() ->
                DisplayCacheRebuildReason.CONTACT_SYNC_COMPLETED
            changes.deletedContactIds.isNotEmpty() ->
                DisplayCacheRebuildReason.DELETED_CONTACT_IDS
            else -> DisplayCacheRebuildReason.CHANGED_CONTACT_IDS
        }
        val request = ContactDisplayRebuildRequest(
            reason = reason,
            changedContactIds = changes.updatedContactIds.toSet(),
            deletedContactIds = changes.deletedContactIds.toSet(),
            forceFull = changes.deletedContactIds.size > PARTIAL_DISPLAY_UPDATE_MAX_IDS,
            mode = ContactDisplayRebuildMode.FAST,
        )
        if (changes.deletedContactIds.size > PARTIAL_DISPLAY_UPDATE_MAX_IDS) {
            scheduleContactDisplayCacheRebuildImmediate(request)
        } else {
            scheduleContactDisplayCacheRebuild(request)
        }
    }

    fun setSecureSqlParamsProvider(provider: (() -> ContactSecureSqlParams?)?) {
        secureSqlParamsProvider = provider
        invalidatePaging(PagingInvalidationReason.SECURE_FILTER)
    }

    suspend fun refreshCacheState() {
        val hadCache = useRoomCache.value
        val wasPhoneIndexReady = _phoneIndexReady.value
        val hasCache = withContext(Dispatchers.IO) { contactDao.getSummaryCount() > 0 }
        useRoomCache.value = hasCache
        _phoneIndexReady.value = withContext(Dispatchers.IO) {
            database.contactPhoneIndexDao().getCount() > 0
        }
        _loadState.value = when {
            shouldBlockContactsProviderFallback() -> ProviderCacheLoadState.ShowingRoomCache
            hasCache -> ProviderCacheLoadState.ShowingRoomCache
            _loadState.value == ProviderCacheLoadState.RebuildingCache &&
                (contactDisplayCacheBuilding || contactDisplayCacheScheduler.isRebuildInProgress()) ->
                ProviderCacheLoadState.RebuildingCache
            else -> ProviderCacheLoadState.ShowingProviderFallback
        }
        val fallbackActive = _loadState.value == ProviderCacheLoadState.ShowingProviderFallback
        DisplayCacheReadinessTracker.setContactsProviderFallbackActive(fallbackActive)
        if (fallbackActive) {
            LegacyCacheGate.logAuthority("CONTACTS")
        }
        val source = currentContactsSource()
        val loadState = _loadState.value
        if (source != lastLoggedContactsSource || loadState != lastLoggedContactsLoadState) {
            lastLoggedContactsSource = source
            lastLoggedContactsLoadState = loadState
            ProviderCacheDebugLogger.logContactsSource(source, loadState)
        }
        if (!hadCache && hasCache) {
            ProviderCacheDebugLogger.logRoomSwitch(
                ProviderCacheDataSource.PROVIDER_FALLBACK,
                ProviderCacheDataSource.ROOM,
            )
            invalidatePaging(PagingInvalidationReason.ROOM_CACHE_READY)
        }
        if (!wasPhoneIndexReady && _phoneIndexReady.value && activeSearchQuery.value.isNotEmpty()) {
            invalidatePaging(PagingInvalidationReason.PHONE_INDEX_READY)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun contactPages(): Flow<PagingData<Contact>> {
        return activeSearchQuery.flatMapLatest { query ->
            useRoomCache.flatMapLatest { fromRoom ->
                pagingGeneration.flatMapLatest {
                    pagingFilters.resetDuplicateKeys()
                    buildContactPager(fromRoom, query)
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun contactSummaries(): Flow<PagingData<ContactSummary>> =
        contactPages().map { pagingData -> pagingData.map { contact -> contactToSummary(contact) } }

    private fun buildContactPager(fromRoom: Boolean, query: String): Flow<PagingData<Contact>> {
        val normalized = normalizeSearchQuery(query)
        val digits = normalized.filter { it.isDigit() }
        val t9Digits = T9Mapper.toT9Digits(query)
        val queryLower = query.lowercase()
        val roomQueryFilters = if (fromRoom) {
            ContactRoomQueryFilters.build(context, secureSqlParamsProvider?.invoke())
        } else {
            null
        }

        val baseFlow = if (fromRoom) {
            Pager(
                config = pagingConfig,
                pagingSourceFactory = {
                    val sqlFilters = roomQueryFilters!!
                    val roomSource = if (query.isBlank()) {
                        contactDao.summaryPagingSource(
                            visibleAccountNames = sqlFilters.visibleAccountNames,
                            applyAccountFilter = sqlFilters.applyAccountFilter,
                            excludeRawIds = sqlFilters.excludeRawIds,
                            applySecureExclude = sqlFilters.applySecureExclude,
                            includeOnlyRawIds = sqlFilters.includeOnlyRawIds,
                            applySecureIncludeOnly = sqlFilters.applySecureIncludeOnly,
                        )
                    } else {
                        contactDao.searchSummaryPagingSource(
                            queryPrefix = likeContains(query),
                            queryLowerPrefix = likeContains(queryLower),
                            normalizedPrefix = likePrefix(normalized),
                            digitsPrefix = likePrefix(digits),
                            t9Prefix = likeContains(t9Digits.ifEmpty { digits }),
                            visibleAccountNames = sqlFilters.visibleAccountNames,
                            applyAccountFilter = sqlFilters.applyAccountFilter,
                            excludeRawIds = sqlFilters.excludeRawIds,
                            applySecureExclude = sqlFilters.applySecureExclude,
                            includeOnlyRawIds = sqlFilters.includeOnlyRawIds,
                            applySecureIncludeOnly = sqlFilters.applySecureIncludeOnly,
                        )
                    }
                    val queryLabel = if (query.isBlank()) "contacts_list" else "contacts_search"
                    val delegate = EntityMappingPagingSource(
                        delegate = roomSource,
                        mapper = { entity -> ContactPagingMapper.entityToContact(entity) },
                        roomQueryLabel = queryLabel,
                    )
                    ContactPageFilterPagingSource(delegate, pagingFilters, sqlFilters)
                },
            ).flow
        } else if (shouldBlockContactsProviderFallback()) {
            blockedContactPager()
        } else {
            Pager(
                config = pagingConfig,
                pagingSourceFactory = {
                    val summarySource = if (query.isBlank()) {
                        providerDataSource.pagingSource()
                    } else {
                        providerDataSource.searchPagingSource(query, normalized, digits, t9Digits)
                    }
                    val enriched = MetadataEnrichedContactPagingSource(summarySource, metadataLoader)
                    ContactPageFilterPagingSource(enriched, pagingFilters)
                },
            ).flow
        }

        return baseFlow
    }

    private fun blockedContactPager(): Flow<PagingData<Contact>> =
        Pager(
            config = pagingConfig,
            pagingSourceFactory = { BlockedContactPagingSource() },
        ).flow

    private class BlockedContactPagingSource : PagingSource<Int, Contact>() {
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Contact> =
            LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)

        override fun getRefreshKey(state: PagingState<Int, Contact>): Int? = null
    }

    suspend fun loadFirstPageAsContacts(limit: Int = PAGE_SIZE): ArrayList<Contact> =
        ArrayList(loadFirstPage(limit))

    suspend fun loadFirstPage(limit: Int = PAGE_SIZE): List<Contact> = withContext(Dispatchers.IO) {
        if (shouldBlockContactsProviderFallback()) {
            ProviderCacheDebugLogger.log("loadFirstPage skipped (bulk delete hold)")
            return@withContext emptyList()
        }
        _loadState.value = ProviderCacheLoadState.LoadingFirstPage
        try {
            refreshCacheState()
            val contacts = if (useRoomCache.value) {
                val start = System.currentTimeMillis()
                val rows = contactDao.getFirstSummaries(limit)
                ProviderCacheDebugLogger.logRoomQuery("contacts_first_page", System.currentTimeMillis() - start, rows.size)
                rows.map { ContactPagingMapper.entityToContact(it) }
            } else {
                _loadState.value = ProviderCacheLoadState.ShowingProviderFallback
                ProviderCacheDebugLogger.logColdStartPath(
                    phase = "first_page",
                    source = ProviderCacheDataSource.PROVIDER_FALLBACK,
                    firstPageCount = -1,
                    loadState = _loadState.value,
                )
                val summaries = providerDataSource.loadPage(0, limit)
                val meta = metadataLoader.loadForSummaries(summaries)
                ProviderCacheDebugLogger.logColdStartPath(
                    phase = "first_page_loaded",
                    source = ProviderCacheDataSource.PROVIDER_FALLBACK,
                    firstPageCount = summaries.size,
                    loadState = _loadState.value,
                )
                summaries.map { summary ->
                    val m = meta[summary.contactId]
                    if (m != null) ContactPagingMapper.summaryToContact(summary, m)
                    else ContactPagingMapper.summaryToContactFallback(summary)
                }
            }
            pagingFilters.resetDuplicateKeys()
            val filtered = pagingFilters.filterPage(contacts)
            if (activeSearchQuery.value.isNotEmpty()) {
                ProviderCacheDebugLogger.logSearch(
                    query = activeSearchQuery.value,
                    fromRoom = useRoomCache.value,
                    phoneIndexReady = _phoneIndexReady.value,
                    firstPageCount = filtered.size,
                )
            }
            filtered
        } catch (_: Exception) {
            _loadState.value = ProviderCacheLoadState.Error
            emptyList()
        }
    }

    /**
     * Validates Room summaries and [contact_display_cache] against the live provider at startup.
     * Rebuilds summaries + display cache when stale; coalesces concurrent callers.
     */
    suspend fun validateContactsCache(): ContactsCacheValidator.ValidationResult = withContext(Dispatchers.IO) {
        runStartupCacheValidationIfNeeded()
    }

    suspend fun runStartupCacheValidationIfNeeded(): ContactsCacheValidator.ValidationResult {
        if (startupValidationCompleted) {
            return lastStartupValidationResult ?: ContactsCacheValidator.validate(
                context = context,
                database = database,
                providerDataSource = providerDataSource,
                metadataStore = cacheMetadataStore,
            )
        }
        return startupValidationMutex.withLock {
            if (startupValidationCompleted) {
                return@withLock lastStartupValidationResult ?: ContactsCacheValidator.validate(
                    context = context,
                    database = database,
                    providerDataSource = providerDataSource,
                    metadataStore = cacheMetadataStore,
                )
            }
            wireSyncCallbacks()
            val result = ContactsCacheValidator.validate(
                context = context,
                database = database,
                providerDataSource = providerDataSource,
                metadataStore = cacheMetadataStore,
            )
            if (result.isValid) {
                val hasCache = contactDao.getSummaryCount() > 0
                if (hasCache && !useRoomCache.value) {
                    useRoomCache.value = true
                    _loadState.value = ProviderCacheLoadState.ShowingRoomCache
                    invalidatePaging(PagingInvalidationReason.ROOM_CACHE_READY)
                }
                if (hasCache) {
                    refreshSearchHintContactCount()
                }
                seedDisplayCacheStateFromRoom()
                scheduleDeferredStartupSyncWhenValidCache()
            } else {
                repairInvalidContactsCache(result.invalidReason ?: "UNKNOWN")
            }
            startupValidationCompleted = true
            lastStartupValidationResult = result
            result
        }
    }

    private suspend fun repairInvalidContactsCache(invalidReason: String) {
        if (shouldBlockContactsProviderFallback()) return
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            DisplayCacheReadinessTracker.setContacts(DisplayCacheReadiness.ERROR_PERMISSION)
            DisplayCacheReadinessTracker.setContactsProviderFallbackActive(true)
            StartupOrchestrator.markPermissionBlockedStartup()
            return
        }
        if (!StartupDomainOwner.tryAcquire(
                CacheDomain.CONTACTS,
                StartupDomainOwnerKind.CACHE_REPAIR,
                "STARTUP_REPAIR",
            )
        ) {
            return
        }
        StartupOrchestrator.markColdStart()
        StartupOrchestrator.beginRawSync()
        ProviderCacheDebugLogger.logStartupCacheRebuildStart(invalidReason)
        contactDisplayCacheBuilding = true
        useRoomCache.value = true
        _loadState.value = ProviderCacheLoadState.RebuildingCache
        try {
            // Summaries only — phone/search indexes and call-log backfill run after display ready
            // so the Contacts tab can paint the full list sooner.
            syncManager.fullSyncContacts()
        } finally {
            StartupOrchestrator.onContactsRawSyncComplete()
        }
        val rebuildRequest = ContactDisplayRebuildRequest(
            reason = DisplayCacheRebuildReason.STARTUP_INVALID_CACHE,
            forceFull = true,
            mode = ContactDisplayRebuildMode.FAST,
        )
        rebuildDisplayCacheBlocking(rebuildRequest)
        syncManager.rebuildSecondaryIndexes()
        // Full sync already populated Room — skip immediate incremental duplicate.
        initialIncrementalSyncScheduled = true
    }

    /** Metadata-driven raw + display repair after crash or sync failure. */
    suspend fun repairRawCacheFromMetadata(reason: String) {
        repairInvalidContactsCache(reason)
    }

    /** Metadata-driven display-only repair when raw mirror is intact. */
    suspend fun repairDisplayCacheFromMetadata(reason: String) {
        if (shouldBlockContactsProviderFallback()) return
        if (!StartupDomainOwner.tryAcquire(
                CacheDomain.CONTACTS,
                StartupDomainOwnerKind.METADATA_REPAIR,
                reason,
            )
        ) {
            return
        }
        ProviderCacheDebugLogger.logStartupCacheRebuildStart("display:$reason")
        contactDisplayCacheBuilding = true
        DisplayCacheReadinessTracker.setContacts(DisplayCacheReadiness.DISPLAY_BUILDING)
        useRoomCache.value = true
        _loadState.value = ProviderCacheLoadState.RebuildingCache
        rebuildDisplayCacheBlocking(
            ContactDisplayRebuildRequest(
                reason = DisplayCacheRebuildReason.STARTUP_INVALID_CACHE,
                forceFull = true,
                mode = ContactDisplayRebuildMode.FAST,
            ),
        )
    }

    private suspend fun rebuildDisplayCacheBlocking(request: ContactDisplayRebuildRequest) {
        if (contactDisplayCacheScheduler.isRebuildInProgress()) {
            ProviderCacheDebugLogger.log("displayCacheRebuild waiting for in-flight rebuild (startup validation)")
            contactDisplayCacheScheduler.awaitIdle()
        }
        StartupOrchestrator.onDisplayCacheRebuildStarted()
        try {
            displayCacheBuilder.rebuild(
                reason = request.reason,
                changedContactIds = request.changedContactIds,
                deletedContactIds = request.deletedContactIds,
                forceFull = request.forceFull,
                mode = request.mode,
            )
        } finally {
            StartupOrchestrator.onDisplayCacheRebuildEnded()
        }
        handleDisplayCacheRebuildComplete(request)
    }

    fun startBackgroundSync() {
        if (shouldBlockContactsProviderFallback()) {
            ProviderCacheDebugLogger.log("startBackgroundSync skipped (bulk delete hold)")
            return
        }
        wireSyncCallbacks()
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            DisplayCacheReadinessTracker.setContacts(DisplayCacheReadiness.ERROR_PERMISSION)
            DisplayCacheReadinessTracker.setContactsProviderFallbackActive(true)
            _loadState.value = ProviderCacheLoadState.ShowingProviderFallback
            StartupOrchestrator.markPermissionBlockedStartup()
            ProviderCacheDebugLogger.logContactsSource(currentContactsSource(), _loadState.value)
            logContactsStartupDecision(ContactsStartupDecision.PermissionBlocked, "PERMISSION")
            return
        }
        if (!DisplayCacheReadinessTracker.resumeContactsAfterPermissionGranted()) {
            return
        }
        scope.launch(Dispatchers.IO) {
            if (contactDao.getSummaryCount() == 0 && displayCacheDao.getCount() == 0) {
                StartupOrchestrator.markColdStart()
            }
        }
        scheduleIdlePhotoBackfillWatcher()
        scope.launch {
            val decision = evaluateContactsStartupDecision()
            applyContactsStartupDecision(decision)
        }
        _loadState.value = when {
            useRoomCache.value -> ProviderCacheLoadState.ShowingRoomCache
            contactDisplayCacheBuilding || contactDisplayCacheScheduler.isRebuildInProgress() ->
                ProviderCacheLoadState.RebuildingCache
            else -> ProviderCacheLoadState.ShowingProviderFallback
        }
        val source = currentContactsSource()
        val loadState = _loadState.value
        if (source != lastLoggedContactsSource || loadState != lastLoggedContactsLoadState) {
            lastLoggedContactsSource = source
            lastLoggedContactsLoadState = loadState
            ProviderCacheDebugLogger.logContactsSource(source, loadState)
        }
    }

    private suspend fun evaluateContactsStartupDecision(): ContactsStartupDecision {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return ContactsStartupDecision.PermissionBlocked
        }
        val result = runStartupCacheValidationIfNeeded()
        return if (result.isValid) {
            ContactsStartupDecision.UseExistingCache
        } else {
            ContactsStartupDecision.RunFullRepairAfterFirstPaint(
                result.invalidReason ?: "UNKNOWN",
            )
        }
    }

    private suspend fun applyContactsStartupDecision(decision: ContactsStartupDecision) {
        logContactsStartupDecision(decision, decision::class.simpleName.orEmpty())
        when (decision) {
            ContactsStartupDecision.UseExistingCache -> {
                android.util.Log.d("ContactsRepository", "contactsFullSync skipped reason=CACHE_VALID")
            }
            ContactsStartupDecision.RunIncrementalAfterFirstPaint -> {
                syncManager.scheduleIncrementalSync()
            }
            is ContactsStartupDecision.RunFullRepairAfterFirstPaint -> {
                android.util.Log.d("ContactsRepository", "contactsFullSync scheduled reason=${decision.reason}")
            }
            ContactsStartupDecision.PermissionBlocked -> Unit
            ContactsStartupDecision.ValidationPending -> {
                android.util.Log.d("ContactsRepository", "contactsFullSync blocked reason=VALIDATION_PENDING")
            }
        }
    }

    private fun logContactsStartupDecision(decision: ContactsStartupDecision, reason: String) {
        android.util.Log.d("ContactsRepository", "contactsStartupDecision decision=${decision::class.simpleName} reason=$reason")
    }

    private fun scheduleIdlePhotoBackfillWatcher() {
        if (idlePhotoBackfillJob?.isActive == true) return
        idlePhotoBackfillJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(IDLE_PHOTO_BACKFILL_POLL_MS)
                if (syncManager.isPhotoThumbnailBackfillCompleted()) {
                    return@launch
                }
                if (StartupOrchestrator.coldStart &&
                    StartupOrchestrator.currentPhase() != StartupOrchestrator.Phase.IDLE_PHOTO_BACKFILL &&
                    StartupOrchestrator.currentPhase() != StartupOrchestrator.Phase.COMPLETE
                ) {
                    continue
                }
                if (ProviderCacheUserInteractionGate.isUserInteracting()) continue
                if (!ProviderCacheUserInteractionGate.consumeDeferredPhotoBackfill() &&
                    StartupOrchestrator.currentPhase() != StartupOrchestrator.Phase.IDLE_PHOTO_BACKFILL
                ) {
                    continue
                }
                syncManager.scheduleIdlePhotoBackfillIfNeeded()
                // Keep polling while chunks may remain; re-arm defer so the next idle wake
                // continues without relying solely on scheduleRetryWhenIdle.
                if (!syncManager.isPhotoThumbnailBackfillCompleted()) {
                    ProviderCacheUserInteractionGate.deferPhotoBackfill()
                }
            }
        }
    }

    fun startIncrementalSync() {
        wireSyncCallbacks()
        syncManager.scheduleIncrementalSync()
    }

    fun invalidatePaging(reason: PagingInvalidationReason = PagingInvalidationReason.UNSPECIFIED) {
        if (shouldBlockContactsProviderFallback()) {
            ProviderCacheDebugLogger.log("invalidate skipped (bulk delete hold) reason=$reason")
            return
        }
        invalidatePagingDebounced(reason)
    }

    private fun invalidatePagingDebounced(reason: PagingInvalidationReason) {
        val now = System.currentTimeMillis()
        if (reason == lastPagingInvalidationReason && now - lastPagingInvalidationMs < PAGING_INVALIDATION_DEBOUNCE_MS) {
            ProviderCacheDebugLogger.log("invalidate coalesced reason=$reason generation=${pagingGeneration.value}")
            return
        }
        lastPagingInvalidationReason = reason
        lastPagingInvalidationMs = now
        pagingFilters.resetDuplicateKeys()
        pagingGeneration.value = pagingGeneration.value + 1
        ProviderCacheDebugLogger.logPagingInvalidation(
            target = "contacts",
            reason = reason,
            generation = pagingGeneration.value,
        )
    }

    fun deleteContactsFromCacheByRawId(rawIds: Collection<Int>): Job =
        removeContactsFromCachesImmediately(rawIds)

    /**
     * Removes contacts from the visible list immediately, then purges Room/display caches in the background.
     * @return the [Job] doing the background purge, so callers (e.g. ContactChangeCoordinator) can await completion for logging.
     */
    fun removeContactsFromCachesImmediately(rawIds: Collection<Int>): Job {
        if (rawIds.isEmpty()) return Job().apply { complete() }
        val idSet = rawIds.toSet()
        // Refuse these ids to any sync for a short window, before anything else runs. A sync that
        // started before this delete still holds a snapshot containing them, and would otherwise
        // write them back after the purge below — the contact reappears on resume. Bulk delete-all
        // is covered by suppressIncrementalSync; single deletes had nothing.
        RecentlyDeletedContacts.rememberRawIds(idSet)
        invalidateMappedDisplayListCache()
        if (!contactsBulkDeleteInProgress) {
            adjustSearchHintContactCountImmediate(-idSet.size)
            cachedDisplayCacheRowCount = (cachedDisplayCacheRowCount - idSet.size).coerceAtLeast(0)
        }
        val uiCallback = onContactsDeletedFromDisplay
        if (!contactsBulkDeleteInProgress) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                uiCallback?.invoke(idSet)
            } else {
                scope.launch(Dispatchers.Main.immediate) { uiCallback?.invoke(idSet) }
            }
        }
        return scope.launch(Dispatchers.IO) {
            val mutationId = displayCacheCoordinator?.beginContactsMutation()
            rawIds.chunked(200).forEach { chunk ->
                val resolved = resolveContactDeletions(chunk)
                val contactIds = resolved.map { it.contactId }.distinct()
                // Aggregate ids are only known here; the sync writes summaries keyed by them.
                RecentlyDeletedContacts.rememberContactIds(contactIds)
                resolved.forEach { deletion ->
                    displayCacheDao.getByRawId(deletion.rawId)?.let { row ->
                        ContactAvatarInvalidUriTracker.removeRawId(row.rawId)
                    }
                    if (deletion.contactId > 0) {
                        displayCacheDao.getByContactIds(listOf(deletion.contactId)).forEach { row ->
                            ContactAvatarInvalidUriTracker.removeRawId(row.rawId)
                        }
                    }
                }
                val deletedEvents = buildContactDisplayDeletedEvents(chunk)
                if (deletedEvents.isNotEmpty()) {
                    onContactsDisplayDeleted?.invoke(deletedEvents)
                }
                ProviderCacheTransactions.purgeContactRoomCaches(
                    database = database,
                    rawIds = chunk,
                    contactIds = contactIds,
                    mutationId = mutationId ?: 0L,
                )
            }
            invalidateSearchPageCaches()
            Log.d(TAG_CONTACT_CHANGE, "contactCacheUpdate rows=${rawIds.size}")
            Log.d(TAG_CONTACT_CHANGE, "contactDisplayUpdate rows=${rawIds.size}")
            cachedDisplayCacheRowCount = displayCacheDao.getCount()
            commitContactsDisplayVersion(
                reason = DisplayCacheRebuildReason.DELETED_CONTACT_IDS,
                rowCount = cachedDisplayCacheRowCount,
                needsFullReload = rawIds.size > PARTIAL_DISPLAY_UPDATE_MAX_IDS,
                mutationId = mutationId,
            )
            invalidatePaging(PagingInvalidationReason.UNSPECIFIED)
        }
    }

    private data class ResolvedContactDeletion(
        val rawId: Int,
        val contactId: Int,
        val summary: ContactSummaryEntity?,
    )

    /**
     * [rawIds] are [ContactsContract.RawContacts] ids (what delete UI passes as [Contact.id]).
     * Room contact tables key off aggregated [ContactsContract.Contacts] ids.
     */
    private suspend fun resolveContactDeletions(rawIds: List<Int>): List<ResolvedContactDeletion> {
        if (rawIds.isEmpty()) return emptyList()
        val summariesByRawId = contactDao.getSummariesByPrimaryRawIds(rawIds).associateBy { it.primaryRawId }
        val summariesByContactId = contactDao.getSummariesByIds(rawIds).associateBy { it.contactId }
        return rawIds.map { rawId ->
            val summary = summariesByRawId[rawId] ?: summariesByContactId[rawId]
            ResolvedContactDeletion(
                rawId = rawId,
                contactId = summary?.contactId ?: rawId,
                summary = summary,
            )
        }
    }

    /**
     * Builds one [ContactDisplayDeleted] per deleted raw contact, resolving lookup key and phone
     * digits from Room while those rows still exist. Must be called before the corresponding Room
     * rows are purged.
     */
    private suspend fun buildContactDisplayDeletedEvents(rawIds: List<Int>): List<ContactDisplayDeleted> {
        if (rawIds.isEmpty()) return emptyList()
        val resolved = resolveContactDeletions(rawIds)
        val contactIds = resolved.map { it.contactId }.distinct()
        val phoneByContact = phoneIndexDao.getByContactIds(contactIds).groupBy { it.contactId }
        val recentDisplayDao = database.recentDisplayCacheDao()
        return resolved.map { deletion ->
            val phones = phoneByContact[deletion.contactId].orEmpty()
            val digits = phones.map { it.phoneDigits.ifEmpty { it.digits } }.filter { it.isNotEmpty() }
            val normalized = phones.map { it.normalizedNumber }.filter { it.isNotEmpty() }
            val hintCallIds = LinkedHashSet<Int>()
            recentDisplayDao.getByContactId(deletion.contactId).forEach { hintCallIds.add(it.callId) }
            val lookupKey = deletion.summary?.lookupKey.orEmpty()
            if (lookupKey.isNotEmpty()) {
                recentDisplayDao.getByLookupKey(lookupKey).forEach { hintCallIds.add(it.callId) }
            }
            if (normalized.isNotEmpty()) {
                recentDisplayDao.getByPhoneNumbers(normalized, normalized)
                    .forEach { hintCallIds.add(it.callId) }
            }
            val displayName = deletion.summary?.displayName.orEmpty()
            if (displayName.isNotEmpty()) {
                recentDisplayDao.getUnlinkedByCachedName(displayName)
                    .forEach { hintCallIds.add(it.callId) }
            }
            ContactDisplayDeleted(
                contactId = deletion.contactId,
                lookupKey = lookupKey,
                displayName = displayName,
                hintCallIds = hintCallIds.toList(),
                phoneDigits = digits,
                normalizedNumbers = normalized,
            ).also {
                ProviderCacheDebugLogger.log(
                    "contactDeleted rawId=${deletion.rawId} contactId=${deletion.contactId} " +
                        "hintCallIds=${hintCallIds.size} phoneDigits=${digits.joinToString(",")}",
                )
            }
        }
    }

    /**
     * Optimistic delete-all: UI is cleared on the main thread before this runs; clears Room, then provider.
     */
    fun deleteAllContactsOptimistic(
        uiClearedAtElapsedMs: Long = 0L,
        onFinished: ((Boolean) -> Unit)? = null,
    ) {
        if (!contactsBulkDeleteInProgress) {
            beginBulkDeleteAll()
        }
        scope.launch {
            if (uiClearedAtElapsedMs > 0L) {
                ProviderCacheDebugLogger.logUiClearToProviderDeleteStartMs(
                    android.os.SystemClock.elapsedRealtime() - uiClearedAtElapsedMs,
                )
            }
            var success = false
            try {
                var allContactIdsCount = 0
                withContext(Dispatchers.IO) {
                    allContactIdsCount = contactDao.getAllSummaryIds().size
                }
                clearAllContactRoomCaches()
                // Strip contact labels from call log + rebuild recents once — do not run thousands of
                // per-contact delete handlers (that thrashes recent_display_cache to zero rows).
                withContext(Dispatchers.IO) {
                    onAllContactsDisplayDeleted?.invoke()
                }
                Log.d(TAG_CONTACT_CHANGE, "contactCacheUpdate rows=$allContactIdsCount")
                Log.d(TAG_CONTACT_CHANGE, "contactDisplayUpdate rows=$allContactIdsCount")
                withContext(Dispatchers.IO) {
                    commitContactsDisplayVersion(
                        reason = DisplayCacheRebuildReason.DELETED_CONTACT_IDS,
                        rowCount = 0,
                        needsFullReload = true,
                    )
                }
                withContext(Dispatchers.Main) {
                    useRoomCache.value = false
                    _phoneIndexReady.value = false
                }
                val outcome = bulkDeleteManager.deleteAllRawContactsProviderOnly()
                success = outcome.success
                if (success) {
                    refreshCacheState()
                }
            } catch (e: Exception) {
                ProviderCacheDebugLogger.logBulkDeleteError(e.message ?: e.javaClass.simpleName)
                bulkDeleteManager.logVerificationAfterFailure()
                success = false
            } finally {
                withContext(Dispatchers.Main) {
                    endBulkDeleteAll(success)
                    if (success) {
                        onBulkDeleteAllCompleted?.invoke()
                    } else {
                        onBulkDeleteAllFailed?.invoke()
                    }
                    onFinished?.invoke(success)
                }
                if (!success) {
                    syncManager.scheduleIncrementalSync()
                    startBackgroundSync()
                }
            }
        }
    }

    /** @see [deleteAllContactsOptimistic] */
    fun deleteAllContactsViaProvider(onFinished: ((Boolean) -> Unit)? = null) {
        deleteAllContactsOptimistic(onFinished = onFinished)
    }

    private suspend fun clearAllContactRoomCaches() = withContext(Dispatchers.IO) {
        contactDao.clearSummaries()
        contactDao.clearDetails()
        displayCacheDao.clearAll()
        cachedDisplayCacheRowCount = 0
        database.contactPhoneIndexDao().clearAll()
        database.contactSearchIndexDao().clearAll()
        ContactAvatarInvalidUriTracker.clearAll()
        ContactAvatarPhotoVersionTracker.clearAll()
        AvatarIdentityResolver.clearAll()
        lastAvatarBindSignature = 0L
    }

    fun retryAfterError() {
        _loadState.value = ProviderCacheLoadState.LoadingFirstPage
        startBackgroundSync()
        invalidatePaging(PagingInvalidationReason.ERROR_RETRY)
    }

    suspend fun getCachedDetailContact(contactId: Int): Contact? = withContext(Dispatchers.IO) {
        val detail = contactDao.getDetail(contactId) ?: return@withContext null
        val phones = contactDao.getPhones(contactId)
        val emails = contactDao.getEmails(contactId)
        detail.toListContact(phones, emails)
    }

    suspend fun loadDetailContact(contactId: Int): Contact? = withContext(Dispatchers.IO) {
        getCachedDetailContact(contactId) ?: run {
            syncManager.cacheContactDetail(contactId)
            getCachedDetailContact(contactId)
        } ?: providerDataSource.loadContactDetail(contactId)?.let { loaded ->
            val summary = loaded.summary
            Contact(
                id = summary.contactId,
                contactId = summary.contactId,
                prefix = loaded.prefix,
                firstName = loaded.firstName.ifEmpty { summary.displayName },
                middleName = loaded.middleName,
                surname = loaded.surname,
                suffix = loaded.suffix,
                nickname = loaded.nickname,
                thumbnailUri = summary.photoThumbnailUri,
                photoUri = summary.photoThumbnailUri,
                notes = loaded.notes,
                organization = Organization(loaded.company, loaded.jobPosition),
                phoneNumbers = ArrayList(
                    loaded.phones.map {
                        PhoneNumber(it.number, it.type, it.label, it.normalizedNumber, it.isPrimary)
                    },
                ),
                emails = ArrayList(
                    loaded.emails.map { Email(it.value, it.type, it.label) },
                ),
            )
        }
    }

    suspend fun hasRoomCache(): Boolean = withContext(Dispatchers.IO) {
        contactDao.getSummaryCount() > 0
    }

    fun debugActiveSearchQuery(): String = activeSearchQuery.value

    fun debugPagingGeneration(): Int = pagingGeneration.value

    fun debugUseRoomCache(): Boolean = useRoomCache.value

    private fun currentContactsSource(): ProviderCacheDataSource =
        if (useRoomCache.value) ProviderCacheDataSource.ROOM else ProviderCacheDataSource.PROVIDER_FALLBACK

    companion object {
        private const val TAG_AVATAR_REFRESH = "ContactPhoto"
        /** Shared tag for the ContactChangeCoordinator structured log lines (see app-layer coordinator). */
        private const val TAG_CONTACT_CHANGE = "ContactChangeCoordinator"
        const val PAGE_SIZE = 50
        private const val DISPLAY_PAGE_SIZE = 2_500
        /** Delay after a fast rebuild before running accurate duplicate merge in the background. */
        private const val IDLE_DUPLICATE_MERGE_DELAY_MS = 15_000L
        /** Max changed IDs for in-place adapter update instead of reloading the full display list. */
        private const val PARTIAL_DISPLAY_UPDATE_MAX_IDS = 50
        private const val PARTIAL_UPDATE_RATIO_DIVISOR = 100
        private const val PAGING_INVALIDATION_DEBOUNCE_MS = 500L
        /** Delay before the first incremental sync when startup cache validation passed. */
        private const val DEFERRED_STARTUP_SYNC_DELAY_MS = 8_000L
        private const val IDLE_PHOTO_BACKFILL_POLL_MS = 1_000L
        /** SQL batch size when progressively scanning display-cache search candidates. */
        private const val SEARCH_SQL_SCAN_BATCH = 40

        fun create(context: Context, syncManager: ContactsSyncManager): ContactsRepository {
            val appContext = context.applicationContext
            val db = ProviderCacheDatabase.getInstance(appContext)
            return ContactsRepository(
                context = appContext,
                database = db,
                providerDataSource = ContactsProviderDataSource(appContext),
                syncManager = syncManager,
            )
        }

        private fun normalizeSearchQuery(query: String): String =
            query.filter { it.isDigit() || it == '+' }

        private fun escapeLike(raw: String): String =
            raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        private fun likePrefix(raw: String): String =
            if (raw.isEmpty()) "" else "${escapeLike(raw)}%"

        private fun likeContains(raw: String): String =
            if (raw.isEmpty()) "" else "%${escapeLike(raw)}%"

        private fun contactToSummary(contact: Contact): ContactSummary = ContactSummary(
            contactId = contact.contactId,
            lookupKey = "",
            displayName = contact.getNameToDisplay(),
            photoThumbnailUri = contact.thumbnailUri,
            hasPhoneNumber = contact.phoneNumbers.isNotEmpty(),
            lastUpdatedTimestamp = 0L,
        )
    }
}
