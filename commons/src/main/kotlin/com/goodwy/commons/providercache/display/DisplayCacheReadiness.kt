package com.goodwy.commons.providercache.display

/**
 * Authoritative readiness for a display-cache domain during cold start and recovery.
 */
enum class DisplayCacheReadiness {
    NOT_STARTED,
    RAW_SYNCING,
    DISPLAY_BUILDING,
    READY_EMPTY,
    READY_WITH_DATA,
    ERROR_PERMISSION,
    ERROR_RETRYABLE,
}

/** Raw call-log / contacts cache layer — independent from display snapshot readiness. */
enum class RawCacheReadiness {
    NOT_STARTED,
    CLEAN,
    DIRTY,
    REPAIR_REQUIRED,
    SYNCING,
}

data class DomainReadinessState(
    val rawReadiness: RawCacheReadiness,
    val displayReadiness: DisplayCacheReadiness,
)

enum class RecentsEmptyReconcileResult {
    AUTHORITATIVE_EMPTY,
    NOT_READY,
    PERMISSION_BLOCKED,
}

enum class CacheDomain {
    CONTACTS,
    RECENTS,
}

enum class StartupDomainOwnerKind {
    CACHE_REPAIR,
    COLD_BOOTSTRAP,
    METADATA_REPAIR,
    DISPLAY_REBUILD,
}

/**
 * In-memory readiness per domain. Persisted dirty/repair flags remain in [cache_metadata].
 */
object DisplayCacheReadinessTracker {

    @Volatile
    private var contactsReadiness: DisplayCacheReadiness = DisplayCacheReadiness.NOT_STARTED

    @Volatile
    private var recentsReadiness: DisplayCacheReadiness = DisplayCacheReadiness.NOT_STARTED

    @Volatile
    private var recentsRawReadiness: RawCacheReadiness = RawCacheReadiness.NOT_STARTED

    @Volatile
    private var recentsDisplaySeeded: Boolean = false

    @Volatile
    private var recentsProviderFallbackActive: Boolean = false

    @Volatile
    private var contactsProviderFallbackActive: Boolean = false

    @Volatile
    private var contactsPermissionBootstrapResumed: Boolean = false

    @Volatile
    private var recentsPermissionBootstrapResumed: Boolean = false

    fun contactsReadiness(): DisplayCacheReadiness = contactsReadiness

    fun recentsReadiness(): DisplayCacheReadiness = recentsReadiness

    fun recentsRawReadiness(): RawCacheReadiness = recentsRawReadiness

    fun recentsDomainState(): DomainReadinessState =
        DomainReadinessState(
            rawReadiness = recentsRawReadiness,
            displayReadiness = recentsReadiness,
        )

    fun recentsDisplayReadinessIsSeeded(): Boolean = recentsDisplaySeeded

    fun setRecentsRawReadiness(readiness: RawCacheReadiness) {
        recentsRawReadiness = readiness
    }

    fun seedRecentsDisplay(readiness: DisplayCacheReadiness) {
        recentsReadiness = readiness
        recentsDisplaySeeded = readiness == DisplayCacheReadiness.READY_WITH_DATA ||
            readiness == DisplayCacheReadiness.READY_EMPTY
    }

    fun setContacts(readiness: DisplayCacheReadiness) {
        contactsReadiness = readiness
    }

    fun setRecents(readiness: DisplayCacheReadiness) {
        recentsReadiness = readiness
    }

    fun setRecentsProviderFallbackActive(active: Boolean) {
        recentsProviderFallbackActive = active
    }

    fun setContactsProviderFallbackActive(active: Boolean) {
        contactsProviderFallbackActive = active
    }

    fun isRecentsProviderFallbackActive(): Boolean = recentsProviderFallbackActive

    fun isContactsProviderFallbackActive(): Boolean = contactsProviderFallbackActive

    fun isRecentsAuthoritative(
        displayVersion: Long,
        dirty: Boolean,
        repairRequired: Boolean,
    ): Boolean = evaluateRecentsAuthoritative(
        readiness = recentsReadiness,
        displayVersion = displayVersion,
        dirty = dirty,
        repairRequired = repairRequired,
        providerFallbackActive = recentsProviderFallbackActive,
    )

