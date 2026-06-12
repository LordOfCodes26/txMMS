package com.android.mms.activities

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.FitCenter
import com.bumptech.glide.request.RequestOptions
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.updateTextColors
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.views.MyRecyclerView
import com.android.mms.R
import com.android.mms.databinding.ActivityViewMmsBinding
import com.android.mms.databinding.ItemViewMmsSlideBinding
import com.android.mms.extensions.getMmsSlides
import com.android.mms.extensions.isVideoMimeType
import com.android.mms.extensions.launchViewIntent
import com.android.mms.helpers.EXTRA_MMS_MESSAGE_ID
import com.android.mms.helpers.EXTRA_SLIDESHOW_JSON
import com.android.mms.helpers.SlideshowHelper
import com.android.mms.models.MmsSlide

/**
 * Alps [com.android.mms.ui.MmsPlayerActivity]: scrollable list of all slides in an MMS.
 * Used both for received messages and compose-time preview. Styled like [ManageSlideshowActivity].
 */
class ViewMmsActivity : SimpleActivity() {

    private lateinit var binding: ActivityViewMmsBinding
    private var appBarVerticalOffset = 0
    private var slides: List<MmsSlide> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewMmsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initTheme()
        setupEdgeToEdge()
        setupTopAppBar()
        setupSpringSync()
        applyWindowSurfaces()

        binding.viewMmsAppbar.addOnOffsetChangedListener { _, verticalOffset ->
            appBarVerticalOffset = verticalOffset
            binding.mVerticalSideFrameTop.update()
        }
        binding.viewMmsList.post {
            binding.viewMmsAppbar.dismissCollapse()
            appBarVerticalOffset = 0
            applyTransparentAppBarChrome()
            syncBlurTargetTopMargin()
            refreshSideFrames()
        }

        // Accept either a compose slideshow (JSON) or a received message (message ID).
        val json = intent.getStringExtra(EXTRA_SLIDESHOW_JSON)
        if (json != null) {
            val loadedSlides = SlideshowHelper.fromJson(json)?.slides.orEmpty()
                .filter { it.uriString.isNotEmpty() || it.text.isNotBlank() }
            if (loadedSlides.isEmpty()) {
                finish()
                return
            }
            slides = loadedSlides
            binding.viewMmsList.adapter = SlidesAdapter()
            return
        }

        val messageId = intent.getLongExtra(EXTRA_MMS_MESSAGE_ID, -1L)
        if (messageId == -1L) {
            finish()
            return
        }

