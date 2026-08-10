package com.goodwy.commons.providercache.grouping

import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.entities.RecentGroupEntity

/**
 * Deterministic membership checksum for cheap stale-group detection.
 *
 * Uses FNV-1a 64-bit — stable across JVM restarts; never uses [Object.hashCode].
 */
object RecentGroupMembershipChecksum {

    fun compute(
        mode: RecentGroupingMode,
        groupKey: String,
        callIds: List<Long>,
        canonicalNumbers: List<String>,
        displayContactId: Long?,
        latestCallId: Long,
        latestTimestamp: Long,
        callCount: Int,
    ): Long {
        var hash = FNV_OFFSET_BASIS
        hash = mix(hash, mode.dbValue.toLong())
        hash = mix(hash, groupKey)
        callIds.sorted().forEach { hash = mix(hash, it) }
        canonicalNumbers.sorted().forEach { hash = mix(hash, it) }
        hash = mix(hash, displayContactId ?: -1L)
        hash = mix(hash, latestCallId)
        hash = mix(hash, latestTimestamp)
        hash = mix(hash, callCount.toLong())
        return hash
    }

    fun computeForGroup(
        mode: RecentGroupingMode,
        group: RecentGroupEntity,
        callIds: List<Long>,
        canonicalNumbers: List<String>,
    ): Long = compute(
        mode = mode,
        groupKey = group.groupKey,
        callIds = callIds,
        canonicalNumbers = canonicalNumbers,
        displayContactId = group.displayContactId,
        latestCallId = group.latestCallId,
        latestTimestamp = group.latestTimestamp,
        callCount = group.callCount,
    )

    fun applyToGroups(
        mode: RecentGroupingMode,
        groups: List<RecentGroupEntity>,
        callIdsByGroup: Map<String, List<Long>>,
        numbersByGroup: Map<String, List<String>>,
    ): List<RecentGroupEntity> = groups.map { group ->
        val checksum = computeForGroup(
            mode = mode,
            group = group,
            callIds = callIdsByGroup[group.groupKey].orEmpty(),
            canonicalNumbers = numbersByGroup[group.groupKey].orEmpty(),
        )
        group.copy(membershipChecksum = checksum)
    }

    private const val FNV_OFFSET_BASIS = -3750763034362895577L
    private const val FNV_PRIME = 1099511628211L

    private fun mix(hash: Long, value: Long): Long {
        var h = hash xor value
        h *= FNV_PRIME
        return h
    }

    private fun mix(hash: Long, value: String): Long {
        var h = hash
        value.forEach { ch ->
            h = h xor ch.code.toLong()
            h *= FNV_PRIME
        }
        return h
    }
}

fun com.goodwy.commons.providercache.display.RecentGroupRelationalBuilder.BuildResult.withMembershipChecksums(
    mode: RecentGroupingMode,
): com.goodwy.commons.providercache.display.RecentGroupRelationalBuilder.BuildResult {
    val callIdsByGroup = calls.groupBy { it.groupKey }.mapValues { (_, rows) ->
        rows.map { it.callId }
    }
    val numbersByGroup = numbers.groupBy { it.groupKey }.mapValues { (_, rows) ->
        rows.map { it.normalizedNumber }
    }
    return copy(
        groups = RecentGroupMembershipChecksum.applyToGroups(
            mode = mode,
            groups = groups,
            callIdsByGroup = callIdsByGroup,
            numbersByGroup = numbersByGroup,
        ),
    )
}
