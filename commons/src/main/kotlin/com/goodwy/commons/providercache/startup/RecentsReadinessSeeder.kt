package com.goodwy.commons.providercache.startup

import android.util.Log
import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.display.DisplayCacheReadiness
import com.goodwy.commons.providercache.display.DisplayCacheReadinessTracker
import com.goodwy.commons.providercache.display.RawCacheReadiness
import com.goodwy.commons.providercache.entities.CacheMetadataDomain
import com.goodwy.commons.providercache.metadata.CacheMetadataStore
import com.goodwy.commons.providercache.repository.CallLogRepository

/**
 * Seeds in-memory Recents DISPLAY readiness from persisted [cache_metadata] before UI/repair decisions.
 */
object RecentsReadinessSeeder {

    private const val TAG = "RecentsReadinessSeed"

    suspend fun seedFromPersistedMetadata(
        database: ProviderCacheDatabase,
        metadataStore: CacheMetadataStore,
        callLogRepository: CallLogRepository,
    ) {
        StartupSessionLogger.log(domain = "RECENTS", stage = "DISPLAY_METADATA_READ")
        metadataStore.ensureSeeded()
        val recentsMeta = database.cacheMetadataDao().getByDomain(CacheMetadataDomain.RECENTS_DISPLAY)
        val rawMeta = database.cacheMetadataDao().getByDomain(CacheMetadataDomain.RECENTS_RAW)
        val mode = if (callLogRepository.peekGroupByContact()) 1 else 0
        val displayRows = database.recentDisplayCacheDao().getCount(mode)
        val displayVersion = recentsMeta?.displayVersion ?: metadataStore.peekRecentsDisplayVersion()
        val dirty = recentsMeta?.dirty ?: metadataStore.peekRecentsDisplayDirty()
        val repairRequired = recentsMeta?.repairRequired ?: metadataStore.peekRecentsDisplayRepairRequired()
        val rawDirty = rawMeta?.dirty == true
        val rawRepairRequired = rawMeta?.repairRequired == true

        DisplayCacheReadinessTracker.setRecentsRawReadiness(
            when {
                rawRepairRequired -> RawCacheReadiness.REPAIR_REQUIRED
                rawDirty -> RawCacheReadiness.DIRTY
                else -> RawCacheReadiness.CLEAN
            },
        )

        val seededReadiness = when {
            displayVersion > 0L && !dirty && !repairRequired && displayRows > 0 ->
                DisplayCacheReadiness.READY_WITH_DATA
            displayVersion > 0L && !dirty && !repairRequired && displayRows == 0 ->
                DisplayCacheReadiness.READY_EMPTY
            else -> DisplayCacheReadiness.NOT_STARTED
        }

        if (seededReadiness != DisplayCacheReadiness.NOT_STARTED) {
            DisplayCacheReadinessTracker.seedRecentsDisplay(seededReadiness)
            DisplayCacheReadinessTracker.setRecentsProviderFallbackActive(false)
            callLogRepository.noteWarmDisplayCacheSeeded(displayRows, displayVersion)
        }

        val msg =
            "recentsReadinessSeed version=$displayVersion rows=$displayRows dirty=$dirty " +
                "repairRequired=$repairRequired rawDirty=$rawDirty rawRepairRequired=$rawRepairRequired " +
                "result=${seededReadiness.name}"
        Log.d(TAG, msg)
        StartupSessionLogger.log(
            domain = "RECENTS",
            stage = "DISPLAY_READINESS_SEEDED",
            extra = msg,
        )
    }
}
