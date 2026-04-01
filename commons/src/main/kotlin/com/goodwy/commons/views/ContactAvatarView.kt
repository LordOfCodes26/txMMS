package com.goodwy.commons.views

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.Gravity
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.goodwy.commons.R
import com.goodwy.commons.extensions.createAvatarGradientDrawable
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.helpers.AvatarSource
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey

/**
 * Custom view for displaying contact avatars with support for multiple sources.
 * Extends FrameLayout and inflates view_contact_avatar.xml layout.
 * 
 * This view handles:
 * - Contact photos
 * - Monogram avatars with initials and gradient backgrounds
 * 
 * Features:
 * - Circular clipping
 * - Efficient image loading with thumbnail scaling
 * - Memory-safe with proper cleanup
 */
class ContactAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        /** Inset on each side as a fraction of avatar size; larger = smaller drawn ic_person. */
        private const val PROFILE_ICON_INSET_RATIO = 0.16f
    }

    // View references
    private val avatarBackgroundLayer: View
    private val avatarImage: ImageView
    private val avatarInitials: TextView

    // Current Glide request for memory leak prevention
    private var currentImageRequest: Any? = null

    // Thumbnail size for performance optimization (used when view size not yet known)
    private val THUMBNAIL_SIZE = 200
    // Max decode size for list preview avatars (keeps list scrolling fast)
    private val PREVIEW_MAX_SIZE = 96
    // Track whether current bind is used in compact list/preview UI.
    private var currentPreviewMode: Boolean = false

    /** When true, avatar shows ic_person; padding must track view size (RecyclerView pre-layout bind). */
    private var showingDefaultProfileIcon: Boolean = false

    /** Drawable bind with ratio-based insets (no fixed iconSizePx); padding must track view size. */
    private var drawableIconInsetRatio: Float? = null

    /** Optional explicit avatar background mode. Null means follow system mode. */
    private var avatarDarkModeOverride: Boolean? = null

    fun setAvatarDarkModeOverride(isDarkMode: Boolean?) {
        avatarDarkModeOverride = isDarkMode
    }

    private fun isAvatarDarkMode(): Boolean = avatarDarkModeOverride ?: context.isSystemInDarkMode()

    /**
     * Returns the pixel size to use for Glide override so the image fills the avatar
     * without unnecessary upscaling. Uses the view's current size when laid out.
     */
    private fun avatarLoadSize(): Int {
        val size = minOf(width, height)
        return if (size > 0) size else THUMBNAIL_SIZE
    }

    init {
        // Inflate the layout
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.view_contact_avatar, this, true)

        avatarBackgroundLayer = view.findViewById(R.id.avatar_background_layer)
        avatarImage = view.findViewById(R.id.avatarImage)
        avatarInitials = view.findViewById(R.id.avatarInitials)

        setupCircularClipping()
        // Clip the background layer to the same circle so monogram drawable stays inside root.
        avatarBackgroundLayer.clipToOutline = true
        avatarBackgroundLayer.outlineProvider = outlineProvider
    }

    /**
     * Sets up circular clipping for the avatar view.
     * Uses ViewOutlineProvider for efficient circular clipping.
     * The outline will be updated when the view size changes.
     */
    private fun setupCircularClipping() {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                val w = view.width.coerceAtLeast(0)
                val h = view.height.coerceAtLeast(0)
                val size = minOf(w, h)
                if (size <= 0) return
                // Center the circle in the view so it never exceeds the root element bounds.
                val left = (w - size) / 2
                val top = (h - size) / 2
                outline.setOval(left, top, left + size, top + size)
            }
        }
    }

    /**
     * Binds the avatar view with an AvatarSource.
     * @param source The avatar source (photo URI, monogram, or drawable).
     * @param cacheSignature Optional signature for Glide cache busting (e.g. list refresh time).
     * @param previewMode When true (e.g. contact list), decodes at most PREVIEW_MAX_SIZE for fast scrolling; full photo in contact view.
     */
    fun bind(source: AvatarSource, cacheSignature: Long? = null, previewMode: Boolean = false) {
        // Detect same-photo reload BEFORE clearing anything.
        // If the incoming URI equals the currently-tracked request, cancel the in-flight Glide
        // load without wiping the displayed bitmap so the photo stays visible on screen
        // (e.g. returning to ViewContactActivity from EditContactActivity without saving).
        // For RecyclerView, onDetachedFromWindow() resets currentImageRequest to null, so this
        // path is only taken when the same view instance re-binds the same URI without detaching.
        val incomingUri = (source as? AvatarSource.Photo)
            ?.let { try { Uri.parse(it.photoUri) } catch (_: Exception) { null } }
        val isSamePhotoReload = incomingUri != null && incomingUri == currentImageRequest

        if (isSamePhotoReload) {
            try { Glide.with(context.applicationContext).clear(avatarImage) } catch (_: IllegalArgumentException) {}
            currentImageRequest = null
        } else {
            clearImageRequest()
        }

        when (source) {
            is AvatarSource.Photo -> bindPhoto(source, cacheSignature, previewMode, isSamePhotoReload)
            is AvatarSource.Drawable -> bindDrawable(
                source.drawableResId,
                source.tintColor,
                source.backgroundColor,
                source.backgroundDrawableIndex,
                source.iconInsetRatio,
                source.iconSizePx
            )
            is AvatarSource.Monogram -> bindMonogram(
                source.initials,
                source.gradientColors,
                source.drawableIndex,
                source.showProfileIcon
            )
        }
    }

    /**
     * Binds Photo avatar source.
     * When previewMode is true (contact list), caps decode at PREVIEW_MAX_SIZE for fast scrolling.
     * When false (contact view), uses avatar size for full-quality display.
     */
    private fun bindPhoto(
        source: AvatarSource.Photo,
        cacheSignature: Long? = null,
        previewMode: Boolean = false,
        isSamePhotoReload: Boolean = false
    ) {
        showingDefaultProfileIcon = false
        drawableIconInsetRatio = null
        background = null
        avatarBackgroundLayer.background = null
        avatarBackgroundLayer.isVisible = false
        // Reset ImageView sizing state in case this recycled view previously displayed
        // a drawable/default icon (which uses FIT_CENTER + insets).
        avatarImage.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )
        avatarImage.scaleType = ImageView.ScaleType.CENTER_CROP
        avatarImage.setPadding(0, 0, 0, 0)
        avatarImage.imageTintList = null

        // Parse URI
        val imageUri = try {
            Uri.parse(source.photoUri)
        } catch (e: Exception) {
            null
        }

        if (imageUri != null) {
            if (isSamePhotoReload) {
                avatarInitials.isVisible = false
                avatarImage.isVisible = true
            } else {
                val fallback = source.fallbackMonogram
                if (fallback != null) {
                    bindMonogram(
                        initials = fallback.initials,
                        gradientColors = fallback.gradientColors,
                        drawableIndex = fallback.drawableIndex
                    )
                }
                avatarImage.isVisible = false
                avatarInitials.isVisible = fallback != null
            }

            currentImageRequest = imageUri
            val size = if (previewMode) minOf(avatarLoadSize(), PREVIEW_MAX_SIZE) else avatarLoadSize()
            val requestOptions = if (cacheSignature != null) {
                RequestOptions().diskCacheStrategy(DiskCacheStrategy.RESOURCE).override(size, size).circleCrop().error(null).signature(ObjectKey(cacheSignature))
            } else {
                RequestOptions().diskCacheStrategy(DiskCacheStrategy.RESOURCE).override(size, size).circleCrop().error(null)
            }
            Glide.with(this)
                .load(imageUri)
                .apply(requestOptions)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                        if (model != currentImageRequest) return true
                        val fb = source.fallbackMonogram
                        return if (fb != null) {
                            bindMonogram(fb.initials, fb.gradientColors, fb.drawableIndex)
                            true
                        } else false
                    }
                    override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                        if (model != currentImageRequest) return true
                        avatarInitials.isVisible = false
                        avatarImage.isVisible = true
                        background = null
                        return false
                    }
                })
                .into(avatarImage)
        } else {
            // Invalid or unparseable URI - show fallback monogram if provided
            val fallback = source.fallbackMonogram
            if (fallback != null) {
                bindMonogram(
                    initials = fallback.initials,
                    gradientColors = fallback.gradientColors,
                    drawableIndex = fallback.drawableIndex
                )
            } else {
                avatarImage.setImageDrawable(null)
            }
        }
    }

    /**
     * Binds Drawable avatar source (e.g. special rows: My Info, Service numbers, Company numbers).
     * Shows a drawable icon with tint. Background: when backgroundDrawableIndex is set, uses
     * contact_avatar_bg_X (same as normal contacts); otherwise uses solid backgroundColor.
     */
    private fun bindDrawable(
        drawableResId: Int,
        tintColor: Int,
        backgroundColor: Int,
        backgroundDrawableIndex: Int? = null,
        iconInsetRatio: Float = 0.2f,
        iconSizePx: Int? = null
    ) {
        showingDefaultProfileIcon = false
        drawableIconInsetRatio = if (iconSizePx != null && iconSizePx > 0) null else iconInsetRatio.coerceIn(0.05f, 0.45f)
        avatarImage.isVisible = true
        avatarInitials.isVisible = false
        if (backgroundDrawableIndex != null) {
            avatarBackgroundLayer.background = context.createAvatarGradientDrawable(
                drawableIndex = backgroundDrawableIndex,
                isDarkMode = isAvatarDarkMode()
            )
        } else {
            avatarBackgroundLayer.background = GradientDrawable().apply {
                setColor(backgroundColor)
                shape = GradientDrawable.OVAL
            }
        }
        background = null
        avatarBackgroundLayer.isVisible = true
        val bw = width.coerceAtLeast(0)
        val bh = height.coerceAtLeast(0)
        if (bw > 0 && bh > 0) {
            avatarBackgroundLayer.background?.setBounds(0, 0, bw, bh)
        } else {
            post { avatarBackgroundLayer.background?.setBounds(0, 0, width, height) }
        }
        avatarImage.background = null
        avatarImage.layoutParams = if (iconSizePx != null && iconSizePx > 0) {
            FrameLayout.LayoutParams(iconSizePx, iconSizePx, Gravity.CENTER)
        } else {
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        avatarImage.scaleType = ImageView.ScaleType.FIT_CENTER
        if (iconSizePx != null && iconSizePx > 0) {
            avatarImage.setPadding(0, 0, 0, 0)
        } else {
            applyDrawableIconInsets(iconInsetRatio)
            if (width <= 0 || height <= 0) {
                post { if (drawableIconInsetRatio != null) applyDrawableIconInsets(drawableIconInsetRatio!!) }
            }
        }
        avatarImage.setImageResource(drawableResId)
        avatarImage.imageTintList = ColorStateList.valueOf(tintColor)
    }

    /**
     * Binds Monogram avatar source.
     * Shows initials with gradient background.
     * Uses drawable resource if drawableIndex is provided, otherwise falls back to programmatic gradient.
     * 
     * @param initials The initials to display
     * @param gradientColors The list of colors for the gradient background (fallback)
     * @param drawableIndex The index (0-26) for the avatar gradient drawable resource
     */
    private fun bindMonogram(
        initials: String,
        gradientColors: List<Int>,
        drawableIndex: Int? = null,
        showProfileIcon: Boolean = false
    ) {
        val monogramChar = extractFirstMonogramCharacter(initials)

        if (drawableIndex != null) {
            avatarBackgroundLayer.background = context.createAvatarGradientDrawable(
                drawableIndex = drawableIndex,
                isDarkMode = isAvatarDarkMode()
            )
        } else {
            avatarBackgroundLayer.background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setColors(gradientColors.toIntArray())
            }
        }
        background = null
        avatarBackgroundLayer.isVisible = true
        val w = width.coerceAtLeast(0)
        val h = height.coerceAtLeast(0)
        if (w > 0 && h > 0) {
            avatarBackgroundLayer.background?.setBounds(0, 0, w, h)
        } else {
            post { avatarBackgroundLayer.background?.setBounds(0, 0, width, height) }
        }
        avatarInitials.background = null

        if (showProfileIcon) {
            bindDefaultProfileIcon()
            return
        }

        showingDefaultProfileIcon = false

        // Always show the first user-visible character for monogram mode.
        avatarImage.isVisible = false
        avatarInitials.isVisible = true
        avatarInitials.gravity = android.view.Gravity.CENTER
        avatarInitials.textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
        avatarInitials.text = monogramChar

        // Clear icon state that might be left from recycled drawable/default-icon binds.
        avatarImage.setImageDrawable(null)
        avatarImage.imageTintList = null
        
        if (width > 0 && height > 0) {
            updateMonogramTextSize()
        } else {
            post { updateMonogramTextSize() }
        }
    }

    private fun extractFirstMonogramCharacter(value: String): String {
        val trimmed = value.trim()
        val firstChar = trimmed.firstOrNull() ?: return "A"
        return if (firstChar.isLetter()) firstChar.uppercaseChar().toString() else firstChar.toString()
    }

    private fun bindDefaultProfileIcon() {
        showingDefaultProfileIcon = true
        drawableIconInsetRatio = null
        avatarImage.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )
        avatarImage.isVisible = true
        avatarInitials.isVisible = false
        avatarImage.scaleType = ImageView.ScaleType.FIT_CENTER
        applyDefaultProfileIconInsets()
        if (width <= 0 || height <= 0) {
            post { if (showingDefaultProfileIcon) applyDefaultProfileIconInsets() }
        }
        avatarImage.setImageResource(R.drawable.ic_person)
        avatarImage.imageTintList = ColorStateList.valueOf(Color.WHITE)
        avatarImage.background = null
    }

    /** Same fallback as ratio-based drawable icons when size not known yet (~48dp). */
    private fun avatarSizeForInsets(): Int {
        val s = minOf(width, height)
        return if (s > 0) s else (resources.displayMetrics.density * 48f).toInt()
    }

    private fun applyDefaultProfileIconInsets() {
        val inset = (avatarSizeForInsets() * PROFILE_ICON_INSET_RATIO).toInt().coerceAtLeast(1)
        avatarImage.setPadding(inset, inset, inset, inset)
    }

    private fun applyDrawableIconInsets(iconInsetRatio: Float) {
        val ratio = iconInsetRatio.coerceIn(0.05f, 0.45f)
        val inset = (avatarSizeForInsets() * ratio).toInt().coerceAtLeast(4)
        avatarImage.setPadding(inset, inset, inset, inset)
    }
    
    /**
     * Updates the monogram text size based on the view's actual size.
     * This ensures the text scales proportionally with the avatar size,
     * fixing centering issues when avatar size changes.
     */
    private fun updateMonogramTextSize() {
        if (!avatarInitials.isVisible) return
        
        val size = minOf(width, height)
        if (size <= 0) return


        // Calculate text size as 50% of the view size (similar to canvas-based approach)
        // This ensures the letter scales proportionally with the avatar
        val textSizePx = size * 0.5f

        // Set text size in pixels for precise control
        avatarInitials.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx)
    }

    /**
     * Clears the current Glide image request and photo state.
     * Does not clear avatarBackgroundLayer so recycled views keep showing their last drawable/monogram
     * until rebound (avoids blank avatar when scrolling back).
     */
    private fun clearImageRequest() {
        showingDefaultProfileIcon = false
        drawableIconInsetRatio = null
        if (currentImageRequest != null) {
            try {
                Glide.with(context.applicationContext).clear(avatarImage)
            } catch (e: IllegalArgumentException) {
                // Activity destroyed; Glide will clean up with the activity lifecycle
            }
            currentImageRequest = null
        }
        avatarImage.setImageDrawable(null)
        avatarImage.isVisible = false
        // Do not clear avatarBackgroundLayer here: on recycle the view is detached then re-attached
        // and rebound; keeping the layer content avoids a blank frame when bind() runs after attach.
    }

    /**
     * Updates the outline when view size changes.
     * Ensures circular clipping remains correct after layout changes.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            avatarBackgroundLayer.background?.setBounds(0, 0, w, h)
            invalidateOutline()
            avatarBackgroundLayer.invalidateOutline()
            if (avatarInitials.isVisible) updateMonogramTextSize()
            if (showingDefaultProfileIcon) applyDefaultProfileIconInsets()
            drawableIconInsetRatio?.let { applyDrawableIconInsets(it) }
        }
    }
}
