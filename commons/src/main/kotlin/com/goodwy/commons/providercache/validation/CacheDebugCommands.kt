package com.goodwy.commons.providercache.validation



import android.content.Context

import com.goodwy.commons.providercache.ProviderCacheDatabase

import com.goodwy.commons.providercache.coordinator.CacheDomain

import com.goodwy.commons.providercache.coordinator.CacheMutationReason

import com.goodwy.commons.providercache.coordinator.DisplayCacheCoordinator

import com.goodwy.commons.providercache.coordinator.DisplayCacheMutationRequest

import com.goodwy.commons.providercache.debug.CacheFailureDomain

import com.goodwy.commons.providercache.debug.CacheFailureInjector

import com.goodwy.commons.providercache.debug.CacheFailurePoint

import com.goodwy.commons.providercache.debug.CachePerformanceMonitor

import com.goodwy.commons.providercache.display.PendingRecentDelta

import com.goodwy.commons.providercache.display.RecentGroupKey

import com.goodwy.commons.providercache.display.RecentGroupingMode

import com.goodwy.commons.providercache.display.RelationalRecentsReadMode

import com.goodwy.commons.providercache.entities.CacheMetadataDomain

import com.goodwy.commons.providercache.identity.ContactIdentityResolver

import com.goodwy.commons.providercache.metadata.CacheMetadataStore

import com.goodwy.commons.providercache.repository.CallLogRepository

import com.goodwy.commons.providercache.repository.ContactsRepository

import com.goodwy.commons.providercache.validation.legacy.LegacyCacheCounters

import com.goodwy.commons.providercache.validation.legacy.LegacyCacheGate

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.withContext



/**

 * Debug-only cache inspection and QA commands (Phase R).

 * Output is structured for Logcat copy/paste. Phone numbers are masked by default.

 */

object CacheDebugCommands {



    private const val LOG_PREFIX = "CacheDebug"



    fun maskPhone(value: String): String {

        if (value.isBlank()) return "(empty)"

        if (value.length <= 4) return "****"

        return "****${value.takeLast(4)}"

    }



    suspend fun dumpCacheMetadata(database: ProviderCacheDatabase): String =

        database.cacheMetadataDao().getAll().joinToString("\n") { entity ->

            "$LOG_PREFIX metadata domain=${entity.domain} rawVersion=${entity.rawVersion} " +

                "displayVersion=${entity.displayVersion} rows=${entity.rowCount} " +

                "dirty=${entity.dirty} repairRequired=${entity.repairRequired} " +

                "checksum=${entity.contentChecksum} reason=${entity.lastMutationReason}"

        }



    fun dumpCacheMetadataSync(database: ProviderCacheDatabase): String =

        kotlinx.coroutines.runBlocking { dumpCacheMetadata(database) }



    fun dumpCoordinatorState(coordinator: DisplayCacheCoordinator): String =

        "$LOG_PREFIX coordinator ${coordinator.dumpCoordinatorState()}"



    fun dumpPendingContactDeltas(

        visibleVersion: Long,

        targetVersion: Long,

        pendingFullReload: Boolean,

    ): String {

        val contiguous = targetVersion <= visibleVersion + 1 || visibleVersion < 0

        return "$LOG_PREFIX pendingDeltas domain=CONTACTS visibleVersion=$visibleVersion " +

            "targetVersion=$targetVersion count=${if (pendingFullReload) 1 else 0} " +

            "contiguous=$contiguous fullReload=$pendingFullReload"

    }



    fun dumpPendingRecentDeltas(

        visibleVersion: Long,

        targetVersion: Long,

        deltas: List<PendingRecentDelta>,

    ): String {

        val contiguous = deltas.isEmpty() || targetVersion > visibleVersion

        return "$LOG_PREFIX pendingDeltas domain=RECENTS visibleVersion=$visibleVersion " +

            "targetVersion=$targetVersion count=${deltas.size} contiguous=$contiguous"

    }



    suspend fun dumpContactIdentity(

        database: ProviderCacheDatabase,

        rawOrAggregateId: Long,

    ): String = withContext(Dispatchers.IO) {

        val resolver = ContactIdentityResolver(database)

        val identity = resolver.resolveFromRawId(rawOrAggregateId)

            ?: resolver.resolveFromAggregateId(rawOrAggregateId)

        if (identity == null) {

            "$LOG_PREFIX contactIdentity id=$rawOrAggregateId status=missing"

        } else {

            "$LOG_PREFIX contactIdentity aggregateId=${identity.aggregateContactId} " +

                "rawIds=${identity.rawContactIds} lookupKey=${identity.lookupKey.orEmpty()} " +

                "phones=${identity.phoneDigits.size}"

        }

    }