    fun isContactsAuthoritative(
        displayVersion: Long,
        dirty: Boolean,
        repairRequired: Boolean,
    ): Boolean = evaluateContactsAuthoritative(
        readiness = contactsReadiness,
        displayVersion = displayVersion,
        dirty = dirty,
        repairRequired = repairRequired,
        providerFallbackActive = contactsProviderFallbackActive,
    )

    fun computeRecentsReadiness(
        rawCount: Int,
        displayRows: Int,
        rawSyncDone: Boolean,
        displayBuilding: Boolean,
        hasCallLogPermission: Boolean,
    ): DisplayCacheReadiness = when {
        !hasCallLogPermission -> DisplayCacheReadiness.ERROR_PERMISSION
        displayBuilding -> DisplayCacheReadiness.DISPLAY_BUILDING
        !rawSyncDone -> DisplayCacheReadiness.RAW_SYNCING
        rawCount == 0 && displayRows == 0 -> DisplayCacheReadiness.READY_EMPTY
        displayRows > 0 -> DisplayCacheReadiness.READY_WITH_DATA
        rawCount > 0 && displayRows == 0 -> DisplayCacheReadiness.DISPLAY_BUILDING
        else -> DisplayCacheReadiness.NOT_STARTED
    }

    fun computeContactsReadiness(
        rawCount: Int,
        displayRows: Int,
        rawSyncDone: Boolean,
        displayBuilding: Boolean,
        hasContactsPermission: Boolean,
    ): DisplayCacheReadiness = when {
        !hasContactsPermission -> DisplayCacheReadiness.ERROR_PERMISSION
        displayBuilding -> DisplayCacheReadiness.DISPLAY_BUILDING
        !rawSyncDone -> DisplayCacheReadiness.RAW_SYNCING
        rawCount == 0 && displayRows == 0 -> DisplayCacheReadiness.READY_EMPTY
        displayRows > 0 -> DisplayCacheReadiness.READY_WITH_DATA
        rawCount > 0 && displayRows == 0 -> DisplayCacheReadiness.DISPLAY_BUILDING
        else -> DisplayCacheReadiness.NOT_STARTED
    }

    fun evaluateRecentsAuthoritative(
        readiness: DisplayCacheReadiness,
        displayVersion: Long,
        dirty: Boolean,
        repairRequired: Boolean,
        providerFallbackActive: Boolean,
    ): Boolean =
        !providerFallbackActive &&
            displayVersion > 0L &&
            !dirty &&
            !repairRequired &&
            (readiness == DisplayCacheReadiness.READY_EMPTY ||
                readiness == DisplayCacheReadiness.READY_WITH_DATA)

    fun evaluateContactsAuthoritative(
        readiness: DisplayCacheReadiness,
        displayVersion: Long,
        dirty: Boolean,
        repairRequired: Boolean,
        providerFallbackActive: Boolean,
    ): Boolean = evaluateRecentsAuthoritative(
        readiness = readiness,
        displayVersion = displayVersion,
        dirty = dirty,
        repairRequired = repairRequired,
        providerFallbackActive = providerFallbackActive,
    )

    fun resetForColdStart() {
        contactsReadiness = DisplayCacheReadiness.NOT_STARTED
        if (!recentsDisplaySeeded) {
            recentsReadiness = DisplayCacheReadiness.NOT_STARTED
        }
        recentsRawReadiness = RawCacheReadiness.NOT_STARTED
        recentsProviderFallbackActive = false
        contactsProviderFallbackActive = false
        contactsPermissionBootstrapResumed = false
        recentsPermissionBootstrapResumed = false
        StartupDomainOwner.reset()
    }

    fun resetRecentsRawOnly() {
        recentsRawReadiness = RawCacheReadiness.NOT_STARTED
    }

    /** Returns true the first time permission is granted after [ERROR_PERMISSION] for contacts. */
    fun resumeContactsAfterPermissionGranted(): Boolean {
        if (contactsReadiness != DisplayCacheReadiness.ERROR_PERMISSION) return true
        if (contactsPermissionBootstrapResumed) return false
        contactsPermissionBootstrapResumed = true
        contactsReadiness = DisplayCacheReadiness.NOT_STARTED
        contactsProviderFallbackActive = false
        return true
    }

    /** Returns true the first time permission is granted after [ERROR_PERMISSION] for recents. */
    fun resumeRecentsAfterPermissionGranted(): Boolean {
        if (recentsReadiness != DisplayCacheReadiness.ERROR_PERMISSION) return true
        if (recentsPermissionBootstrapResumed) return false
        recentsPermissionBootstrapResumed = true
        recentsReadiness = DisplayCacheReadiness.NOT_STARTED
        recentsProviderFallbackActive = false
        return true
    }
}

