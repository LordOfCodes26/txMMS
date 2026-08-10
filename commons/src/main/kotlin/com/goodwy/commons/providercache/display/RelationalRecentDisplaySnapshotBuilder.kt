package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.entities.CallLogEntity
import com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity

/**
 * Builds [RecentDisplayCacheEntity] rows from relational grouping tables — no legacy SQL,
 * no parsing [RecentDisplayCacheEntity.grouped_call_ids] for membership.
 */
object RelationalRecentDisplaySnapshotBuilder {

    suspend fun buildDisplayRowsFromRelationalGroups(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        groupByContactFlag: Boolean,
        rowLimit: Int = 10_000,
    ): List<RecentDisplayCacheEntity> {
        val groups = database.recentGroupDao().getGroups(mode.dbValue).take(rowLimit)
        if (groups.isEmpty()) return emptyList()
        val callDao = database.recentGroupCallDao()
        return buildDisplayRows(
            database = database,
            mode = mode,
            groupByContactFlag = groupByContactFlag,
            groups = groups,
            memberIdsForGroup = { groupKey -> callDao.getCallIdsForGroup(mode.dbValue, groupKey) },
        )
    }

    suspend fun buildDisplayRowsFromBuildResult(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        groupByContactFlag: Boolean,
        buildResult: RecentGroupRelationalBuilder.BuildResult,
        rowLimit: Int = 10_000,
    ): List<RecentDisplayCacheEntity> {
        val groups = buildResult.groups.take(rowLimit)
        if (groups.isEmpty()) return emptyList()
        val callsByGroup = buildResult.calls.groupBy { it.groupKey }
        return buildDisplayRows(
            database = database,
            mode = mode,
            groupByContactFlag = groupByContactFlag,
            groups = groups,
            memberIdsForGroup = { groupKey ->
                callsByGroup[groupKey].orEmpty().map { it.callId }
            },
        )
    }

    private suspend fun buildDisplayRows(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        groupByContactFlag: Boolean,
        groups: List<com.goodwy.commons.providercache.entities.RecentGroupEntity>,
        memberIdsForGroup: suspend (String) -> List<Long>,
    ): List<RecentDisplayCacheEntity> {
        val allMemberIds = groups.flatMap { group ->
            memberIdsForGroup(group.groupKey)
        }.map { it.toInt() }.distinct()
        val callsById = database.callLogDao().getByCallIds(allMemberIds).associateBy { it.callId }
        val context = RecentGroupBuildContextLoader.load(database)

        return groups.mapIndexed { index, group ->
            val memberIds = memberIdsForGroup(group.groupKey)
            val members = memberIds.mapNotNull { callsById[it.toInt()] }
            // Take the head of the descending sort, not maxWith. callSort orders newest-first, so
            // the maximum *under that comparator* is the element it sorts last -- the oldest call
            // in the group. That is what this used to pick, which is why a group row showed the
            // first call in the group instead of the most recent one.
            //
            // Same idiom as RecentGroupRelationalBuilder and ComparableRecentGroupDeriver, which
            // both sortWith(callSort).first(). Keeping the three identical is the point: the
            // fallback below resolves group.latestCallId, which those builders set to the newest
            // call, so a disagreeing primary path silently produced two different "latest" calls
            // depending only on whether members happened to load.
            val latest = members.sortedWith(callSort).firstOrNull()
                ?: callsById[group.latestCallId.toInt()]
                ?: return@mapIndexed null
            val donor = RecentGroupMemberContact.pickContactDonor(members)
            val groupedIds = memberIds.sortedDescending().joinToString(",")
            val identity = RecentGroupIdentityResolver.resolve(donor ?: latest, mode, context)
            val displayContactId = group.displayContactId?.toInt()
                ?: identity.displayContactId?.toInt()
                ?: donor?.contactID?.takeIf { it > 0 }
            val canonical = CanonicalPhoneNumberResolver.canonicalDigits(
                latest.normalizedNumber,
                latest.phoneNumber,
            ).ifEmpty {
                CanonicalPhoneNumberResolver.canonicalDigits(
                    group.primaryNumber,
                    group.primaryNumber,
                )
            }
            // Prefer latest dialed digits — never ASC-first group numbers (wrong head for
            // multi-number BY_CONTACT contacts).
            val cachedName = when {
                donor != null && donor.cachedName.isNotBlank() -> donor.cachedName
                latest.cachedName.isNotBlank() -> latest.cachedName
                else -> latest.cachedName
            }
            val photoUri = donor?.cachedPhotoUri?.takeIf { it.isNotEmpty() } ?: latest.cachedPhotoUri
            RecentDisplayCacheEntity(
                callId = latest.callId,
                phoneNumber = latest.phoneNumber.ifEmpty { group.primaryNumber },
                cachedName = cachedName,
                photoUri = photoUri,
                startTS = group.latestTimestamp,
                duration = latest.duration,
                type = latest.type,
                simID = latest.simID,
                simTypeID = latest.simTypeID,
                simColor = latest.simColor,
                contactID = displayContactId,
                callCount = group.callCount,
                groupedCallIds = groupedIds.ifEmpty { latest.callId.toString() },
                normalizedNumber = canonical,
                groupKey = group.groupKey,
                isUnknownNumber = latest.isUnknownNumber,
                isVoiceMail = latest.isVoiceMail,
                blockReason = latest.blockReason,
                features = latest.features,
                groupByContact = if (groupByContactFlag) 1 else 0,
                displayOrder = index,
            )
        }.filterNotNull()
    }

