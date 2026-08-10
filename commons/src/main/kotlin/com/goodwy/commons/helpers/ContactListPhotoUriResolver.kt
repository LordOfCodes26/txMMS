package com.goodwy.commons.helpers

import com.goodwy.commons.providercache.ProviderCacheUserInteractionGate
import com.goodwy.commons.providercache.startup.StartupPhotoBackfillGate

/**
 * Resolves list avatar URIs using the same provider probes as contact detail view.
 * Used when [android.provider.ContactsContract.Contacts.PHOTO_THUMBNAIL_URI] is empty but photo bytes exist.
 */
object ContactListPhotoUriResolver {

    /** Prefer [providerThumbnailUri]; otherwise probe via [ContactsHelper.queryContactPhotoFromProvider]. */
    fun resolveForList(
        helper: ContactsHelper,
        contactId: Int,
        rawContactId: Int,
        providerThumbnailUri: String,
        allowProviderProbe: Boolean = StartupPhotoBackfillGate.allowProviderPhotoProbe(logOnSkip = false),
    ): String {
        val fromThumb = ContactListPhotoUriPolicy.resolveListPhotoThumbUri(
            thumbnailUri = providerThumbnailUri,
            photoUri = "",
        )
        if (fromThumb.isNotEmpty()) return fromThumb
        if (contactId <= 0 || !allowProviderProbe) return ""
        if (StartupPhotoBackfillGate.hasNegativePhotoResult(contactId)) return ""
        val photo = helper.queryContactPhotoFromProvider(contactId, rawContactId)
        val resolved = listUriFromDetailedQuery(photo.thumbnailUri, photo.photoUri)
        if (resolved.isEmpty()) {
            StartupPhotoBackfillGate.recordNoPhoto(contactId)
        }
        return resolved
    }

    /** URIs from [ContactsHelper.queryContactPhotoFromProvider] are verified and safe for the list. */
    fun listUriFromDetailedQuery(thumbnailUri: String, photoUri: String): String {
        val thumb = thumbnailUri.trim()
        if (thumb.isNotEmpty()) return thumb
        val photo = photoUri.trim()
        return photo
    }
}