    suspend fun dumpRecentGroup(

        database: ProviderCacheDatabase,

        groupKey: String,

        groupingMode: Int,

    ): String = withContext(Dispatchers.IO) {

        val dao = database.recentDisplayCacheDao()

        val rows = dao.getOrdered(groupingMode, 500).filter {

            RecentGroupKey.fromEntity(it) == groupKey ||

                it.groupKey == groupKey

        }

        if (rows.isEmpty()) {

            return@withContext "$LOG_PREFIX recentGroup mode=$groupingMode key=${maskPhone(groupKey)} status=missing"

        }

        rows.joinToString("\n") { row ->

            "$LOG_PREFIX recentGroup mode=$groupingMode key=${maskPhone(groupKey)} " +

                "calls=${row.callCount} contactId=${row.contactID ?: "null"} " +

                "title=${row.displayName.ifEmpty { row.cachedName }} latestTs=${row.startTS}"

        }

    }



    suspend fun validateContactsLight(

        metadataStore: CacheMetadataStore,

        database: ProviderCacheDatabase,

    ): CacheValidator.ValidationReport = CacheValidator.validateLight(

        metadataStore = metadataStore,

        database = database,

        recentsGroupByContact = 0,

    ).let { report ->

        CacheValidator.ValidationReport(

            scope = report.scope,

            issues = report.issues.filter {

                it.domain == CacheMetadataDomain.CONTACTS_RAW ||

                    it.domain == CacheMetadataDomain.CONTACTS_DISPLAY

            },

        )

    }



    suspend fun validateRecentsLight(

        metadataStore: CacheMetadataStore,

        database: ProviderCacheDatabase,

        groupByContact: Int = 0,

    ): CacheValidator.ValidationReport = CacheValidator.validateLight(

        metadataStore = metadataStore,

        database = database,

        recentsGroupByContact = groupByContact,

    ).let { report ->

        CacheValidator.ValidationReport(

            scope = report.scope,

            issues = report.issues.filter {

                it.domain == CacheMetadataDomain.RECENTS_RAW ||

                    it.domain == CacheMetadataDomain.RECENTS_DISPLAY

            },

        )

    }



    suspend fun validateContactsDeep(

        context: Context,

        database: ProviderCacheDatabase,

        metadataStore: CacheMetadataStore,

        contactsRepository: ContactsRepository,

    ): String = withContext(Dispatchers.IO) {

        val light = validateContactsLight(metadataStore, database)

        buildString {

            appendLine("$LOG_PREFIX validateContactsDeep")

            append(formatLightValidationReport(light))

            appendLine("displayVersion=${contactsRepository.peekDisplayCacheVersion()}")

            appendLine("displayRows=${contactsRepository.peekDisplayCacheRowCount()}")

            appendLine("rawRows=${database.contactDao().getSummaryCount()}")

        }

    }



    suspend fun validateRecentsDeep(

        database: ProviderCacheDatabase,

        metadataStore: CacheMetadataStore,

        callLogRepository: CallLogRepository,

        groupByContact: Int = 0,

    ): String = withContext(Dispatchers.IO) {

        val light = validateRecentsLight(metadataStore, database, groupByContact)

        val deep = if (database.recentDisplayCacheDao().getCount(groupByContact) > 0) {

            RecentDisplayCacheValidator.validate(database, groupByContact)

        } else {

            null

        }

        buildString {

            appendLine("$LOG_PREFIX validateRecentsDeep")

            append(formatLightValidationReport(light))

            appendLine("displayVersion=${callLogRepository.recentsCacheVersion()}")

            appendLine("displayRows=${callLogRepository.recentsDisplayCacheRowCount()}")

            appendLine("rawRows=${database.callLogDao().getCount()}")

            if (deep != null) {

                appendLine("deepIssues=${deep.issues.size} valid=${deep.isValid}")

                deep.issues.take(5).forEach { issue ->

                    appendLine("  ${issue.reason} key=${maskPhone(issue.identityKey)} ${issue.detail}")

                }

            }

        }

    }



    suspend fun repairContactsDisplay(contactsRepository: ContactsRepository): String {

        contactsRepository.repairDisplayCacheFromMetadata("debug_command")

        return "$LOG_PREFIX repairContactsDisplay triggered"

    }



