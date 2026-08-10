package com.goodwy.commons.providercache.transaction

import androidx.room.withTransaction
import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.debug.CacheFailureInjector
import com.goodwy.commons.providercache.debug.CacheFailureDomain
import com.goodwy.commons.providercache.debug.CacheFailurePoint
import com.goodwy.commons.providercache.debug.CacheMutationLogger
import com.goodwy.commons.providercache.display.AffectedRecentGroups
import com.goodwy.commons.providercache.display.CanonicalPhoneNumberResolver
import com.goodwy.commons.providercache.display.RecentGroupRelationalBuilder
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.display.RecentGroupingReplacement
import com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity

/**
 * Cross-table Room transactions for cache mutations.
 * Provider reads must complete before calling these methods.
 */
object ProviderCacheTransactions {
    suspend fun purgeContactRoomCaches(
        database: ProviderCacheDatabase,
        rawIds: List<Int>,
        contactIds: List<Int>,
        mutationId: Long = 0L,
    ) {
        if (rawIds.isEmpty()) return
        if (mutationId > 0L) {
            CacheMutationLogger.mutationStage(mutationId, CacheMutationLogger.Stage.RAW_WRITE)
        }
        database.withTransaction {
            val contactDao = database.contactDao()
            val displayCacheDao = database.contactDisplayCacheDao()
            val searchIndexDao = database.contactSearchIndexDao()
            val phoneIndexDao = database.contactPhoneIndexDao()
            contactDao.deleteSummariesByRawIds(rawIds)
            if (contactIds.isNotEmpty()) {
                contactDao.deleteSummariesByIds(contactIds)
                contactDao.deleteDetailsByContactIds(contactIds)
                displayCacheDao.deleteByContactIds(contactIds)
                searchIndexDao.deleteForContacts(contactIds)
                phoneIndexDao.deleteForContacts(contactIds)
            }
            displayCacheDao.deleteByRawIds(rawIds)
            CacheFailureInjector.maybeThrow(CacheFailureDomain.CONTACTS, CacheFailurePoint.AFTER_RAW_WRITE)
        }
    }

    suspend fun purgeCallLogRoomCaches(
        database: ProviderCacheDatabase,
        callLogIds: List<Int>,
        groupKeys: List<String>,
        displayRowCallIds: List<Int>,
        mutationId: Long = 0L,
    ) {
        if (callLogIds.isEmpty() && displayRowCallIds.isEmpty()) return
        if (mutationId > 0L) {
            CacheMutationLogger.mutationStage(mutationId, CacheMutationLogger.Stage.RAW_WRITE)
        }
        database.withTransaction {
            val callLogDao = database.callLogDao()
            val recentDisplayDao = database.recentDisplayCacheDao()
            callLogIds.distinct().chunked(200).forEach { chunk ->
                callLogDao.deleteByIds(chunk)
            }
            groupKeys.distinct().forEach { groupKey ->
                recentDisplayDao.deleteByGroupKeyForMode(groupKey, 0)
                recentDisplayDao.deleteByGroupKeyForMode(groupKey, 1)
            }
            displayRowCallIds.distinct().chunked(200).forEach { chunk ->
                recentDisplayDao.deleteByCallIds(chunk)
            }
            CacheFailureInjector.maybeThrow(CacheFailureDomain.RECENTS, CacheFailurePoint.AFTER_RAW_WRITE)
        }
    }

    suspend fun clearAllCallLogRoomCaches(
        database: ProviderCacheDatabase,
        mutationId: Long = 0L,
    ) {
        if (mutationId > 0L) {
            CacheMutationLogger.mutationStage(mutationId, CacheMutationLogger.Stage.RAW_WRITE)
        }
        database.withTransaction {
            database.callLogDao().clearAll()
            database.recentDisplayCacheDao().clearForMode(0)
            database.recentDisplayCacheDao().clearForMode(1)
            database.recentGroupCallDao().deleteMode(0)
            database.recentGroupCallDao().deleteMode(1)
            database.recentGroupNumberDao().deleteMode(0)
            database.recentGroupNumberDao().deleteMode(1)
            database.recentGroupDao().deleteMode(0)
            database.recentGroupDao().deleteMode(1)
            CacheFailureInjector.maybeThrow(CacheFailureDomain.RECENTS, CacheFailurePoint.AFTER_RAW_WRITE)
        }
    }