/**
 * Recents readiness for a snapshot that did **not** come from the display cache.
 *
 * Under an active call-type filter the recents engine groups `call_log_entries` directly — the
 * display cache cannot answer a per-type question, because a group key never includes the call
 * type (see `RecentsDisplayBridge.loadAuthoritativeSnapshot`). That makes
 * [DisplayCacheReadinessTracker.recentsReadiness] the wrong oracle for an empty filtered result:
 * it describes an artifact that path never reads.
 *
 * The visible cost was a Recents tab stuck on the "Loading…" placeholder. Filter set to Missed
 * with no missed calls, display cache mid-build: the filtered build over the raw log returned zero
 * — already the final answer — but readiness said [DisplayCacheReadiness.DISPLAY_BUILDING], the
 * empty publish was withheld as NOT_READY, and nothing cleared it until a display-cache build that
 * the filtered path does not even consult finally landed.
 *
 * Same shape as [ContactsDisplayReadiness.effectiveAfterRebuild]: zero rows over a demonstrably
 * non-empty raw mirror is the filter doing its job, not a sync that has not arrived.
 */
object RecentsDisplayReadiness {

    /**
     * @param bypassedDisplayCache true when the snapshot was grouped from `call_log_entries`
     *   instead of read from the display cache — an active call-type filter, or protected numbers
     *   excluded before grouping. False means the snapshot did come from the cache and [computed]
     *   already applies.
     * @param rawCount `call_log_entries` rows, or null when this path did not measure them
     * @param rawSyncDone [DisplayCacheReadinessTracker.computeRecentsReadiness]'s `rawSyncDone`
     *
     * Only [DisplayCacheReadiness.DISPLAY_BUILDING] and [DisplayCacheReadiness.NOT_STARTED] are
     * reinterpreted — those are the two states that describe the display cache specifically.
     * [DisplayCacheReadiness.RAW_SYNCING] keeps its meaning: the filtered build reads the raw
     * table, so an incomplete raw sync really can still turn up a matching call.
     * [DisplayCacheReadiness.ERROR_PERMISSION] is not an emptiness claim at all.
     */
    fun effectiveForFilteredEmpty(
        computed: DisplayCacheReadiness,
        bypassedDisplayCache: Boolean,
        rawCount: Int?,
        rawSyncDone: Boolean,
    ): DisplayCacheReadiness {
        if (!bypassedDisplayCache || rawCount == null) return computed
        if (!rawSyncDone || rawCount <= 0) return computed
        return when (computed) {
            DisplayCacheReadiness.DISPLAY_BUILDING,
            DisplayCacheReadiness.NOT_STARTED,
            -> DisplayCacheReadiness.READY_WITH_DATA
            else -> computed
        }
    }
}

/**
 * Post-rebuild contacts readiness. [DisplayCacheReadinessTracker.computeContactsReadiness] models
 * mid-flight state (empty display + Room rows ⇒ [DisplayCacheReadiness.DISPLAY_BUILDING]); after a
 * finished rebuild, zero display rows may be authoritative empty under secure/source filters.
 *
 * Mirrors dial/recents: do not claim [DisplayCacheReadiness.READY_EMPTY] during an active cold start
 * before raw contacts sync completes — a later sync-driven rebuild must be allowed to settle.
 */
object ContactsDisplayReadiness {

