package com.goodwy.commons.providercache.display

import android.util.Log
import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.entities.ContactPhoneIndexEntity

/**
 * Resolves surviving contacts for phone numbers affected by contact deletion while Room tables
 * still contain [contact_phone_index] / [contact_summaries] rows.
 */
object ContactDeletedRecentsResolver {

    private const val TAG = "RecentContactDelete"

    suspend fun resolve(
        database: ProviderCacheDatabase,
        deleted: List<ContactDisplayDeleted>,
        deletedContactIds: List<Int>,
        groupByContact: Boolean,
    ): ContactDeletedRecentsResolution {
        if (deleted.isEmpty()) {
            return ContactDeletedRecentsResolution.EMPTY.copy(groupByContact = groupByContact)
        }
        val excludeIds = deletedContactIds.filter { it > 0 }.distinct()
        val allNormalized = deleted.flatMap { it.normalizedNumbers }.filter { it.isNotEmpty() }.distinct()
        val allDigits = deleted.flatMap { it.phoneDigits }.filter { it.isNotEmpty() }.distinct()
        if (allNormalized.isEmpty() && allDigits.isEmpty()) {
            return ContactDeletedRecentsResolution(
                replacementByNormalizedNumber = emptyMap(),
                deletedContactIds = excludeIds.toSet(),
                groupByContact = groupByContact,
            )
        }

        val phoneIndexDao = database.contactPhoneIndexDao()
        val indexRows = phoneIndexDao.findByPhoneNumbersExcluding(
            excludeContactIds = excludeIds.ifEmpty { listOf(-1) },
            normalizedNumbers = allNormalized,
            phoneDigits = allDigits,
            hasNormalized = if (allNormalized.isNotEmpty()) 1 else 0,
            hasDigits = if (allDigits.isNotEmpty()) 1 else 0,
        )
        val survivingContactIds = indexRows.map { it.contactId }.distinct()
        val summariesById = if (survivingContactIds.isEmpty()) {
            emptyMap()
        } else {
            database.contactDao().getSummariesByIds(survivingContactIds).associateBy { it.contactId }
        }
        val displayByContactId = if (survivingContactIds.isEmpty()) {
            emptyMap()
        } else {
            database.contactDisplayCacheDao()
                .getByContactIds(survivingContactIds)
                .groupBy { it.contactId }
                .mapValues { (_, rows) -> rows.minByOrNull { it.rawId } ?: rows.first() }
        }

        val replacementByNormalized = LinkedHashMap<String, ContactReplacementInfo?>()
        deleted.forEach { event ->
            val phoneKeys = LinkedHashSet<String>()
            event.normalizedNumbers.filter { it.isNotEmpty() }.forEach { phoneKeys.add(it) }
            event.phoneDigits.filter { it.isNotEmpty() }.forEach { phoneKeys.add(it) }

            phoneKeys.forEach { phoneKey ->
                if (phoneKey in replacementByNormalized) return@forEach
                val candidates = indexRows.filter { row ->
                    phoneIndexRowMatches(row, phoneKey, event.normalizedNumbers, event.phoneDigits)
                }
                val replacement = RecentDisplayContactPicker.pickBest(
                    candidates,
                    summariesById,
                    displayByContactId,
                )
                replacementByNormalized[phoneKey] = replacement
                Log.d(
                    TAG,
                    "contactDeleteResolve phoneDigits=$phoneKey deletedContactId=${event.contactId} " +
                        "replacementContactId=${replacement?.contactId}",
                )
            }
        }

        return ContactDeletedRecentsResolution(
            replacementByNormalizedNumber = replacementByNormalized,
            deletedContactIds = excludeIds.toSet(),
            groupByContact = groupByContact,
        )
    }

    private fun phoneIndexRowMatches(
        row: ContactPhoneIndexEntity,
        phoneKey: String,
        normalizedNumbers: List<String>,
        phoneDigits: List<String>,
    ): Boolean {
        if (row.normalizedNumber == phoneKey) return true
        if (normalizedNumbers.any { it == row.normalizedNumber }) return true
        val keyDigits = phoneKey.filter { it.isDigit() }
        if (keyDigits.isEmpty()) return false
        val rowDigits = listOf(row.phoneDigits, row.digits).filter { it.isNotEmpty() }
        return phoneDigits.any { it == row.phoneDigits || it == row.digits } ||
            rowDigits.any { digits ->
                digits == keyDigits || digits.endsWith(keyDigits) || keyDigits.endsWith(digits)
            }
    }
}
