package com.goodwy.commons.views

import android.content.Context
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.goodwy.commons.R
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.goodwy.commons.extensions.getNameLetter
import com.goodwy.commons.extensions.isEmoji
import com.goodwy.commons.models.contacts.ContactPosterConfig
import com.goodwy.commons.models.contacts.PosterBackgroundType
import com.goodwy.commons.models.contacts.PosterTextStyle

/**
 * Custom view for displaying Contact Poster similar to iOS Contact Poster.
 * Extends FrameLayout and inflates view_contact_poster.xml layout.
 * 
 * This view handles:
 * - Image backgrounds
 * - Gradient backgrounds
 * - Monogram backgrounds with initials
 * - Subject mask foreground images
 * - Avatar display
 * - Text styling
 */
class ContactPosterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // View references
    private val posterBackground: ImageView
    private val posterBlurOverlay: View
    private val posterGradientOverlay: View
    private val posterName: TextView
    private val posterAvatar: ImageView
    private val posterForeground: ImageView

    // Current Glide request tags for memory leak prevention
    private var currentBackgroundRequest: Any? = null
    private var currentForegroundRequest: Any? = null
    private var currentAvatarRequest: Any? = null

    // Blur effect constants
    private val BLUR_RADIUS = 8f // Subtle blur radius
    private val OVERLAY_ALPHA = 0.3f // Semi-transparent overlay for older Android versions

    init {
        // Inflate the layout
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.view_contact_poster, this, true)

        // Get view references
        posterBackground = view.findViewById(R.id.posterBackground)
        posterBlurOverlay = view.findViewById(R.id.posterBlurOverlay)
        posterGradientOverlay = view.findViewById(R.id.posterGradientOverlay)
        posterName = view.findViewById(R.id.posterName)
        posterAvatar = view.findViewById(R.id.posterAvatar)
        posterForeground = view.findViewById(R.id.posterForeground)
    }

    /**
     * Binds the view with contact name, configuration, and avatar.
     * 
     * @param name The contact name to display
     * @param config The ContactPosterConfig containing styling and background settings
     * @param avatarUri The URI of the contact's avatar image (nullable)
     */
    fun bind(
        name: String,
        config: ContactPosterConfig?,
        avatarUri: Uri?
    ) {
        // Clear previous requests to prevent memory leaks
        clearImageRequests()

        // Set name text
        posterName.text = name
        posterName.isVisible = name.isNotEmpty()

        // Apply text styling if config is available
        if (config != null) {
            applyTextStyle(config.textStyle, config.textColor)
            applyNameLayoutStyle(config.nameLayoutStyle)
            
            // Handle avatar visibility
            posterAvatar.isVisible = config.avatarVisible
            if (config.avatarVisible && avatarUri != null) {
                loadAvatar(avatarUri)
            } else {
                clearAvatar()
            }

            // Handle background based on type
            when (config.backgroundType) {
                PosterBackgroundType.IMAGE -> {
                    loadBackgroundImage(config.backgroundUri)
                }
                PosterBackgroundType.GRADIENT -> {
                    showGradientOverlay(config.gradientColors)
                }
                PosterBackgroundType.MONOGRAM -> {
                    generateMonogramBackground(name, config.gradientColors)
                }
            }

            // Handle subject mask foreground
            if (config.subjectMaskUri != null && config.subjectMaskUri!!.isNotEmpty()) {
                loadForegroundImage(config.subjectMaskUri!!)
            } else {
                clearForeground()
            }
        } else {
            // No config - use defaults
            resetToDefaults()
        }
    }

    /**
     * Loads background image using Glide.
     */
    private fun loadBackgroundImage(uri: String?) {
        posterBackground.isVisible = true
        posterGradientOverlay.isVisible = false

        if (uri.isNullOrEmpty()) {
            posterBackground.setImageDrawable(null)
            applyBlurEffect(false)
            return
        }

        val imageUri = try {
            Uri.parse(uri)
        } catch (e: Exception) {
            null
        }

        if (imageUri != null) {
            currentBackgroundRequest = imageUri
            
            Glide.with(this)
                .load(imageUri)
                .apply(
                    RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .centerCrop()
                        .error(null)
                )
                .into(posterBackground)
            
            // Apply blur effect after image is loaded
            applyBlurEffect(true)
        } else {
            posterBackground.setImageDrawable(null)
            applyBlurEffect(false)
        }
    }

    /**
     * Shows gradient overlay with specified colors.
     */
    private fun showGradientOverlay(colors: List<Int>?) {
        posterBackground.isVisible = false
        posterBlurOverlay.isVisible = false
        posterGradientOverlay.isVisible = true

        if (colors.isNullOrEmpty()) {
            // Default gradient if no colors provided
            posterGradientOverlay.background = createDefaultGradient()
        } else {
            val gradientDrawable = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setColors(colors.toIntArray())
            }
            posterGradientOverlay.background = gradientDrawable
        }
    }

    /**
     * Generates monogram background with initials and gradient.
     */
    private fun generateMonogramBackground(name: String, gradientColors: List<Int>?) {
        posterBackground.isVisible = false
        posterBlurOverlay.isVisible = false
        posterGradientOverlay.isVisible = true

        // Get initials from name
        val initials = getInitialsFromName(name)
        
        // Create gradient background
        val colors = gradientColors ?: generateGradientFromName(name)
        val gradientDrawable = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            setColors(colors.toIntArray())
        }
        posterGradientOverlay.background = gradientDrawable

        // Optionally display initials as text overlay (if needed)
        // For now, we'll just use the gradient background
        // The name text will be displayed separately via posterName
    }

    /**
     * Extracts initials from a name string.
     * Returns first letter of first word and first letter of last word,
     * or just first letter if single word.
     */
    private fun getInitialsFromName(name: String): String {
        if (name.isEmpty()) return "A"
        
        // Check for emoji first
        val emoji = name.take(2)
        if (emoji.isEmoji()) {
            return emoji
        }

        val words = name.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        return when {
            words.isEmpty() -> "A"
            words.size == 1 -> words[0].getNameLetter()
            else -> {
                val firstInitial = words.first().getNameLetter()
                val lastInitial = words.last().getNameLetter()
                "$firstInitial$lastInitial"
            }
        }
    }

    /**
     * Generates gradient colors from name hash (for monogram fallback).
     */
    private fun generateGradientFromName(name: String): List<Int> {
        // Use name hash to generate consistent colors
        val hash = name.hashCode()
        val baseColor = Color.HSVToColor(floatArrayOf(
            (hash % 360).toFloat().coerceIn(0f, 360f),
            0.6f + (hash % 20) / 100f,
            0.7f + (hash % 20) / 100f
        ))
        
        // Create lighter and darker variants
        val hsv = FloatArray(3)
        Color.colorToHSV(baseColor, hsv)
        
        val topColor = Color.HSVToColor(floatArrayOf(
            hsv[0],
            (hsv[1] * 0.7f).coerceIn(0f, 1f),
            (hsv[2] * 1.15f).coerceAtMost(0.95f)
        ))
        
        val bottomColor = Color.HSVToColor(floatArrayOf(
            hsv[0],
            (hsv[1] * 1.1f).coerceIn(0f, 1f),
            (hsv[2] * 0.85f).coerceAtLeast(0.1f)
        ))
        
        return listOf(topColor, bottomColor)
    }

    /**
     * Creates default gradient drawable.
     */
    private fun createDefaultGradient(): GradientDrawable {
        return GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(
                0xFF6C7A89.toInt(),
                0xFF4A5568.toInt()
            )
        }
    }

    /**
     * Loads foreground image (subject mask) using Glide.
     */
    private fun loadForegroundImage(uri: String) {
        posterForeground.isVisible = true
        // Set elevation higher than text
        posterForeground.elevation = 8f
        posterName.elevation = 2f

        val imageUri = try {
            Uri.parse(uri)
        } catch (e: Exception) {
            null
        }

        if (imageUri != null) {
            currentForegroundRequest = imageUri
            
            Glide.with(this)
                .load(imageUri)
                .apply(
                    RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .centerCrop()
                        .error(null)
                )
                .into(posterForeground)
        } else {
            clearForeground()
        }
    }

    /**
     * Clears foreground image.
     */
    private fun clearForeground() {
        posterForeground.isVisible = false
        posterForeground.setImageDrawable(null)
        currentForegroundRequest = null
        // Reset text elevation
        posterName.elevation = 2f
    }

    /**
     * Loads avatar image using Glide.
     */
    private fun loadAvatar(uri: Uri) {
        currentAvatarRequest = uri
        
        Glide.with(this)
            .load(uri)
            .apply(
                RequestOptions()
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .circleCrop()
                    .error(null)
            )
            .into(posterAvatar)
    }

    /**
     * Clears avatar image.
     */
    private fun clearAvatar() {
        posterAvatar.setImageDrawable(null)
        currentAvatarRequest = null
    }

    /**
     * Applies text style based on PosterTextStyle enum.
     */
    private fun applyTextStyle(textStyle: PosterTextStyle, textColor: Int) {
        posterName.setTextColor(textColor)
        
        when (textStyle) {
            PosterTextStyle.DEFAULT -> {
                posterName.typeface = android.graphics.Typeface.DEFAULT
                posterName.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
            PosterTextStyle.BOLD -> {
                posterName.typeface = android.graphics.Typeface.DEFAULT_BOLD
                posterName.setTypeface(null, android.graphics.Typeface.BOLD)
            }
            PosterTextStyle.SERIF -> {
                posterName.typeface = android.graphics.Typeface.SERIF
                posterName.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
            PosterTextStyle.ROUNDED -> {
                // Rounded font - try to use system rounded font if available
                try {
                    val roundedTypeface = android.graphics.Typeface.create("sans-serif-rounded", android.graphics.Typeface.NORMAL)
                    posterName.typeface = roundedTypeface
                } catch (e: Exception) {
                    // Fallback to default if rounded font not available
                    posterName.typeface = android.graphics.Typeface.DEFAULT
                }
            }
        }
    }

    /**
     * Applies name layout style (alignment).
     */
    private fun applyNameLayoutStyle(layoutStyle: Int) {
        val gravity = when (layoutStyle) {
            0 -> android.view.Gravity.CENTER // Centered
            1 -> android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL // Left-aligned
            2 -> android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL // Right-aligned
            else -> android.view.Gravity.CENTER
        }
        posterName.gravity = gravity
    }

    /**
     * Resets view to default state when no config is provided.
     */
    private fun resetToDefaults() {
        posterBackground.isVisible = false
        posterBlurOverlay.isVisible = false
        posterGradientOverlay.isVisible = true
        posterGradientOverlay.background = createDefaultGradient()
        posterForeground.isVisible = false
        posterAvatar.isVisible = false
        
        // Default text styling
        posterName.setTextColor(Color.WHITE)
        posterName.typeface = android.graphics.Typeface.DEFAULT_BOLD
        posterName.gravity = android.view.Gravity.CENTER
    }

    /**
     * Clears all Glide image requests to prevent memory leaks.
     * Should be called when view is detached or before loading new images.
     */
    private fun clearImageRequests() {
        // Clear Glide requests
        if (currentBackgroundRequest != null) {
            Glide.with(this).clear(posterBackground)
            currentBackgroundRequest = null
        }
        if (currentForegroundRequest != null) {
            Glide.with(this).clear(posterForeground)
            currentForegroundRequest = null
        }
        if (currentAvatarRequest != null) {
            Glide.with(this).clear(posterAvatar)
            currentAvatarRequest = null
        }
    }

    /**
     * Applies blur effect to posterBlurOverlay.
     * For Android 12+ (API 31+): Uses RenderEffect.createBlurEffect() to blur the background
     * For older versions: Uses semi-transparent black overlay
     * 
     * The blur effect is subtle (8dp radius) and optimized for performance using hardware acceleration.
     * 
     * @param enabled Whether to show the blur effect
     */
    private fun applyBlurEffect(enabled: Boolean) {
        if (!enabled) {
            posterBlurOverlay.isVisible = false
            // Clear RenderEffect if it was applied
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                posterBackground.setRenderEffect(null)
            }
            posterBlurOverlay.background = null
            return
        }

        posterBlurOverlay.isVisible = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+): Use RenderEffect for hardware-accelerated blur
            // Apply blur effect to the background ImageView for optimal performance
            try {
                val blurEffect = RenderEffect.createBlurEffect(
                    BLUR_RADIUS,
                    BLUR_RADIUS,
                    Shader.TileMode.CLAMP
                )
                posterBackground.setRenderEffect(blurEffect)
                // Use lighter semi-transparent overlay when blur is active
                val overlayColor = Color.argb(
                    (255 * (OVERLAY_ALPHA * 0.5f)).toInt(),
                    0,
                    0,
                    0
                )
                posterBlurOverlay.background = ColorDrawable(overlayColor)
            } catch (e: Exception) {
                // Fallback to overlay if RenderEffect fails
                posterBackground.setRenderEffect(null)
                applySemiTransparentOverlay()
            }
        } else {
            // Older Android versions: Use semi-transparent black overlay
            applySemiTransparentOverlay()
        }
    }

    /**
     * Applies semi-transparent black overlay for older Android versions.
     * This provides a subtle darkening effect similar to blur.
     */
    private fun applySemiTransparentOverlay() {
        val overlayColor = Color.argb(
            (255 * OVERLAY_ALPHA).toInt(),
            0,
            0,
            0
        )
        posterBlurOverlay.background = ColorDrawable(overlayColor)
        // Clear RenderEffect if it was set (shouldn't happen on older versions, but safe)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            posterBlurOverlay.setRenderEffect(null)
        }
    }

    /**
     * Clean up when view is detached from window.
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clearImageRequests()
        // Clear RenderEffect to prevent memory leaks and improve performance
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            posterBackground.setRenderEffect(null)
        }
    }
}
