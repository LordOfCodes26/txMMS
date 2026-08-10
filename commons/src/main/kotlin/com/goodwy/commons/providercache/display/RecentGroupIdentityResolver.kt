package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.entities.CallLogEntity
import com.goodwy.commons.providercache.entities.ContactDisplayCacheEntity
import com.goodwy.commons.providercache.entities.ContactPhoneIndexEntity
import com.goodwy.commons.providercache.entities.ContactSummaryEntity

/**
 * Derives stable relational group identity for a raw call-log row.
 *
 * BY_CONTACT groups by resolved aggregate contact identity ([CanonicalPhoneNumberResolver.contactGroupKey]),
 * never by display name — a rename must not change the group key, and two different contacts sharing a
 * display name must not merge. Unresolved callers fall back to [CanonicalPhoneNumberResolver.numberGroupKey].
 */
object RecentGroupIdentityResolver {

    data class Context(
        val summariesById: Map<Int, ContactSummaryEntity>,
        val phoneIndexByDigits: Map<String, List<ContactPhoneIndexEntity>>,
        val displayByContactId: Map<Int, ContactDisplayCacheEntity> = emptyMap(),
    )

    fun resolve(
        call: CallLogEntity,
        mode: RecentGroupingMode,
        context: Context,
    ): RecentGroupIdentity {
        val canonical = CanonicalPhoneNumberResolver.canonicalDigits(
            call.normalizedNumber,
            call.phoneNumber,
        )
        return when (mode) {
            RecentGroupingMode.BY_NUMBER -> resolveByNumber(canonical, call, context)
            RecentGroupingMode.BY_CONTACT -> resolveByContact(canonical, call, context)
        }
    }

    private fun resolveByNumber(
        canonical: String,
        call: CallLogEntity,
        context: Context,
    ): RecentGroupIdentity {
        val displayContactId = resolveDisplayContactId(canonical, call, context)
        return RecentGroupIdentity(
            mode = RecentGroupingMode.BY_NUMBER,
            groupKey = CanonicalPhoneNumberResolver.numberGroupKey(canonical),
            displayContactId = displayContactId,
            normalizedNumbers = setOf(canonical),
        )
    }

    private fun resolveByContact(
        canonical: String,
        call: CallLogEntity,
        context: Context,
    ): RecentGroupIdentity {
        val aggregateId = resolveAggregateContactId(canonical, call, context)
        return if (aggregateId != null) {
            RecentGroupIdentity(
                mode = RecentGroupingMode.BY_CONTACT,
                groupKey = CanonicalPhoneNumberResolver.contactGroupKey(aggregateId),
                displayContactId = aggregateId,
                normalizedNumbers = setOf(canonical),
            )
        } else {
            RecentGroupIdentity(
                mode = RecentGroupingMode.BY_CONTACT,
                groupKey = CanonicalPhoneNumberResolver.numberGroupKey(canonical),
                displayContactId = null,
                normalizedNumbers = setOf(canonical),
            )
        }
    }

    private fun resolveAggregateContactId(
        canonical: String,
        call: CallLogEntity,
        context: Context,
    ): Long? {
        val fromCall = call.contactID?.takeIf { it > 0 && context.summariesById.containsKey(it) }
        if (fromCall != null) return fromCall.toLong()

        val indexRows = phoneIndexRowsForCanonical(canonical, context)
        if (indexRows.isEmpty()) return null

        return RecentDisplayContactPicker.pickBest(
            indexRows = indexRows,
            summariesById = context.summariesById,
            displayByContactId = context.displayByContactId,
            preferredContactId = fromCall,
        )?.contactId?.toLong()
    }

    /**
     * Resolves phone-index rows for a canonical call-log number, including suffix overlap when the
     * stored number is truncated (aligned with [com.goodwy.commons.providercache.dao.CallLogDao.backfillContactInfo]).
     */
    internal fun phoneIndexRowsForCanonical(
        canonical: String,
        context: Context,
    ): List<ContactPhoneIndexEntity> {
        context.phoneIndexByDigits[canonical]?.let { return it }
        return suffixMatchedPhoneIndexRows(canonical, context)
    }

    private fun suffixMatchedPhoneIndexRows(
        canonical: String,
        context: Context,
    ): List<ContactPhoneIndexEntity> {
        val trimmed = canonical.trimStart('0').ifEmpty { canonical }
        // Align with CallLogDao.backfillContactInfo (≥ 7). Shorter dialed numbers (e.g. "22255")
        // must not suffix-match into longer contact numbers (e.g. "061922255").
        if (trimmed.length < CanonicalPhoneNumberResolver.MIN_SUFFIX_MATCH_DIGITS) return emptyList()
        val matched = context.phoneIndexByDigits.values
            .flatten()
            .distinctBy { it.id }
            .filter { row ->
                val stored = rowDigits(row)
                stored.length > trimmed.length && stored.endsWith(trimmed)
            }
        val contactIds = matched.map { it.contactId }.toSet()
        return if (contactIds.size == 1) matched else emptyList()
    }

    private fun rowDigits(row: ContactPhoneIndexEntity): String {
        val fromDigits = row.digits.filter { it.isDigit() }
        if (fromDigits.isNotEmpty()) return fromDigits
        return row.normalizedNumber.filter { it.isDigit() }
    }

    private fun resolveDisplayContactId(
        canonical: String,
        call: CallLogEntity,
        context: Context,
    ): Long? {
        val fromCall = call.contactID?.takeIf { it > 0 && context.summariesById.containsKey(it) }
        if (fromCall != null) return fromCall.toLong()
        val indexRows = phoneIndexRowsForCanonical(canonical, context)
        if (indexRows.isEmpty()) return null
        return RecentDisplayContactPicker.pickBest(
            indexRows = indexRows,
            summariesById = context.summariesById,
            displayByContactId = context.displayByContactId,
            preferredContactId = fromCall,
        )?.contactId?.toLong()
    }

    fun buildPhoneIndexByDigits(
        indexRows: List<ContactPhoneIndexEntity>,
    ): Map<String, List<ContactPhoneIndexEntity>> {
        val map = mutableMapOf<String, MutableList<ContactPhoneIndexEntity>>()
        indexRows.forEach { row ->
            val keys = buildSet {
                add(CanonicalPhoneNumberResolver.canonicalDigits(row.normalizedNumber, row.normalizedNumber))
                if (row.phoneDigits.isNotEmpty()) add(row.phoneDigits)
                if (row.digits.isNotEmpty()) add(row.digits.filter { it.isDigit() }.ifEmpty { row.digits })
            }
            keys.filter { it.isNotEmpty() }.forEach { key ->
                map.getOrPut(key) { mutableListOf() }.add(row)
            }
        }
        return map.mapValues { (_, rows) -> rows.distinctBy { it.id } }
    }
}
