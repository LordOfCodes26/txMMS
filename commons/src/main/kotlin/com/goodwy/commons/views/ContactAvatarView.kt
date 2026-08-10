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
import com.goodwy.commons.extensions.getNameLetter
import com.goodwy.commons.extensions.isNightDisplay
import com.goodwy.commons.helpers.AvatarSource
import com.goodwy.commons.helpers.AvatarBindLogger
import com.goodwy.commons.helpers.ContactAvatarInvalidUriTracker
import com.goodwy.commons.helpers.ContactListPhotoUriPolicy
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import java.io.FileNotFoundException

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
    private var currentPhotoUri: String? = null
    private var currentCacheSignature: Long? = null
    private var currentSourceType: String? = null
    private var currentMonogramKey: String? = null
    private var onPhotoLoadFailedCallback: ((String) -> Unit)? = null

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

    fun refreshMonogramLetterIfNeeded() {
        if (!avatarInitials.isVisible) return
        if (width > 0 && height > 0) updateMonogramTextSize()
        else post { updateMonogramTextSize() }
    }

    private fun isAvatarDarkMode(): Boolean = avatarDarkModeOverride ?: context.isNightDisplay()

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
    fun bind(
        source: AvatarSource,
        cacheSignature: Long? = null,
        previewMode: Boolean = false,
        onPhotoLoadFailed: ((uri: String) -> Unit)? = null,
    ) {
        currentPreviewMode = previewMode
        onPhotoLoadFailedCallback = onPhotoLoadFailed
        val sourceType = when (source) {
            is AvatarSource.Photo -> "PHOTO"
            is AvatarSource.Drawable -> "DRAWABLE"
            is AvatarSource.Monogram -> if (source.showProfileIcon) "PROFILE" else "MONOGRAM"
        }
        val incomingUri = (source as? AvatarSource.Photo)?.photoUri
        val incomingSignature = cacheSignature
        val isSamePhotoReload = source is AvatarSource.Photo &&
            incomingUri != null &&
            incomingUri == currentPhotoUri &&
            incomingSignature == currentCacheSignature &&
            sourceType == currentSourceType &&
            !ContactAvatarInvalidUriTracker.isInvalidUri(incomingUri)

        if (isSamePhotoReload) {
            AvatarBindLogger.bindSkipped("SAME_URI_AND_VERSION")
            return
        }

        currentPhotoUri = incomingUri
        currentCacheSignature = incomingSignature
        currentSourceType = sourceType

        when (source) {
            is AvatarSource.Photo -> {
                // Do not Glide.clear / blank the ImageView first — into() replaces the request and
                // keeps the previous frame until the new load paints (avoids monogram/empty flash).
                bindPhoto(source, cacheSignature, previewMode, onPhotoLoadFailed)
            }
            is AvatarSource.Drawable -> {
                clearImageRequest(keepBindState = true)
                bindDrawable(
                    source.drawableResId,
                    source.tintColor,
                    source.backgroundColor,
                    source.backgroundDrawableIndex,
                    source.iconInsetRatio,
                    source.iconSizePx
                )
            }
            is AvatarSource.Monogram -> {
                clearImageRequest(keepBindState = true)
                bindMonogram(
                    source.initials,
                    source.gradientColors,
                    source.drawableIndex,
                    source.showProfileIcon,
                    source.displayName,
                )
            }
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
        onPhotoLoadFailed: ((uri: String) -> Unit)? = null,
    ) {
        showingDefaultProfileIcon = false
        currentMonogramKey = null
        drawableIconInsetRatio = null
        background = null
        avatarBackgroundLayer.background = null
        avatarBackgroundLayer.isVisible = false
        avatarInitials.isVisible = false
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
            val fallback = source.fallbackMonogram
            val skipGlide = ContactAvatarInvalidUriTracker.isInvalidUri(source.photoUri)
            if (skipGlide) {
                if (fallback != null) {
                    clearImageRequest(keepBindState = true)
                    bindMonogram(
                        initials = fallback.initials,
                        gradientColors = fallback.gradientColors,
                        drawableIndex = fallback.drawableIndex,
                        showProfileIcon = fallback.showProfileIcon,
                        displayName = fallback.displayName,
                    )
                }
                return
            }

            // Keep ImageView visible and do not paint monogram first. Showing the fallback while
            // Glide loads caused a monogram→photo flash on detail/edit entry.
            avatarImage.isVisible = true

            currentImageRequest = imageUri
            val size = if (previewMode) minOf(avatarLoadSize(), PREVIEW_MAX_SIZE) else avatarLoadSize()
            val requestOptions = photoRequestOptions(size, cacheSignature)
            var request = Glide.with(this)
                .load(imageUri)
                .apply(requestOptions)
                .dontAnimate()
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean,
                    ): Boolean {
                        // Superseded bind, or thumbnail miss while full request continues.
                        if (currentImageRequest != imageUri) return true
                        if (model != currentImageRequest) return false
                        if (e.hasFileNotFoundCause()) {
                            (onPhotoLoadFailed ?: onPhotoLoadFailedCallback)?.invoke(source.photoUri)
                        }
                        val fb = source.fallbackMonogram
                        return if (fb != null) {
                            clearImageRequest(keepBindState = true)
                            bindMonogram(
                                fb.initials,
                                fb.gradientColors,
                                fb.drawableIndex,
                                fb.showProfileIcon,
                                displayName = fb.displayName,
                            )
                            true
                        } else false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean,
                    ): Boolean {
                        // Accept both list-thumb preview and full photo for this bind.
                        if (currentImageRequest != imageUri) return true
                        avatarBackgroundLayer.isVisible = false
                        avatarBackgroundLayer.background = null
                        avatarInitials.isVisible = false
                        avatarImage.isVisible = true
                        background = null
                        return false
                    }
                })

            // Detail/edit: paint list-cached 96px first (instant), then upgrade to full-size decode.
            // Matches list RequestOptions so Glide hits MEMORY/RESOURCE cache from the contacts list.
            if (!previewMode) {
                val previewModel = source.previewUri?.takeIf { it.isNotBlank() }?.let { preview ->
                    try {
                        Uri.parse(preview)
                    } catch (_: Exception) {
                        null
                    }
                } ?: imageUri
                request = request.thumbnail(
                    Glide.with(this)
                        .load(previewModel)
                        .apply(photoRequestOptions(PREVIEW_MAX_SIZE, cacheSignature))
                )
            }

            request.into(avatarImage)
        } else {
            // Invalid or unparseable URI - show fallback monogram if provided
            val fallback = source.fallbackMonogram
            if (fallback != null) {
                clearImageRequest(keepBindState = true)
                bindMonogram(
                    initials = fallback.initials,
                    gradientColors = fallback.gradientColors,
                    drawableIndex = fallback.drawableIndex,
                    showProfileIcon = fallback.showProfileIcon,
                    displayName = fallback.displayName,
                )
            } else {
                avatarImage.setImageDrawable(null)
                avatarImage.isVisible = false
            }
        }
    }

    private fun photoRequestOptions(size: Int, cacheSignature: Long?): RequestOptions {
        val options = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .override(size, size)
            .circleCrop()
            .error(null)
        return if (cacheSignature != null) {
            options.signature(ObjectKey(cacheSignature))
        } else {
            options
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
                isDarkMode = isAvatarDarkMode(),
                forList = currentPreviewMode,
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
        showProfileIcon: Boolean = false,
        displayName: String = "",
    ) {
        val monogramChar = when {
            initials.isNotBlank() -> initials.getNameLetter()
            displayName.isNotBlank() -> displayName.getNameLetter()
            else -> "A"
        }
        val dark = isAvatarDarkMode()
        val monogramKey = "m:$drawableIndex:$dark:$monogramChar:$showProfileIcon:$currentPreviewMode"
        if (currentMonogramKey == monogramKey &&
            currentSourceType == "monogram" &&
            avatarBackgroundLayer.isVisible
        ) {
            AvatarBindLogger.bindSkipped("SAME_MONOGRAM")
            if (showProfileIcon) {
                bindDefaultProfileIcon()
            } else if (avatarInitials.isVisible) {
                refreshMonogramLetterIfNeeded()
            }
            return
        }
        currentMonogramKey = monogramKey
        currentSourceType = "monogram"
        currentPhotoUri = null

        if (drawableIndex != null) {
            avatarBackgroundLayer.background = context.createAvatarGradientDrawable(
                drawableIndex = drawableIndex,
                isDarkMode = dark,
                forList = currentPreviewMode,
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
    private fun clearImageRequest(keepBindState: Boolean = false) {
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
        if (!keepBindState) {
            currentPhotoUri = null
            currentCacheSignature = null
            currentSourceType = null
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

private fun GlideException?.hasFileNotFoundCause(): Boolean {
    var cur: Throwable? = this
    while (cur != null) {
        if (cur is FileNotFoundException) return true
        cur = cur.cause
    }
    return false
}
