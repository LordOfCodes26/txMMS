package com.goodwy.commons.providercache.grouping

import android.util.Log
import com.goodwy.commons.providercache.display.CanonicalPhoneNumberResolver
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.entities.CallLogEntity
import com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity
import com.goodwy.commons.providercache.entities.RecentGroupCallEntity
import com.goodwy.commons.providercache.entities.RecentGroupEntity

object RecentGroupingInvariantValidator {

    private const val TAG = "RecentGroupingInvariant"

    data class DisplaySnapshotValidation(
        val valid: Boolean,
        val failures: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    )

    fun validateDisplaySnapshot(
        mode: RecentGroupingMode,
        groups: List<RecentGroupEntity>,
        memberships: List<RecentGroupCallEntity>,
        displayRows: List<RecentDisplayCacheEntity>,
        eligibleCallCount: Int,
    ): DisplaySnapshotValidation {
        val failures = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val groupKeys = groups.map { it.groupKey }.toSet()
        val displayKeys = displayRows.map { it.groupKey }.toSet()
        groupKeys.forEach { key ->
            if (key !in displayKeys) failures += "groupMissingDisplay:$key"
        }
        displayKeys.forEach { key ->
            if (key !in groupKeys) failures += "displayMissingGroup:$key"
        }
        displayRows.forEach { row ->
            if (!isValidGroupKey(row.groupKey)) {
                failures += "invalidDisplayGroupKey:${row.groupKey}"
            }
            if (row.callCount <= 0) {
                failures += "zeroCallDisplay:${row.groupKey}"
            }
            if (row.groupByContact != mode.dbValue) {
                failures += "modeMismatch:${row.groupKey}"
            }
            if (row.groupKey.startsWith("contact:") && row.avatarInitials.isEmpty() &&
                row.photoThumbUri.isEmpty() && row.usePhotoAvatar == 0
            ) {
                warnings += "contactGroupMissingAvatarSeed:${row.groupKey}"
            }
            if (row.avatarDrawableIndex < 0 && row.groupKey.startsWith("contact:")) {
                warnings += "drawableIndexMissing:${row.groupKey}"
            }
            if (row.avatarColor == 0 && row.groupKey.startsWith("contact:")) {
                warnings += "avatarColorMissing:${row.groupKey}"
            }
        }
        val sumCounts = displayRows.sumOf { it.callCount }
        if (sumCounts != eligibleCallCount) {
            failures += "callCountSumMismatch expected=$eligibleCallCount actual=$sumCounts"
        }
        return DisplaySnapshotValidation(
            valid = failures.isEmpty(),
            failures = failures,
            warnings = warnings,
        )
    }

    fun validate(
        mode: RecentGroupingMode,
        eligibleCalls: List<CallLogEntity>,
        groups: List<RecentGroupEntity>,
        memberships: List<RecentGroupCallEntity>,
    ): RecentGroupingValidationResult {
        val failures = mutableListOf<String>()
        val eligibleIds = eligibleCalls.map { it.callId }.toSet()

        groups.forEach { group ->
            if (!isValidGroupKey(group.groupKey)) {
                failures += "invalidGroupKey:${group.groupKey}"
            }
            if (group.callCount <= 0) {
                failures += "zeroCallGroup:${group.groupKey}"
            }
            val members = memberships.filter { it.groupKey == group.groupKey }
            if (members.size != group.callCount) {
                failures += "countMismatch:${group.groupKey} expected=${group.callCount} actual=${members.size}"
            }
            if (members.none { it.callId.toInt() == group.latestCallId.toInt() }) {
                failures += "latestNotMember:${group.groupKey}"
            }
            val memberCalls = eligibleCalls.filter { call ->
                members.any { it.callId.toInt() == call.callId }
            }
            val maxTs = memberCalls.maxOfOrNull { it.startTS } ?: 0L
            if (memberCalls.isNotEmpty() && maxTs != group.latestTimestamp) {
                failures += "latestTsMismatch:${group.groupKey}"
            }
            if (group.groupKey.startsWith("contact:")) {
                val expectedId = group.groupKey.removePrefix("contact:").toLongOrNull()
                if (expectedId != null && group.displayContactId != expectedId) {
                    failures += "contactIdKeyMismatch:${group.groupKey}"
                }
            }
        }

        val assignedIds = memberships.map { it.callId.toInt() }.toSet()
        val unassigned = eligibleIds - assignedIds
        if (unassigned.isNotEmpty()) {
            failures += "unassignedCalls:${unassigned.size}"
        }
        val duplicateAssignments = memberships.groupBy { it.callId }.filter { it.value.size > 1 }
        if (duplicateAssignments.isNotEmpty()) {
            failures += "duplicateMembership:${duplicateAssignments.size}"
        }

        val valid = failures.isEmpty()
        val result = RecentGroupingValidationResult(
            valid = valid,
            eligibleCallCount = eligibleIds.size,
            groupCount = groups.size,
            membershipCount = memberships.size,
            failures = failures,
        )
        Log.d(
            TAG,
            "recentGroupingInvariant mode=${mode.name} calls=${result.eligibleCallCount} " +
                "groups=${result.groupCount} memberships=${result.membershipCount} valid=$valid",
        )
        return result
    }

    fun isValidGroupKey(groupKey: String): Boolean {
        if (groupKey.isEmpty()) return false
        if (CanonicalPhoneNumberResolver.isNameGroupKey(groupKey)) return false
        return groupKey.startsWith("contact:") || groupKey.startsWith("number:")
    }
}
