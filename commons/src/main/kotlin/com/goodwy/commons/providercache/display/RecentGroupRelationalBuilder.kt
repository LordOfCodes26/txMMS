package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.entities.CallLogEntity
import com.goodwy.commons.providercache.entities.RecentGroupCallEntity
import com.goodwy.commons.providercache.entities.RecentGroupEntity
import com.goodwy.commons.providercache.entities.RecentGroupNumberEntity

/**
 * Builds normalized relational recents groups from raw call-log rows.
 */
object RecentGroupRelationalBuilder {

    data class BuildResult(
        val groups: List<RecentGroupEntity>,
        val calls: List<RecentGroupCallEntity>,
        val numbers: List<RecentGroupNumberEntity>,
    )

    fun build(
        calls: List<CallLogEntity>,
        mode: RecentGroupingMode,
        context: RecentGroupIdentityResolver.Context,
        limit: Int = Int.MAX_VALUE,
    ): BuildResult {
        if (calls.isEmpty()) {
            return BuildResult(emptyList(), emptyList(), emptyList())
        }

        val grouped = linkedMapOf<String, MutableList<CallLogEntity>>()
        calls.forEach { call ->
            val identity = RecentGroupIdentityResolver.resolve(call, mode, context)
            grouped.getOrPut(identity.groupKey) { mutableListOf() }.add(call)
        }
        if (mode == RecentGroupingMode.BY_CONTACT) {
            RecentGroupMemberContact.coalesceNumberBucketsIntoContact(grouped)
        }

        val sortedGroups = grouped.entries
            .map { (groupKey, members) -> groupKey to members.sortedWith(callSort) }
            .sortedWith(groupSort)
            .take(limit)

        val groupEntities = mutableListOf<RecentGroupEntity>()
        val callEntities = mutableListOf<RecentGroupCallEntity>()
        val numberEntities = mutableListOf<RecentGroupNumberEntity>()

        sortedGroups.forEachIndexed { index, (groupKey, members) ->
            val latest = members.first()
            val displayContactId = RecentGroupMemberContact.resolveDisplayContactId(
                members = members,
                latest = latest,
                mode = mode,
                context = context,
            )
            val identity = RecentGroupIdentityResolver.resolve(
                RecentGroupMemberContact.pickContactDonor(members) ?: latest,
                mode,
                context,
            )
            val canonicalNumbers = members
                .map {
                    CanonicalPhoneNumberResolver.canonicalDigits(it.normalizedNumber, it.phoneNumber)
                }
                .filter { it.isNotEmpty() }
                .toSet()

            groupEntities += RecentGroupEntity(
                groupingMode = mode.dbValue,
                groupKey = groupKey,
                displayContactId = displayContactId,
                latestCallId = latest.callId.toLong(),
                latestTimestamp = latest.startTS,
                callCount = members.size,
                primaryNumber = latest.phoneNumber.ifEmpty { latest.normalizedNumber },
                displayOrder = index,
            )

            members.forEach { call ->
                callEntities += RecentGroupCallEntity(
                    groupingMode = mode.dbValue,
                    groupKey = groupKey,
                    callId = call.callId.toLong(),
                )
            }

            (identity.normalizedNumbers + canonicalNumbers).distinct().forEach { number ->
                numberEntities += RecentGroupNumberEntity(
                    groupingMode = mode.dbValue,
                    groupKey = groupKey,
                    normalizedNumber = number,
                )
            }
        }

        return BuildResult(
            groups = groupEntities,
            calls = callEntities,
            numbers = numberEntities.distinctBy { Triple(it.groupingMode, it.groupKey, it.normalizedNumber) },
        )
    }

    fun buildForCallIds(
        allCalls: List<CallLogEntity>,
        affectedCallIds: Set<Int>,
        mode: RecentGroupingMode,
        context: RecentGroupIdentityResolver.Context,
        affectedGroupKeys: Set<String> = emptySet(),
        affectedNumbers: Set<String> = emptySet(),
    ): BuildResult {
        if (affectedCallIds.isEmpty() && affectedGroupKeys.isEmpty() && affectedNumbers.isEmpty()) {
            return BuildResult(emptyList(), emptyList(), emptyList())
        }
        val seedCalls = allCalls.filter { it.callId in affectedCallIds }
        val seedKeys = linkedSetOf<String>().apply {
            addAll(affectedGroupKeys)
            seedCalls.forEach { call ->
                add(RecentGroupIdentityResolver.resolve(call, mode, context).groupKey)
            }
        }
        val seedDigits = linkedSetOf<String>().apply {
            addAll(affectedNumbers)
            seedCalls.forEach { call ->
                val digits = CanonicalPhoneNumberResolver.canonicalDigits(
                    call.normalizedNumber,
                    call.phoneNumber,
                )
                if (digits.isNotEmpty()) add(digits)
            }
        }
        // Include coalesce siblings: contact: members whose digits / keys were expanded into
        // the affected set, not only calls that share the new row's pre-coalesce identity.
        val regroupCalls = allCalls.filter { call ->
            if (call.callId in affectedCallIds) return@filter true
            val identity = RecentGroupIdentityResolver.resolve(call, mode, context)
            identity.groupKey in seedKeys ||
                identity.normalizedNumbers.any { it in seedDigits }
        }
        return build(regroupCalls, mode, context)
    }

    private val callSort = compareByDescending<CallLogEntity> { it.startTS }
        .thenByDescending { it.callId }
        .thenBy { it.phoneNumber }

    private val groupSort = compareByDescending<Pair<String, List<CallLogEntity>>> { (_, members) ->
        members.maxOfOrNull { it.startTS } ?: 0L
    }.thenByDescending { (_, members) ->
        members.maxOfOrNull { it.callId } ?: 0
    }.thenBy { (groupKey, _) -> groupKey }
}