    /**
     * Atomically replaces relational grouping tables for one mode (dual-write path).
     */
    suspend fun replaceRecentGroupingTables(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        relational: RecentGroupRelationalBuilder.BuildResult,
    ) {
        val modeValue = mode.dbValue
        database.withTransaction {
            val groupDao = database.recentGroupDao()
            val callDao = database.recentGroupCallDao()
            val numberDao = database.recentGroupNumberDao()
            callDao.deleteMode(modeValue)
            numberDao.deleteMode(modeValue)
            groupDao.deleteMode(modeValue)
            if (relational.groups.isNotEmpty()) groupDao.upsertAll(relational.groups)
            if (relational.calls.isNotEmpty()) callDao.insertAll(relational.calls)
            if (relational.numbers.isNotEmpty()) numberDao.insertAll(relational.numbers)
        }
    }

    /**
     * Atomically replaces relational grouping tables and the display snapshot for one mode.
     * Provider/contact reads must finish before calling this method.
     * Display version commit remains with [DisplayCacheCoordinator].
     */
    suspend fun replaceRecentGroupingMode(
        database: ProviderCacheDatabase,
        replacement: RecentGroupingReplacement,
        mutationId: Long = 0L,
    ) {
        val mode = replacement.mode.dbValue
        if (mutationId > 0L) {
            CacheMutationLogger.mutationStage(mutationId, CacheMutationLogger.Stage.DISPLAY_WRITE)
        }
        database.withTransaction {
            val groupDao = database.recentGroupDao()
            val callDao = database.recentGroupCallDao()
            val numberDao = database.recentGroupNumberDao()
            val displayDao = database.recentDisplayCacheDao()

            callDao.deleteMode(mode)
            numberDao.deleteMode(mode)
            groupDao.deleteMode(mode)

            if (replacement.relational.groups.isNotEmpty()) {
                groupDao.upsertAll(replacement.relational.groups)
            }
            if (replacement.relational.calls.isNotEmpty()) {
                callDao.insertAll(replacement.relational.calls)
            }
            if (replacement.relational.numbers.isNotEmpty()) {
                numberDao.insertAll(replacement.relational.numbers)
            }

            displayDao.clearForMode(mode)
            if (replacement.displayRows.isNotEmpty()) {
                displayDao.insertAll(replacement.displayRows)
                displayDao.trimToLimit(mode, replacement.displayLimit)
            }
            CacheFailureInjector.maybeThrow(CacheFailureDomain.RECENTS, CacheFailurePoint.AFTER_DISPLAY_WRITE)
        }
    }

    /**
     * Replaces multiple affected groups atomically when membership may move between groups.
     * Do not use [replaceRecentGroup] for contact merge/split/delete paths.
     */
    suspend fun replaceAffectedRecentGroups(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        affected: AffectedRecentGroups,
        relational: RecentGroupRelationalBuilder.BuildResult,
        displayRows: List<RecentDisplayCacheEntity>,
    ) {
        val modeValue = mode.dbValue
        database.withTransaction {
            val groupDao = database.recentGroupDao()
            val callDao = database.recentGroupCallDao()
            val numberDao = database.recentGroupNumberDao()
            val displayDao = database.recentDisplayCacheDao()

            affected.allGroupKeys.forEach { groupKey ->
                callDao.deleteForGroup(modeValue, groupKey)
                numberDao.deleteForGroup(modeValue, groupKey)
                groupDao.deleteGroup(modeValue, groupKey)
                displayDao.deleteByGroupKeyForMode(groupKey, modeValue)
                val legacyKey = groupKey.removePrefix("number:").removePrefix("contact:")
                if (legacyKey != groupKey) {
                    displayDao.deleteByGroupKeyForMode(legacyKey, modeValue)
                }
            }

            val touchedKeys = relational.groups.map { it.groupKey }.toSet()
            if (relational.groups.isNotEmpty()) {
                groupDao.upsertAll(relational.groups.filter { it.groupKey in touchedKeys })
            }
            if (relational.calls.isNotEmpty()) {
                callDao.insertAll(relational.calls.filter { it.groupKey in touchedKeys })
            }
            if (relational.numbers.isNotEmpty()) {
                numberDao.insertAll(relational.numbers.filter { it.groupKey in touchedKeys })
            }
            if (displayRows.isNotEmpty()) {
                displayDao.insertAll(displayRows)
            }
        }
    }

