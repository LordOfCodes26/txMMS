package com.android.mms.activities

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.android.common.helper.IconItem
import com.android.mms.R
import com.android.mms.adapters.MessageBubblePickerAdapter
import com.android.mms.databinding.ActivityMessageBubblePickerBinding
import com.android.mms.extensions.config
import com.android.mms.helpers.BUBBLE_DRAWABLE_OPTIONS
import com.android.mms.helpers.refreshMessages
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.hideKeyboard
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.updateTextColors

class MessageBubblePickerActivity : SimpleActivity() {
    private lateinit var binding: ActivityMessageBubblePickerBinding
    private var pendingSelectedOptionId = 0
    private var bubblePickerAppBarVerticalOffset = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessageBubblePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        pendingSelectedOptionId = config.bubbleDrawableSet
        initTheme()
        setupEdgeToEdge()
        makeSystemBarsToTransparent()
        applyBubblePickerWindowSurfacesAndChrome()
        setupBubblePickerTopAppBar()
        setupNestBouncyScroll()
        setupActionTabs()
        setupList()
        scrollingView = binding.bubblePickerList
        binding.bubblePickerAppbar.addOnOffsetChangedListener { _, verticalOffset ->
            bubblePickerAppBarVerticalOffset = verticalOffset
            binding.mVerticalSideFrameTop.update()
            syncListTopPadding()
        }
        binding.bubblePickerList.post {
            binding.bubblePickerAppbar.dismissCollapse()
            bubblePickerAppBarVerticalOffset = 0
            applyTransparentMAppBarChrome()
            syncListTopPadding()
            refreshSideFrameBlurAndInsets()
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
        setupBubblePickerTopAppBar()
        binding.bubblePickerAppbar.translationY = 0f
        applyTransparentMAppBarChrome()
        syncListTopPadding()
        refreshSideFrameBlurAndInsets()
    }

    private fun applyBubblePickerWindowSurfacesAndChrome() {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        binding.root.setBackgroundColor(backgroundColor)
        binding.rootView.setBackgroundColor(backgroundColor)
        binding.mainBlurTarget.setBackgroundColor(backgroundColor)
        binding.bubblePickerList.setBackgroundColor(backgroundColor)
        scrollingView = binding.bubblePickerList
        applyTransparentMAppBarChrome()
    }

