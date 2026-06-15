package com.android.mms.models

/**
 * Serializable subset of [AttachmentSelection] for persisting compose drafts in [Draft.attachmentsJson].
 */
data class DraftStoredAttachment(
    val uriString: String,
    val mimetype: String,
    val filename: String,
    val isPending: Boolean,
    /** Full [MmsSlideshow] slide list (text, order, duration) when compose used the slideshow editor. */
    val slideshowJson: String? = null,
)
