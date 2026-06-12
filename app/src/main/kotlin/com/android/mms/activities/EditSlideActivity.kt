package com.android.mms.activities

import android.content.Intent
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.bumptech.glide.Glide
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.getFilenameFromUri
import com.goodwy.commons.extensions.hideKeyboard
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.updateTextColors
import com.android.mms.R
import com.android.mms.databinding.ActivityEditSlideBinding
import com.android.mms.extensions.isAudioMimeType
import com.android.mms.extensions.isImageMimeType
import com.android.mms.extensions.isVideoMimeType
import com.android.mms.extensions.launchViewIntent
import com.android.mms.helpers.ComposeSlideshowBridge
import com.android.mms.helpers.EXTRA_SLIDESHOW_DONE
import com.android.mms.helpers.EXTRA_SLIDESHOW_JSON
import com.android.mms.helpers.SlideshowHelper
import com.android.mms.models.MmsSlide
import com.android.mms.models.MmsSlideshow

/**
 * Alps [com.android.mms.ui.SlideEditorActivity] for a single slide.
 */
class EditSlideActivity : SimpleActivity() {

    private lateinit var binding: ActivityEditSlideBinding
    private var slideshow = MmsSlideshow(emptyList())
    private var slideIndex = 0

    private val pickReplaceMedia = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val mimeType = SlideshowHelper.resolveMediaMimeType(this, uri) ?: return@registerForActivityResult
        val stableUri = SlideshowHelper.stabilizeAttachmentUri(this, uri, mimeType)
        val slide = slideshow.slideAt(slideIndex) ?: return@registerForActivityResult
        val newDurationMs = if (mimeType.isVideoMimeType() || mimeType.isAudioMimeType()) {
            readMediaDurationMs(stableUri) ?: MmsSlide.DEFAULT_DURATION_MS
        } else {
            MmsSlide.DEFAULT_DURATION_MS
        }
        slideshow = slideshow.replaceSlideAt(
            slideIndex,
            slide.copy(
                uriString = stableUri.toString(),
                mimetype = mimeType,
                filename = getFilenameFromUri(stableUri),
                durationMs = newDurationMs,
            ),
        )
        ComposeSlideshowBridge.slideshow = slideshow
        showCurrentSlide()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditSlideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        slideshow = ComposeSlideshowBridge.slideshow
            ?: SlideshowHelper.fromJsonForEditor(intent.getStringExtra(EXTRA_SLIDESHOW_JSON))
            ?: MmsSlideshow(emptyList())
        slideIndex = ComposeSlideshowBridge.editingSlideIndex
        if (slideIndex !in slideshow.slides.indices) {
            slideIndex = slideshow.slides.lastIndex.coerceAtLeast(0)
        }
        ComposeSlideshowBridge.slideshow = slideshow
        ComposeSlideshowBridge.editingSlideIndex = slideIndex

