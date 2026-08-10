package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity

/**
 * Version-based Recents UI state. [lastVisibleVersion] and [adapterLoaded] are owned by the
 * app-layer bridge; the repository supplies [cacheVersion], [pendingDeltas], and [needsFullReload].
 */
data class RecentsDisplayState(
    val cacheVersion: Long,
    val lastVisibleVersion: Long,
    val adapterLoaded: Boolean,
    val pendingDeltas: List<PendingRecentDelta> = emptyList(),
    val needsFullReload: Boolean = false,
) {
    fun isStale(): Boolean = cacheVersion != lastVisibleVersion

    fun needsReconcile(): Boolean =
        !adapterLoaded || needsFullReload || pendingDeltas.isNotEmpty() || isStale()
}

/** Same target version while reconcile is in-flight coalesces duplicate callbacks. */
fun shouldCoalesceRecentsReconcile(
    inFlightTargetVersion: Long,
    targetVersion: Long,
    reconcileJobActive: Boolean,
): Boolean = reconcileJobActive && inFlightTargetVersion == targetVersion

/** Search/dialpad-active UI states defer adapter reconciliation. */
fun shouldDeferRecentsReconcile(
    uiStateAllowsReconcile: Boolean,
): Boolean = !uiStateAllowsReconcile

/**
 * Mislink repair rewrites [recent_display_cache] without bumping display version.
 * If paint is blocked (dialpad/search), the bridge must force a later reconcile —
 * otherwise dialpad/search close sees matching versions and leaves wrong `number:` groups.
 */
fun shouldForceRecentsReconcileAfterBlockedMislinkRepair(
    uiBlocked: Boolean,
    repairedRowCount: Int,
): Boolean = uiBlocked && repairedRowCount > 0

/** Multiple reconcile reasons for the same version collapse to one load — prefer explicit refresh. */
fun pickRecentsReconcileReason(reasons: Collection<RecentsReconcileReason>): RecentsReconcileReason =
    when {
        RecentsReconcileReason.MANUAL_REFRESH in reasons -> RecentsReconcileReason.MANUAL_REFRESH
        RecentsReconcileReason.GROUPING_CHANGED in reasons -> RecentsReconcileReason.GROUPING_CHANGED
        RecentsReconcileReason.FILTER_CHANGED in reasons -> RecentsReconcileReason.FILTER_CHANGED
        RecentsReconcileReason.SYNC_COMPLETE in reasons -> RecentsReconcileReason.SYNC_COMPLETE
        RecentsReconcileReason.APP_RESUME in reasons -> RecentsReconcileReason.APP_RESUME
        RecentsReconcileReason.TAB_ENTER in reasons -> RecentsReconcileReason.TAB_ENTER
        else -> reasons.firstOrNull() ?: RecentsReconcileReason.STARTUP
    }

/** Delta chain is contiguous when target version is ahead of visible and deltas exist. */
fun areRecentsDeltasContiguous(
    visibleVersion: Long,
    targetVersion: Long,
    deltaCount: Int,
): Boolean = deltaCount == 0 || targetVersion > visibleVersion

/** visibleVersion must not advance when delta apply fails. */
fun shouldAdvanceRecentsVisibleVersion(
    appliedCount: Int,
    expectedCount: Int,
    adapterPublishSucceeded: Boolean,
): Boolean = appliedCount == expectedCount && adapterPublishSucceeded

/**
 * Effective visible version for reconcile gating while a DiffUtil submit awaits PreDraw.
 * [pendingFrameCommitVersion] is the version submitted to the adapter but not yet frame-confirmed.
 */
fun effectiveRecentsVisibleVersion(
    lastVisibleVersion: Long,
    pendingFrameCommitVersion: Long,
): Long = maxOf(lastVisibleVersion, pendingFrameCommitVersion.coerceAtLeast(0L))

/** Why [com.android.dialer.providercache.RecentsDisplayBridge.reconcileRecentsUi] is running. */
enum class RecentsReconcileReason {
    STARTUP,
    TAB_ENTER,
    APP_RESUME,
    SYNC_COMPLETE,
    FILTER_CHANGED,
    GROUPING_CHANGED,
    MANUAL_REFRESH,
}

fun groupKeyFromDisplayEntity(entity: RecentDisplayCacheEntity): String =
    RecentGroupKey.fromEntity(entity)

fun DisplayCacheRebuildReason.recentsChangeNeedsFullReload(
    hasDeltas: Boolean,
    forceFull: Boolean,
): Boolean {
    if (forceFull || requiresFullRecentRebuild) return true
    return when (this) {
        DisplayCacheRebuildReason.CALL_LOG_INSERTED -> !hasDeltas
        DisplayCacheRebuildReason.CONTACT_DISPLAY_CHANGED -> !hasDeltas
        DisplayCacheRebuildReason.CALL_LOG_DELETED,
        DisplayCacheRebuildReason.CALL_LOG_SYNC_COMPLETED,
        DisplayCacheRebuildReason.COLD_EMPTY_CACHE,
        DisplayCacheRebuildReason.RECENTS_GROUPING_CHANGED,
        DisplayCacheRebuildReason.CONTACTS_CHANGED,
        DisplayCacheRebuildReason.MIGRATION,
        -> true
        else -> !hasDeltas
    }
}
