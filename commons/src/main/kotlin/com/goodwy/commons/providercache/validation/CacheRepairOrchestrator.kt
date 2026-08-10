package com.goodwy.commons.providercache.validation

import android.content.Context
import android.util.Log
import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.debug.CacheMutationLogger
import com.goodwy.commons.providercache.entities.CacheMetadataDomain
import com.goodwy.commons.providercache.metadata.CacheMetadataStore
import com.goodwy.commons.providercache.repository.CallLogRepository
import com.goodwy.commons.providercache.repository.ContactsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Startup crash-recovery and metadata-driven repair (Phase 4).
 *
 * Runs light validation from persisted [cache_metadata] dirty/repair flags, repairs affected
 * domains, then delegates to repository deep-validation paths.
 *
 * Contacts full sync is owned by content validation ([ContactsRepository.runStartupCacheValidationIfNeeded]),
 * not by metadata dirty flags alone — stale METADATA_DIRTY must not force a full provider sync when
 * the Room mirror already matches the provider.
 */
object CacheRepairOrchestrator {

    private const val TAG = "CacheRepair"

    private val mutex = Mutex()

    @Volatile
    private var startupRecoveryCompleted = false

    @Volatile
    private var lastReport: CacheValidator.ValidationReport? = null

    suspend fun runStartupRecoveryIfNeeded(
        context: Context,
        database: ProviderCacheDatabase,
        metadataStore: CacheMetadataStore,
        contactsRepository: ContactsRepository,
        callLogRepository: CallLogRepository,
        deferRecentsRawRepair: Boolean = false,
    ): CacheValidator.ValidationReport = mutex.withLock {
        if (startupRecoveryCompleted) {
            return@withLock lastReport ?: CacheValidator.validateLight(
                metadataStore = metadataStore,
                database = database,
                recentsGroupByContact = if (callLogRepository.peekGroupByContact()) 1 else 0,
            )
        }
        metadataStore.ensureSeeded()
        metadataStore.refreshFlowsFromRoom()

        val groupByContact = callLogRepository.peekGroupByContact()
        val light = CacheValidator.validateLight(
            metadataStore = metadataStore,
            database = database,
            recentsGroupByContact = if (groupByContact) 1 else 0,
        )
        lastReport = light
        CacheMutationLogger.cacheValidationStart(CacheValidator.Scope.LIGHT.name, light.issues.size)
        Log.d(TAG, "startupRecovery light issues=${light.issues.size} domains=${light.domainsNeedingRepair()}")

        if (light.requiresRepair) {
            repairFromLightReport(
                report = light,
                callLogRepository = callLogRepository,
                deferRecentsRawRepair = deferRecentsRawRepair,
            )
        }

        if (com.goodwy.commons.providercache.startup.StartupFirstPaintGate.shouldDeferContactsStartupWork()) {
            // ContactsRepository.startBackgroundSync runs content validation after Recents paint.
            com.goodwy.commons.providercache.startup.StartupFirstPaintGate.logContactsWorkDeferred("CONTACTS_FULL_SYNC")
        } else {
            runContactsContentValidation(
                metadataStore = metadataStore,
                contactsRepository = contactsRepository,
                lightDomains = light.domainsNeedingRepair(),
            )
            com.goodwy.commons.providercache.startup.StartupFirstPaintGate.logContactsWorkResumed("CONTACTS_FULL_SYNC")
        }
        callLogRepository.runStartupCacheRecoveryIfNeeded(light)

        startupRecoveryCompleted = true
        CacheMutationLogger.cacheValidationEnd(light.issues.size, repaired = light.requiresRepair)
        light
    }

    private suspend fun repairFromLightReport(
        report: CacheValidator.ValidationReport,
        callLogRepository: CallLogRepository,
        deferRecentsRawRepair: Boolean = false,
    ) {
        val domains = report.domainsNeedingRepair()
        val plan = CacheDebugCommands.planMetadataRepairDomains(domains)
        Log.d(TAG, "startupRepair plan=${plan.joinToString(",")}")

        val contactsInPlan =
            CacheMetadataDomain.CONTACTS_RAW in domains ||
                CacheMetadataDomain.CONTACTS_DISPLAY in domains
        if (contactsInPlan) {
            if (com.goodwy.commons.providercache.startup.StartupFirstPaintGate.shouldDeferContactsStartupWork()) {
                com.goodwy.commons.providercache.startup.StartupFirstPaintGate
                    .logContactsWorkDeferred("CONTACTS_RAW_REPAIR")
            } else {
                // Do not full-sync from metadata dirty alone — content validation owns Contacts repair.
                Log.d(TAG, "startupRepair contacts skipped reason=DEFER_TO_CONTENT_VALIDATION")
            }
        }

        if (CacheMetadataDomain.RECENTS_RAW in domains) {
            if (deferRecentsRawRepair) {
                Log.d(TAG, "startupRepair recentsRaw deferred reason=RECENTS_FIRST_PAINT")
            } else {
                // Ownership is acquired inside repairRawCacheFromMetadata (domain+generation).
                Log.d(TAG, "startupRepair recentsRaw reason=metadata")
                callLogRepository.repairRawCacheFromMetadata(
                    report.issues.firstOrNull {
                        it.domain == CacheMetadataDomain.RECENTS_RAW
                    }?.detail ?: "METADATA_REPAIR",
                )
            }
        }
        if (CacheMetadataDomain.RECENTS_DISPLAY in domains) {
            // Ownership is acquired inside repairDisplayCacheFromMetadata (domain+generation).
            Log.d(TAG, "startupRepair recentsDisplay reason=metadata")
            callLogRepository.repairDisplayCacheFromMetadata(
                report.issues.firstOrNull {
                    it.domain == CacheMetadataDomain.RECENTS_DISPLAY
                }?.detail ?: "METADATA_REPAIR",
                groupByContact = callLogRepository.peekGroupByContact(),
            )
        }
    }

    private suspend fun runContactsContentValidation(
        metadataStore: CacheMetadataStore,
        contactsRepository: ContactsRepository,
        lightDomains: Set<String>,
    ) {
        val result = contactsRepository.runStartupCacheValidationIfNeeded()
        if (result.isValid) {
            val contactsLight =
                CacheMetadataDomain.CONTACTS_RAW in lightDomains ||
                    CacheMetadataDomain.CONTACTS_DISPLAY in lightDomains
            if (contactsLight) {
                Log.d(TAG, "startupRepair contacts skipped reason=CONTENT_VALID")
                metadataStore.acknowledgeHealthy(
                    CacheMetadataDomain.CONTACTS_RAW,
                    "CONTENT_VALID",
                )
                metadataStore.acknowledgeHealthy(
                    CacheMetadataDomain.CONTACTS_DISPLAY,
                    "CONTENT_VALID",
                )
            }
        }
    }

    /** Debug helper — reset so the next launch re-runs recovery. */
    fun resetStartupRecoveryForDebug() {
        startupRecoveryCompleted = false
        lastReport = null
    }
}
