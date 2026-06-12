package com.android.mms.activities

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.android.common.view.MActionBar
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.hideKeyboard
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.extensions.updateTextColors
import com.goodwy.commons.views.MyRecyclerView
import com.android.mms.R
import com.android.mms.adapters.SlideshowSlidesAdapter
import com.android.mms.databinding.ActivityManageSlideshowBinding
import com.android.mms.helpers.ComposeSlideshowBridge
import com.android.mms.helpers.EXTRA_SLIDESHOW_DONE
import com.android.mms.helpers.EXTRA_SLIDESHOW_JSON
import com.android.mms.helpers.SlideshowHelper
import com.android.mms.models.MmsSlide
import com.android.mms.models.MmsSlideshow

/**
 * Alps [com.android.mms.ui.SlideshowEditActivity] slide list, with [ManageQuickTextsActivity] shell styling.
 */
class ManageSlideshowActivity : SimpleActivity() {

    private lateinit var binding: ActivityManageSlideshowBinding
    private var slideshowAppBarVerticalOffset = 0
    private lateinit var adapter: SlideshowSlidesAdapter
    private var slideshow = MmsSlideshow(emptyList())
    private var selectedPosition = -1

    private val editSlideLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) {
            return@registerForActivityResult
        }
        slideshow = ComposeSlideshowBridge.slideshow ?: slideshow
        val resultData = result.data
        if (resultData?.getBooleanExtra(EXTRA_SLIDESHOW_DONE, false) == true) {
            finishWithResult(done = true)
            return@registerForActivityResult
        }
        refreshList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManageSlideshowBinding.inflate(layoutInflater)
        setContentView(binding.root)

        slideshow = ComposeSlideshowBridge.slideshow
            ?: SlideshowHelper.fromJsonForEditor(intent.getStringExtra(EXTRA_SLIDESHOW_JSON))
            ?: MmsSlideshow(emptyList())
        ComposeSlideshowBridge.slideshow = slideshow

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finishWithResult(done = false)
                }
            },
        )

        initTheme()
        setupEdgeToEdge()
        setupTopAppBar()
        setupSpringSync()
        applyWindowSurfaces()
        setupAdapter()
        refreshList()

        binding.slideshowAppbar.addOnOffsetChangedListener { _, verticalOffset ->
            slideshowAppBarVerticalOffset = verticalOffset
            binding.mVerticalSideFrameTop.update()
        }
        binding.manageSlideshowList.post {
            binding.slideshowAppbar.dismissCollapse()
            slideshowAppBarVerticalOffset = 0
            applyTransparentAppBarChrome()
            syncBlurTargetTopMargin()
            refreshSideFrames()
        }
    }

    override fun onResume() {
        super.onResume()
        slideshow = ComposeSlideshowBridge.slideshow ?: slideshow
        refreshList()
        applySystemBarUi()
        applyWindowSurfaces()
        updateTextColors(binding.rootView)
        setupTopAppBar()
        binding.slideshowAppbar.translationY = 0f
        applyTransparentAppBarChrome()
        syncBlurTargetTopMargin()
        refreshSideFrames()
    }

    private fun setupAdapter() {
        adapter = SlideshowSlidesAdapter(
            activity = this,
            onSlideClicked = { position -> openSlideEditor(position) },
            onAddSlideClicked = { addNewSlide() },
            onSlideLongClicked = { position, view ->
                selectedPosition = position
                showSlideContextMenu(view, position)
            },
        )
        binding.manageSlideshowList.adapter = adapter
    }

    private fun showSlideContextMenu(anchor: View, position: Int) {
        val popup = PopupMenu(this, anchor)
        if (position > 0) {
            popup.menu.add(0, MENU_MOVE_UP, 0, R.string.move_up)
        }
        if (position < slideshow.size - 1) {
            popup.menu.add(0, MENU_MOVE_DOWN, 1, R.string.move_down)
        }
        if (slideshow.size < MmsSlideshow.MAX_SLIDE_NUM) {
            popup.menu.add(0, MENU_ADD_SLIDE, 2, R.string.add_slide)
        }
        popup.menu.add(0, MENU_REMOVE_SLIDE, 3, R.string.remove_slide)
        popup.setOnMenuItemClickListener { item ->
            handleSlideContextMenuItem(item.itemId, position)
        }
        popup.show()
    }

    private fun handleSlideContextMenuItem(itemId: Int, position: Int): Boolean {
        when (itemId) {
            MENU_MOVE_UP -> {
                slideshow = slideshow.moveSlideUp(position)
                ComposeSlideshowBridge.slideshow = slideshow
                refreshList()
                return true
            }
            MENU_MOVE_DOWN -> {
                slideshow = slideshow.moveSlideDown(position)
                ComposeSlideshowBridge.slideshow = slideshow
                refreshList()
                return true
            }
            MENU_ADD_SLIDE -> {
                addNewSlideAt(position + 1)
                return true
            }
            MENU_REMOVE_SLIDE -> {
                removeSlideAt(position)
                return true
            }
        }
        return false
    }

    private fun refreshList() {
        val canAdd = slideshow.size < MmsSlideshow.MAX_SLIDE_NUM
        adapter.submitSlideshow(slideshow, canAdd)
        binding.manageSlideshowPlaceholder.beVisibleIf(slideshow.size == 0)
        binding.manageSlideshowList.beVisibleIf(slideshow.size > 0 || canAdd)
    }

    private fun addNewSlide() = addNewSlideAt(slideshow.size)

    /**
     * Alps [SlideshowEditActivity.addNewSlide] / [SlideshowEditor.addNewSlide]: insert a blank slide
     * (text-only placeholder) at the list, scroll to it, and let the user tap it to open
     * [EditSlideActivity] and attach a picture there.
     */
    private fun addNewSlideAt(index: Int) {
        if (slideshow.size >= MmsSlideshow.MAX_SLIDE_NUM) {
            toast(R.string.cannot_add_slide_anymore)
            return
        }
        val insertAt = index.coerceIn(0, slideshow.size)
        val updated = slideshow.addSlideAt(insertAt, MmsSlide.empty()) ?: run {
            toast(R.string.cannot_add_slide_anymore)
            return
        }
        slideshow = updated
        ComposeSlideshowBridge.slideshow = slideshow
        refreshList()
        binding.manageSlideshowList.post {
            binding.manageSlideshowList.smoothScrollToPosition(insertAt)
        }
    }

    private fun removeSlideAt(index: Int) {
        slideshow = slideshow.removeSlideAt(index)
        ComposeSlideshowBridge.slideshow = slideshow
        if (slideshow.size == 0) {
            finishWithResult(done = true)
            return
        }
        refreshList()
    }

    private fun openSlideEditor(index: Int) {
        ComposeSlideshowBridge.slideshow = slideshow
        ComposeSlideshowBridge.editingSlideIndex = index
        editSlideLauncher.launch(Intent(this, EditSlideActivity::class.java))
    }

    private fun finishWithResult(done: Boolean) {
        ComposeSlideshowBridge.slideshow = slideshow
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
            syncListBottomPadding()
            insets
        }
    }

    private fun syncListBottomPadding() {
        val activityMargin = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.activity_margin)
        val rootInsets = ViewCompat.getRootWindowInsets(binding.root)
        val bottomInset = rootInsets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        binding.manageSlideshowList.updatePadding(bottom = bottomInset + activityMargin)
    }

    private fun applyWindowSurfaces() {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        binding.root.setBackgroundColor(backgroundColor)
        binding.rootView.setBackgroundColor(backgroundColor)
        binding.mainBlurTarget.setBackgroundColor(backgroundColor)
        binding.manageSlideshowList.setBackgroundColor(backgroundColor)
        scrollingView = binding.manageSlideshowList
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
        binding.slideshowAppbar.setTitle(getString(R.string.edit_slideshow))
        binding.slideshowAppbar.getBackArrow()?.apply {
            bindBlurTarget(this@ManageSlideshowActivity, binding.mainBlurTarget)
            setOnMenuItemClickListener { menuItem ->
                if (menuItem.itemId == com.android.common.R.id.back_arrow) {
                    finishWithResult(done = false)
                    true
                } else {
                    false
                }
            }
        }
        binding.slideshowAppbar.getSearchView()?.visibility = View.GONE
        binding.slideshowAppbar.getActionBarView()?.let(::setupActionBarMenu)
        applyTransparentAppBarChrome()
    }

    private fun setupActionBarMenu(actionBar: MActionBar) {
        actionBar.bindBlurTarget(this, binding.mainBlurTarget)
        actionBar.setPosition("right")
        actionBar.inflateMenu(R.menu.menu_manage_slideshow)
        actionBar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.add_slide -> {
                    addNewSlide()
                    true
                }
                R.id.discard_slideshow -> {
                    slideshow = MmsSlideshow(emptyList())
                    ComposeSlideshowBridge.slideshow = slideshow
                    finishWithResult(done = true)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupSpringSync() {
        (binding.manageSlideshowList as? MyRecyclerView)?.onOverscrollTranslationChanged = { translationY ->
            binding.slideshowAppbar.translationY = translationY * NEST_BOUNCY_OVERSCROLL_FACTOR
        }
    }

    private fun applyTransparentAppBarChrome() {
        binding.slideshowAppbar.apply {
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
            setLiftOnScrollColor(null)
        }
    }

    private fun syncBlurTargetTopMargin() {
        val targetTopMargin = if (binding.slideshowAppbar.visibility == View.VISIBLE) {
            resources.getDimensionPixelSize(R.dimen.main_app_bar_blur_offset)
        } else {
            0
        }
        binding.mainBlurTarget.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            if (topMargin != targetTopMargin) {
                topMargin = targetTopMargin
            }
        }
        val topPad = resources.getDimensionPixelSize(R.dimen.conversations_list_top_padding)
        binding.manageSlideshowList.updatePadding(top = topPad)
        binding.manageSlideshowPlaceholder.updatePadding(top = topPad)
    }

    private fun refreshSideFrames() {
        binding.root.post {
            ViewCompat.requestApplyInsets(binding.root)
            binding.mVerticalSideFrameTop.bindBlurTarget(binding.mainBlurTarget)
            binding.mVerticalSideFrameBottom.bindBlurTarget(binding.mainBlurTarget)
            binding.slideshowAppbar.getBackArrow()?.bindBlurTarget(this, binding.mainBlurTarget)
            binding.slideshowAppbar.getActionBarView()?.bindBlurTarget(this, binding.mainBlurTarget)
            applyTransparentAppBarChrome()
            binding.mVerticalSideFrameTop.update()
        }
    }

    companion object {
        private const val NEST_BOUNCY_OVERSCROLL_FACTOR = 0.35f
        private const val MENU_MOVE_UP = 10
        private const val MENU_MOVE_DOWN = 11
        private const val MENU_ADD_SLIDE = 12
        private const val MENU_REMOVE_SLIDE = 13
    }
}
