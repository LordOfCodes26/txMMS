package com.goodwy.commons.providercache.coordinator

import com.goodwy.commons.providercache.display.PendingRecentDelta

enum class CacheDomain {
    CONTACTS,
    RECENTS,
    BOTH,
}

enum class CacheMutationReason {
    CONTACT_INSERTED,
    CONTACT_UPDATED,
    CONTACT_DELETED,
    CONTACTS_BULK_DELETED,
    CONTACT_IMPORT,
    CONTACT_PROVIDER_CHANGED,
    CALL_INSERTED,
    CALL_UPDATED,
    CALL_DELETED,
    CALL_HISTORY_CLEARED,
    GROUPING_CHANGED,
    FILTER_CHANGED,
    STARTUP_REPAIR,
    MIGRATION_REPAIR,
    SYNC_FAILURE_REPAIR,
    MANUAL_REFRESH,
}

data class DisplayCacheMutationRequest(
    val mutationId: Long,
    val domain: CacheDomain,
    val reason: CacheMutationReason,
    val changedContactIds: Set<Long> = emptySet(),
    val deletedContactIds: Set<Long> = emptySet(),
    val affectedPhoneDigits: Set<String> = emptySet(),
    val changedCallIds: Set<Long> = emptySet(),
    val deletedCallIds: Set<Long> = emptySet(),
    val forceFullContacts: Boolean = false,
    val forceFullRecents: Boolean = false,
)

data class ContactDisplayDelta(
    val contactId: Long,
    val mode: String = "UPDATE",
)

data class DisplayCacheMutationResult(
    val mutationId: Long,
    val contactsVersion: Long,
    val recentsVersion: Long,
    val contactDeltas: List<ContactDisplayDelta> = emptyList(),
    val recentDeltas: List<PendingRecentDelta> = emptyList(),
    val contactsNeedFullReload: Boolean = false,
    val recentsNeedFullReload: Boolean = false,
    val validationRepairs: Int = 0,
)
