package com.goodwy.commons.providercache.display

/**
 * Emitted right before a contact's rows are purged from [contact_summaries] / [contact_display_cache]
 * so listeners (e.g. recents display cache) can clear cached name/photo for that contact while the
 * lookup data (lookup key, phone digits) is still available.
 */
data class ContactDisplayDeleted(
    /** Aggregated [ContactsContract.Contacts] id — matches [recent_display_cache.contact_id]. */
    val contactId: Int,
    val lookupKey: String,
    /** Last display name from [contact_summaries] before purge (for cached-name row matching). */
    val displayName: String = "",
    /** Call ids resolved pre-purge so lookup still works after contact tables are cleared. */
    val hintCallIds: List<Int> = emptyList(),
    /** Digit-only strings for each phone the contact had (from [contact_phone_index]). */
    val phoneDigits: List<String>,
    /** E.164 / normalized numbers for each phone the contact had. */
    val normalizedNumbers: List<String>,
)
