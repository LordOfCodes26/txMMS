package com.goodwy.commons.providercache.coordinator

import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.debug.CacheFailureDomain
import com.goodwy.commons.providercache.debug.CacheFailureInjector
import com.goodwy.commons.providercache.debug.CacheFailurePoint
import com.goodwy.commons.providercache.debug.CacheMutationLogger
import com.goodwy.commons.providercache.display.PendingRecentDelta
import com.goodwy.commons.providercache.entities.CacheMetadataDomain
import com.goodwy.commons.providercache.metadata.CacheMetadataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Single owner for display-cache version increments, mutation serialization, and post-commit UI signals.
 *
 * Repositories and sync managers enqueue mutations here instead of bumping versions independently.
 * Provider IO must complete before entering [enqueueMutation]; Room writes run under [mutationMutex].
 */
class DisplayCacheCoordinator(
    private val database: ProviderCacheDatabase,
    val metadataStore: CacheMetadataStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutationMutex = Mutex()
    private val nextMutationId = AtomicLong(1L)

    @Volatile
    private var latestCommittedContactsMutationId = 0L

    @Volatile
    private var latestCommittedRecentsMutationId = 0L

    fun peekLatestCommittedContactsMutationId(): Long = latestCommittedContactsMutationId

    fun peekLatestCommittedRecentsMutationId(): Long = latestCommittedRecentsMutationId

    @Volatile
    private var inFlightContactsMutationId: Long? = null

    @Volatile
    private var inFlightRecentsMutationId: Long? = null

    /** Set by app layer via [DisplayCacheInstall] for recents rebuilds. */
    var recentDisplayRebuildHandler: suspend (com.goodwy.commons.providercache.display.RecentDisplayRebuildRequest) ->
        com.goodwy.commons.providercache.display.RecentDisplayCacheRebuildResult = {
            com.goodwy.commons.providercache.display.RecentDisplayCacheRebuildResult.EMPTY
        }

    /** Contacts display rebuild — delegated to ContactsRepository internals during migration. */
    var contactsDisplayRebuildHandler: suspend (DisplayCacheMutationRequest) -> DisplayCacheMutationResult = {
        emptyResult(it.mutationId)
    }

    /** Called on main thread after a mutation commits successfully. */
    var onContactsDisplayCommitted: ((DisplayCacheMutationResult) -> Unit)? = null

    /** Called on main thread after recents display cache commits. */
    var onRecentsDisplayCommitted: ((DisplayCacheMutationResult) -> Unit)? = null

    /**
     * Allocates the next mutation id, never returning one already committed to Room.
     *
     * [nextMutationId] is in-memory and restarts at 1 every process, but
     * [CacheMetadataStore.commitDisplayMutation] compares against a *persisted*
     * `lastSuccessfulMutationId`. Without this floor, the opening commits of each cold start reuse
     * last session's ids, get rejected as duplicates, and skip their version bump — which leaves the
     * UI reconciling on `versions_match` and never repainting.
     *
     * The CAS loop keeps the counter monotonic under concurrent allocation and lets the floor rise
     * as the store finishes seeding, so no init ordering is required.
     */
    fun allocateMutationId(): Long {
        val floor = metadataStore.peekMaxCommittedMutationId() + 1L
        while (true) {
            val current = nextMutationId.get()
            val candidate = maxOf(current, floor)
            if (nextMutationId.compareAndSet(current, candidate + 1L)) return candidate
        }
    }

    fun peekLatestCommittedMutationId(): Long =
        maxOf(latestCommittedContactsMutationId, latestCommittedRecentsMutationId)

    fun dumpCoordinatorState(): String =
        "inFlightContacts=$inFlightContactsMutationId " +
            "inFlightRecents=$inFlightRecentsMutationId " +
            "latestCommittedContacts=$latestCommittedContactsMutationId " +
            "latestCommittedRecents=$latestCommittedRecentsMutationId " +
            "contactsDisplay=${metadataStore.peekContactsDisplayVersion()} " +
            "recentsDisplay=${metadataStore.peekRecentsDisplayVersion()} " +
            "failureHook=${CacheFailureInjector.peekPending()}"

    /** @see shouldIgnoreStaleCommit */
    fun isMutationCurrent(mutationId: Long): Boolean =
        mutationId >= maxOf(latestCommittedContactsMutationId, latestCommittedRecentsMutationId)

    /** Call before scheduling a contacts display-cache rebuild. */
    fun beginContactsMutation(): Long {
        val id = allocateMutationId()
        inFlightContactsMutationId = id
        scope.launch {
            metadataStore.markDirty(CacheMetadataDomain.CONTACTS_DISPLAY, id, "in_flight")
        }
        return id
    }

    /** Call before scheduling a recents display-cache rebuild. */
    fun beginRecentsMutation(): Long {
        val id = allocateMutationId()
        inFlightRecentsMutationId = id
        scope.launch {
            metadataStore.markDirty(CacheMetadataDomain.RECENTS_DISPLAY, id, "in_flight")
        }
        return id
    }

    /**
     * The id of the tracked in-flight mutation, or `null` when there is none.
     *
     * Null rather than `nextMutationId.get()`: that peek does not consume the id, so every commit
     * arriving outside a `begin*Mutation()` window read back the *same* value and tripped the
     * duplicate guard in [CacheMetadataStore.commitDisplayMutation]. Callers chain `?:
     * allocateMutationId()`, which is the correct answer — a commit with nothing in flight is a new
     * mutation and deserves a fresh id.
     */
    fun peekInFlightContactsMutationId(): Long? = inFlightContactsMutationId

    /** @see peekInFlightContactsMutationId */
    fun peekInFlightRecentsMutationId(): Long? = inFlightRecentsMutationId

    private fun clearInFlightContacts(mutationId: Long) {
        if (inFlightContactsMutationId == mutationId) inFlightContactsMutationId = null
    }

    private fun clearInFlightRecents(mutationId: Long) {
        if (inFlightRecentsMutationId == mutationId) inFlightRecentsMutationId = null
    }

    /**
     * Enqueue and execute a display-cache mutation. Compatible requests may be coalesced by callers
     * before reaching here; this method serializes all commits.
     */
    suspend fun enqueueMutation(request: DisplayCacheMutationRequest): DisplayCacheMutationResult =
        mutationMutex.withLock {
            executeMutationLocked(request)
        }

    /**
     * Commit a contacts display version bump after an existing rebuild path completes.
     * Bridges legacy ContactsRepository rebuild handlers into the coordinator.
     */
    suspend fun commitContactsDisplay(
        mutationId: Long,
        reason: CacheMutationReason,
        rowCount: Int,
        meaningfulChange: Boolean = true,
        contactDeltas: List<ContactDisplayDelta> = emptyList(),
        needsFullReload: Boolean = false,
    ): Long {
        if (shouldIgnoreStaleCommit(mutationId, latestCommittedContactsMutationId)) {
            com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger.log(
                "cacheMutationSkipped id=$mutationId reason=ALREADY_COMMITTED",
            )
            return metadataStore.peekContactsDisplayVersion()
        }
        CacheFailureInjector.maybeThrow(CacheFailureDomain.CONTACTS, CacheFailurePoint.BEFORE_DISPLAY_WRITE)
        CacheMutationLogger.mutationStart(mutationId, CacheMutationLogger.Domain.CONTACTS, reason.name)
        CacheMutationLogger.mutationStage(mutationId, CacheMutationLogger.Stage.DISPLAY_WRITE)
        CacheFailureInjector.maybeThrow(CacheFailureDomain.CONTACTS, CacheFailurePoint.AFTER_DISPLAY_WRITE)
        CacheFailureInjector.maybeThrow(CacheFailureDomain.CONTACTS, CacheFailurePoint.BEFORE_VERSION_COMMIT)
        val version = metadataStore.commitDisplayMutation(
            domain = CacheMetadataDomain.CONTACTS_DISPLAY,
            mutationId = mutationId,
            reason = reason.name,
            rowCount = rowCount,
            meaningfulChange = meaningfulChange,
        )
        latestCommittedContactsMutationId = mutationId
        CacheMutationLogger.mutationStage(mutationId, CacheMutationLogger.Stage.COMMIT)
        val result = DisplayCacheMutationResult(
            mutationId = mutationId,
            contactsVersion = version,
            recentsVersion = metadataStore.peekRecentsDisplayVersion(),
            contactDeltas = contactDeltas,
            contactsNeedFullReload = needsFullReload,
        )
        CacheMutationLogger.mutationEnd(mutationId, success = true, version = version)
        CacheFailureInjector.maybeThrow(CacheFailureDomain.CONTACTS, CacheFailurePoint.AFTER_VERSION_COMMIT_BEFORE_NOTIFY)
        CacheMutationLogger.mutationStage(mutationId, CacheMutationLogger.Stage.NOTIFY)
        clearInFlightContacts(mutationId)
        onContactsDisplayCommitted?.invoke(result)
        return version
    }

    /**
     * Commit a recents display version bump after an existing rebuild path completes.
     */
    suspend fun commitRecentsDisplay(
        mutationId: Long,
        reason: CacheMutationReason,
        rowCount: Int,
        meaningfulChange: Boolean = true,
        recentDeltas: List<PendingRecentDelta> = emptyList(),
        needsFullReload: Boolean = false,
    ): Long {
        if (shouldIgnoreStaleCommit(mutationId, latestCommittedRecentsMutationId)) {
            com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger.log(
                "cacheMutationSkipped id=$mutationId reason=ALREADY_COMMITTED",
            )
            return metadataStore.peekRecentsDisplayVersion()
        }
        CacheFailureInjector.maybeThrow(CacheFailureDomain.RECENTS, CacheFailurePoint.BEFORE_DISPLAY_WRITE)
        CacheMutationLogger.mutationStart(mutationId, CacheMutationLogger.Domain.RECENTS, reason.name)
        CacheMutationLogger.mutationStage(mutationId, CacheMutationLogger.Stage.DISPLAY_WRITE)
        CacheFailureInjector.maybeThrow(CacheFailureDomain.RECENTS, CacheFailurePoint.AFTER_DISPLAY_WRITE)
        CacheFailureInjector.maybeThrow(CacheFailureDomain.RECENTS, CacheFailurePoint.BEFORE_VERSION_COMMIT)
        val version = metadataStore.commitDisplayMutation(
            domain = CacheMetadataDomain.RECENTS_DISPLAY,
            mutationId = mutationId,
            reason = reason.name,
            rowCount = rowCount,
            meaningfulChange = meaningfulChange,
        )
        latestCommittedRecentsMutationId = mutationId
        CacheMutationLogger.mutationStage(mutationId, CacheMutationLogger.Stage.COMMIT)
        val result = DisplayCacheMutationResult(
            mutationId = mutationId,
            contactsVersion = metadataStore.peekContactsDisplayVersion(),
            recentsVersion = version,
            recentDeltas = recentDeltas,
            recentsNeedFullReload = needsFullReload,
        )
        CacheMutationLogger.mutationEnd(mutationId, success = true, version = version)
        CacheFailureInjector.maybeThrow(CacheFailureDomain.RECENTS, CacheFailurePoint.AFTER_VERSION_COMMIT_BEFORE_NOTIFY)
        CacheMutationLogger.mutationStage(mutationId, CacheMutationLogger.Stage.NOTIFY)
        clearInFlightRecents(mutationId)
        onRecentsDisplayCommitted?.invoke(result)
        return version
    }

    suspend fun commitRawContacts(
        mutationId: Long,
        reason: CacheMutationReason,
        rowCount: Int,
        meaningfulChange: Boolean = true,
    ): Long = metadataStore.commitRawMutation(
        domain = CacheMetadataDomain.CONTACTS_RAW,
        mutationId = mutationId,
        reason = reason.name,
        rowCount = rowCount,
        meaningfulChange = meaningfulChange,
    )

    suspend fun commitRawRecents(
        mutationId: Long,
        reason: CacheMutationReason,
        rowCount: Int,
        meaningfulChange: Boolean = true,
    ): Long = metadataStore.commitRawMutation(
        domain = CacheMetadataDomain.RECENTS_RAW,
        mutationId = mutationId,
        reason = reason.name,
        rowCount = rowCount,
        meaningfulChange = meaningfulChange,
    )

    suspend fun markRepairRequired(domain: String, reason: String) {
        metadataStore.markRepairRequired(domain, reason)
    }

    private suspend fun executeMutationLocked(
        request: DisplayCacheMutationRequest,
    ): DisplayCacheMutationResult {
        CacheMutationLogger.mutationStart(request.mutationId, request.domain.toLogDomain(), request.reason.name)
        return try {
            when (request.domain) {
                CacheDomain.CONTACTS -> contactsDisplayRebuildHandler(request)
                CacheDomain.RECENTS -> {
                    // Recents rebuilds still flow through CallLogRepository scheduler during migration.
                    emptyResult(request.mutationId)
                }
                CacheDomain.BOTH -> {
                    val contacts = contactsDisplayRebuildHandler(request)
                    contacts.copy(
                        recentsVersion = metadataStore.peekRecentsDisplayVersion(),
                    )
                }
            }.also { result ->
                when (request.domain) {
                    CacheDomain.CONTACTS -> latestCommittedContactsMutationId = request.mutationId
                    CacheDomain.RECENTS -> latestCommittedRecentsMutationId = request.mutationId
                    CacheDomain.BOTH -> {
                        latestCommittedContactsMutationId = request.mutationId
                        latestCommittedRecentsMutationId = request.mutationId
                    }
                }
                CacheMutationLogger.mutationEnd(
                    request.mutationId,
                    success = true,
                    version = result.contactsVersion.coerceAtLeast(result.recentsVersion),
                )
            }
        } catch (e: Exception) {
            CacheMutationLogger.mutationFailed(
                request.mutationId,
                CacheMutationLogger.Stage.DISPLAY_WRITE,
                e.message ?: e.javaClass.simpleName,
            )
            metadataStore.markRepairRequired(
                when (request.domain) {
                    CacheDomain.CONTACTS -> CacheMetadataDomain.CONTACTS_DISPLAY
                    CacheDomain.RECENTS -> CacheMetadataDomain.RECENTS_DISPLAY
                    CacheDomain.BOTH -> CacheMetadataDomain.CONTACTS_DISPLAY
                },
                "mutation_failed:${request.reason}",
            )
            throw e
        }
    }

    private fun emptyResult(mutationId: Long) = DisplayCacheMutationResult(
        mutationId = mutationId,
        contactsVersion = metadataStore.peekContactsDisplayVersion(),
        recentsVersion = metadataStore.peekRecentsDisplayVersion(),
    )

    private fun CacheDomain.toLogDomain(): CacheMutationLogger.Domain = when (this) {
        CacheDomain.CONTACTS -> CacheMutationLogger.Domain.CONTACTS
        CacheDomain.RECENTS -> CacheMutationLogger.Domain.RECENTS
        CacheDomain.BOTH -> CacheMutationLogger.Domain.BOTH
    }

    companion object {
        /** Older mutation finishing after a newer commit must be ignored. */
        fun shouldIgnoreStaleCommit(mutationId: Long, latestCommittedMutationId: Long): Boolean =
            mutationId < latestCommittedMutationId

        /** Same target version while reconcile is in-flight coalesces duplicate callbacks. */
        fun shouldCoalesceInFlightReconcile(
            inFlightTargetVersion: Long,
            targetVersion: Long,
            reconcileJobActive: Boolean,
        ): Boolean = reconcileJobActive && inFlightTargetVersion == targetVersion
    }
}
