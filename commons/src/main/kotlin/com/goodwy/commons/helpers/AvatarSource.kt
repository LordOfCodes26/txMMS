package com.goodwy.commons.helpers

import android.content.Context
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.getAvatarColorForName
import com.goodwy.commons.extensions.isPhoneNumber
import com.goodwy.commons.extensions.toAvatarColorSeed

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
        val fallbackMonogram: Monogram? = null,
        /** List-sized URI shown first on detail/edit so Glide can hit the list cache. */
        val previewUri: String? = null,
    ) : AvatarSource()

    /**
     * Avatar source from a drawable resource (e.g. for special rows: My Info, Service numbers).
     */
    data class Drawable(
        val drawableResId: Int,
        val tintColor: Int,
        val backgroundColor: Int,
        val backgroundDrawableIndex: Int? = null,
        val iconInsetRatio: Float = 0.2f,
        val iconSizePx: Int? = null
    ) : AvatarSource()

    /**
     * Avatar source from monogram (initials with gradient background).
     */
    data class Monogram(
        val initials: String,
        val gradientColors: List<Int>,
        val drawableIndex: Int? = null,
        val fontFamily: String? = null,
        val showProfileIcon: Boolean = false,
        val displayName: String = "",
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
     *
     * @param context When provided, the "colored contacts" setting is honored so the result
     * matches [com.goodwy.commons.providercache.display.ContactDisplayBindComputer.monogramSource]
     * (used by the contacts list / call log cache) — flat single color when the setting is off,
     * the 27-drawable gradient set when it's on. When null, colored contacts is assumed enabled.
     */
    fun resolve(
        photoUri: String?,
        displayName: String,
        preferProfileIconForPhoneIdentity: Boolean = false,
        context: Context? = null
    ): AvatarSource {
        val initials = MonogramGenerator.generateInitials(displayName)
        val useColoredContacts = context?.baseConfig?.useColoredContacts ?: true
        val drawableIndex = if (useColoredContacts) {
            val colorSeed = displayName.toAvatarColorSeed()
            kotlin.math.abs(colorSeed.hashCode()) % 27
        } else {
            null
        }
        val gradientColors = if (drawableIndex == null) {
            val flatColor = context?.getAvatarColorForName(displayName)
            if (flatColor != null) listOf(flatColor) else MonogramGenerator.generateGradientColors(displayName)
        } else {
            emptyList()
        }
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
