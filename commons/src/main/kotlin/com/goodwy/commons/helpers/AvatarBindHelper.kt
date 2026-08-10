package com.goodwy.commons.helpers

import com.goodwy.commons.views.ContactAvatarView

/**
 * Single entry point for binding [ContactAvatarView] from precomputed [AvatarBindData].
 */
object AvatarBindHelper {

    fun bind(
        avatarView: ContactAvatarView,
        data: AvatarBindData,
        surface: AvatarBindLogger.Surface,
        previewMode: Boolean = true,
        onPhotoLoadFailed: ((uri: String) -> Unit)? = null,
    ) {
        val source = AvatarSourceFactory.create(data, preferFullPhoto = !previewMode)
        val sourceType = when {
            data.showProfileIcon -> AvatarBindLogger.SourceType.PROFILE
            source is AvatarSource.Photo -> AvatarBindLogger.SourceType.PHOTO
            else -> AvatarBindLogger.SourceType.MONOGRAM
        }
        AvatarBindLogger.bind(
            surface = surface,
            contactId = data.contactId,
            source = sourceType,
            version = data.avatarVersion,
            rawContactId = data.rawContactId,
            photoUri = (source as? AvatarSource.Photo)?.photoUri ?: data.safePhotoThumbUri,
            drawableIndex = data.drawableIndex,
        )
        avatarView.bind(
            source = source,
            cacheSignature = data.avatarVersion.takeIf { it != 0L },
            previewMode = previewMode,
            onPhotoLoadFailed = onPhotoLoadFailed,
        )
    }
}
