package com.goodwy.commons.providercache.display

/**
 * Why a display-cache rebuild was scheduled.
 */
enum class DisplayCacheRebuildReason {
    COLD_EMPTY_CACHE,
    CONTACT_SYNC_COMPLETED,
    CHANGED_CONTACT_IDS,
    DELETED_CONTACT_IDS,
    SECURE_MODE_CHANGED,
    SOURCE_FILTER_CHANGED,
    DUPLICATE_MERGE_IDLE,
    RECENTS_GROUPING_CHANGED,
    CALL_LOG_SYNC_COMPLETED,
    CALL_LOG_INSERTED,
    CALL_LOG_DELETED,
    /** Address-book change (delete/rename) — recents labels must be re-resolved. */
    CONTACTS_CHANGED,
    /** Single-contact name/photo edit — targeted recent display refresh when row match fails. */
    CONTACT_DISPLAY_CHANGED,
    MANUAL_DEBUG,
    MIGRATION,
    /** Room display cache failed startup validation — full swap required. */
    STARTUP_INVALID_CACHE,
    /** External bulk provider write (e.g. VCF import) detected via ContactChangeCoordinator.handleProviderChanged. */
    PROVIDER_CHANGED_IMPORT,
    ;

    val requiresFullContactRebuild: Boolean
        get() = when (this) {
            COLD_EMPTY_CACHE,
            SECURE_MODE_CHANGED,
            SOURCE_FILTER_CHANGED,
            DUPLICATE_MERGE_IDLE,
            MIGRATION,
            MANUAL_DEBUG,
            STARTUP_INVALID_CACHE,
            -> true
            else -> false
        }

    /**
     * True when the rebuild is the direct result of the user flipping a visibility control, so the
     * list on screen is expected to change *now*.
     *
     * The display-version commit is normally gated on readiness, because committing a bootstrap
     * rebuild before the cache settles can re-trigger the load path and loop. That reasoning only
     * holds for rebuilds the startup machinery itself raises: a user action does not loop, and
     * nothing guarantees a later settle rebuild will carry it. Skipping the version bump for these
     * strands the change — new rows are written, the version does not move, and every reconcile
     * no-ops on VERSION_MATCH.
     */
    val reflectsUserVisibilityAction: Boolean
        get() = when (this) {
            SECURE_MODE_CHANGED,
            SOURCE_FILTER_CHANGED,
            -> true
            else -> false
        }

    /** Fast = incremental-friendly, no global duplicate merge. Accurate = full merge when enabled. */
    val defaultContactRebuildMode: ContactDisplayRebuildMode
        get() = when (this) {
            SECURE_MODE_CHANGED,
            SOURCE_FILTER_CHANGED,
            MANUAL_DEBUG,
            MIGRATION,
            DUPLICATE_MERGE_IDLE,
            -> ContactDisplayRebuildMode.ACCURATE
            else -> ContactDisplayRebuildMode.FAST
        }

    val requiresFullRecentRebuild: Boolean
        get() = when (this) {
            COLD_EMPTY_CACHE,
            RECENTS_GROUPING_CHANGED,
            CONTACTS_CHANGED,
            MIGRATION,
            -> true
            else -> false
        }
}

/** Controls whether duplicate merge runs during a contact display-cache rebuild. */
enum class ContactDisplayRebuildMode {
    /** Filter + upsert only; duplicate merge deferred to idle/manual/accurate triggers. */
    FAST,
    /** Full duplicate merge (when setting enabled) + transactional swap. */
    ACCURATE,
}

data class ContactDisplayRebuildRequest(
    val reason: DisplayCacheRebuildReason,
    val changedContactIds: Set<Int> = emptySet(),
    val deletedContactIds: Set<Int> = emptySet(),
    val forceFull: Boolean = false,
    val mode: ContactDisplayRebuildMode = reason.defaultContactRebuildMode,
)

data class RecentDisplayRebuildRequest(
    val reason: DisplayCacheRebuildReason,
    val groupByContact: Boolean,
    val insertedCallIds: Set<Int> = emptySet(),
    val deletedCallIds: Set<Int> = emptySet(),
    val forceFull: Boolean = false,
    val limit: Int = 1000,
)

data class DisplayCacheRebuildMetrics(
    val reason: DisplayCacheRebuildReason,
    val mode: String,
    val rebuildMode: ContactDisplayRebuildMode = ContactDisplayRebuildMode.FAST,
    val rawQueryMs: Long = 0,
    val filterMs: Long = 0,
    val duplicateMergeMs: Long = 0,
    val sortMs: Long = 0,
    val dbWriteMs: Long = 0,
    val totalMs: Long = 0,
    val rowsUpdated: Int = 0,
    val rowsDeleted: Int = 0,
    val legacyMetadataBackfillNeeded: Int = 0,
)
