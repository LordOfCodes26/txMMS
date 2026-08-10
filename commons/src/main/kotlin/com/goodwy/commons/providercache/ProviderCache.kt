package com.goodwy.commons.providercache

import android.content.Context
import com.goodwy.commons.providercache.coordinator.DisplayCacheCoordinator
import com.goodwy.commons.providercache.metadata.CacheMetadataStore
import com.goodwy.commons.providercache.observer.CallLogChangeObserver
import com.goodwy.commons.providercache.observer.ContactsChangeObserver
import com.goodwy.commons.providercache.pending.PendingRecentsInsertTracker
import com.goodwy.commons.providercache.repository.CallLogRepository
import com.goodwy.commons.providercache.repository.ContactsRepository
import com.goodwy.commons.providercache.sync.CallLogSyncManager
import com.goodwy.commons.providercache.sync.ContactsSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.goodwy.commons.providercache.validation.CacheRepairOrchestrator

/**
 * Application-scoped holder for provider-cache data layer components.
 */
object ProviderCache {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false

    lateinit var contactsSyncManager: ContactsSyncManager
        private set
    lateinit var callLogSyncManager: CallLogSyncManager
        private set
    lateinit var contactsRepository: ContactsRepository
        private set
    lateinit var callLogRepository: CallLogRepository
        private set
    lateinit var contactsChangeObserver: ContactsChangeObserver
        private set
    lateinit var callLogChangeObserver: CallLogChangeObserver
        private set
    lateinit var cacheMetadataStore: CacheMetadataStore
        private set
    lateinit var displayCacheCoordinator: DisplayCacheCoordinator
        private set

    /** One-shot outgoing CallLog insert tokens for Recents optimistic preview. */
    val pendingRecentsInsertTracker: PendingRecentsInsertTracker = PendingRecentsInsertTracker(
        callLogDaoProvider = {
            if (!initialized) null
            else ProviderCacheDatabase.getInstanceOrNull()?.callLogDao()
        },
        snapshotVersionProvider = {
            if (!initialized) 0L
            else runCatching { callLogRepository.recentsCacheVersion() }.getOrDefault(0L)
        },
    )

    /**
     * Optional hook from the app-layer [RecentsPipelineCoordinator]. When set, CallLog/outgoing
     * registration paths submit pipeline events instead of driving bridge workflows directly.
     */
    @Volatile
    var recentsPipelineSubmit: ((com.goodwy.commons.providercache.pipeline.RecentsPipelineEvent) -> Unit)? = null

    /** Optional QA dump from attached RecentsPipelineCoordinator. */
    @Volatile
    var recentsPipelineQaDump: (() -> String)? = null

    /** IO scope for pending-insert baseline capture from app-layer dial hooks. */
    fun ioScope(): CoroutineScope = scope

    /**
     * App-layer hook for ContactsProvider changes. When set (e.g. from Application.onCreate to
     * `ContactChangeCoordinator::handleProviderChanged`), [ContactsChangeObserver] invokes only this
     * callback and does not also call [ContactsSyncManager.scheduleIncrementalSync] — one owner per change.
     */
    var onProviderContactsChangedExtra: (() -> Unit)? = null

