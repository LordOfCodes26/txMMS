package com.goodwy.commons.providercache.grouping

import com.goodwy.commons.providercache.entities.ContactDisplayCacheEntity
import com.goodwy.commons.providercache.entities.ContactPhoneIndexEntity
import com.goodwy.commons.providercache.entities.ContactSummaryEntity

/**
 * Immutable contact-resolution input for [RecentGroupingEngine].
 *
 * Built once before grouping; must not trigger per-call provider or Room queries.
 *
 * Shared-number owner tie-break (deterministic):
 * 1. visible/allowed contact
 * 2. current valid call [CallLogEntity.contactID] when still eligible
 * 3. starred
 * 4. valid photo
 * 5. non-blank display name
 * 6. lowest aggregate contact ID
 */
data class RecentContactResolutionSnapshot(
    val contactsById: Map<Long, ContactSummaryEntity>,
    val phoneIndexByCanonicalNumber: Map<String, List<ContactPhoneIndexEntity>>,
    val displayByContactId: Map<Long, ContactDisplayCacheEntity>,
    val visibleContactIds: Set<Long>,
    val starredContactIds: Set<Long>,
    val contactsWithValidPhoto: Set<Long>,
) {
    companion object {
        /** BY_NUMBER grouping does not need contact/phone-index tables. */
        val EMPTY = RecentContactResolutionSnapshot(
            contactsById = emptyMap(),
            phoneIndexByCanonicalNumber = emptyMap(),
            displayByContactId = emptyMap(),
            visibleContactIds = emptySet(),
            starredContactIds = emptySet(),
            contactsWithValidPhoto = emptySet(),
        )
    }
}
