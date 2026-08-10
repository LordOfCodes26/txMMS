package com.goodwy.commons.providercache.debug

import android.content.Context
import com.goodwy.commons.providercache.ProviderCache
import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.display.DisplayCacheReadinessTracker
import com.goodwy.commons.providercache.display.RecentAuthorityMismatchStore
import com.goodwy.commons.providercache.display.RecentDisplayBuildAuthorityResolver
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.display.RelationalReadAuthorityGate
import com.goodwy.commons.providercache.entities.CacheMetadataDomain
import com.goodwy.commons.providercache.grouping.RecentAuthorityPathLogger
import com.goodwy.commons.providercache.model.ProviderCacheLoadState
import com.goodwy.commons.providercache.repository.CallLogRepository
import com.goodwy.commons.providercache.repository.ContactsRepository
import com.goodwy.commons.providercache.validation.RecentDisplayRelationalConsistencyValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ProviderCacheDiagnosticsReader {

    suspend fun read(
        contactsRepository: ContactsRepository,
        callLogRepository: CallLogRepository,
        context: Context,
        activeSearchQuery: String = "",
        pagingGeneration: Int = 0,
        phoneIndexReady: Boolean = false,
        recentsVisibleVersion: Long = 0L,
        runRelationalConsistency: Boolean = false,
    ): ProviderCacheDiagnostics = withContext(Dispatchers.IO) {
        val db = ProviderCacheDatabase.getInstance(context.applicationContext)
        val summaryCount = db.contactDao().getSummaryCount()
        val phoneIndexCount = db.contactPhoneIndexDao().getCount()
        val searchIndexCount = db.contactSearchIndexDao().getCount()
        val callLogCount = db.callLogDao().getCount()
        val contactDisplayCount = db.contactDisplayCacheDao().getCount()
        val recentDisplayPerPhone = db.recentDisplayCacheDao().getCount(0)
        val recentDisplayPerContact = db.recentDisplayCacheDao().getCount(1)
        val contactsLoadState = contactsRepository.loadState.value
        val callLogLoadState = callLogRepository.loadState.value
        val soak = RelationalReadAuthorityGate.soakSnapshot()
        val metadataReady = runCatching { ProviderCache.isInitialized() }.getOrDefault(false)
        if (runRelationalConsistency) {
            val mode = if (callLogRepository.peekGroupByContact()) {
                RecentGroupingMode.BY_CONTACT
            } else {
                RecentGroupingMode.BY_NUMBER
            }
            val consistency = RecentDisplayRelationalConsistencyValidator.validate(db, mode)
            if (consistency.deferred) {
                RecentAuthorityMismatchStore.setLastConsistencyDeferred(consistency.summary())
            } else {
                RecentAuthorityMismatchStore.setLastConsistencyResult(
                    consistency.valid,
                    if (consistency.valid) "PASS" else "FAIL",
                )
            }
        }
        val lastMismatch = RecentAuthorityMismatchStore.lastOrNull()
        val consistencyLabel = RecentAuthorityMismatchStore.lastConsistencyLabel()
        ProviderCacheDiagnostics(
            contactsLoadState = contactsLoadState,
            callLogLoadState = callLogLoadState,
            contactsSource = contactsLoadState.toDataSource(),
            callLogSource = callLogLoadState.toDataSource(),
            contactSummaryCount = summaryCount,
            phoneIndexCount = phoneIndexCount,
            searchIndexCount = searchIndexCount,
            callLogCount = callLogCount,
            contactDisplayCount = contactDisplayCount,
            recentDisplayPerPhoneCount = recentDisplayPerPhone,
            recentDisplayPerContactCount = recentDisplayPerContact,
            contactsDisplayVersion = contactsRepository.peekDisplayCacheVersion(),
            recentsDisplayVersion = callLogRepository.recentsCacheVersion(),
            phoneIndexReady = phoneIndexReady,
            activeSearchQuery = activeSearchQuery,
            pagingGeneration = pagingGeneration,
            contactsReadiness = DisplayCacheReadinessTracker.contactsReadiness().name,
            recentsReadiness = DisplayCacheReadinessTracker.recentsReadiness().name,
            recentsVisibleVersion = recentsVisibleVersion,
            contactsRepairRequired = if (metadataReady) {
                ProviderCache.cacheMetadataStore.peekContactsDisplayRepairRequired()
            } else {
                false
            },
            recentsRepairRequired = if (metadataReady) {
                ProviderCache.cacheMetadataStore.peekRecentsDisplayRepairRequired()
            } else {
                false
            },
            contactsFallbackActive = DisplayCacheReadinessTracker.isContactsProviderFallbackActive(),
            recentsFallbackActive = DisplayCacheReadinessTracker.isRecentsProviderFallbackActive(),
            lastContactsMutationId = if (metadataReady) {
                ProviderCache.displayCacheCoordinator.peekLatestCommittedContactsMutationId()
            } else {
                0L
            },
            lastRecentsMutationId = if (metadataReady) {
                ProviderCache.displayCacheCoordinator.peekLatestCommittedRecentsMutationId()
            } else {
                0L
            },
            compareTotal = soak.compareTotal,
            compareMismatch = soak.compareMismatch,
            displayMismatch = soak.displayMismatch,
            dualWriteTotal = soak.dualWriteTotal,
            dualWriteMismatch = soak.dualWriteMismatch,
            checksumCompareTotal = soak.checksumCompareTotal,
            checksumMismatch = soak.checksumMismatch,
            incrementalFallbackCount = soak.incrementalFallbackCount,
            noOpMutationCount = soak.noOpMutationCount,
            displayOnlyMutationCount = soak.displayOnlyMutationCount,
            membershipChangedCount = soak.membershipChangedCount,
            authorityPathViolations = RecentAuthorityPathLogger.violationCount(),
            soakSessionPanel = RecentsAuthoritySoakSessionManager.toQaPanelSection(),
            byContactAuthority = RecentDisplayBuildAuthorityResolver
                .resolveForFullBuild(RecentGroupingMode.BY_CONTACT).name,
            byNumberAuthority = RecentDisplayBuildAuthorityResolver
                .resolveForFullBuild(RecentGroupingMode.BY_NUMBER).name,
            databaseVersion = 16,
            dirtyRecents = if (metadataReady) {
                db.cacheMetadataDao().getByDomain(CacheMetadataDomain.RECENTS_DISPLAY)?.dirty == true
            } else {
                false
            },
            relationalConsistency = consistencyLabel,
            lastMismatchAtMs = lastMismatch?.capturedAtMs ?: 0L,
            lastMismatchReason = RecentsAuthoritySoakSessionManager.maskSensitive(
                lastMismatch?.mismatchReason.orEmpty(),
            ),
            lastMismatchMode = lastMismatch?.groupingMode?.name.orEmpty(),
            lastMismatchKey = RecentsAuthoritySoakSessionManager.maskSensitive(
                lastMismatch?.semanticGroupKey.orEmpty(),
            ),
        )
    }

    private fun ProviderCacheLoadState.toDataSource(): ProviderCacheDataSource = when (this) {
        ProviderCacheLoadState.ShowingRoomCache -> ProviderCacheDataSource.ROOM
        ProviderCacheLoadState.LoadingFirstPage,
        ProviderCacheLoadState.ShowingProviderFallback,
        ProviderCacheLoadState.RebuildingCache,
        ProviderCacheLoadState.Error,
        -> ProviderCacheDataSource.PROVIDER_FALLBACK
    }
}
