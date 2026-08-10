package com.goodwy.commons.providercache.identity

import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.debug.CacheMutationLogger
import com.goodwy.commons.providercache.entities.ContactSummaryEntity

/**
 * Resolves ambiguous contact identifiers to a canonical [ContactIdentity].
 *
 * Rules:
 * - [contact_summaries] and [contact_display_cache] key on aggregate contact ID.
 * - Provider mutation APIs may supply raw contact IDs.
 * - Delete/edit flows must resolve before Room purge.
 */
class ContactIdentityResolver(
    database: ProviderCacheDatabase,
) {
    private val contactDao = database.contactDao()
    private val phoneIndexDao = database.contactPhoneIndexDao()

    suspend fun resolveFromRawId(rawContactId: Long): ContactIdentity? {
        if (rawContactId <= 0L) return null
        val summaries = contactDao.getSummariesByPrimaryRawIds(listOf(rawContactId.toInt()))
            .ifEmpty {
                contactDao.getSummariesByIds(listOf(rawContactId.toInt()))
            }
        val summary = summaries.firstOrNull() ?: return null
        return buildIdentity(summary, setOf(rawContactId))
    }

    suspend fun resolveFromAggregateId(aggregateContactId: Long): ContactIdentity? {
        if (aggregateContactId <= 0L) return null
        val summary = contactDao.getSummaryById(aggregateContactId.toInt()) ?: return null
        val rawIds = buildSet {
            if (summary.primaryRawId > 0) add(summary.primaryRawId.toLong())
            add(aggregateContactId)
        }
        return buildIdentity(summary, rawIds)
    }

    suspend fun resolveFromLookupKey(lookupKey: String): ContactIdentity? {
        if (lookupKey.isBlank()) return null
        val summary = contactDao.getSummaryByLookupKey(lookupKey) ?: return null
        val rawIds = buildSet {
            if (summary.primaryRawId > 0) add(summary.primaryRawId.toLong())
            add(summary.contactId.toLong())
        }
        return buildIdentity(summary, rawIds)
    }

    suspend fun resolveFromPhoneDigits(digits: String): ContactIdentity? {
        if (digits.isBlank()) return null
        val contactId = phoneIndexDao.getContactIdForDigits(digits) ?: return null
        return resolveFromAggregateId(contactId.toLong())
    }

    suspend fun resolveRawIds(rawContactIds: Collection<Long>): List<ContactIdentity> =
        rawContactIds
            .filter { it > 0L }
            .distinct()
            .mapNotNull { resolveFromRawId(it) }
            .distinctBy { it.aggregateContactId }

    private suspend fun buildIdentity(
        summary: ContactSummaryEntity,
        rawIds: Set<Long>,
    ): ContactIdentity {
        val phones = phoneIndexDao.getDigitsForContactId(summary.contactId).toSet()
        val identity = ContactIdentity(
            aggregateContactId = summary.contactId.toLong(),
            rawContactIds = rawIds,
            lookupKey = summary.lookupKey.takeIf { it.isNotEmpty() },
            phoneDigits = phones,
        )
        CacheMutationLogger.contactIdentityResolved(
            rawIds = identity.rawContactIds,
            aggregateId = identity.aggregateContactId,
            lookupKey = identity.lookupKey,
            phones = identity.phoneDigits.size,
        )
        return identity
    }

    fun logMissing(inputType: ContactIdentityInputType, id: Long) {
        CacheMutationLogger.contactIdentityMissing(inputType.name, id)
    }
}
