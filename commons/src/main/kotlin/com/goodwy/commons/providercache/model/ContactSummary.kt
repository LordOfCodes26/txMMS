package com.goodwy.commons.providercache.model

data class ContactSummary(
    val contactId: Int,
    val lookupKey: String,
    val displayName: String,
    val photoThumbnailUri: String,
    val hasPhoneNumber: Boolean,
    val lastUpdatedTimestamp: Long,
)
