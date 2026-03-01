package com.goodwy.commons.models.contacts

/**
 * Enum representing the source type for a contact avatar.
 * Similar to iOS 26 avatar source types.
 *
 * @property POSTER Avatar source from Contact Poster subject mask image
 * @property PHOTO Avatar source from contact photo
 * @property MONOGRAM Avatar source from monogram (initials with gradient background)
 */
enum class AvatarSourceType {
    /**
     * Avatar source from Contact Poster subject mask image.
     * Used when a custom poster configuration includes a subject mask URI.
     */
    POSTER,

    /**
     * Avatar source from contact photo.
     * Used when the contact has a regular photo available.
     */
    PHOTO,

    /**
     * Avatar source from monogram (initials with gradient background).
     * Used as fallback when no photo or poster subject mask is available.
     */
    MONOGRAM
}

/**
 * Data class representing structured avatar style configuration for a contact.
 * Similar to iOS 26 avatar style storage, this stores all visual styling information
 * needed to render an avatar without storing the bitmap directly.
 *
 * **Important:** Avatar must not be stored as bitmap only. This configuration
 * allows for dynamic rendering with customizable styling parameters.
 *
 * @property contactId The unique identifier of the contact this avatar belongs to
 * @property sourceType The type of avatar source (POSTER, PHOTO, or MONOGRAM)
 * @property fontFamily The font family name for monogram text, nullable
 * @property fontWeight The font weight for monogram text (e.g., 400 = normal, 700 = bold), nullable
 * @property textColor The color of the text displayed in the avatar (ARGB format)
 * @property backgroundColors List of color integers (ARGB format) for gradient background,
 *                            used primarily for MONOGRAM type, nullable
 * @property customPhotoUri URI of a custom photo image, used when sourceType is PHOTO, nullable
 * @property usePosterSubject Whether to use the poster subject mask when sourceType is POSTER
 * @property updatedAt Timestamp (in milliseconds since epoch) when this configuration was last updated
 */
data class AvatarStyleConfig(
    val contactId: Long,
    val sourceType: AvatarSourceType,
    val fontFamily: String?,
    val fontWeight: Int?,
    val textColor: Int,
    val backgroundColors: List<Int>?,
    val customPhotoUri: String?,
    val usePosterSubject: Boolean,
    val updatedAt: Long
)
