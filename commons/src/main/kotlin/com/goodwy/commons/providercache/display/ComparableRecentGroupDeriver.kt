package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.entities.CallLogEntity
import com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity

/**
 * Derives [ComparableRecentGroup] snapshots from relational tables or legacy display cache.
 */
object ComparableRecentGroupDeriver {

    suspend fun fromRelational(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        rowLimit: Int = 10_000,
    ): Map<String, ComparableRecentGroup> {
        val groupDao = database.recentGroupDao()
        val callDao = database.recentGroupCallDao()
        val numberDao = database.recentGroupNumberDao()
        return groupDao.getGroups(mode.dbValue)
            .take(rowLimit)
            .associate { group ->
                val semanticKey = ComparableRecentGroup.semanticKeyFromStoredGroupKey(
                    group.groupKey,
                    group.displayContactId,
                )
                val callIds = callDao.getCallIdsForGroup(mode.dbValue, group.groupKey).toSet()
                val numbers = numberDao.getNumbersForGroup(mode.dbValue, group.groupKey).toSet()
                semanticKey to ComparableRecentGroup(
                    semanticKey = semanticKey,
                    callIds = callIds,
                    displayContactId = group.displayContactId,
                    normalizedNumbers = numbers,
                    callCount = group.callCount,
                    latestCallId = group.latestCallId,
                    latestTimestamp = group.latestTimestamp,
                    primaryNumber = group.primaryNumber,
                    displayOrder = group.displayOrder,
                )
            }
    }

    /**
     * Aggregates legacy [recent_display_cache] rows into semantic groups by re-resolving
     * each member call's identity (required for exact BY_CONTACT comparison).
     */
    suspend fun fromDisplayCache(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        rowLimit: Int = 10_000,
    ): Map<String, ComparableRecentGroup> {
        val context = RecentGroupBuildContextLoader.load(database)
        val displayRows = database.recentDisplayCacheDao().getOrdered(mode.dbValue, rowLimit)
        if (displayRows.isEmpty()) return emptyMap()

        val callIds = displayRows.flatMap { it.groupedCallIds.toCallIdSet() }.map { it.toInt() }.distinct()
        val callsById = database.callLogDao().getByCallIds(callIds).associateBy { it.callId }

        val buckets = linkedMapOf<String, MutableList<CallLogEntity>>()
        displayRows.forEach { row ->
            row.groupedCallIds.toCallIdSet().forEach { callIdLong ->
                val call = callsById[callIdLong.toInt()] ?: return@forEach
                val identity = RecentGroupIdentityResolver.resolve(call, mode, context)
                val semanticKey = ComparableRecentGroup.semanticKeyFromStoredGroupKey(
                    identity.groupKey,
                    identity.displayContactId,
                )
                buckets.getOrPut(semanticKey) { mutableListOf() }.add(call)
            }
        }

        return buckets.mapValues { (semanticKey, members) ->
            toComparableGroup(semanticKey, members, mode, context)
        }
    }

    /**
     * Derives semantic groups directly from raw call-log rows (identity-only, no display cache).
     */
    fun fromRawCalls(
        calls: List<CallLogEntity>,
        mode: RecentGroupingMode,
        context: RecentGroupIdentityResolver.Context,
        limit: Int = Int.MAX_VALUE,
    ): Map<String, ComparableRecentGroup> {
        val buckets = linkedMapOf<String, MutableList<CallLogEntity>>()
        calls.forEach { call ->
            val identity = RecentGroupIdentityResolver.resolve(call, mode, context)
            val semanticKey = ComparableRecentGroup.semanticKeyFromStoredGroupKey(
                identity.groupKey,
                identity.displayContactId,
            )
            buckets.getOrPut(semanticKey) { mutableListOf() }.add(call)
        }
        return buckets.entries
            .sortedWith(groupSort)
            .take(limit)
            .mapIndexed { index, (semanticKey, members) ->
                semanticKey to toComparableGroup(semanticKey, members.sortedWith(callSort), mode, context)
                    .copy(displayOrder = index)
            }
            .toMap()
    }

    fun fromDisplayEntities(
        rows: List<RecentDisplayCacheEntity>,
        callsById: Map<Int, CallLogEntity>,
        mode: RecentGroupingMode,
        context: RecentGroupIdentityResolver.Context,
    ): Map<String, ComparableRecentGroup> {
        val buckets = linkedMapOf<String, MutableList<CallLogEntity>>()
        rows.forEach { row ->
            row.groupedCallIds.toCallIdSet().forEach { callIdLong ->
                val call = callsById[callIdLong.toInt()] ?: return@forEach
                val identity = RecentGroupIdentityResolver.resolve(call, mode, context)
                val semanticKey = ComparableRecentGroup.semanticKeyFromStoredGroupKey(
                    identity.groupKey,
                    identity.displayContactId,
                )
                buckets.getOrPut(semanticKey) { mutableListOf() }.add(call)
            }
        }
        return buckets.mapValues { (semanticKey, members) ->
            toComparableGroup(semanticKey, members, mode, context)
        }
    }

    private fun toComparableGroup(
        semanticKey: String,
        members: List<CallLogEntity>,
        mode: RecentGroupingMode,
        context: RecentGroupIdentityResolver.Context,
    ): ComparableRecentGroup {
        val sorted = members.sortedWith(callSort)
        val latest = sorted.first()
        val identity = RecentGroupIdentityResolver.resolve(latest, mode, context)
        val numbers = sorted.map {
            CanonicalPhoneNumberResolver.canonicalDigits(it.normalizedNumber, it.phoneNumber)
        }.filter { it.isNotEmpty() }.toSet()
        return ComparableRecentGroup(
            semanticKey = semanticKey,
            callIds = sorted.map { it.callId.toLong() }.toSet(),
            displayContactId = identity.displayContactId,
            normalizedNumbers = numbers,
            callCount = sorted.size,
            latestCallId = latest.callId.toLong(),
            latestTimestamp = latest.startTS,
            primaryNumber = latest.phoneNumber.ifEmpty { latest.normalizedNumber },
        )
    }

    private fun String.toCallIdSet(): Set<Long> =
        split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    private val callSort = compareByDescending<CallLogEntity> { it.startTS }
        .thenByDescending { it.callId }
        .thenBy { it.phoneNumber }

    private val groupSort = compareByDescending<Map.Entry<String, MutableList<CallLogEntity>>> { (_, members) ->
        members.maxOfOrNull { it.startTS } ?: 0L
    }.thenByDescending { (_, members) ->
        members.maxOfOrNull { it.callId } ?: 0
    }.thenBy { (key, _) -> key }
}