    override fun onDestroy() {
        binding.bubblePickerList.onOverscrollTranslationChanged = null
        super.onDestroy()
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    /** BlurView + MVSideFrame can stop updating after another activity was shown; re-apply insets and re-bind. */
    private fun refreshSideFrameBlurAndInsets() {
        binding.root.post {
            ViewCompat.requestApplyInsets(binding.root)
            binding.mVerticalSideFrameTop.bindBlurTarget(binding.mainBlurTarget)
            binding.mVerticalSideFrameBottom.bindBlurTarget(binding.mainBlurTarget)
            binding.bubblePickerAppbar.getBackArrow()?.bindBlurTarget(
                this@MessageBubblePickerActivity,
                binding.mainBlurTarget,
            )
            binding.bubblePickerAppbar.getActionBarView()?.bindBlurTarget(
                this@MessageBubblePickerActivity,
                binding.mainBlurTarget,
            )
            applyTransparentMAppBarChrome()
            binding.mVerticalSideFrameTop.update()
        }
    }

    private fun makeSystemBarsToTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val navHeight = nav.bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val dp5 = (5 * resources.displayMetrics.density).toInt()
            binding.mVerticalSideFrameBottom.layoutParams =
                binding.mVerticalSideFrameBottom.layoutParams.apply { height = navHeight + dp5 }
//            val barLp = binding.lytAction.layoutParams as ViewGroup.MarginLayoutParams
//            val activityMargin = dp(0)
//            if (ime.bottom > 0) {
//                barLp.bottomMargin = ime.bottom + activityMargin
//            } else {
//                barLp.bottomMargin = navHeight + activityMargin
//            }
//            binding.lytAction.layoutParams = barLp
            applyBubbleListBottomInset(navHeight, ime.bottom)
            android.util.Log.d("SUN_DEBUG", "navHeight = " + navHeight + ", ime.bottom = " + ime.bottom)
            insets
        }
    }

    private fun applyBubbleListBottomInset(navHeight: Int, imeBottom: Int) {
        val activityMargin = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.activity_margin)
        val bottomInset = if (imeBottom > 0) imeBottom else navHeight
        binding.bubblePickerList.updatePadding(
            bottom = bottomInset + activityMargin + dp(30),
        )
    }

    /** Glass top chrome: keep [MAppBarLayout] transparent so [MVSideFrame] blur shows through (txCommon). */
    private fun applyTransparentMAppBarChrome() {
        binding.bubblePickerAppbar.apply {
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
            setLiftOnScrollColor(null)
        }
    }

    private fun setupBubblePickerTopAppBar() {
        binding.bubblePickerAppbar.setTitle(getString(com.goodwy.strings.R.string.speech_bubble))

        binding.bubblePickerAppbar.getBackArrow()?.apply {
            bindBlurTarget(this@MessageBubblePickerActivity, binding.mainBlurTarget)
            setOnMenuItemClickListener { menuItem ->
                if (menuItem.itemId == com.android.common.R.id.back_arrow) {
                    hideKeyboard()
                    cancelAndFinish()
                    true
                } else {
                    false
                }
            }
        }

        binding.bubblePickerAppbar.getSearchView()?.visibility = View.GONE
        binding.bubblePickerAppbar.getActionBarView()?.visibility = View.GONE
        applyTransparentMAppBarChrome()
    }

    /** Pinned toolbar row height when [MAppBarLayout] is fully collapsed (txCommon). */
    private fun getCollapsedAppBarHeightPx(): Int =
        resources.getDimensionPixelSize(com.android.common.R.dimen.tx_top_bar_toolbar_margin_top) +
            resources.getDimensionPixelSize(com.android.common.R.dimen.tx_top_bar_toolbar_height)

    private fun getExpandedAppBarHeightPx(): Int =
        resources.getDimensionPixelSize(com.android.common.R.dimen.tx_nest_bouncy_content_padding_top)

    private fun listTopPaddingForAppBarOffset(verticalOffset: Int): Int {
        val expanded = getExpandedAppBarHeightPx()
        val collapsed = getCollapsedAppBarHeightPx()
        val totalRange = binding.bubblePickerAppbar.totalScrollRange
        if (totalRange <= 0) return expanded
        val collapseFraction = (
            kotlin.math.abs(verticalOffset).toFloat() / totalRange.toFloat()
            ).coerceIn(0f, 1f)
        return kotlin.math.round(collapsed + (expanded - collapsed) * (1f - collapseFraction)).toInt()
    }

    /** Keep list content aligned with visible top chrome (expanded / collapsed). */
    private fun syncListTopPadding() {
        val topPad = listTopPaddingForAppBarOffset(bubblePickerAppBarVerticalOffset)
        binding.bubblePickerList.updatePadding(top = topPad)
    }

    private fun setupNestBouncyScroll() {
        val list = binding.bubblePickerList
        list.setOnScrollChangeListener { _, _, _, _, _ ->
            applyTransparentMAppBarChrome()
            binding.mVerticalSideFrameTop.update()
        }
        list.onOverscrollTranslationChanged = { overScrolledDistance ->
            val overscrollTranslation = overScrolledDistance * NEST_BOUNCY_OVERSCROLL_FACTOR
            binding.bubblePickerAppbar.translationY = overscrollTranslation
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
        binding.bubblePickerList.adapter = MessageBubblePickerAdapter(
            items = BUBBLE_DRAWABLE_OPTIONS,
            selectedOptionId = pendingSelectedOptionId
        ) { selected ->
            pendingSelectedOptionId = selected.id
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val NEST_BOUNCY_OVERSCROLL_FACTOR = 0.35f
    }
}
