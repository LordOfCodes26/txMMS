package com.goodwy.commons.views

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import com.google.android.material.appbar.MaterialToolbar
import com.goodwy.commons.R
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.databinding.MenuSearchBinding
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperPrimaryColor

open class MySearchMenu(context: Context, attrs: AttributeSet) : MyAppBarLayout(context, attrs) {
    var isSearchOpen = false
    var onSearchTextChangedListener: ((text: String) -> Unit)? = null

    val binding = MenuSearchBinding.inflate(LayoutInflater.from(context), this)

    override val toolbar: MaterialToolbar?
        get() = null // CustomToolbar is used instead
    
    override val customToolbar: CustomToolbar?
        get() = binding.topToolbar
    
    override fun requireCustomToolbar(): CustomToolbar = customToolbar ?: error("CustomToolbar not found")

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
    }

    fun searchBeVisibleIf(visible: Boolean = true) {
        // Search bar removed - no-op
    }

    fun requestFocusAndShowKeyboard() {
        // Search bar removed - no-op
    }

    fun setText(text: String) {
        // Search bar removed - no-op
    }

    fun clearSearch() {
        // Search bar removed - no-op
    }
}
