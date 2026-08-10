package com.goodwy.commons.helpers

/**
 * List avatars prefer [android.provider.ContactsContract.Contacts.PHOTO_THUMBNAIL_URI].
 *
 * Aggregate `content://…/contacts/{id}/photo` URIs are also allowed: the in-call UI loads them
 * successfully via [android.content.ContentResolver], and many contacts only expose PHOTO_URI
 * (no PHOTO_THUMBNAIL_URI). Broken aggregates are remembered in [ContactAvatarInvalidUriTracker]
 * after the first Glide/[ContentResolver] failure so empty-photo contacts do not retry forever.
 */
object ContactListPhotoUriPolicy {

    /** Prefer thumbnail; fall back to full photo URI (including verified aggregate `/photo`). */
    fun resolveListPhotoThumbUri(thumbnailUri: String, photoUri: String): String {
        val thumb = thumbnailUri.trim()
        if (thumb.isNotEmpty()) {
            return if (ContactAvatarInvalidUriTracker.isInvalidUri(thumb)) "" else thumb
        }
        val photo = photoUri.trim()
        if (photo.isEmpty()) return ""
        return if (ContactAvatarInvalidUriTracker.isInvalidUri(photo)) "" else photo
    }

    /** Re-sanitize a URI loaded from Room (covers rows cached before this policy). */
    fun sanitizeCachedListUri(uri: String): String {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return ""
        if (ContactAvatarInvalidUriTracker.isInvalidUri(trimmed)) return ""
        return trimmed
    }

    fun isAggregateContactsFullPhotoUri(uri: String): Boolean =
        uri.endsWith("/photo", ignoreCase = true)
}
