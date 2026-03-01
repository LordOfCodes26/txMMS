package com.goodwy.commons.models.contacts

/**
 * Enum representing the type of background for a Contact Poster.
 * Similar to iOS Contact Poster background options.
 *
 * @property IMAGE Background is a custom image
 * @property GRADIENT Background is a gradient with multiple colors
 * @property MONOGRAM Background is a monogram (typically a single letter or initials)
 */
enum class PosterBackgroundType {
    /**
     * Background uses a custom image.
     * When this type is selected, [ContactPosterConfig.backgroundUri] should contain the image URI.
     */
    IMAGE,

    /**
     * Background uses a gradient with multiple colors.
     * When this type is selected, [ContactPosterConfig.gradientColors] should contain the color list.
     */
    GRADIENT,

    /**
     * Background uses a monogram (typically a single letter or initials).
     * This is usually a simple, text-based background design.
     */
    MONOGRAM
}

/**
 * Enum representing the text style for a Contact Poster.
 * Similar to iOS Contact Poster text style options.
 *
 * @property DEFAULT Default text style (standard system font)
 * @property BOLD Bold text style
 * @property SERIF Serif text style (traditional, formal appearance)
 * @property ROUNDED Rounded text style (softer, modern appearance)
 */
enum class PosterTextStyle {
    /**
     * Default text style using the standard system font.
     * This is the most common and versatile option.
     */
    DEFAULT,

    /**
     * Bold text style for emphasis.
     * Makes the text stand out more prominently.
     */
    BOLD,

    /**
     * Serif text style with traditional, formal appearance.
     * Often used for a more classic or elegant look.
     */
    SERIF,

    /**
     * Rounded text style with softer, modern appearance.
     * Provides a friendly and contemporary aesthetic.
     */
    ROUNDED
}

/**
 * Data class representing the complete configuration for a Contact Poster.
 * Similar to iOS Contact Poster configuration, this stores all visual and layout settings
 * for customizing how a contact's poster appears.
 *
 * @property contactId The unique identifier of the contact this poster belongs to
 * @property backgroundType The type of background (image, gradient, or monogram)
 * @property backgroundUri URI of the background image, if [backgroundType] is [PosterBackgroundType.IMAGE]
 * @property subjectMaskUri URI of the subject mask image, used for advanced image composition
 * @property gradientColors List of color integers (ARGB format) for gradient background,
 *                          used when [backgroundType] is [PosterBackgroundType.GRADIENT]
 * @property textColor The color of the text displayed on the poster (ARGB format)
 * @property textStyle The style of the text (default, bold, serif, or rounded)
 * @property nameLayoutStyle Integer representing the layout style for the contact name
 *                          (e.g., 0 = centered, 1 = left-aligned, 2 = right-aligned)
 * @property avatarVisible Whether the contact's avatar/photo should be visible on the poster
 * @property updatedAt Timestamp (in milliseconds since epoch) when this configuration was last updated
 */
data class ContactPosterConfig(
    val contactId: Long,
    val backgroundType: PosterBackgroundType,
    val backgroundUri: String?,
    val subjectMaskUri: String?,
    val gradientColors: List<Int>?,
    val textColor: Int,
    val textStyle: PosterTextStyle,
    val nameLayoutStyle: Int,
    val avatarVisible: Boolean,
    val updatedAt: Long
)
