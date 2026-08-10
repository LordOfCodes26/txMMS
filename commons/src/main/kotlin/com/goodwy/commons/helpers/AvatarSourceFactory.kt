package com.goodwy.commons.helpers

/**
 * Maps precomputed [AvatarBindData] to [AvatarSource] for [com.goodwy.commons.views.ContactAvatarView].
 * No provider queries, URI parsing policy, or monogram color generation at bind time.
 */
object AvatarSourceFactory {

    /**
     * @param preferFullPhoto When true (detail/edit), load [AvatarBindData.safeFullPhotoUri] and
     * use the list thumb as a Glide preview so the avatar appears from list cache immediately.
     */
    fun create(bind: AvatarBindData, preferFullPhoto: Boolean = false): AvatarSource {
        // Person icon only when callers opt in (unsaved / call-log-only / new-contact placeholder).
        // Saved contacts whose display name is a phone number still get a letter/digit monogram —
        // matching Recents when contactID is resolved.
        if (bind.showProfileIcon) {
            return AvatarSource.Monogram(
                initials = "",
                gradientColors = bind.gradientColors,
                drawableIndex = bind.drawableIndex,
                showProfileIcon = true,
                displayName = bind.displayName,
            )
        }

        val monogram = AvatarSource.Monogram(
            initials = bind.initials,
            gradientColors = bind.gradientColors,
            drawableIndex = bind.drawableIndex,
            displayName = bind.displayName,
        )

        if (!bind.usePhotoAvatar || bind.safePhotoThumbUri.isBlank()) {
            return monogram
        }

        if (ContactAvatarInvalidUriTracker.isInvalid(bind.rawContactId, bind.safePhotoThumbUri)) {
            AvatarBindLogger.photoRejected(bind.contactId, bind.safePhotoThumbUri, "INVALID_TRACKER")
            return monogram
        }

        val thumbUri = bind.safePhotoThumbUri
        val fullUri = bind.safeFullPhotoUri.trim().takeIf { it.isNotEmpty() }
            ?.takeUnless { ContactAvatarInvalidUriTracker.isInvalidUri(it) }
        val loadUri = if (preferFullPhoto && fullUri != null) fullUri else thumbUri
        val previewUri = if (preferFullPhoto && thumbUri.isNotBlank() && thumbUri != loadUri) {
            thumbUri
        } else {
            null
        }

        return AvatarSource.Photo(
            photoUri = loadUri,
            fallbackMonogram = monogram,
            previewUri = previewUri,
        )
    }
}
