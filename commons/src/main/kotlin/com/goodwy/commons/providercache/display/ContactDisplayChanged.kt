package com.goodwy.commons.providercache.display

/**
 * Emitted after a single-contact display refresh (e.g. post-edit) so recents display cache
 * and visible adapters can update caller name/photo without a full rebuild.
 */
data class ContactDisplayChanged(
    val contactId: Int,
    val lookupKey: String,
    val oldName: String,
    val newName: String,
    val oldPhotoThumbUri: String,
    val newPhotoThumbUri: String,
    /** Digit-only strings for each phone on the contact (from [contact_phone_index]). */
    val phoneDigits: List<String>,
    /** E.164 / normalized numbers for each phone on the contact. */
    val normalizedNumbers: List<String>,
    /** Phones before the edit (from [contact_phone_index] pre-refresh). */
    val oldPhoneDigits: List<String> = emptyList(),
    val oldNormalizedNumbers: List<String> = emptyList(),
    /** True when lookup key changed (merge/split signal). */
    val lookupKeyChanged: Boolean = false,
)
