package com.goodwy.commons.providercache.grouping

import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.display.RecentGroupIdentityResolver
import com.goodwy.commons.providercache.display.RecentDisplayContactPicker

/**
 * Loads [RecentContactResolutionSnapshot] from Room contact caches.
 */
object RecentContactResolutionSnapshotLoader {

    suspend fun load(database: ProviderCacheDatabase): RecentContactResolutionSnapshot {
        val phoneIndex = database.contactPhoneIndexDao().getAll()
        val summaries = database.contactDao().getAllSummaries()
        val displayByContactId = database.contactDisplayCacheDao()
            .getByContactIds(summaries.map { it.contactId })
            .groupBy { it.contactId }
            .mapValues { (_, rows) -> rows.minByOrNull { it.rawId } ?: rows.first() }

        val phoneIndexByCanonical = RecentGroupIdentityResolver.buildPhoneIndexByDigits(phoneIndex)

        val visibleContactIds = summaries.mapNotNull { summary ->
            val display = displayByContactId[summary.contactId]
            if (RecentDisplayContactPicker.isVisibleForSnapshot(summary, display)) {
                summary.contactId.toLong()
            } else {
                null
            }
        }.toSet()

        val starredContactIds = displayByContactId.values
            .filter { it.starred > 0 }
            .map { it.contactId.toLong() }
            .toSet()

        val contactsWithValidPhoto = summaries.mapNotNull { summary ->
            val display = displayByContactId[summary.contactId]
            if (RecentDisplayContactPicker.hasPhotoForSnapshot(summary, display)) {
                summary.contactId.toLong()
            } else {
                null
            }
        }.toSet()

        return RecentContactResolutionSnapshot(
            contactsById = summaries.associate { it.contactId.toLong() to it },
            phoneIndexByCanonicalNumber = phoneIndexByCanonical,
            displayByContactId = displayByContactId.mapKeys { it.key.toLong() },
            visibleContactIds = visibleContactIds,
            starredContactIds = starredContactIds,
            contactsWithValidPhoto = contactsWithValidPhoto,
        )
    }
}
