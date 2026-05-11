package com.android.mms.activities

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.android.common.helper.IconItem
import com.android.common.view.MVSideFrame
import com.android.mms.R
import com.android.mms.adapters.MessageBubblePickerAdapter
import com.android.mms.databinding.ActivityMessageBubblePickerBinding
import com.android.mms.extensions.applyLargeTitleOnly
import com.android.mms.extensions.clearMySearchMenuSpringSync
import com.android.mms.extensions.config
import com.android.mms.extensions.postSyncMySearchMenuToolbarGeometry
import com.android.mms.extensions.setupMySearchMenuSpringSync
import com.android.mms.helpers.BUBBLE_DRAWABLE_OPTIONS
import com.android.mms.helpers.refreshMessages
import com.goodwy.commons.extensions.getColoredDrawableWithColor
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.updateTextColors
import eightbitlab.com.blurview.BlurTarget

class MessageBubblePickerActivity : SimpleActivity() {
    private lateinit var binding: ActivityMessageBubblePickerBinding
    private var pendingSelectedOptionId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessageBubblePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        pendingSelectedOptionId = config.bubbleDrawableSet
        initTheme()
        initMVSideFrames()
        setupEdgeToEdge()
        makeSystemBarsToTransparent()
        applyBubblePickerWindowSurfacesAndChrome()
        setupTopBar()
        setupActionTabs()
        setupList()
        binding.nestScroll.post {
            postSyncMySearchMenuToolbarGeometry(
                binding.root,
                binding.bubblePickerAppbar,
                binding.blurTarget,
                binding.mVerticalSideFrameTop,
                binding.bubblePickerList,
            )
            setupMySearchMenuSpringSync(binding.bubblePickerAppbar, binding.bubblePickerList)
            if (config.changeColourTopBar) {
                scrollingView = binding.bubblePickerList
                val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
                setupSearchMenuScrollListener(
                    binding.bubblePickerList,
                    binding.bubblePickerAppbar,
                    useSurfaceColor,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
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

        applyBubblePickerWindowSurfacesAndChrome()
        updateTextColors(binding.rootView)
        setupTopBar()
        refreshSideFrameBlurAndInsets()
    }

    private fun applyBubblePickerWindowSurfacesAndChrome() {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        binding.root.setBackgroundColor(backgroundColor)
        binding.rootView.setBackgroundColor(backgroundColor)
        binding.mainBlurTarget.setBackgroundColor(backgroundColor)
        binding.blurTarget.setBackgroundColor(backgroundColor)
        binding.bubblePickerList.setBackgroundColor(backgroundColor)
        scrollingView = binding.bubblePickerList
        binding.bubblePickerAppbar.updateColors(
            getStartRequiredStatusBarColor(),
            scrollingView?.computeVerticalScrollOffset() ?: 0,
        )
        setBubblePickerTransparentAppBarBackground()
    }

    override fun onDestroy() {
        clearMySearchMenuSpringSync(binding.bubblePickerAppbar, binding.bubblePickerList)
        super.onDestroy()
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun initMVSideFrames() {
        val blurTarget = findViewById<BlurTarget>(R.id.blurTarget)
        findViewById<MVSideFrame>(R.id.m_vertical_side_frame_top).bindBlurTarget(blurTarget)
        findViewById<MVSideFrame>(R.id.m_vertical_side_frame_bottom).bindBlurTarget(blurTarget)
    }

    /** Top chrome uses the shifted inner blur target; the bottom ripple bar uses the unshifted outer target. */
    private fun refreshSideFrameBlurAndInsets() {
        binding.root.post {
            ViewCompat.requestApplyInsets(binding.root)
            binding.mVerticalSideFrameTop.bindBlurTarget(binding.blurTarget)
            binding.mVerticalSideFrameBottom.bindBlurTarget(binding.blurTarget)
            binding.bubblePickerAppbar.requireCustomToolbar().bindBlurTarget(
                this@MessageBubblePickerActivity,
                binding.blurTarget,
            )
            postSyncMySearchMenuToolbarGeometry(
                binding.root,
                binding.bubblePickerAppbar,
                binding.blurTarget,
                binding.mVerticalSideFrameTop,
                binding.bubblePickerList,
            )
            setupActionTabs()
        }
    }

    private fun makeSystemBarsToTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val navHeight = nav.bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val dp5 = (15 * resources.displayMetrics.density).toInt()
            binding.mVerticalSideFrameBottom.layoutParams =
                binding.mVerticalSideFrameBottom.layoutParams.apply { height = navHeight + dp5 }
            val barLp = binding.lytAction.layoutParams as ViewGroup.MarginLayoutParams
            val activityMargin = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.activity_margin)
            if (ime.bottom > 0) {
                barLp.bottomMargin = ime.bottom + activityMargin
            } else {
                barLp.bottomMargin = navHeight + activityMargin
            }
            binding.lytAction.layoutParams = barLp
            applyBubbleListBottomInset(navHeight, ime.bottom)
            insets
        }
    }

    private fun applyBubbleListBottomInset(navHeight: Int, imeBottom: Int) {
        val activityMargin = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.activity_margin)
        val bottomInset = if (imeBottom > 0) imeBottom else navHeight
        binding.bubblePickerList.updatePadding(
            bottom = bottomInset + activityMargin + dp(90),
        )
    }

    private fun setBubblePickerTransparentAppBarBackground() {
        binding.bubblePickerAppbar.setBackgroundColor(Color.TRANSPARENT)
        binding.bubblePickerAppbar.binding.searchBarContainer.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun setupTopBar() {
        binding.bubblePickerAppbar.applyLargeTitleOnly(getString(com.goodwy.strings.R.string.speech_bubble))
        binding.bubblePickerAppbar.requireCustomToolbar().apply {
            val textColor = getProperTextColor()
            navigationIcon = resources.getColoredDrawableWithColor(
                this@MessageBubblePickerActivity,
                com.android.common.R.drawable.ic_cmn_arrow_left_fill,
                textColor
            )
            setNavigationOnClickListener { cancelAndFinish() }
            setNavigationContentDescription(com.goodwy.commons.R.string.back)
            bindBlurTarget(this@MessageBubblePickerActivity, binding.blurTarget)
        }
    }

    private fun setupActionTabs() {
        val items = ArrayList<IconItem>().apply {
            add(IconItem().apply {
                icon = com.android.common.R.drawable.ic_cmn_cancel_fill
                title = getString(com.android.common.R.string.cancel_common)
            })
            add(IconItem().apply {
                icon = com.android.common.R.drawable.ic_cmn_circle_check_fill
                title = getString(com.android.common.R.string.confirm_common)
            })
        }
        binding.confirmTab.setTabs(this, items, binding.mainBlurTarget)
        binding.confirmTab.setOnClickedListener { index ->
            when (index) {
                0 -> cancelAndFinish()
                1 -> applySelectionAndFinish()
            }
        }
    }

    private fun cancelAndFinish() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun applySelectionAndFinish() {
        if (config.bubbleDrawableSet != pendingSelectedOptionId) {
            config.bubbleDrawableSet = pendingSelectedOptionId
            refreshMessages()
        }
        finish()
    }

    private fun setupList() {
        binding.bubblePickerList.layoutManager = LinearLayoutManager(this)
        binding.bubblePickerList.adapter = MessageBubblePickerAdapter(
            items = BUBBLE_DRAWABLE_OPTIONS,
            selectedOptionId = pendingSelectedOptionId
        ) { selected ->
            pendingSelectedOptionId = selected.id
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