    suspend fun compareWithLegacyDisplay(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        rowLimit: Int = 10_000,
        includeDisplayFields: Boolean = false,
    ): RecentAuthorityComparator.Result {
        val legacyRows = database.recentDisplayCacheDao().getOrdered(mode.dbValue, rowLimit)
        val callIds = legacyRows.flatMap { it.groupedCallIds.split(',') }
            .mapNotNull { it.trim().toIntOrNull() }
            .distinct()
        val callsById = database.callLogDao().getByCallIds(callIds).associateBy { it.callId }
        val context = RecentGroupBuildContextLoader.load(database)
        val legacy = ComparableRecentGroupDeriver.fromDisplayEntities(legacyRows, callsById, mode, context)
        val relationalRows = buildDisplayRowsFromRelationalGroups(
            database = database,
            mode = mode,
            groupByContactFlag = mode == RecentGroupingMode.BY_CONTACT,
            rowLimit = rowLimit,
        )
        val relationalCallsById = callsById + database.callLogDao().getByCallIds(
            relationalRows.flatMap { it.groupedCallIds.split(',') }.mapNotNull { it.trim().toIntOrNull() },
        ).associateBy { it.callId }
        val relational = ComparableRecentGroupDeriver.fromDisplayEntities(
            relationalRows,
            relationalCallsById,
            mode,
            context,
        )
        val semantic = RecentAuthorityComparator.compareSemanticGroups(mode, legacy, relational)
        if (!includeDisplayFields) {
            if (!semantic.valid) {
                RecentAuthorityMismatchStore.captureFromCompare(
                    mode = mode,
                    legacy = legacy,
                    relational = relational,
                    mismatches = semantic.mismatches,
                )
            }
            return semantic
        }
        val displayMismatches = RecentAuthorityComparator.compareDisplayGroups(
            mode = mode,
            legacy = ComparableDisplayGroupDeriver.fromEntities(legacyRows, mode),
            relational = ComparableDisplayGroupDeriver.fromEntities(relationalRows, mode),
            // relationalRows come from buildDisplayRowsFromRelationalGroups, which never enriches.
            fields = RecentAuthorityComparator.PROJECTION_COMPARABLE_COSMETIC_FIELDS,
        )
        // Cosmetics are informational only — authority validity stays semantic.
        val result = semantic.copy(displayMismatches = displayMismatches)
        if (!semantic.valid) {
            RecentAuthorityMismatchStore.captureFromCompare(
                mode = mode,
                legacy = legacy,
                relational = relational,
                mismatches = result.mismatches,
            )
        }
        return result
    }

    private val callSort = compareByDescending<CallLogEntity> { it.startTS }
        .thenByDescending { it.callId }
}