    fun isInitialized(): Boolean = initialized

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            val database = ProviderCacheDatabase.getInstance(appContext)
            cacheMetadataStore = CacheMetadataStore(database)
            displayCacheCoordinator = DisplayCacheCoordinator(database, cacheMetadataStore)
            contactsSyncManager = ContactsSyncManager.create(appContext, scope)
            callLogSyncManager = CallLogSyncManager.create(appContext, scope)
            contactsRepository = ContactsRepository.create(appContext, contactsSyncManager)
            callLogRepository = CallLogRepository.create(appContext, callLogSyncManager)
            contactsRepository.wireDisplayCacheCoordinator(displayCacheCoordinator)
            callLogRepository.wireDisplayCacheCoordinator(displayCacheCoordinator)
            callLogRepository.onCallLogSyncChangeSet = { changes ->
                pendingRecentsInsertTracker.onSyncChangeSet(changes)
            }
            // Wire before startup repair so call-log sync that finishes during recovery still
            // schedules a display-cache rebuild once the app-layer handler is installed.
            callLogRepository.wireSyncCallbacksIfNeeded()
            scope.launch(Dispatchers.IO) {
                cacheMetadataStore.ensureSeeded()
                com.goodwy.commons.providercache.startup.StartupSessionLogger.beginSession()
                com.goodwy.commons.providercache.startup.RecentsReadinessSeeder.seedFromPersistedMetadata(
                    database = database,
                    metadataStore = cacheMetadataStore,
                    callLogRepository = callLogRepository,
                )
                val warmRows = callLogRepository.recentsDisplayCacheRowCount()
                val warmVersion = callLogRepository.recentsCacheVersion()
                val warmDecision = com.goodwy.commons.providercache.display.RecentsWarmPaintPolicy.evaluate(
                    displayVersion = warmVersion,
                    displayRows = warmRows,
                    displayDirty = cacheMetadataStore.peekRecentsDisplayDirty(),
                    displayRepairRequired = cacheMetadataStore.peekRecentsDisplayRepairRequired(),
                    hasCallLogPermission = callLogRepository.hasCallLogPermissionForStartup(),
                )
                val deferRepair = warmDecision.allowed && warmRows > 0
                if (deferRepair) {
                    com.goodwy.commons.providercache.startup.StartupFirstPaintGate.markWarmRecentsExpected()
                    com.goodwy.commons.providercache.startup.StartupFirstPaintGate.logRecentsRepairDeferred()
                    // Wait for Recents paint when Recents is priority; abort early if Contacts is visible
                    // so Contacts content validation is not blocked behind a hidden Recents frame.
                    com.goodwy.commons.providercache.startup.StartupFirstPaintGate
                        .awaitSurfaceRequestThenPaint()
                }
                val deferRecentsRaw =
                    deferRepair &&
                        !com.goodwy.commons.providercache.startup.StartupFirstPaintGate
                            .recentsFirstPaintCompleted()
                CacheRepairOrchestrator.runStartupRecoveryIfNeeded(
                    context = appContext,
                    database = database,
                    metadataStore = cacheMetadataStore,
                    contactsRepository = contactsRepository,
                    callLogRepository = callLogRepository,
                    deferRecentsRawRepair = deferRecentsRaw,
                )
            }
            callLogRepository.wireContactDisplayChangeListener(contactsRepository)
            contactsChangeObserver = ContactsChangeObserver(
                context = appContext,
                syncManager = contactsSyncManager,
                onChangeDebounced = {
                    contactsRepository.cancelDeferredStartupSyncIfScheduled()
                    onProviderContactsChangedExtra?.invoke()
                },
            )
            callLogChangeObserver = CallLogChangeObserver(
                context = appContext,
                syncManager = callLogSyncManager,
                onChangeDebounced = {
                    val submit = recentsPipelineSubmit
                    if (submit != null) {
                        submit(
                            com.goodwy.commons.providercache.pipeline.RecentsPipelineEvent.CallLogObserverTriggered(
                                observedAt = System.currentTimeMillis(),
                                source = com.goodwy.commons.providercache.pipeline.ObserverSource.GLOBAL,
                            ),
                        )
                    }
                    // When no coordinator is attached, observer still schedules sync via SyncManager.
                },
            )
            initialized = true
        }
    }

    /** Register secure-mode filter from the app layer (requires Activity context at filter time). */
    fun setSecureContactPageFilter(
        filter: com.goodwy.commons.providercache.filter.ContactPageFilter?,
        scheduleDisplayRebuild: Boolean = true,
    ) {
        if (!initialized) return
        contactsRepository.setSecurePageFilter(filter, scheduleDisplayRebuild = scheduleDisplayRebuild)
    }

    /** Register secure-mode SQL params for Room query push-down. */
    fun setSecureContactSqlParamsProvider(
        provider: (() -> com.goodwy.commons.providercache.filter.ContactSecureSqlParams?)?,
    ) {
        if (!initialized) return
        contactsRepository.setSecureSqlParamsProvider(provider)
    }

    fun registerObservers() {
        if (!initialized) return
        contactsChangeObserver.register()
        callLogChangeObserver.register()
    }

    fun unregisterObservers() {
        if (!initialized) return
        contactsChangeObserver.unregister()
        callLogChangeObserver.unregister()
    }

    fun clearDatabaseForDebug(context: Context) {
        synchronized(this) {
            if (initialized) {
                unregisterObservers()
            }
            ProviderCacheDatabase.destroyInstance()
            initialized = false
            context.applicationContext.deleteDatabase("provider_cache.db")
            initialize(context)
            registerObservers()
        }
    }

    /**
     * Debug-only: clears the Room singleton so [provider_cache.db] can be deleted and rebuilt.
     */
    fun reinitializeForDebug(context: Context) {
        synchronized(this) {
            if (initialized) {
                unregisterObservers()
            }
            ProviderCacheDatabase.destroyInstance()
            initialized = false
            initialize(context)
            registerObservers()
        }
    }
}