    suspend fun repairRecentsDisplay(

        callLogRepository: CallLogRepository,

    ): String {

        callLogRepository.repairDisplayCacheFromMetadata(

            reason = "debug_command",

            groupByContact = callLogRepository.peekGroupByContact(),

        )

        return "$LOG_PREFIX repairRecentsDisplay triggered"

    }



    suspend fun repairAllCaches(

        contactsRepository: ContactsRepository,

        callLogRepository: CallLogRepository,

    ): String {

        repairContactsDisplay(contactsRepository)

        repairRecentsDisplay(callLogRepository)

        return "$LOG_PREFIX repairAllCaches triggered"

    }



    suspend fun validateAllCachesLight(

        metadataStore: CacheMetadataStore,

        database: ProviderCacheDatabase,

        recentsGroupByContact: Int = 0,

    ): Map<String, Boolean> {

        val report = CacheValidator.validateLight(metadataStore, database, recentsGroupByContact)

        return CacheMetadataDomain.ALL.associateWith { domain ->

            domain !in report.domainsNeedingRepair()

        }

    }



    fun formatLightValidationReport(report: CacheValidator.ValidationReport): String = buildString {

        appendLine("$LOG_PREFIX validation scope=${report.scope} issues=${report.issues.size}")

        if (report.issues.isEmpty()) {

            appendLine("ok")

            return@buildString

        }

        report.issues.forEach { issue ->

            appendLine("${issue.domain} ${issue.reason} ${issue.detail}")

        }

    }



    fun planMetadataRepairDomains(domains: Set<String>): List<String> {

        val planned = mutableListOf<String>()

        if (CacheMetadataDomain.CONTACTS_RAW in domains) {

            planned += CacheMetadataDomain.CONTACTS_RAW

        } else if (CacheMetadataDomain.CONTACTS_DISPLAY in domains) {

            planned += CacheMetadataDomain.CONTACTS_DISPLAY

        }

        if (CacheMetadataDomain.RECENTS_RAW in domains) {

            planned += CacheMetadataDomain.RECENTS_RAW

        } else if (CacheMetadataDomain.RECENTS_DISPLAY in domains) {

            planned += CacheMetadataDomain.RECENTS_DISPLAY

        }

        return planned

    }



    suspend fun buildFullReport(

        context: Context,

        database: ProviderCacheDatabase,

        metadataStore: CacheMetadataStore,

        contactsRepository: ContactsRepository,

        callLogRepository: CallLogRepository,

        coordinator: DisplayCacheCoordinator? = null,

    ): String = withContext(Dispatchers.IO) {

        val groupByContact = callLogRepository.peekGroupByContact()

        val light = CacheValidator.validateLight(

            metadataStore = metadataStore,

            database = database,

            recentsGroupByContact = if (groupByContact) 1 else 0,

        )

        buildString {

            appendLine("=== cache_metadata ===")

            appendLine(dumpCacheMetadata(database).ifEmpty { "(empty)" })

            appendLine()

            if (coordinator != null) {

                appendLine("=== coordinator ===")

                appendLine(dumpCoordinatorState(coordinator))

                appendLine()

            }

            appendLine("=== light_validation ===")

            append(formatLightValidationReport(light))

            appendLine()

            appendLine("=== display_versions ===")

            appendLine(

                "contactsDisplay=${contactsRepository.peekDisplayCacheVersion()} " +

                    "rows=${contactsRepository.peekDisplayCacheRowCount()}",

            )

            appendLine(

                "recentsDisplay=${callLogRepository.recentsCacheVersion()} " +

                    "rows=${callLogRepository.recentsDisplayCacheRowCount()}",

            )

            appendLine()

            appendLine("=== legacy_authority ===")

            appendLine(dumpLegacyCacheAuthority())

            appendLine()

            appendLine("=== performance ===")

            appendLine("reconcileCounts=${CachePerformanceMonitor.dumpReconcileCounts()}")

            appendLine()

            appendLine("=== row_counts ===")

            appendLine("contact_summaries=${database.contactDao().getSummaryCount()}")

            appendLine("contact_display=${database.contactDisplayCacheDao().getCount()}")

            appendLine("call_log=${database.callLogDao().getCount()}")

            appendLine("recent_display_per_phone=${database.recentDisplayCacheDao().getCount(0)}")

            appendLine("recent_display_per_contact=${database.recentDisplayCacheDao().getCount(1)}")

            appendLine()

            appendLine("=== repair_plan ===")

            appendLine(planMetadataRepairDomains(light.domainsNeedingRepair()).joinToString(",").ifEmpty { "none" })

        }

    }



