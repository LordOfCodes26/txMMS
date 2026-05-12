package com.goodwy.commons.views

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.ViewGroup
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.core.view.isVisible
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.widget.TextViewCompat
import com.android.common.view.MActionBar
import com.goodwy.commons.R
import com.goodwy.commons.databinding.CustomToolbarBinding
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getSearchFieldCursorColor
import com.goodwy.commons.extensions.isNightDisplay
import com.goodwy.commons.extensions.onTextChangeListener
import eightbitlab.com.blurview.BlurTarget
import java.lang.reflect.Method

/**
 * Custom toolbar implementation using LinearLayout that mimics MaterialToolbar API
 * while preserving the same styling and functionality.
 */
class CustomToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        private val getMenuMethodCache = mutableMapOf<Class<*>, Method>()
        private val setMenuItemListenerMethodCache = mutableMapOf<Class<*>, Method>()

        private fun getMenuViaReflection(target: Any): Menu? = runCatching {
            val clazz = target.javaClass
            val method = getMenuMethodCache.getOrPut(clazz) {
                clazz.getMethod("getMenu")
            }
            @Suppress("UNCHECKED_CAST")
            method.invoke(target) as? Menu
        }.getOrNull()

        private fun setOnMenuItemClickListenerViaReflection(
            target: Any,
            listener: MenuItem.OnMenuItemClickListener
        ): Boolean = runCatching {
            val clazz = target.javaClass
            val method = setMenuItemListenerMethodCache.getOrPut(clazz) {
                clazz.getMethod("setOnMenuItemClickListener", MenuItem.OnMenuItemClickListener::class.java)
            }
            method.invoke(target, listener)
            true
        }.getOrDefault(false)
    }

    private var binding: CustomToolbarBinding? = null

    private var _menu: Menu? = null
    private var _action_menu: Menu? = null
    private var hasInflatedActionMenu = false
    private var menuInflater: MenuInflater? = null
    private var actionMenuInflater: MenuInflater? = null
    private var navigationMenu: Menu? = null
    private var navigationMenuInflater: MenuInflater? = null
    private var onMenuItemClickListener: MenuItem.OnMenuItemClickListener? = null
    private var onNavigationClickListener: OnClickListener? = null
    private var onNavigationMenuItemClickListener: MenuItem.OnMenuItemClickListener? = null
    private var onSearchTextChangedListener: ((String) -> Unit)? = null
    private var onSearchBackClickListener: OnClickListener? = null
    private var onSearchExpandListener: OnClickListener? = null
    private var onSearchClearClickListener: (() -> Unit)? = null
    private var forceShowSearchClearButton: Boolean = false

    /** When non-null, action bar visibility is fixed to this value and not overridden by [updateMenuDisplay]. */
    private var forceActionBarVisible: Boolean? = null

    // Cached values for performance
    private var cachedTextColor: Int? = null
    private var cachedPrimaryColor: Int? = null
    private var cachedCursorColor: Int? = null

    private var overflowIconDrawable: Drawable? = null
    private var navigationIconDrawable: Drawable? = null
    /** Blur target for overflow popups and optional wiring; set via [bindBlurTarget]. */
    private var boundBlurTarget: BlurTarget? = null
    private var isSearchBound = false
    private var searchAnimator: ValueAnimator? = null

    var isSearchExpanded: Boolean = false
        private set

    private fun navigationActionBarView(): View? {
        val currentBinding = binding ?: return null
        return currentBinding.root.findViewById(R.id.navigationIconView)
    }

    private fun navigationMActionBar(): MActionBar? = navigationActionBarView() as? MActionBar

    private fun actionMActionBar(): MActionBar? = binding?.actionBar as? MActionBar

    private fun navigationActionBarMenu(): Menu? {
        val actionBar = navigationActionBarView() ?: return null
        return getMenuViaReflection(actionBar)
    }

    private fun bindNavigationActionBarClickListener() {
        val actionBar = navigationActionBarView() ?: return
        setOnMenuItemClickListenerViaReflection(actionBar, MenuItem.OnMenuItemClickListener {
            onNavigationIconClicked(actionBar)
            true
        })
    }

    private fun inflateNavigationIconViewMenu() {
        binding?.navigationIconView?.inflateMenu(R.menu.cab_navigation_only)
    }

    private fun applyNavigationIconDrawable(drawable: Drawable?) {
        navigationIconDrawable = drawable?.mutate()
        val actionBarView = navigationActionBarView() ?: return
        val menu = navigationActionBarMenu()

        if (menu != null) {
            val fallbackItem = if (menu.size() > 0) menu.getItem(0) else null
            val navItem = menu.findItem(R.id.cab_remove) ?: fallbackItem
            if (navItem != null) {
                if (navigationIconDrawable != null) {
                    navItem.icon = navigationIconDrawable
                }
                navItem.isVisible = true
            }
        }

        // Show nav icon area when we have a drawable or when the menu has items (default back)
        val showNav = (menu?.size() ?: 0) > 0 || navigationIconDrawable != null
        actionBarView.visibility = if (showNav) View.VISIBLE else View.GONE
        bindNavigationActionBarClickListener()
    }

    private fun onNavigationIconClicked(view: View) {
        if (dispatchNavigationMenuItemClick()) {
            return
        }
        onNavigationClickListener?.onClick(view)
    }

    private fun dispatchNavigationMenuItemClick(): Boolean {
        val currentMenu = navigationMenu ?: return false
        for (i in 0 until currentMenu.size()) {
            val item = currentMenu.getItem(i)
            if (item.isVisible) {
                return onNavigationMenuItemClickListener?.onMenuItemClick(item) ?: false
            }
        }
        return false
    }

    var navigationIcon: Drawable?
        get() = navigationIconDrawable
        set(value) {
            applyNavigationIconDrawable(value)
        }

    var title: CharSequence?
        get() = binding?.titleTextView?.text
        set(value) {
            binding?.titleTextView?.apply {
                text = value
                visibility = if (value != null && value.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }

    val menu: Menu
        get() {
            if (_menu == null) {
                _menu = MenuBuilder(context)
            }
            return _menu!!
        }
    val actionMenu: Menu
        get() {
            if (_action_menu == null) {
                _action_menu = MenuBuilder(context)
            }
            return _action_menu!!
        }

    var overflowIcon: Drawable?
        get() = overflowIconDrawable
        set(value) {
            overflowIconDrawable = value
        }

    var collapseIcon: Drawable? = null

    init {
        // Inflate toolbar layout using ViewBinding
        val toolbarBinding = CustomToolbarBinding.inflate(LayoutInflater.from(context), this, false)
        binding = toolbarBinding

        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(toolbarBinding.root)
        setupActionBarAndSearchBindings()

        attrs?.let { attrSet ->
            val titleMenuAttrs = context.obtainStyledAttributes(
                attrSet,
                intArrayOf(android.R.attr.title, androidx.appcompat.R.attr.menu)
            )
            when (val titleRes = titleMenuAttrs.getResourceId(0, 0)) {
                0 -> titleMenuAttrs.getString(0)?.takeIf { it.isNotEmpty() }?.let { title = it }
                else -> title = context.getString(titleRes)
            }
            titleMenuAttrs.getResourceId(1, 0).takeIf { it != 0 }?.let { menuRes ->
                post { inflateMenu(menuRes) }
            }
            titleMenuAttrs.recycle()

            runCatching {
                val toolbarAttrs = context.obtainStyledAttributes(attrSet, androidx.appcompat.R.styleable.Toolbar, 0, 0)
                toolbarAttrs.getDrawable(androidx.appcompat.R.styleable.Toolbar_navigationIcon)?.let { drawable ->
                    post { navigationIcon = drawable }
                }
                toolbarAttrs.recycle()
            }
        }
    }

    private fun getTextColor(): Int {
        if (cachedTextColor == null) {
            cachedTextColor = context.getProperTextColor()
        }
        return cachedTextColor!!
    }

    private fun getPrimaryColor(): Int {
        if (cachedPrimaryColor == null) {
            cachedPrimaryColor = context.getProperPrimaryColor()
        }
        return cachedPrimaryColor!!
    }

    private fun getCursorColor(): Int {
        if (cachedCursorColor == null) {
            cachedCursorColor = context.getSearchFieldCursorColor()
        }
        return cachedCursorColor!!
    }

    private fun setupActionBarAndSearchBindings() {
        val binding = binding ?: return

        inflateNavigationIconViewMenu()
        applyNavigationIconDrawable(null)
        binding.actionBar.setPosition("right")
        bindSearchCallbacks()
        bindActionBarMenuListener()
        syncMenuFromActionBar()
        updateMenuDisplay()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindSearchCallbacks() {
        if (isSearchBound) return
        val binding = binding ?: return
        val queryView = binding.actionBarSearch.searchEditText

        // Search query text callback
        queryView.onTextChangeListener { text ->
            updateSearchClearIconVisibility(text)
            if (isSearchExpanded) {
                onSearchTextChangedListener?.invoke(text)
            }
        }

        queryView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val endDrawable = queryView.compoundDrawablesRelative[2]
                if (endDrawable != null) {
                    val drawableWidth = if (endDrawable.bounds.width() > 0) {
                        endDrawable.bounds.width()
                    } else {
                        endDrawable.intrinsicWidth
                    }
                    val touchStart = queryView.width - queryView.paddingEnd - drawableWidth
                    if (event.x >= touchStart) {
                        if (queryView.text?.isNotEmpty() == true) {
                            queryView.setText("")
                        } else if (forceShowSearchClearButton) {
                            onSearchClearClickListener?.invoke()
                        }
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }

        // Use clear action as the explicit "close search" action
        binding.actionBarSearch.searchBackButton.setOnClickListener {
            collapseSearch()
            onSearchBackClickListener?.onClick(it)
        }

        updateSearchClearIconVisibility(queryView.text?.toString().orEmpty())
        isSearchBound = true
    }

    private fun updateSearchClearIconVisibility(text: String) {
        val binding = binding ?: return
        val queryView = binding.actionBarSearch.searchEditText
        val showClear = text.isNotEmpty() || forceShowSearchClearButton
        queryView.setCompoundDrawablesRelativeWithIntrinsicBounds(
            com.android.common.R.drawable.ic_cmn_search,
            0,
            if (showClear) com.android.common.R.drawable.ic_cmn_circle_close_fill else 0,
            0
        )
        applySearchFieldCompoundDrawableTint(queryView)
    }

    private fun applySearchFieldCompoundDrawableTint(queryView: EditText) {
        if (queryView.context.isNightDisplay()) {
            TextViewCompat.setCompoundDrawableTintList(
                queryView,
                ColorStateList.valueOf(queryView.context.getColor(R.color.white))
            )
            TextViewCompat.setCompoundDrawableTintMode(queryView, PorterDuff.Mode.SRC_IN)
        } else {
            TextViewCompat.setCompoundDrawableTintList(queryView, null)
            TextViewCompat.setCompoundDrawableTintMode(queryView, null)
        }
    }

    private fun bindActionBarMenuListener() {
        val binding = binding ?: return
        binding.actionBar.setOnMenuItemClickListener { menuItem ->
            val handledByClient = onMenuItemClickListener?.onMenuItemClick(menuItem) ?: false
            if (handledByClient) {
                true
            } else if (isSearchMenuItem(menuItem.itemId)) {
                expandSearch()
                true
            } else {
                false
            }
        }
    }

    private fun isSearchMenuItem(itemId: Int): Boolean {
        val entryName = runCatching { resources.getResourceEntryName(itemId) }.getOrNull()
        return entryName == "search" || entryName == "action_search"
    }

    private fun syncMenuFromActionBar() {
        val actionBar = binding?.actionBar ?: return
        val reflectedMenu = getMenuViaReflection(actionBar)
        if (reflectedMenu != null && (_action_menu == null || !hasInflatedActionMenu)) {
            _action_menu = reflectedMenu
        }
    }

    private fun hasVisibleMenuItems(menu: Menu?): Boolean {
        if (menu == null) return false
        return (0 until menu.size()).any { menu.getItem(it).isVisible }
    }

    fun setOnSearchTextChangedListener(listener: ((String) -> Unit)?) {
        onSearchTextChangedListener = listener
        bindSearchCallbacks()
    }

    fun setOnSearchBackClickListener(listener: OnClickListener?) {
        onSearchBackClickListener = listener
        bindSearchCallbacks()
    }

    fun setOnSearchClearClickListener(listener: (() -> Unit)?) {
        onSearchClearClickListener = listener
        bindSearchCallbacks()
    }

    fun setForceShowSearchClearButton(forceShow: Boolean) {
        forceShowSearchClearButton = forceShow
        val currentText = binding?.actionBarSearch?.searchEditText?.text?.toString().orEmpty()
        updateSearchClearIconVisibility(currentText)
    }
    /**
     * Sets the visibility of the action bar (toolbar menu / overflow).
     * This value is remembered and will not be overridden by [updateMenuDisplay] (e.g. when
     * the menu is invalidated or search expands/collapses), except while search is expanded
     * the action bar stays hidden. Call [setActionBarVisibilityAutomatic] to revert to
     * automatic visibility based on menu items.
     */
    fun setActionBarVisible(visible: Boolean) {
        forceActionBarVisible = visible
        getActionBar()?.isVisible = visible
    }

    /** Clears the forced visibility and recalculates action bar visibility from menu state. */
    fun setActionBarVisibilityAutomatic() {
        forceActionBarVisible = null
        updateMenuDisplay()
    }

    /** @deprecated Use [setActionBarVisible] instead. */
    @Deprecated("Use setActionBarVisible(visible) instead", ReplaceWith("setActionBarVisible(forceShow)"))
    fun setActionBarShow(forceShow: Boolean) {
        setActionBarVisible(forceShow)
    }

    /** Returns the navigation icon view (e.g. back button), or null if not available. */
    fun getNavigationIconView(): View? = navigationActionBarView()

    /** Sets the visibility of the navigation icon view. */
    fun setNavigationIconViewVisible(visible: Boolean) {
        navigationActionBarView()?.isVisible = visible
    }

    fun setOnSearchExpandListener(listener: OnClickListener?) {
        onSearchExpandListener = listener
    }


    fun expandSearch() {
        val binding = binding ?: return
        if (isSearchExpanded) return

        isSearchExpanded = true
        val searchRoot = binding.actionBarSearch.root

        binding.actionBar.visibility = View.GONE
        binding.titleTextView.visibility = View.GONE
        updateSearchClearIconVisibility(binding.actionBarSearch.searchEditText.text?.toString().orEmpty())
        binding.actionBarSearch.searchBackButton.visibility = View.VISIBLE

        searchRoot.visibility = View.VISIBLE
        searchRoot.alpha = 0f
        val params = searchRoot.layoutParams ?: return
        params.width = 0
        searchRoot.layoutParams = params

        searchRoot.post {
            searchAnimator?.cancel()
            val parentView = searchRoot.parent as? View ?: return@post
            val targetWidth = parentView.width
            if (targetWidth <= 0) {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                searchRoot.layoutParams = params
                searchRoot.alpha = 1f
                searchRoot.translationX = 0f
                if (isSearchInputEditable()) focusSearchInput()
                onSearchExpandListener?.onClick(this@CustomToolbar)
                return@post
            }

            searchRoot.translationX = targetWidth.toFloat()

            searchAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 250L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val fraction = anim.animatedFraction
                    val currentWidth = (targetWidth * fraction).toInt()
                    (searchRoot.layoutParams)?.let { lp ->
                        lp.width = currentWidth
                        searchRoot.layoutParams = lp
                    }
                    searchRoot.alpha = fraction
                    searchRoot.translationX = targetWidth * (1f - fraction)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        searchAnimator = null
                        (searchRoot.layoutParams)?.let { lp ->
                            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                            searchRoot.layoutParams = lp
                        }
                        searchRoot.alpha = 1f
                        searchRoot.translationX = 0f
                        if (isSearchInputEditable()) focusSearchInput()
                        onSearchExpandListener?.onClick(this@CustomToolbar)
                    }
                })
                start()
            }
        }
    }

    fun collapseSearch() {
        val binding = binding ?: return
        if (!isSearchExpanded && !binding.actionBarSearch.root.isVisible) return

        isSearchExpanded = false
        forceShowSearchClearButton = false
        val queryView = binding.actionBarSearch.searchEditText
        queryView.setText("")
        queryView.clearFocus()
        updateSearchClearIconVisibility("")
        binding.actionBarSearch.searchBackButton.visibility = View.GONE

        val searchRoot = binding.actionBarSearch.root
        val startWidth = searchRoot.width.coerceAtLeast(0)

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)

        if (startWidth <= 0) {
            searchRoot.visibility = View.GONE
            (searchRoot.layoutParams)?.let { lp ->
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                searchRoot.layoutParams = lp
            }
            searchRoot.alpha = 1f
            searchRoot.translationX = 0f
            updateMenuDisplay()
            // Use current title so caller can restore title after collapse (e.g. dialpad-search collapse)
            val hasTitleEarly = binding.titleTextView.text?.isNotEmpty() == true
            binding.titleTextView.visibility = if (hasTitleEarly) View.VISIBLE else View.GONE
            if (hasTitleEarly && !isSearchExpanded) binding.actionBar.visibility = View.VISIBLE
            return
        }

        // Fade in title and action bar as search collapses
        val showTitle = binding.titleTextView.text?.isNotEmpty() == true
        if (showTitle) {
            binding.titleTextView.visibility = View.VISIBLE
            binding.titleTextView.alpha = 0f
        }
        updateMenuDisplay()
        binding.actionBar.alpha = 0f
        binding.actionBar.visibility = View.VISIBLE

        searchAnimator?.cancel()
        searchAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 250L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                val currentWidth = (startWidth * (1f - fraction)).toInt()
                (searchRoot.layoutParams)?.let { lp ->
                    lp.width = currentWidth
                    searchRoot.layoutParams = lp
                }
                searchRoot.alpha = 1f - fraction
                searchRoot.translationX = startWidth * fraction
                searchRoot.requestLayout()
                val contentAlpha = fraction
                binding.actionBar.alpha = contentAlpha
                if (showTitle) binding.titleTextView.alpha = contentAlpha
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    searchAnimator = null
                    searchRoot.visibility = View.GONE
                    (searchRoot.layoutParams)?.let { lp ->
                        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                        searchRoot.layoutParams = lp
                    }
                    searchRoot.alpha = 1f
                    searchRoot.translationX = 0f
                    binding.actionBar.alpha = 1f
                    if (showTitle) binding.titleTextView.alpha = 1f
                    updateMenuDisplay()
                    // Use current title state so activity can restore title/action bar during collapse (e.g. after hiding dialpad search)
                    val hasTitle = binding.titleTextView.text?.isNotEmpty() == true
                    binding.titleTextView.visibility =
                        if (hasTitle) View.VISIBLE else View.GONE
                    // When activity restored the title, keep action bar visible (updateMenuDisplay may have hidden it)
                    if (hasTitle && !isSearchExpanded) {
                        binding.actionBar.visibility = View.VISIBLE
                    }
                }
            })
            start()
        }
    }

    fun getSearchText(): String {
        val binding = binding ?: return ""
        return binding.actionBarSearch.searchEditText.text?.toString().orEmpty()
    }

    fun setSearchText(text: String) {
        val binding = binding ?: return
        binding.actionBarSearch.searchEditText.setText(text)
        updateSearchClearIconVisibility(text)
    }

    fun setSearchHint(hint: CharSequence?) {
        val binding = binding ?: return
        binding.actionBarSearch.searchEditText.hint = hint
    }

    fun resetSearchHint() {
        setSearchHint(context.getString(R.string.search))
    }

    fun setSearchInputEditable(isEditable: Boolean) {
        val binding = binding ?: return
        binding.actionBarSearch.searchEditText.apply {
            isFocusable = isEditable
            isFocusableInTouchMode = isEditable
            isCursorVisible = isEditable
            isLongClickable = isEditable
            setTextIsSelectable(isEditable)
        }
    }

    fun focusSearchInput(showKeyboard: Boolean = true) {
        val binding = binding ?: return
        if (!isSearchExpanded) {
            expandSearch()
        }
        if (!isSearchInputEditable()) {
            return
        }

        val searchEditText = binding.actionBarSearch.searchEditText
        searchEditText.requestFocus()
        searchEditText.setSelection(searchEditText.text?.length ?: 0)
        if (showKeyboard) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(searchEditText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun isSearchInputEditable(): Boolean {
        val searchEditText = binding?.actionBarSearch?.searchEditText ?: return false
        return searchEditText.isEnabled && searchEditText.isFocusable && searchEditText.isFocusableInTouchMode
    }

    fun updateSearchColors() {
        cachedTextColor = null
        cachedPrimaryColor = null
        cachedCursorColor = null

        val binding = binding ?: return
        val textColor = getTextColor()
        val primaryColor = getPrimaryColor()
        val cursorColor = getCursorColor()

        binding.actionBarSearch.searchEditText.setColors(
            textColor,
            primaryColor,
            cursorColor
        )

        binding.actionBarSearch.searchBackButton.imageTintList =
            ColorStateList.valueOf(textColor)
        updateSearchClearIconVisibility(binding.actionBarSearch.searchEditText.text?.toString().orEmpty())
    }

    fun setNavigationContentDescription(resId: Int) {
        navigationActionBarView()?.contentDescription = context.getString(resId)
    }

    fun setNavigationContentDescription(description: CharSequence?) {
        navigationActionBarView()?.contentDescription = description
    }

    fun setNavigationOnClickListener(listener: OnClickListener?) {
        onNavigationClickListener = listener
        bindNavigationActionBarClickListener()
    }

    fun inflateNavigationMenu(resId: Int) {
        if (navigationMenuInflater == null) {
            navigationMenuInflater = MenuInflater(context)
        }
        if (navigationMenu == null) {
            navigationMenu = MenuBuilder(context)
        }
        navigationMenu?.clear()
        navigationMenuInflater?.inflate(resId, navigationMenu)
        bindNavigationActionBarClickListener()
    }

    fun setOnNavigationMenuItemClickListener(listener: MenuItem.OnMenuItemClickListener?) {
        onNavigationMenuItemClickListener = listener
    }

    fun setTitleTextColor(color: Int) {
        binding?.titleTextView?.setTextColor(color)
    }

    fun setTitleTextColor(colors: ColorStateList?) {
        binding?.titleTextView?.setTextColor(colors)
    }

    fun getActionBar() = binding?.actionBar

    /**
     * Binds [BlurTarget] on both navigation and action [MActionBar] pills (same contract as
     * [MActionBar.bindBlurTarget]).
     */
    fun bindBlurTarget(activity: Activity, blurTarget: BlurTarget) {
        boundBlurTarget = blurTarget
        navigationMActionBar()?.bindBlurTarget(activity, blurTarget)
        actionMActionBar()?.bindBlurTarget(activity, blurTarget)
    }

    /**
     * Binds blur target with optional overlay tint (`0` keeps each bar’s current resolved overlay).
     */
    fun bindBlurTarget(activity: Activity, blurTarget: BlurTarget, @ColorInt overlayColor: Int) {
        boundBlurTarget = blurTarget
        val nav = navigationMActionBar()
        val action = actionMActionBar()
        if (overlayColor != 0) {
            nav?.bindBlurTarget(activity, blurTarget, overlayColor)
            action?.bindBlurTarget(activity, blurTarget, overlayColor)
        } else {
            nav?.bindBlurTarget(activity, blurTarget)
            action?.bindBlurTarget(activity, blurTarget)
        }
    }

    /**
     * Sets [MActionBar] theme mode for both navigation and action bars.
     * Supported styles: "dark", "light", "system" (fallback: "system").
     */
    fun setActionBarsThemeStyle(style: String?) {
        val normalizedStyle = when (style?.trim()?.lowercase()) {
            "dark" -> "dark"
            "light" -> "light"
            "system" -> "system"
            else -> "system"
        }
        navigationMActionBar()?.setTheme(normalizedStyle)
        actionMActionBar()?.setTheme(normalizedStyle)
    }

    /** Type-safe overload for [setActionBarsThemeStyle]. */
    fun setActionBarsThemeStyle(mode: MActionBar.ThemeMode) {
        navigationMActionBar()?.setTheme(mode)
        actionMActionBar()?.setTheme(mode)
    }

    /**
     * Sets blur overlay color on both action bars. Pass `0` to restore each bar’s default
     * ([MActionBar.setOverlay]).
     */
    fun setActionBarsOverlay(@ColorInt overlayColor: Int) {
        navigationMActionBar()?.setOverlay(overlayColor)
        actionMActionBar()?.setOverlay(overlayColor)
    }

    /**
     * @param keepDefaultAlpha When true, only RGB is replaced; alpha comes from the bar default overlay.
     */
    fun setActionBarsOverlay(@ColorInt overlayColor: Int, keepDefaultAlpha: Boolean) {
        navigationMActionBar()?.setOverlay(overlayColor, keepDefaultAlpha)
        actionMActionBar()?.setOverlay(overlayColor, keepDefaultAlpha)
    }

    private fun getLiveActionBarMenu(): Menu? {
        val actionBar = binding?.actionBar ?: return null
        return getMenuViaReflection(actionBar)
    }

    private fun invalidateActionBarMenuPresentation() {
        val actionBar = binding?.actionBar ?: return
        val liveMenu = getLiveActionBarMenu()
        if (liveMenu is MenuBuilder) {
            liveMenu.onItemsChanged(true)
        }
        (actionBar as? View)?.let { actionBarView ->
            actionBarView.requestLayout()
            actionBarView.invalidate()
        }
    }

    fun setActionMenuItemVisibility(itemId: Int, isVisible: Boolean): Boolean {
        val liveMenu = getLiveActionBarMenu()
        if (liveMenu != null) {
            val targetItem = liveMenu.findItem(itemId) ?: return false
            if (targetItem.isVisible != isVisible) {
                targetItem.isVisible = isVisible
                invalidateActionBarMenuPresentation()
            }
            _action_menu?.findItem(itemId)?.let { modelItem ->
                if (modelItem.isVisible != isVisible) {
                    modelItem.isVisible = isVisible
                    if (_action_menu is MenuBuilder) {
                        (_action_menu as MenuBuilder).onItemsChanged(true)
                    }
                }
            }
            updateMenuDisplay()
            return true
        }

        val fallbackMenu = _action_menu ?: return false
        val fallbackItem = fallbackMenu.findItem(itemId) ?: return false
        if (fallbackItem.isVisible != isVisible) {
            fallbackItem.isVisible = isVisible
            if (fallbackMenu is MenuBuilder) {
                fallbackMenu.onItemsChanged(true)
            }
            updateMenuDisplay()
        }
        return true
    }

    fun setPopupForMoreItem(
        moreItemId: Int,
        menuResId: Int,
        blurTargetView: View?,
        listener: MenuItem.OnMenuItemClickListener?
    ): Boolean {
        val actionBar = binding?.actionBar ?: return false
        if (listener == null) return false

        // Keep popup menu in toolbar cache, so callers can mutate visibility/title dynamically.
        if (menuInflater == null) {
            menuInflater = MenuInflater(context)
        }
        if (_menu == null) {
            _menu = MenuBuilder(context)
        } else {
            _menu?.clear()
        }
        menuInflater?.inflate(menuResId, _menu)

        boundBlurTarget = blurTargetView as? BlurTarget ?: boundBlurTarget

        val popupAnchor = actionBar as? View ?: return false
        val fallbackClickListener = MenuItem.OnMenuItemClickListener { clickedItem ->
            if (clickedItem.itemId == moreItemId) {
                showMorePopupMenu(popupAnchor, listener)
                true
            } else {
                onMenuItemClickListener?.onMenuItemClick(clickedItem) == true ||
                    isSearchMenuItem(clickedItem.itemId).also { if (it) expandSearch() }
            }
        }
        return setOnMenuItemClickListenerViaReflection(actionBar, fallbackClickListener)
    }

    private fun showMorePopupMenu(
        anchor: View,
        listener: MenuItem.OnMenuItemClickListener
    ) {
        val sourceMenu = _menu ?: return
        val menu = MenuBuilder(context)
        for (i in 0 until sourceMenu.size()) {
            val item = sourceMenu.getItem(i)
            val popupItem = menu.add(item.groupId, item.itemId, item.order, item.title)
            popupItem.icon = item.icon
            popupItem.isVisible = item.isVisible
            popupItem.isEnabled = item.isEnabled
            popupItem.isCheckable = item.isCheckable
            popupItem.isChecked = item.isChecked
        }
        showMPopupMenu(
            context = context,
            anchor = anchor,
            menu = menu,
            gravity = Gravity.END,
            blurTarget = boundBlurTarget,
            listener = listener,
        )
    }

    fun inflateMenu(actionMenuResId: Int) {
        getLiveActionBarMenu()?.clear()
        binding?.actionBar?.inflateMenu(actionMenuResId)
        hasInflatedActionMenu = true

        if (actionMenuInflater == null) actionMenuInflater = MenuInflater(context)
        _action_menu = MenuBuilder(context).also { actionMenuInflater?.inflate(actionMenuResId, it) }
        updateMenuDisplay()
    }

    fun setOnMenuItemClickListener(listener: MenuItem.OnMenuItemClickListener?) {
        onMenuItemClickListener = listener
        bindActionBarMenuListener()
    }

    fun invalidateMenu() {
        syncMenuFromActionBar()
        invalidateActionBarMenuPresentation()
        updateMenuDisplay()
    }

    private fun updateMenuDisplay() {
        val toolbarBinding = binding ?: return
        if (isSearchExpanded) {
            toolbarBinding.actionBar.visibility = View.GONE
            return
        }

        forceActionBarVisible?.let { forced ->
            toolbarBinding.actionBar.visibility = if (forced) View.VISIBLE else View.GONE
            return
        }

        if (!hasInflatedActionMenu) {
            toolbarBinding.actionBar.visibility = View.GONE
            return
        }

        val shouldShowActionBar = hasVisibleMenuItems(_action_menu)
        toolbarBinding.actionBar.visibility = if (shouldShowActionBar) View.VISIBLE else View.GONE
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Don't update menu display on every layout change to prevent infinite loops
        // Menu display is updated when menu is inflated or invalidated explicitly
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        searchAnimator?.cancel()
        searchAnimator = null
        cachedTextColor = null
        cachedPrimaryColor = null
        cachedCursorColor = null
        boundBlurTarget = null
    }
}
