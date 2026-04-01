package com.goodwy.commons.views

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.databinding.MenuSearchBinding
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperPrimaryColor

open class MySearchMenu(context: Context, attrs: AttributeSet) : MyAppBarLayout(context, attrs) {
    var isSearchOpen = false
    var onSearchTextChangedListener: ((text: String) -> Unit)? = null

    val binding = MenuSearchBinding.inflate(LayoutInflater.from(context), this)
    private var savedScrollFlags: Int? = null
    private var savedAppBarHeight: Int? = null
    private val minCollapsedTitleScale = 0.8f

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
        }
    }

    override val toolbar: MaterialToolbar?
        get() = null // CustomToolbar is used instead
    
    override val customToolbar: CustomToolbar?
        get() = binding.topToolbar
    
    override fun requireCustomToolbar(): CustomToolbar = customToolbar ?: error("CustomToolbar not found")

    fun getActionModeToolbar(): CustomActionModeToolbar = binding.actionModeToolbar

    fun showActionModeToolbar() {
        binding.actionModeToolbar.visibility = View.VISIBLE
        binding.searchBarContainer.visibility = View.GONE
    }

    fun hideActionModeToolbar() {
        binding.actionModeToolbar.visibility = View.GONE
        binding.searchBarContainer.visibility = View.VISIBLE
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
        val params = binding.searchBarContainer.layoutParams as? AppBarLayout.LayoutParams ?: return
        params.scrollFlags = savedScrollFlags
            ?: AppBarLayout.LayoutParams.SCROLL_FLAG_NO_SCROLL
        binding.searchBarContainer.layoutParams = params
        savedScrollFlags = null

        layoutParams = layoutParams.apply {
            height = savedAppBarHeight ?: LayoutParams.WRAP_CONTENT
        }
        savedAppBarHeight = null
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

        setBackgroundColor(background)
        binding.searchBarContainer.setBackgroundColor(background)
        (context as? BaseSimpleActivity)?.updateTopBarColors(this, background)

        if (context.baseConfig.topAppBarColorTitle) binding.topToolbar.setTitleTextColor(ColorStateList.valueOf(primaryColor))
    }

    fun updateTitle(title: String) {
        binding.topToolbar.title = title
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
