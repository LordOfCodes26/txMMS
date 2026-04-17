package com.android.mms.models

/**
 * Serializable subset of [AttachmentSelection] for persisting compose drafts in [Draft.attachmentsJson].
 */
data class DraftStoredAttachment(
    val uriString: String,
    val mimetype: String,
    val filename: String,
    val isPending: Boolean,
)
