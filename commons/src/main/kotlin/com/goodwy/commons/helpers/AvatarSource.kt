package com.goodwy.commons.helpers

import com.goodwy.commons.extensions.isPhoneNumber

/**
 * Sealed class representing different sources for contact avatars.
 * Poster logic has been removed; only Photo, Drawable, and Monogram are used.
 */
sealed class AvatarSource {
    /**
     * Avatar source from contact photo.
     */
    data class Photo(
        val photoUri: String,
        val fallbackMonogram: Monogram? = null
    ) : AvatarSource()

    /**
     * Avatar source from a drawable resource (e.g. for special rows: My Info, Service numbers).
     */
    data class Drawable(
        val drawableResId: Int,
        val tintColor: Int,
        val backgroundColor: Int,
        val backgroundDrawableIndex: Int? = null
    ) : AvatarSource()

    /**
     * Avatar source from monogram (initials with gradient background).
     */
    data class Monogram(
        val initials: String,
        val gradientColors: List<Int>,
        val drawableIndex: Int? = null,
        val fontFamily: String? = null,
        val showProfileIcon: Boolean = false
    ) : AvatarSource()
}

/**
 * Resolves the appropriate avatar source for a contact:
 * - When contact has a photo URI: show image, with monogram as fallback if load fails.
 * - When no photo: show monogram (initials + gradient).
 */
object AvatarResolver {

    /**
     * Resolves avatar source: contact photo if present (with monogram fallback on load failure),
     * otherwise monogram from contact name.
     */
    fun resolve(
        photoUri: String?,
        displayName: String,
        preferProfileIconForPhoneIdentity: Boolean = false
    ): AvatarSource {
        val initials = MonogramGenerator.generateInitials(displayName)
        val drawableIndex = kotlin.math.abs(displayName.hashCode()) % 27
        val gradientColors = MonogramGenerator.generateGradientColors(displayName)
        val monogram = AvatarSource.Monogram(
            initials = initials,
            gradientColors = gradientColors,
            drawableIndex = drawableIndex,
            showProfileIcon = preferProfileIconForPhoneIdentity && displayName.isPhoneNumber()
        )

        return if (!photoUri.isNullOrEmpty()) {
            AvatarSource.Photo(photoUri = photoUri, fallbackMonogram = monogram)
        } else {
            monogram
        }
    }
}
