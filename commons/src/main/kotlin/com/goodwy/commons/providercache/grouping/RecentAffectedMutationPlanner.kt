package com.goodwy.commons.providercache.grouping

import android.util.Log
import com.goodwy.commons.providercache.debug.CompareOnlySoakCounters
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity
import com.goodwy.commons.providercache.entities.RecentGroupEntity

/**
 * Checksum-based no-op detection before affected group writes.
 */
object RecentAffectedMutationPlanner {

    private const val TAG = "RecentAffectedMutation"

    enum class MutationResult {
        NO_OP,
        DISPLAY_ONLY,
        MEMBERSHIP_CHANGED,
    }

    data class Plan(
        val result: MutationResult,
        val groupKey: String,
        val oldChecksum: Long,
        val newChecksum: Long,
    )

    fun planGroupUpdate(
        mode: RecentGroupingMode,
        existingGroup: RecentGroupEntity?,
        newGroup: RecentGroupEntity,
        newCallIds: List<Long>,
        newNumbers: List<String>,
        existingDisplay: RecentDisplayCacheEntity?,
        newDisplay: RecentDisplayCacheEntity?,
    ): Plan {
        val newChecksum = RecentGroupMembershipChecksum.computeForGroup(
            mode = mode,
            group = newGroup,
            callIds = newCallIds,
            canonicalNumbers = newNumbers,
        )
        val oldChecksum = existingGroup?.membershipChecksum ?: 0L
        val result = when {
            existingGroup == null -> MutationResult.MEMBERSHIP_CHANGED
            newChecksum != oldChecksum -> MutationResult.MEMBERSHIP_CHANGED
            displayMetadataChanged(existingDisplay, newDisplay) -> MutationResult.DISPLAY_ONLY
            else -> MutationResult.NO_OP
        }
        Log.d(
            TAG,
            "recentAffectedMutation result=$result key=${newGroup.groupKey} old=$oldChecksum new=$newChecksum",
        )
        when (result) {
            MutationResult.NO_OP -> CompareOnlySoakCounters.recordNoOpMutation()
            MutationResult.DISPLAY_ONLY -> CompareOnlySoakCounters.recordDisplayOnlyMutation()
            MutationResult.MEMBERSHIP_CHANGED -> CompareOnlySoakCounters.recordMembershipChanged()
        }
        if (oldChecksum != 0L && newChecksum != oldChecksum) {
            Log.d(
                TAG,
                "recentGroupChecksum key=${newGroup.groupKey} old=$oldChecksum new=$newChecksum changed=true",
            )
        }
        return Plan(
            result = result,
            groupKey = newGroup.groupKey,
            oldChecksum = oldChecksum,
            newChecksum = newChecksum,
        )
    }

    private fun displayMetadataChanged(
        old: RecentDisplayCacheEntity?,
        new: RecentDisplayCacheEntity?,
    ): Boolean {
        if (old == null || new == null) return old != new
        return old.callId != new.callId ||
            old.startTS != new.startTS ||
            old.callCount != new.callCount ||
            old.phoneNumber != new.phoneNumber ||
            old.normalizedNumber != new.normalizedNumber ||
            old.displayNumber != new.displayNumber ||
            old.type != new.type ||
            old.simID != new.simID ||
            old.cachedName != new.cachedName ||
            old.displayName != new.displayName ||
            old.photoUri != new.photoUri ||
            old.photoThumbUri != new.photoThumbUri ||
            old.avatarColor != new.avatarColor ||
            old.avatarInitials != new.avatarInitials ||
            old.avatarDrawableIndex != new.avatarDrawableIndex ||
            old.usePhotoAvatar != new.usePhotoAvatar ||
            old.avatarVersion != new.avatarVersion
    }
}