    /**
     * @param filterDrivenEmpty the rebuild was raised by the user changing a visibility filter
     *   (see `DisplayCacheRebuildReason.reflectsUserVisibilityAction`), so zero display rows over a
     *   non-empty Room mirror is the filter doing its job rather than a sync that has not landed.
     */
    fun effectiveAfterRebuild(
        computed: DisplayCacheReadiness,
        displayRows: Int,
        rawCount: Int,
        hasPermission: Boolean,
        contactsSyncDone: Boolean,
        coldStart: Boolean,
        filterDrivenEmpty: Boolean = false,
    ): DisplayCacheReadiness {
        if (!hasPermission) return computed
        if (displayRows > 0) return DisplayCacheReadiness.READY_WITH_DATA
        if (computed == DisplayCacheReadiness.RAW_SYNCING) return computed
        // displayRows == 0: true empty Room, or filter-empty (protected/hidden rows remain in Room).
        // Withholding READY_EMPTY during cold start exists so a sync still in flight can settle --
        // it cannot tell "empty because nothing synced yet" from "empty because everything is
        // hidden", and those have identical inputs here. A user visibility action resolves it: the
        // rows are demonstrably in Room, and the user just hid them. Without this, protecting every
        // contact leaves readiness at DISPLAY_BUILDING, isDisplayCacheAuthoritativelyEmpty() stays
        // false, the search hint falls back to the Room total, and the Contacts tab holds
        // MProgressDialog open over a list that is already final.
        val filterEmptyIsAuthoritative = filterDrivenEmpty && rawCount > 0
        val canClaimAuthoritativeEmpty = filterEmptyIsAuthoritative || contactsSyncDone || !coldStart
        return if (canClaimAuthoritativeEmpty) {
            DisplayCacheReadiness.READY_EMPTY
        } else {
            computed
        }
    }
}

/**
 * Ensures exactly one startup repair/bootstrap owner per domain per generation.
 * Rebuild reasons and [StartupDomainOwnerKind] are metadata for logs only — not part of the key.
 *
 * Identity example: `RECENTS:1` ([mutationKey]).
 */
object StartupDomainOwner {

    private data class Ownership(
        val generation: Long,
        val owner: StartupDomainOwnerKind,
        val reason: String,
    )

    private val active = mutableMapOf<CacheDomain, Ownership>()
    private val committed = mutableMapOf<CacheDomain, Ownership>()

    @Volatile
    private var startupGeneration: Long = 0L

    fun nextStartupGeneration(): Long {
        synchronized(this) {
            startupGeneration += 1L
            active.clear()
            committed.clear()
            return startupGeneration
        }
    }

    fun currentStartupGeneration(): Long = startupGeneration

    /** Ownership identity: domain + current startup generation (e.g. `RECENTS:1`). */
    fun mutationKey(domain: CacheDomain): String =
        "${domain.name}:$startupGeneration"

    fun hasActive(domain: CacheDomain): Boolean = synchronized(this) { domain in active }

    fun hasCommitted(domain: CacheDomain): Boolean = synchronized(this) { domain in committed }

    /**
     * @param owner who is requesting ownership (logged only)
     * @param reason rebuild/repair reason (logged only; not part of the key)
     */
    fun tryAcquire(
        domain: CacheDomain,
        owner: StartupDomainOwnerKind,
        reason: String = "",
    ): Boolean {
        val key = mutationKey(domain)
        synchronized(this) {
            val existing = active[domain] ?: committed[domain]
            if (existing != null) {
                val skipReason = if (domain in active) "ALREADY_IN_FLIGHT" else "ALREADY_COMMITTED"
                com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger.log(
                    "cacheMutationSkipped id=$key reason=$skipReason " +
                        "heldOwner=${existing.owner.name} heldReason=${existing.reason} " +
                        "requestedOwner=${owner.name} requestedReason=$reason",
                )
                com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger.log(
                    "startupDomainOwner skipped key=$key owner=${owner.name} reason=$reason ($skipReason)",
                )
                return false
            }
            active[domain] = Ownership(
                generation = startupGeneration,
                owner = owner,
                reason = reason,
            )
            com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger.log(
                "startupDomainOwner acquired key=$key owner=${owner.name} reason=$reason",
            )
            com.goodwy.commons.providercache.debug.StartupTimeline.markStartupOwnerAcquire(
                domain,
                owner.name,
            )
            return true
        }
    }

    /**
     * Commits the active startup owner for [domain]. No-op if this domain was not acquired —
     * incremental mutations (e.g. CALL_DELETED) must not mark startup ownership committed.
     */
    fun markCommitted(domain: CacheDomain) {
        val key = mutationKey(domain)
        synchronized(this) {
            val ownership = active.remove(domain) ?: run {
                com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger.log(
                    "startupDomainOwner commitSkipped key=$key (no active owner)",
                )
                return
            }
            committed[domain] = ownership
            com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger.log(
                "startupDomainOwner committed key=$key owner=${ownership.owner.name} " +
                    "reason=${ownership.reason}",
            )
        }
    }

    fun release(domain: CacheDomain) {
        synchronized(this) {
            active.remove(domain)
        }
    }

    fun reset() {
        synchronized(this) {
            active.clear()
            committed.clear()
        }
    }
}