    fun sampleMutationRequest(

        mutationId: Long,

        domain: CacheDomain = CacheDomain.CONTACTS,

        reason: CacheMutationReason = CacheMutationReason.MANUAL_REFRESH,

    ) = DisplayCacheMutationRequest(

        mutationId = mutationId,

        domain = domain,

        reason = reason,

    )



    val metadataDomains: List<String> = CacheMetadataDomain.ALL



    fun dumpLegacyCacheAuthority(): String {

        val snap = LegacyCacheGate.snapshot()

        return "$LOG_PREFIX legacyAuthority contactsRoomAuthoritative=${snap.contactsRoomAuthoritative} " +

            "recentsRoomAuthoritative=${snap.recentsRoomAuthoritative} ${LegacyCacheCounters.dump()}"

    }



    fun evictLegacyCaches(context: Context) {

        LegacyCacheEvictorBridge.evictIfAuthoritative(context)

    }



    suspend fun simulateDirtyContactsMetadata(metadataStore: CacheMetadataStore): String {

        metadataStore.markDirty(CacheMetadataDomain.CONTACTS_DISPLAY, 0L, "debug_simulate")

        return "$LOG_PREFIX simulateDirtyContactsMetadata done"

    }



    suspend fun simulateDirtyRecentsMetadata(metadataStore: CacheMetadataStore): String {

        metadataStore.markDirty(CacheMetadataDomain.RECENTS_DISPLAY, 0L, "debug_simulate")

        return "$LOG_PREFIX simulateDirtyRecentsMetadata done"

    }



    fun simulateInterruptedContactsMutation() {

        CacheFailureInjector.arm(CacheFailureDomain.CONTACTS, CacheFailurePoint.BEFORE_VERSION_COMMIT)

    }



    fun simulateInterruptedRecentsMutation() {

        CacheFailureInjector.arm(CacheFailureDomain.RECENTS, CacheFailurePoint.BEFORE_VERSION_COMMIT)

    }



    fun resetStartupRecoveryForDebug() {

        CacheRepairOrchestrator.resetStartupRecoveryForDebug()

        CachePerformanceMonitor.resetForDebug()

        LegacyCacheCounters.reset()

        CacheFailureInjector.clear()

    }

    suspend fun dumpRelationalRecentGroup(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        key: String,
    ): String = RecentGroupingDebugCommands.dumpRelationalRecentGroup(database, mode, key)

    suspend fun dumpLegacyRecentGroup(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        key: String,
    ): String = RecentGroupingDebugCommands.dumpLegacyRecentGroup(database, mode, key)

    suspend fun compareRecentGrouping(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
    ): String = RecentGroupingDebugCommands.compareRecentGrouping(database, mode)

    suspend fun compareRecentGroup(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        semanticKey: String,
    ): String = RecentGroupingDebugCommands.compareRecentGroup(database, mode, semanticKey)

    suspend fun validateRelationalRecentGrouping(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
    ): String = RecentGroupingDebugCommands.validateRelationalRecentGrouping(database, mode)

    fun dumpRecentAuthorityMismatches(): String =
        RecentGroupingDebugCommands.dumpRecentAuthorityMismatches()

    fun clearRecentAuthorityMismatches(): String =
        RecentGroupingDebugCommands.clearRecentAuthorityMismatches()

    suspend fun dumpRecentCacheInvariant(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode = RecentGroupingMode.BY_NUMBER,
    ): String = RecentGroupingDebugCommands.dumpRecentCacheInvariant(database, mode)

    suspend fun validateRecentDisplayRelationalConsistency(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
    ): String = RecentGroupingDebugCommands.validateRecentDisplayRelationalConsistency(database, mode)

    fun setRelationalReadModeForDebug(mode: RelationalRecentsReadMode): String =
        RecentGroupingDebugCommands.setRelationalReadModeForDebug(mode)

}



/**

 * App-layer bridge so commons debug commands can trigger legacy eviction without a compile dependency cycle.

 * Registered from [com.android.dialer.providercache.ProviderCacheDebugSupport.install].

 */

object LegacyCacheEvictorBridge {

    var evictIfAuthoritative: (Context) -> Unit = {}

}