        ensureBackgroundThread {
            val loadedSlides = getMmsSlides(messageId)
            runOnUiThread {
                if (loadedSlides.isEmpty()) {
                    finish()
                    return@runOnUiThread
                }
                slides = loadedSlides
                binding.viewMmsList.adapter = SlidesAdapter()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applySystemBarUi()
        applyWindowSurfaces()
        updateTextColors(binding.rootView)
        setupTopAppBar()
        binding.viewMmsAppbar.translationY = 0f
        applyTransparentAppBarChrome()
        syncBlurTargetTopMargin()
        refreshSideFrames()
    }

    // ---- UI shell (matches ManageSlideshowActivity / ManageQuickTextsActivity) ----

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
            syncListBottomPadding()
            insets
        }
    }

    private fun syncListBottomPadding() {
        val activityMargin = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.activity_margin)
        val rootInsets = ViewCompat.getRootWindowInsets(binding.root)
        val bottomInset = rootInsets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        binding.viewMmsList.updatePadding(bottom = bottomInset + activityMargin)
    }

    private fun applyWindowSurfaces() {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        binding.root.setBackgroundColor(backgroundColor)
        binding.rootView.setBackgroundColor(backgroundColor)
        binding.mainBlurTarget.setBackgroundColor(backgroundColor)
        binding.viewMmsList.setBackgroundColor(backgroundColor)
        scrollingView = binding.viewMmsList
        applyTransparentAppBarChrome()
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
        binding.viewMmsAppbar.setTitle(getString(R.string.mms))
        binding.viewMmsAppbar.getBackArrow()?.apply {
            bindBlurTarget(this@ViewMmsActivity, binding.mainBlurTarget)
            setOnMenuItemClickListener { menuItem ->
                if (menuItem.itemId == com.android.common.R.id.back_arrow) {
                    finish()
                    true
                } else {
                    false
                }
            }
        }
        binding.viewMmsAppbar.getSearchView()?.visibility = View.GONE
        binding.viewMmsAppbar.getActionBarView()?.visibility = View.GONE
        applyTransparentAppBarChrome()
    }

    private fun setupSpringSync() {
        (binding.viewMmsList as? MyRecyclerView)?.onOverscrollTranslationChanged = { translationY ->
            binding.viewMmsAppbar.translationY = translationY * NEST_BOUNCY_OVERSCROLL_FACTOR
        }
    }

    private fun applyTransparentAppBarChrome() {
        binding.viewMmsAppbar.apply {
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
            setLiftOnScrollColor(null)
        }
    }

    private fun syncBlurTargetTopMargin() {
        val targetTopMargin = if (binding.viewMmsAppbar.visibility == View.VISIBLE) {
            resources.getDimensionPixelSize(R.dimen.main_app_bar_blur_offset)
        } else {
            0
        }
        binding.mainBlurTarget.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            if (topMargin != targetTopMargin) topMargin = targetTopMargin
        }
        val topPad = resources.getDimensionPixelSize(R.dimen.conversations_list_top_padding)
        binding.viewMmsList.updatePadding(top = topPad)
    }

    private fun refreshSideFrames() {
        binding.root.post {
            ViewCompat.requestApplyInsets(binding.root)
            binding.mVerticalSideFrameTop.bindBlurTarget(binding.mainBlurTarget)
            binding.mVerticalSideFrameBottom.bindBlurTarget(binding.mainBlurTarget)
            binding.viewMmsAppbar.getBackArrow()?.bindBlurTarget(this, binding.mainBlurTarget)
            applyTransparentAppBarChrome()
            binding.mVerticalSideFrameTop.update()
        }
    }

    // ---- Adapter ----

    private inner class SlidesAdapter : RecyclerView.Adapter<SlidesAdapter.SlideViewHolder>() {

        override fun getItemCount() = slides.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideViewHolder {
            val b = ItemViewMmsSlideBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return SlideViewHolder(b)
        }

        override fun onBindViewHolder(holder: SlideViewHolder, position: Int) {
            holder.bind(slides[position], position)
        }

        inner class SlideViewHolder(private val b: ItemViewMmsSlideBinding) :
            RecyclerView.ViewHolder(b.root) {

            fun bind(slide: MmsSlide, position: Int) {
                b.slidePageNumber.text = getString(R.string.mms_page_number, position + 1)

                if (slide.text.isNotBlank()) {
                    b.slideText.text = slide.text
                    b.slideText.beVisible()
                } else {
                    b.slideText.beGone()
                }

                if (slide.uriString.isNotEmpty() && slide.isMediaMimeType()) {
                    b.slideMediaFrame.beVisible()
                    b.slideVideoPlayIcon.beVisibleIf(slide.mimetype.isVideoMimeType())

                    Glide.with(b.slideImage)
                        .load(slide.uri)
                        .apply(RequestOptions().transform(FitCenter()))
                        .into(b.slideImage)

                    val clickUri = slide.uri
                    val clickMime = slide.mimetype
                    b.slideMediaFrame.setOnClickListener {
                        launchViewIntent(clickUri, clickMime, slide.filename)
                    }
                } else {
                    b.slideMediaFrame.beGone()
                    Glide.with(b.slideImage).clear(b.slideImage)
                }
            }
        }
    }

    companion object {
        private const val NEST_BOUNCY_OVERSCROLL_FACTOR = 0.35f
    }
}
