package com.goodwy.commons.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.util.TypedValue
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.databinding.MenuSearchBinding
import com.goodwy.commons.extensions.baseConfig
import androidx.core.view.ViewCompat
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperPrimaryColor

open class MySearchMenu(context: Context, attrs: AttributeSet) : MyAppBarLayout(context, attrs) {
    var isSearchOpen = false
    var onSearchTextChangedListener: ((text: String) -> Unit)? = null

    val binding = MenuSearchBinding.inflate(LayoutInflater.from(context), this)
    private var savedScrollFlags: Int? = null
    private var savedAppBarHeight: Int? = null
    /** Preserves measured height while action mode swaps toolbar so CoordinatorLayout does not reflow the list. */
    private var savedLayoutHeightBeforeActionMode: Int? = null
    private val minCollapsedTitleScale = 0.8f
    private val navTitleGapDp = 40f
    private val fallbackNavShiftDp = 10f

    init {
        addOnOffsetChangedListener { appBarLayout, verticalOffset ->
            val totalRange = appBarLayout.totalScrollRange
            if (totalRange <= 0) return@addOnOffsetChangedListener

            val collapseFraction = kotlin.math.abs(verticalOffset).toFloat() / totalRange.toFloat()
            val targetScale = 1f - ((1f - minCollapsedTitleScale) * collapseFraction)

            binding.collapsingTitle.pivotX = 0f
            binding.collapsingTitle.pivotY = binding.collapsingTitle.height / 2f
            binding.collapsingTitle.scaleX = targetScale
            binding.collapsingTitle.scaleY = targetScale

            val isNavigationVisible = binding.topToolbar.getNavigationIconView()?.isShown == true
            if (isNavigationVisible) {
                val navGapPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    navTitleGapDp,
                    resources.displayMetrics
                )
                val fallbackShiftPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    fallbackNavShiftDp,
                    resources.displayMetrics
                )

                val navRect = Rect()
                val titleRect = Rect()
                val navView = binding.topToolbar.getNavigationIconView()
                val hasRects = navView?.getGlobalVisibleRect(navRect) == true &&
                    binding.collapsingTitle.getGlobalVisibleRect(titleRect)
                val requiredShiftPx = if (hasRects) {
                    // Move title so its left edge starts after nav icon + gap.
                    (navRect.right + navGapPx - titleRect.left).coerceAtLeast(0f)
                } else {
                    fallbackShiftPx
                }

                binding.collapsingTitle.translationX = 100f * collapseFraction
            } else {
                binding.collapsingTitle.translationX = 0f
            }
        }
    }

    override val toolbar: MaterialToolbar?
        get() = null // CustomToolbar is used instead
    
    override val customToolbar: CustomToolbar?
        get() = binding.topToolbar
    
    override fun requireCustomToolbar(): CustomToolbar = customToolbar ?: error("CustomToolbar not found")

    fun getActionModeToolbar(): CustomActionModeToolbar = binding.actionModeToolbar

    fun isActionModeToolbarVisible(): Boolean = binding.actionModeToolbar.visibility == View.VISIBLE

    /** Large title under the toolbar (e.g. "Recents"); hide while the inline search field is expanded. */
    fun setCollapsingTitleVisible(visible: Boolean) {
        binding.collapsingTitle.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun showActionModeToolbar() {
        fun stableHeightPx(): Int = when {
            height > 0 -> height
            measuredHeight > 0 -> measuredHeight
            else -> 0
        }
        fun lockHeightIfNeeded() {
            if (savedLayoutHeightBeforeActionMode != null) return
            val sh = stableHeightPx()
            if (sh > 0) {
                savedLayoutHeightBeforeActionMode = layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
                layoutParams = layoutParams.apply { height = sh }
            }
        }
        fun applyVisibility() {
            binding.actionModeToolbar.visibility = View.VISIBLE
            binding.searchBarContainer.visibility = View.GONE
            binding.searchBarContainer.requestLayout()
            requestLayout()
        }
        lockHeightIfNeeded()
        if (savedLayoutHeightBeforeActionMode == null) {
            // After resume the app bar can be unmeasured on the first frame; lock on the next pass.
            post {
                lockHeightIfNeeded()
                applyVisibility()
            }
            return
        }
        applyVisibility()
    }

    fun hideActionModeToolbar() {
        binding.actionModeToolbar.visibility = View.GONE
        binding.searchBarContainer.visibility = View.VISIBLE
        savedLayoutHeightBeforeActionMode?.let { previousHeight ->
            layoutParams = layoutParams.apply {
                height = previousHeight
            }
            savedLayoutHeightBeforeActionMode = null
        }
        binding.searchBarContainer.requestLayout()
        // Restore full collapsing toolbar geometry for CoordinatorLayout + list behavior.
        setExpanded(true, false)
        requestLayout()
    }

    /**
     * Glass-style top chrome: app bar and collapsing container stay transparent so blur can read
     * content behind. [background] is only for API symmetry; status bar / system UI still use it
     * via [updateTopBarColors] in [updateColors]. Put a solid fill on the coordinator behind this
     * view so the status-bar inset does not show the window black.
     */
    fun applyChromeBackground(_background: Int) {
        setBackgroundColor(Color.TRANSPARENT)
        binding.searchBarContainer.setBackgroundColor(Color.TRANSPARENT)
        binding.actionModeToolbar.setBackgroundColor(Color.TRANSPARENT)
    }

    /** One place to recover app bar geometry after process resume / action mode. */
    fun refreshChromeAfterActivityResume(expandForRecentsMode: Boolean) {
        if (expandForRecentsMode && !isActionModeToolbarVisible()) {
            setExpanded(true, false)
        }
        ViewCompat.requestApplyInsets(this)
        requestLayout()
    }

    fun collapseAndLockCollapsing() {
        setExpanded(false, true)
        val params = binding.searchBarContainer.layoutParams as? AppBarLayout.LayoutParams ?: return
        if (savedScrollFlags == null) {
            savedScrollFlags = params.scrollFlags
        }
        params.scrollFlags = 0
        binding.searchBarContainer.layoutParams = params

        if (savedAppBarHeight == null) {
            savedAppBarHeight = layoutParams?.height
        }
        val collapsedHeight = resolveActionBarSizePx() + paddingTop + paddingBottom
        layoutParams = layoutParams.apply {
            height = collapsedHeight
        }
        requestLayout()
    }

    fun unlockCollapsing() {
        val hadLockedScrollFlags = savedScrollFlags != null
        val hadLockedHeight = savedAppBarHeight != null

        if (!hadLockedScrollFlags && !hadLockedHeight) return

        if (hadLockedScrollFlags) {
            (binding.searchBarContainer.layoutParams as? AppBarLayout.LayoutParams)?.let { params ->
                params.scrollFlags = savedScrollFlags!!
                binding.searchBarContainer.layoutParams = params
            }
            savedScrollFlags = null
        }

        if (hadLockedHeight) {
            layoutParams = layoutParams.apply {
                height = savedAppBarHeight ?: LayoutParams.WRAP_CONTENT
            }
            savedAppBarHeight = null
        }

        requestLayout()
    }

    private fun resolveActionBarSizePx(): Int {
        val outValue = TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.actionBarSize, outValue, true)) {
            TypedValue.complexToDimensionPixelSize(outValue.data, resources.displayMetrics)
        } else {
            resources.getDimensionPixelSize(androidx.appcompat.R.dimen.abc_action_bar_default_height_material)
        }
    }

    fun getCollapsedHeightPx(): Int = resolveActionBarSizePx() + paddingTop + paddingBottom

    fun setupMenu() {
        // Search bar removed - no setup needed
    }

    fun closeSearch() {
        isSearchOpen = false
    }

    fun getCurrentQuery() = ""

    @Suppress("unused", "EmptyFunctionBlock")
    @Deprecated("This feature is broken for now.")
    fun toggleHideOnScroll(hideOnScroll: Boolean) {}

    fun updateColors(background: Int = context.getProperBackgroundColor(), scrollOffset: Int = 0) {
        val primaryColor = context.getProperPrimaryColor()

        (context as? BaseSimpleActivity)?.updateTopBarColors(this, background)
        // [updateTopBarColors] sets a solid bar fill; restore glass transparency for this menu.
        applyChromeBackground(background)

        if (context.baseConfig.topAppBarColorTitle) binding.topToolbar.setTitleTextColor(ColorStateList.valueOf(primaryColor))
    }

    /** App bar + search strip area only; does not change status bar / title colors from [updateColors]. */
    fun setMenuBarBackgroundColor(color: Int) {
        applyChromeBackground(color)
    }

    fun updateTitle(title: String) {
//        binding.topToolbar.title = title
        binding.collapsingTitle.text = title
    }

    fun searchBeVisibleIf(visible: Boolean = true) {
        // Search bar removed - no-op
    }

    fun requestFocusAndShowKeyboard() {
        // Search bar removed - no-op
    }

    fun setText(text: String?) {
        // Search bar removed - no-op
        binding.collapsingTitle.text = text
    }

    fun clearSearch() {
        // Search bar removed - no-op
    }
}