        if (slideshow.size == 0) {
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    persistSlideText()
                    finishWithResult(done = false)
                }
            },
        )

        initTheme()
        setupEdgeToEdge()
        setupTopAppBar()
        applyWindowSurfaces()
        setupActions()
        showCurrentSlide()

        binding.editSlideAppbar.addOnOffsetChangedListener { _, _ ->
            binding.mVerticalSideFrameTop.update()
        }
        binding.root.post { refreshSideFrames() }
    }

    override fun onResume() {
        super.onResume()
        applySystemBarUi()
        applyWindowSurfaces()
        updateTextColors(binding.rootView)
        setupTopAppBar()
        refreshSideFrames()
    }

    private fun setupActions() {
        binding.preSlideButton.setOnClickListener { navigateSlide(-1) }
        binding.nextSlideButton.setOnClickListener { navigateSlide(1) }
        binding.previewSlideButton.setOnClickListener { previewCurrentSlide() }
        binding.replaceSlideButton.setOnClickListener { replaceCurrentSlideMedia() }
        binding.removeSlideButton.setOnClickListener { removeCurrentSlide() }
        binding.doneSlideButton.setOnClickListener { finishWithResult(done = false) }
        binding.slideTextMessage.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                persistSlideText()
            }
        }
    }

    private fun navigateSlide(delta: Int) {
        persistSlideText()
        val newIndex = slideIndex + delta
        if (newIndex !in slideshow.slides.indices) return
        slideIndex = newIndex
        showCurrentSlide()
    }

    private fun persistSlideText() {
        val slide = slideshow.slideAt(slideIndex) ?: return
        val text = binding.slideTextMessage.text?.toString().orEmpty()
        if (text != slide.text) {
            slideshow = slideshow.replaceSlideAt(slideIndex, slide.copy(text = text))
            ComposeSlideshowBridge.slideshow = slideshow
        }
    }

    private fun showCurrentSlide() {
        val slide = slideshow.slideAt(slideIndex) ?: return
        binding.slideTextMessage.setText(slide.text)
        binding.preSlideButton.isEnabled = slideIndex > 0
        binding.nextSlideButton.isEnabled = slideIndex < slideshow.size - 1
        binding.editSlideAppbar.setTitle(getString(R.string.slide_number, (slideIndex + 1).toString()))
        binding.replaceSlideButton.text = getString(
            if (slide.uriString.isEmpty()) R.string.add_picture else R.string.replace_attachment,
        )

        if (slide.uriString.isNotEmpty() && slide.isMediaMimeType()) {
            Glide.with(binding.slideEditorImage).load(slide.uri).into(binding.slideEditorImage)
            binding.slideEditorImage.beVisible()
            binding.slideEditorPlayIcon.beVisibleIf(slide.mimetype.isVideoMimeType())
        } else {
            Glide.with(binding.slideEditorImage).clear(binding.slideEditorImage)
            binding.slideEditorImage.setImageDrawable(null)
            binding.slideEditorPlayIcon.beGone()
        }
    }

    private fun previewCurrentSlide() {
        val slide = slideshow.slideAt(slideIndex) ?: return
        if (slide.uriString.isNotEmpty()) {
            launchViewIntent(slide.uri, slide.mimetype, slide.filename)
        }
    }

    private fun replaceCurrentSlideMedia() {
        pickReplaceMedia.launch("*/*")
    }

    private fun removeCurrentSlide() {
        slideshow = slideshow.removeSlideAt(slideIndex)
        ComposeSlideshowBridge.slideshow = slideshow
        if (slideshow.size == 0) {
            finishWithResult(done = true)
            return
        }
        if (slideIndex >= slideshow.size) {
            slideIndex = slideshow.size - 1
        }
        ComposeSlideshowBridge.editingSlideIndex = slideIndex
        showCurrentSlide()
    }

    private fun finishWithResult(done: Boolean) {
        persistSlideText()
        ComposeSlideshowBridge.slideshow = slideshow
        ComposeSlideshowBridge.editingSlideIndex = slideIndex
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_SLIDESHOW_JSON, SlideshowHelper.toJson(slideshow))
                putExtra(EXTRA_SLIDESHOW_DONE, done)
            },
        )
        hideKeyboard()
        finish()
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val dp5 = (5 * resources.displayMetrics.density).toInt()
            binding.mVerticalSideFrameBottom.layoutParams =
                binding.mVerticalSideFrameBottom.layoutParams.apply { height = nav.bottom + dp5 }
            // The text row is outside BlurTarget so it always stays at the screen bottom.
            // Give it bottom padding to clear the navigation bar.
            val smallMargin = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.small_margin)
            binding.editSlideTextRow.updatePadding(bottom = nav.bottom + smallMargin)
            // Once the text row re-measures, push the scroll area's bottom padding up by
            // the same amount so the scroll content is never hidden under the text bar.
            binding.editSlideTextRow.post {
                binding.editSlideScroll.updatePadding(bottom = binding.editSlideTextRow.height)
            }
            insets
        }
    }

    private fun applyWindowSurfaces() {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        binding.root.setBackgroundColor(backgroundColor)
        binding.rootView.setBackgroundColor(backgroundColor)
        binding.mainBlurTarget.setBackgroundColor(backgroundColor)
        applyTransparentAppBarChrome()
    }

    private fun readMediaDurationMs(uri: Uri): Long? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
            retriever.release()
            ms
        } catch (_: Exception) {
            null
        }
    }

    private fun applySystemBarUi() {
        if (isSystemInDarkMode()) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                )
        }
    }

    private fun setupTopAppBar() {
        binding.editSlideAppbar.getBackArrow()?.apply {
            bindBlurTarget(this@EditSlideActivity, binding.mainBlurTarget)
            setOnMenuItemClickListener { menuItem ->
                if (menuItem.itemId == com.android.common.R.id.back_arrow) {
                    finishWithResult(done = false)
                    true
                } else {
                    false
                }
            }
        }
        binding.editSlideAppbar.getSearchView()?.visibility = View.GONE
        binding.editSlideAppbar.getActionBarView()?.visibility = View.GONE
        applyTransparentAppBarChrome()
    }

    private fun applyTransparentAppBarChrome() {
        binding.editSlideAppbar.apply {
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
            setLiftOnScrollColor(null)
        }
    }

    private fun refreshSideFrames() {
        binding.root.post {
            ViewCompat.requestApplyInsets(binding.root)
            binding.mVerticalSideFrameTop.bindBlurTarget(binding.mainBlurTarget)
            binding.mVerticalSideFrameBottom.bindBlurTarget(binding.mainBlurTarget)
            binding.editSlideAppbar.getBackArrow()?.bindBlurTarget(this, binding.mainBlurTarget)
            applyTransparentAppBarChrome()
            binding.mVerticalSideFrameTop.update()
            val topPad = resources.getDimensionPixelSize(R.dimen.conversations_list_top_padding)
            binding.editSlideScroll.updatePadding(top = topPad)
            // Keep scroll bottom padding in sync with the fixed text bar height.
            val scrollBottomPad = binding.editSlideTextRow.height
            if (scrollBottomPad > 0) {
                binding.editSlideScroll.updatePadding(bottom = scrollBottomPad)
            }
            binding.mainBlurTarget.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = resources.getDimensionPixelSize(R.dimen.main_app_bar_blur_offset)
            }
        }
    }

}
