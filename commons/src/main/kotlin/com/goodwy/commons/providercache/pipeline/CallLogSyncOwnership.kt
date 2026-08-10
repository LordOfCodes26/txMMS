package com.goodwy.commons.providercache.pipeline

/**
 * Who is requesting a CallLog incremental sync.
 *
 * [RECENTS_UI] must be owned by RecentsPipelineCoordinator when attached.
 * Startup / background / debug sync may run without the coordinator and must never
 * directly publish Recents UI.
 */
enum class CallLogSyncOwnership {
    /** Recents tab end-call / observer / resume catch-up path. */
    RECENTS_UI,

    /** Cold start / first-page cache warm. */
    STARTUP_CACHE,

    /** Non-UI maintenance (ViewModels, background catch-up outside Recents pipeline). */
    BACKGROUND_MAINTENANCE,

    /** Debug panel / adb force sync. */
    DEBUG,
}
