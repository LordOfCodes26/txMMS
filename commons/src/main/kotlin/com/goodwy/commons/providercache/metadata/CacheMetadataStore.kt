package com.goodwy.commons.providercache.metadata

import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.dao.CacheMetadataDao
import com.goodwy.commons.providercache.entities.CacheMetadataDomain
import com.goodwy.commons.providercache.entities.CacheMetadataEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Durable cache version and dirty/repair state backed by [cache_metadata] Room table.
 * In-memory [StateFlow] mirrors are seeded at startup and updated on each commit.
 */
class CacheMetadataStore(
    database: ProviderCacheDatabase,
) {
    private val dao: CacheMetadataDao = database.cacheMetadataDao()
    private val mutex = Mutex()

    private val _contactsDisplayVersion = MutableStateFlow(0L)
    val contactsDisplayVersion: StateFlow<Long> = _contactsDisplayVersion.asStateFlow()

    private val _recentsDisplayVersion = MutableStateFlow(0L)
    val recentsDisplayVersion: StateFlow<Long> = _recentsDisplayVersion.asStateFlow()

    private val _contactsRawVersion = MutableStateFlow(0L)
    val contactsRawVersion: StateFlow<Long> = _contactsRawVersion.asStateFlow()

    private val _recentsRawVersion = MutableStateFlow(0L)
    val recentsRawVersion: StateFlow<Long> = _recentsRawVersion.asStateFlow()

    @Volatile
    private var contactsDisplayDirty: Boolean = true

    @Volatile
    private var recentsDisplayDirty: Boolean = true

    @Volatile
    private var contactsDisplayRepairRequired: Boolean = true

    @Volatile
    private var recentsDisplayRepairRequired: Boolean = true

    fun peekContactsDisplayDirty(): Boolean = contactsDisplayDirty

    fun peekRecentsDisplayDirty(): Boolean = recentsDisplayDirty

    fun peekContactsDisplayRepairRequired(): Boolean = contactsDisplayRepairRequired

    fun peekRecentsDisplayRepairRequired(): Boolean = recentsDisplayRepairRequired

    @Volatile
    private var maxCommittedMutationId: Long = 0L

    /**
     * Highest [CacheMetadataEntity.lastSuccessfulMutationId] persisted across every domain,
     * *including ids written by previous processes*.
     *
     * [commitDisplayMutation] rejects a commit whose id equals the persisted one, so the allocator
     * feeding it must never hand out an id at or below this mark. It is in-memory and restarts at 1
     * each process, which is why it has to be floored against this durable value — otherwise the
     * first commits of every cold start collide with last session's and are silently dropped.
     */
    fun peekMaxCommittedMutationId(): Long = maxCommittedMutationId

    suspend fun ensureSeeded() = mutex.withLock {
        CacheMetadataDomain.ALL.forEach { domain ->
            if (dao.getByDomain(domain) == null) {
                dao.upsert(
                    CacheMetadataEntity(
                        domain = domain,
                        displayVersion = 0L,
                        rawVersion = 0L,
                        dirty = true,
                        repairRequired = true,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        refreshFlowsFromRoom()
    }

    suspend fun refreshFlowsFromRoom() {
        dao.getAll().forEach { entity ->
            publishFlow(entity.domain, entity)
        }
    }

    fun peekContactsDisplayVersion(): Long = _contactsDisplayVersion.value

    fun peekRecentsDisplayVersion(): Long = _recentsDisplayVersion.value

    suspend fun markDirty(domain: String, mutationId: Long, reason: String) = mutex.withLock {
        val existing = dao.getByDomain(domain) ?: defaultEntity(domain)
        dao.upsert(
            existing.copy(
                dirty = true,
                lastMutationReason = reason,
                lastSuccessfulMutationId = mutationId,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun markRepairRequired(domain: String, reason: String) = mutex.withLock {
        val existing = dao.getByDomain(domain) ?: defaultEntity(domain)
        dao.upsert(
            existing.copy(
                repairRequired = true,
                lastMutationReason = reason,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Clears stale dirty/repair flags without bumping versions — used when content validation
     * already confirmed the domain is healthy.
     */
    suspend fun acknowledgeHealthy(domain: String, reason: String) = mutex.withLock {
        val existing = dao.getByDomain(domain) ?: return@withLock
        if (!existing.dirty && !existing.repairRequired) return@withLock
        val updated = existing.copy(
            dirty = false,
            repairRequired = false,
            lastMutationReason = reason,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(updated)
        publishFlow(domain, updated)
    }

    /**
     * Bumps display version only when [meaningfulChange] is true.
     * Returns the new display version.
     */
    suspend fun commitDisplayMutation(
        domain: String,
        mutationId: Long,
        reason: String,
        rowCount: Int,
        checksum: Long = 0L,
        bumpRaw: Boolean = false,
        meaningfulChange: Boolean = true,
    ): Long = mutex.withLock {
        val existing = dao.getByDomain(domain) ?: defaultEntity(domain)
        if (mutationId > 0L &&
            existing.lastSuccessfulMutationId == mutationId &&
            !existing.dirty
        ) {
            com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger.log(
                "duplicateMutationCommitPrevented domain=$domain mutationId=$mutationId " +
                    "displayVersion=${existing.displayVersion}",
            )
            publishFlow(domain, existing)
            return@withLock existing.displayVersion
        }
        val newDisplayVersion = if (meaningfulChange) {
            existing.displayVersion + 1L
        } else {
            existing.displayVersion
        }
        val newRawVersion = if (bumpRaw && meaningfulChange) {
            existing.rawVersion + 1L
        } else {
            existing.rawVersion
        }
        val updated = existing.copy(
            displayVersion = newDisplayVersion,
            rawVersion = newRawVersion,
            lastSuccessfulMutationId = mutationId,
            lastMutationReason = reason,
            rowCount = rowCount,
            contentChecksum = checksum,
            dirty = false,
            repairRequired = false,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(updated)
        publishFlow(domain, updated)
        newDisplayVersion
    }

    suspend fun commitRawMutation(
        domain: String,
        mutationId: Long,
        reason: String,
        rowCount: Int,
        checksum: Long = 0L,
        meaningfulChange: Boolean = true,
    ): Long = mutex.withLock {
        val existing = dao.getByDomain(domain) ?: defaultEntity(domain)
        val newRawVersion = if (meaningfulChange) {
            existing.rawVersion + 1L
        } else {
            existing.rawVersion
        }
        val updated = existing.copy(
            rawVersion = newRawVersion,
            lastSuccessfulMutationId = mutationId,
            lastMutationReason = reason,
            rowCount = rowCount,
            contentChecksum = checksum,
            dirty = false,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(updated)
        publishFlow(domain, updated)
        newRawVersion
    }

    suspend fun getEntity(domain: String): CacheMetadataEntity? = dao.getByDomain(domain)

    suspend fun getAll(): List<CacheMetadataEntity> = dao.getAll()

    private fun publishFlow(domain: String, entity: CacheMetadataEntity) {
        // Outside the `when` on purpose: the watermark spans every domain, not just the four
        // with version mirrors.
        if (entity.lastSuccessfulMutationId > maxCommittedMutationId) {
            maxCommittedMutationId = entity.lastSuccessfulMutationId
        }
        when (domain) {
            CacheMetadataDomain.CONTACTS_DISPLAY -> {
                _contactsDisplayVersion.value = entity.displayVersion
                contactsDisplayDirty = entity.dirty
                contactsDisplayRepairRequired = entity.repairRequired
            }
            CacheMetadataDomain.RECENTS_DISPLAY -> {
                _recentsDisplayVersion.value = entity.displayVersion
                recentsDisplayDirty = entity.dirty
                recentsDisplayRepairRequired = entity.repairRequired
            }
            CacheMetadataDomain.CONTACTS_RAW -> _contactsRawVersion.value = entity.rawVersion
            CacheMetadataDomain.RECENTS_RAW -> _recentsRawVersion.value = entity.rawVersion
        }
    }

    private fun defaultEntity(domain: String) = CacheMetadataEntity(
        domain = domain,
        dirty = true,
        repairRequired = true,
        updatedAt = System.currentTimeMillis(),
    )
}