    /**
     * Updates relational grouping tables for affected groups without touching [recent_display_cache].
     * Use when the display snapshot was already updated by the legacy builder path.
     */
    suspend fun replaceAffectedRecentGroupsRelationalOnly(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        affected: AffectedRecentGroups,
        relational: RecentGroupRelationalBuilder.BuildResult,
    ) {
        val modeValue = mode.dbValue
        database.withTransaction {
            val groupDao = database.recentGroupDao()
            val callDao = database.recentGroupCallDao()
            val numberDao = database.recentGroupNumberDao()

            affected.allGroupKeys.forEach { groupKey ->
                callDao.deleteForGroup(modeValue, groupKey)
                numberDao.deleteForGroup(modeValue, groupKey)
                groupDao.deleteGroup(modeValue, groupKey)
            }

            val touchedKeys = relational.groups.map { it.groupKey }.toSet()
            if (relational.groups.isNotEmpty()) {
                groupDao.upsertAll(relational.groups.filter { it.groupKey in touchedKeys })
            }
            if (relational.calls.isNotEmpty()) {
                callDao.insertAll(relational.calls.filter { it.groupKey in touchedKeys })
            }
            if (relational.numbers.isNotEmpty()) {
                numberDao.insertAll(relational.numbers.filter { it.groupKey in touchedKeys })
            }
        }
    }

    /**
     * Replaces one affected group without clearing the whole mode (single-group incremental path only).
     */
    suspend fun replaceRecentGroup(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        groupKey: String,
        relational: RecentGroupRelationalBuilder.BuildResult,
        displayRows: List<RecentDisplayCacheEntity>,
    ) {
        val modeValue = mode.dbValue
        database.withTransaction {
            val groupDao = database.recentGroupDao()
            val callDao = database.recentGroupCallDao()
            val numberDao = database.recentGroupNumberDao()
            val displayDao = database.recentDisplayCacheDao()

            callDao.deleteForGroup(modeValue, groupKey)
            numberDao.deleteForGroup(modeValue, groupKey)
            groupDao.deleteGroup(modeValue, groupKey)
            displayDao.deleteByGroupKeyForMode(groupKey, modeValue)
            // Legacy rows may still use digit-only keys before relational authority.
            val legacyKey = groupKey.removePrefix("number:").removePrefix("contact:")
            if (legacyKey != groupKey) {
                displayDao.deleteByGroupKeyForMode(legacyKey, modeValue)
            }

            val group = relational.groups.singleOrNull { it.groupKey == groupKey }
            if (group != null) {
                groupDao.upsertAll(listOf(group))
                val calls = relational.calls.filter { it.groupKey == groupKey }
                val numbers = relational.numbers.filter { it.groupKey == groupKey }
                if (calls.isNotEmpty()) callDao.insertAll(calls)
                if (numbers.isNotEmpty()) numberDao.insertAll(numbers)
                displayRows.filter {
                    it.groupKey == groupKey ||
                        it.groupKey == legacyKey ||
                        CanonicalPhoneNumberResolver.legacyDisplayGroupKey(it.groupKey, mode) == groupKey
                }.forEach { displayDao.insertAll(listOf(it)) }
            }
        }
    }
}