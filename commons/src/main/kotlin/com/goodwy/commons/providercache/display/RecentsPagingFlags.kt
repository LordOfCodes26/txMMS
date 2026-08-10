package com.goodwy.commons.providercache.display

/**
 * Which machinery drives the Recents list.
 *
 * Mirrors the staged rollout [RelationalRecentsGroupingFlags] uses, and for the same reason: the
 * two paths must be able to run side by side and be compared on a device before either is trusted.
 */
enum class RecentsListSource {
    /** Production. `RecentsDisplayBridge` computes a full list and publishes it to the adapter. */
    BRIDGE_PUBLISH,

    /**
     * Both paths run. The bridge still owns the UI; the pager is collected and its output compared
     * against what the bridge published. Nothing user-visible changes.
     */
    COMPARE_ONLY,

    /**
     * The pager owns the UI. `CallLogRepository.recentDisplayPages` drives the adapter through
     * `RecentsPagedItems.callLogItemPages`, and the bridge's publish path is inert.
     */
    PAGER_AUTHORITATIVE,
}

/**
 * Rollout gate for Stage 3 blocker C of `docs/recents-remediation-plan.md` — inverting the
 * `RecentsDisplayBridge` publish contract so Room's Paging invalidation drives Recents directly.
 *
 * C cannot be subdivided the way blockers A and B were: the bridge, the pipeline coordinator, the
 * reconcile machinery and provisional paint all assume "someone hands the adapter a complete list",
 * and Paging removes that premise for all of them at once. The only safe way to land it is the way
 * the provider single-source migration landed — both paths present, compare-only soak first, flip
 * after the counters stay clean.
 *
 * Default is [RecentsListSource.BRIDGE_PUBLISH]: nothing reads the pager yet.
 */
object RecentsPagingFlags {

    /** Never anything but BRIDGE_PUBLISH in release without an explicit opt-in. */
    @Volatile
    var listSource: RecentsListSource = RecentsListSource.BRIDGE_PUBLISH

    /** True when the pager should be collected at all (either to compare, or to drive the UI). */
    fun shouldCollectPager(): Boolean =
        listSource == RecentsListSource.COMPARE_ONLY ||
            listSource == RecentsListSource.PAGER_AUTHORITATIVE

    /** True when the pager, not the bridge, owns what the adapter shows. */
    fun pagerOwnsUi(): Boolean = listSource == RecentsListSource.PAGER_AUTHORITATIVE

    /** True when the bridge should keep publishing — production, and throughout the soak. */
    fun bridgePublishes(): Boolean = !pagerOwnsUi()

    fun setListSourceForDebug(source: RecentsListSource) {
        listSource = source
    }
}
