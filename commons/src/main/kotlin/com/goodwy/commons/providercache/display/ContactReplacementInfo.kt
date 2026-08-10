package com.goodwy.commons.providercache.display

/**
 * Surviving contact chosen to replace a deleted contact on shared phone-number recents rows.
 */
data class ContactReplacementInfo(
    val contactId: Int,
    val lookupKey: String,
    val displayName: String,
    val photoThumbUri: String,
    val avatarInitials: String,
)
