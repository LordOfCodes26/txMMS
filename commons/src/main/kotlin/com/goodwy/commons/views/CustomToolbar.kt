package com.goodwy.commons.views

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.PorterDuff
import android.util.Log
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.goodwy.commons.views.MyEditText
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import java.lang.reflect.Method
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.view.MenuCompat
import com.goodwy.commons.R
import com.goodwy.commons.databinding.CustomToolbarBinding
import com.goodwy.commons.databinding.CustomToolbarSearchContainerBinding
import com.goodwy.commons.extensions.adjustAlpha
import com.goodwy.commons.extensions.applyColorFilter
import com.goodwy.commons.extensions.getColoredMaterialSearchBarColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getProperTextCursorColor
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.onTextChangeListener
import android.graphics.drawable.GradientDrawable
import com.android.common.util.Util
import com.android.common.view.MImageButton
import com.goodwy.commons.views.BlurPopupMenu

/**
 * Custom toolbar implementation using LinearLayout that mimics MaterialToolbar API
 * while preserving the same styling and functionality.
 */
class CustomToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var binding: CustomToolbarBinding? = null
    private var searchBinding: CustomToolbarSearchContainerBinding? = null

    private var _menu: Menu? = null
    private var menuInflater: MenuInflater? = null
    private var onMenuItemClickListener: MenuItem.OnMenuItemClickListener? = null
    private var onNavigationClickListener: OnClickListener? = null
    private var onSearchTextChangedListener: ((String) -> Unit)? = null
    private var onSearchBackClickListener: OnClickListener? = null
    private var onSearchExpandListener: OnClickListener? = null

    // Cached values for performance
    private var cachedTextColor: Int? = null
    private var cachedPrimaryColor: Int? = null
    private var cachedCursorColor: Int? = null
    private var cachedIconSize: Int? = null
    private var cachedMediumIconSize: Int? = null
    private var cachedSmallerMargin: Int? = null
    private var cachedIconPadding: Int? = null
    private var cachedNormalMargin: Int? = null
    private var cachedBackgroundDrawable: Drawable? = null

    // Cached reflection method for requiresActionButton
    private var requiresActionButtonMethod: Method? = null

    var isSearchExpanded: Boolean = false
        private set

    var navigationIcon: Drawable?
        get() = binding?.navigationIconView?.drawable
        set(value) {
            binding?.navigationIconView?.apply {
                setImageDrawable(value)
                visibility = if (value != null) View.VISIBLE else View.GONE
                if (value != null) {
                    setOnClickListener(onNavigationClickListener)
                }
            }
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

    var overflowIcon: Drawable?
        get() = binding?.menuButton?.drawable
        set(value) {
            binding?.menuButton?.apply {
                setImageDrawable(value)
                visibility = if (value != null) View.VISIBLE else View.GONE
            }
            // Update action buttons container margin when menu button visibility changes
            updateActionButtonsContainerMargin()
        }

    var collapseIcon: Drawable? = null

    init {
        // Inflate toolbar layout using ViewBinding
        val toolbarBinding = CustomToolbarBinding.inflate(LayoutInflater.from(context), this, false)
        binding = toolbarBinding

        // Copy layout parameters and properties from inflated layout
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
//        setPadding(
//            toolbarBinding.root.paddingLeft,
//            toolbarBinding.root.paddingTop,
//            toolbarBinding.root.paddingRight,
//            toolbarBinding.root.paddingBottom
//        )

        // Add the root view to this CustomToolbar
        addView(toolbarBinding.root)

        // Find search container binding from included layout
        setupSearchContainer()

        // Setup click listeners
        binding?.menuButton?.setOnClickListener {
            showMenuPopup()
            Util.startAnimationVectorDrawable(binding?.menuButton)
        }

        // Read attributes from XML
        attrs?.let {
            val attrsArray = intArrayOf(
                android.R.attr.title,
                androidx.appcompat.R.attr.menu
            )
            val typedArray = context.obtainStyledAttributes(it, attrsArray)

            // Read title
            val titleRes = typedArray.getResourceId(0, 0)
            if (titleRes != 0) {
                title = context.getString(titleRes)
            } else {
                val titleText = typedArray.getString(0)
                if (!titleText.isNullOrEmpty()) {
                    title = titleText
                }
            }

            // Read menu resource
            val menuRes = typedArray.getResourceId(1, 0)
            if (menuRes != 0) {
                post { inflateMenu(menuRes) }
            }

            typedArray.recycle()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSearchContainer() {
        if (searchBinding != null) return

        val binding = this.binding ?: return
        // Find the included search container view by ID
        val searchContainerView = binding.root.findViewById<ViewGroup>(R.id.searchContainer) ?: return

        // Create binding for search container using bind()
        searchBinding = CustomToolbarSearchContainerBinding.bind(searchContainerView)
        val search = searchBinding ?: return

        // Setup click listeners
        search.searchBackButton.setOnClickListener {
            collapseSearch()
            onSearchBackClickListener?.onClick(it)
        }

        // Setup search EditText
        val editText = search.searchEditText
        val textColor = getTextColor()
        val primaryColor = getPrimaryColor()
        val cursorColor = getCursorColor()
        editText.setColors(textColor, primaryColor, cursorColor)

        // Update icon colors
        updateSearchIconColors(editText)

        // Setup text watcher
        editText.onTextChangeListener { text ->
            onSearchTextChangedListener?.invoke(text)
            updateClearButton(editText, text.isNotEmpty())
        }

        // Setup clear button click listener using OnTouchListener for drawableEnd
        editText.setOnTouchListener { v, event ->
            val editText = v as? MyEditText ?: return@setOnTouchListener false

            // Only handle if there's a clear icon (drawableEnd)
            val drawables = editText.compoundDrawables
            val clearDrawable = drawables[2] // drawableEnd

            if (clearDrawable != null && event.action == android.view.MotionEvent.ACTION_UP) {
                val x = event.x
                val y = event.y

                // Calculate the position of drawableEnd
                val drawableWidth = clearDrawable.bounds.width()
                val drawableHeight = clearDrawable.bounds.height()
                val drawablePadding = editText.compoundDrawablePadding

                // Right edge: width - paddingEnd
                // Left edge: right - drawableWidth - drawablePadding
                val right = editText.width - editText.paddingEnd
                val left = right - drawableWidth - drawablePadding

                // Vertical center
                val top = (editText.height - drawableHeight) / 2
                val bottom = top + drawableHeight

                // Check if touch is within the drawable bounds
                if (x >= left && x <= right && y >= top && y <= bottom) {
                    editText.setText("")
                    editText.requestFocus()
                    return@setOnTouchListener true
                }
            }
            false
        }

        // Initially hide clear button
        updateClearButton(editText, false)

        // Apply initial colors
        updateSearchButtonColors()

        // Apply search bar background color for dark mode
        updateSearchBarBackground()
    }

    private fun updateSearchBarBackground() {
        val search = searchBinding ?: return
        val editText = search.searchEditText

        // Update search bar background color for dark mode
        val searchBarBackgroundColor = if (context.isSystemInDarkMode()) {
            context.getColoredMaterialSearchBarColor()
        } else {
            // Use the original light color for light mode
            ContextCompat.getColor(context, R.color.md_grey_100)
        }

        // Create a rounded rectangle drawable for the search bar background
        // Use 24dp corner radius (same as search_bg.xml)
        val radiusValue = (24 * resources.displayMetrics.density + 0.5f)
        val searchBarDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusValue
            setColor(searchBarBackgroundColor)
        }
        editText.background = searchBarDrawable
    }

    // Cached color getters
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
            cachedCursorColor = context.getProperTextCursorColor()
        }
        return cachedCursorColor!!
    }

    private fun getIconSize(): Int {
        if (cachedIconSize == null) {
            cachedIconSize = (18 * resources.displayMetrics.density + 0.5f).toInt()
        }
        return cachedIconSize!!
    }

    private fun getMediumIconSize(): Int {
        if (cachedMediumIconSize == null) {
            cachedMediumIconSize = resources.getDimensionPixelSize(R.dimen.medium_icon_size)
        }
        return cachedMediumIconSize!!
    }

    private fun getSmallerMargin(): Int {
        if (cachedSmallerMargin == null) {
            cachedSmallerMargin = resources.getDimensionPixelSize(R.dimen.smaller_margin)
        }
        return cachedSmallerMargin!!
    }

    private fun getIconPadding(): Int {
        if (cachedIconPadding == null) {
            cachedIconPadding = resources.getDimensionPixelSize(R.dimen.icon_padding)
        }
        return cachedIconPadding!!
    }

    private fun getNormalMargin(): Int {
        if (cachedNormalMargin == null) {
            cachedNormalMargin = resources.getDimensionPixelSize(R.dimen.normal_margin)
        }
        return cachedNormalMargin!!
    }

    private fun getBackgroundDrawable(): Drawable? {
        if (cachedBackgroundDrawable == null) {
            val typedValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)
            cachedBackgroundDrawable = ContextCompat.getDrawable(context, typedValue.resourceId)
        }
        return cachedBackgroundDrawable
    }

    /**
     * Get a fresh instance of the background drawable for action buttons.
     * Each button needs its own instance for ripple effects to work properly.
     */
    private fun getActionButtonBackgroundDrawable(): Drawable? {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)
        return ContextCompat.getDrawable(context, typedValue.resourceId)
    }

    private fun updateSearchIconColors(editText: MyEditText) {
        val textColor = getTextColor()
        val iconColor = textColor.adjustAlpha(0.4f)
        val iconSize = getIconSize()

        val currentDrawables = editText.compoundDrawables
        val searchIcon = currentDrawables[0] ?: ContextCompat.getDrawable(context, com.android.common.R.drawable.ic_cmn_search)
        val clearIcon = currentDrawables[2] // Preserve clear icon if it exists

        searchIcon?.let {
            val drawable = it.mutate()
            drawable.setBounds(0, 0, iconSize, iconSize)
            drawable.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
            // Preserve clear icon if it exists
            editText.setCompoundDrawables(drawable, null, null, clearIcon)
        }
    }

    private fun updateClearButton(editText: MyEditText, show: Boolean) {
        val drawables = editText.compoundDrawables
        val searchIcon = drawables[0] // drawableStart

        if (show) {
            // Show clear icon (drawableEnd)
            val iconSize = getIconSize()
            val clearIcon = ContextCompat.getDrawable(context, com.android.common.R.drawable.ic_cmn_search_close)

            clearIcon?.let {
                val drawable = it.mutate()
                // Set bounds for the drawable - this determines its size
                drawable.setBounds(0, 0, iconSize, iconSize)
                val textColor = getTextColor()
                val iconColor = textColor.adjustAlpha(0.4f)
                drawable.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
                editText.setCompoundDrawables(searchIcon, null, drawable, null)
            }
        } else {
            // Hide clear icon
            editText.setCompoundDrawables(searchIcon, null, null, null)
        }
    }

    private fun updateSearchButtonColors() {
        val search = searchBinding ?: return
        val textColor = getTextColor()

        // Update back button
        val backIcon = ContextCompat.getDrawable(context, R.drawable.ic_chevron_left_vector)
        backIcon?.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        search.searchBackButton.setImageDrawable(backIcon)

        // Clear button is now drawableEnd, handled in updateClearButton
    }

    fun setOnSearchTextChangedListener(listener: ((String) -> Unit)?) {
        onSearchTextChangedListener = listener
        if (searchBinding == null) {
            setupSearchContainer()
        }
    }

    fun setOnSearchBackClickListener(listener: OnClickListener?) {
        onSearchBackClickListener = listener
    }

    fun setOnSearchExpandListener(listener: OnClickListener?) {
        onSearchExpandListener = listener
    }

    fun expandSearch() {
        if (isSearchExpanded) return

        setupSearchContainer()
        val binding = this.binding ?: return
        val search = searchBinding ?: return

        isSearchExpanded = true

        // Notify listener that search was expanded
        onSearchExpandListener?.onClick(this)

        // Hide title and action buttons (keep menu button visible)
        binding.titleTextView.visibility = View.GONE
        val actionButtonsContainer = binding.root.findViewById<LinearLayout>(R.id.actionButtonsContainer)
        actionButtonsContainer?.visibility = View.GONE

        // Show search container and back button
        search.root.visibility = View.VISIBLE
        search.searchBackButton.visibility = View.VISIBLE

        // Ensure EditText is visible
        search.searchEditText.visibility = View.VISIBLE

        // Animate search field expansion
        post {
            val search = searchBinding ?: return@post
            val binding = this@CustomToolbar.binding ?: return@post

            // Measure the actual available space by getting positions
            val actionButtonsContainer = binding.root.findViewById<LinearLayout>(R.id.actionButtonsContainer)
            val menuButton = binding.menuButton
            val searchContainer = search.root

            // Calculate the rightmost element (action buttons or menu button)
            // Only consider visible elements
            val rightmostElement = when {
                actionButtonsContainer != null && actionButtonsContainer.visibility == View.VISIBLE -> actionButtonsContainer
                menuButton.visibility == View.VISIBLE -> menuButton
                else -> null // Both are GONE
            }

            // Update search container layout params if menu button is GONE
            if (menuButton.visibility == View.GONE && actionButtonsContainer?.visibility != View.VISIBLE) {
                val searchContainerParams = searchContainer.layoutParams as? RelativeLayout.LayoutParams
                searchContainerParams?.let {
                    // Align search container to parent end when menu button is GONE
                    // This will override the original toStartOf constraint
                    it.addRule(RelativeLayout.ALIGN_PARENT_END)
                    it.marginEnd = getNormalMargin()
                    searchContainer.layoutParams = it
                }
            }

            val targetWidth = if (rightmostElement != null && rightmostElement.visibility == View.VISIBLE && searchContainer != null) {
                // Get the location of rightmost element and search container
                val rightmostLocation = IntArray(2)
                rightmostElement.getLocationOnScreen(rightmostLocation)

                val searchContainerLocation = IntArray(2)
                searchContainer.getLocationOnScreen(searchContainerLocation)

                // Calculate available width: distance from search container start to rightmost element start
                // Account for the back button and its margin
                val backButtonWidth = search.searchBackButton.width.takeIf { it > 0 } ?: getMediumIconSize()
                val backButtonMargin = getSmallerMargin() * 2
                val editTextMargin = getSmallerMargin() * 2

                // Available width = distance to rightmost element - back button - margins
                val distanceToRightmost = rightmostLocation[0] - searchContainerLocation[0]
                (distanceToRightmost - backButtonWidth - backButtonMargin - editTextMargin).coerceAtLeast(100)
            } else {
                // Fallback calculation if measurement fails or both menu button and action buttons are GONE
                val toolbarWidth = width
                val actionButtonsWidth = if (actionButtonsContainer != null && actionButtonsContainer.visibility == View.VISIBLE) {
                    actionButtonsContainer.width
                } else {
                    0
                }
                // Only include menu button width if it's visible
                val menuButtonWidth = if (menuButton.visibility == View.VISIBLE) {
                    menuButton.width.takeIf { it > 0 } ?: getMediumIconSize()
                } else {
                    0
                }
                val backButtonWidth = search.searchBackButton.width.takeIf { it > 0 } ?: getMediumIconSize()
                val toolbarPadding = paddingStart + paddingEnd
                val margins = getNormalMargin() + getSmallerMargin() * 2

                (toolbarWidth - toolbarPadding - backButtonWidth - actionButtonsWidth - menuButtonWidth - margins).coerceAtLeast(100)
            }

            // Animate the EditText width directly
            val searchEditText = search.searchEditText
            val layoutParams = searchEditText.layoutParams ?: return@post

            // Set initial width to 0 for smooth expansion
            layoutParams.width = 0
            searchEditText.layoutParams = layoutParams
            searchEditText.requestLayout()

            // Animate the EditText width
            ValueAnimator.ofInt(0, targetWidth).apply {
                duration = 300
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { animator ->
                    val params = searchEditText.layoutParams
                    if (params != null) {
                        params.width = animator.animatedValue as Int
                        searchEditText.layoutParams = params
                    }
                    search.root.requestLayout()
                }
                start()
            }

            // Show keyboard after delay
            postDelayed({
                search.searchEditText.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(search.searchEditText, InputMethodManager.SHOW_IMPLICIT)
            }, 150)
        }
    }

    fun collapseSearch() {
        if (!isSearchExpanded) return

        isSearchExpanded = false
        val search = searchBinding ?: return
        val binding = this.binding ?: return

        // Animate search field collapse
        val searchEditText = search.searchEditText
        val currentWidth = searchEditText.width.takeIf { it > 0 } ?: 0
        val layoutParams = searchEditText.layoutParams ?: return

        ValueAnimator.ofInt(currentWidth, 0).apply {
            duration = 100
            interpolator = AccelerateInterpolator(1.2f)
            addUpdateListener { animator ->
                val params = searchEditText.layoutParams
                if (params != null) {
                    params.width = animator.animatedValue as Int
                    searchEditText.layoutParams = params
                }
                search.root.requestLayout()
            }
            doOnEnd {
                // Hide search container and back button
                search.root.visibility = View.GONE
                search.searchBackButton.visibility = View.GONE

                // Restore search container layout params to original constraints
                val searchContainerParams = search.root.layoutParams as? RelativeLayout.LayoutParams
                searchContainerParams?.let {
                    // Remove ALIGN_PARENT_END rule (set to 0 to remove)
                    // This will restore the original XML constraint (toStartOf actionButtonsContainer)
                    it.addRule(RelativeLayout.ALIGN_PARENT_END, 0)
                    it.marginEnd = getNormalMargin()
                    search.root.layoutParams = it
                }

                // Show title and action buttons (menu button was already visible)
                binding.titleTextView.visibility =
                    if (binding.titleTextView.text?.isNotEmpty() == true) View.VISIBLE else View.GONE
                // Restore action buttons visibility based on menu state
                updateMenuDisplay()

                // Clear search text
                search.searchEditText.setText("")

                // Hide keyboard
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(search.searchEditText.windowToken, 0)
            }
            start()
        }
    }

    fun getSearchText(): String {
        return searchBinding?.searchEditText?.text?.toString() ?: ""
    }

    fun setSearchText(text: String) {
        if (searchBinding == null) {
            setupSearchContainer()
        }
        searchBinding?.searchEditText?.setText(text)
    }

    fun updateSearchColors() {
        // Invalidate cached colors to force recalculation
        cachedTextColor = null
        cachedPrimaryColor = null
        cachedCursorColor = null

        val search = searchBinding ?: return
        val textColor = getTextColor()
        val primaryColor = getPrimaryColor()
        val cursorColor = getCursorColor()

        search.searchEditText.setColors(textColor, primaryColor, cursorColor)

        // Update search bar background color for dark mode
        updateSearchBarBackground()

        val editText = search.searchEditText
        updateSearchIconColors(editText)
        val hasText = editText.text?.isNotEmpty() == true
        updateClearButton(editText, hasText)

        // Update button colors
        updateSearchButtonColors()
    }

    fun setNavigationContentDescription(resId: Int) {
        binding?.navigationIconView?.contentDescription = context.getString(resId)
    }

    fun setNavigationContentDescription(description: CharSequence?) {
        binding?.navigationIconView?.contentDescription = description
    }

    fun setNavigationOnClickListener(listener: OnClickListener?) {
        onNavigationClickListener = listener
        binding?.navigationIconView?.setOnClickListener(listener)
    }

    fun setTitleTextColor(color: Int) {
        binding?.titleTextView?.setTextColor(color)
    }

    fun setTitleTextColor(colors: ColorStateList?) {
        binding?.titleTextView?.setTextColor(colors)
    }

    fun inflateMenu(resId: Int) {
        if (menuInflater == null) {
            menuInflater = MenuInflater(context)
        }
        menuInflater?.inflate(resId, menu)
        updateMenuDisplay()
    }

    fun setOnMenuItemClickListener(listener: MenuItem.OnMenuItemClickListener?) {
        onMenuItemClickListener = listener
        // Update menu display to ensure action buttons have the correct listener
        updateMenuDisplay()
    }

    fun invalidateMenu() {
        updateMenuDisplay()
    }

    private fun updateMenuDisplay() {
        // Prevent recursive calls
        if (isUpdatingMenu) return
        isUpdatingMenu = true

        try {
            val menu = _menu ?: return
            val binding = this.binding ?: return
            val actionButtonsContainer = binding.root.findViewById<LinearLayout>(R.id.actionButtonsContainer)

            // Clear existing action buttons if container exists
            actionButtonsContainer?.removeAllViews()

            // Separate menu items into action buttons and overflow items
            val actionItems = mutableListOf<MenuItem>()
            val overflowItems = mutableListOf<MenuItem>()
            val menuSize = menu.size()

            for (i in 0 until menuSize) {
                val item = menu.getItem(i) ?: continue
                if (!item.isVisible) continue

                // Show as action button if requiresActionButton returns true and item has an icon
                if (requiresActionButton(item) && item.icon != null) {
                    actionItems.add(item)
                } else {
                    overflowItems.add(item)
                }
            }

            // Show overflow menu button if there are overflow items
            val hasOverflowItems = overflowItems.isNotEmpty()
            val menuButtonVisible = hasOverflowItems

            // Create action buttons for items that should be shown as actions
            if (actionButtonsContainer != null) {
                // Ensure container doesn't intercept touch events
                actionButtonsContainer.isClickable = false
                actionButtonsContainer.isFocusable = false
                actionButtonsContainer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

                actionItems.forEach { item ->
                    val actionButton = createActionButton(item, menuButtonVisible)
                    actionButtonsContainer.addView(actionButton)
                }

                // Show/hide action buttons container
                actionButtonsContainer.visibility = if (actionItems.isNotEmpty()) View.VISIBLE else View.GONE
            }

            // Update menu button visibility
            binding.menuButton.visibility = if (menuButtonVisible) View.VISIBLE else View.GONE

            // Update action buttons container margin based on menu button visibility
            updateActionButtonsContainerMargin()

            // Store overflow items for popup menu
            this.overflowItems = overflowItems
        } finally {
            isUpdatingMenu = false
        }
    }

    /**
     * Update the end margin of action buttons container based on menu button visibility.
     * When menu button is hidden, add margin to prevent action buttons from overlapping the title.
     */
    private fun updateActionButtonsContainerMargin() {
        val binding = this.binding ?: return
        val actionButtonsContainer = binding.root.findViewById<LinearLayout>(R.id.actionButtonsContainer) ?: return
        val menuButton = binding.menuButton
        
        val params = actionButtonsContainer.layoutParams
        if (params is RelativeLayout.LayoutParams) {
            // If menu button is hidden and action buttons are visible, align to parent end
            if (menuButton.visibility == View.GONE && actionButtonsContainer.visibility == View.VISIBLE) {
                params.addRule(RelativeLayout.ALIGN_PARENT_END)
                params.marginEnd = resources.getDimensionPixelSize(R.dimen.medium_margin)
            } else {
                // When menu button is visible, use toStartOf constraint (remove alignParentEnd)
                params.addRule(RelativeLayout.ALIGN_PARENT_END, 0)
                params.marginEnd = 0
            }
            actionButtonsContainer.layoutParams = params
        }
    }

    /**
     * Create an action button for a menu item with proper click handling
     */
    private fun createActionButton(item: MenuItem, menuButtonVisible: Boolean): MImageButton {
        val iconSize = getMediumIconSize()
        val margin = getNormalMargin()
        val textColor = getTextColor()
        val padding = getIconPadding()

        return MImageButton(context).apply {
            // Set layout params (same size as menu button)
            // Adjust margin based on menu button visibility
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = if (menuButtonVisible) {
                    // Normal margin when menu button is visible
                    margin
                } else {
                    // Use medium margin (same as menu button margin) when menu button is hidden
                    // This ensures proper spacing from the edge
                    0
                }
                marginStart = if(menuButtonVisible) {
                    0
                } else {
                    margin
                }
            }

            // Set padding for the action button
            setPadding(padding, padding, padding, padding)

            // Set clickable, focusable, and enabled properties FIRST
            isClickable = true
            isFocusable = true
            isEnabled = true

            // Get a fresh instance of the background drawable for ripple effect
            // Each view needs its own instance for ripple to work properly
            background = getActionButtonBackgroundDrawable()

            // Set icon with proper color
            item.icon?.let { icon ->
                val iconDrawable = icon.mutate()
                iconDrawable.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
                setImageDrawable(iconDrawable)
            }

            contentDescription = item.title
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            adjustViewBounds = true

            // Store the menu item ID for click handling
            tag = item.itemId

            // Set click listener directly using a lambda that captures itemId
            // Always use the current listener from the toolbar instance
            val capturedItemId = item.itemId
            val capturedItem = item // Also capture the item itself as a fallback

            setOnClickListener { view ->
                // Always get the current listener and menu from the toolbar instance
                val currentListener = this@CustomToolbar.onMenuItemClickListener
                val currentMenu = this@CustomToolbar._menu

                // Try to find the menu item from current menu first
                val menuItem = currentMenu?.findItem(capturedItemId) ?: capturedItem

                // Ensure the listener exists and the item is visible
                if (currentListener != null && menuItem != null && menuItem.isVisible) {
                    // Call the menu item click listener
                    currentListener.onMenuItemClick(menuItem)
                } else {
                    false
                }
            }
        }
    }


    private var overflowItems: List<MenuItem> = emptyList()
    private var isUpdatingMenu = false

    /**
     * Check if a MenuItem requires an action button.
     * Uses MenuItemImpl.requiresActionButton() method via reflection.
     */
    private fun requiresActionButton(item: MenuItem): Boolean {
        if (item !is MenuItemImpl) return false

        try {
            if (requiresActionButtonMethod == null) {
                requiresActionButtonMethod = MenuItemImpl::class.java.getMethod("requiresActionButton")
            }
            return requiresActionButtonMethod?.invoke(item) as? Boolean ?: false
        } catch (e: Exception) {
            return false
        }
    }

    private fun showMenuPopup() {
        val menu = _menu ?: return
        val binding = this.binding ?: return
        val anchor = binding.menuButton

        // Get overflow items to show
        val visibleItems = if (overflowItems.isNotEmpty()) {
            overflowItems.filter { it.isVisible }
        } else {
            // Fallback: show all visible items if overflowItems is empty
            val menuSize = menu.size()
            buildList {
                for (i in 0 until menuSize) {
                    val item = menu.getItem(i) ?: continue
                    if (item.isVisible) add(item)
                }
            }
        }

        // Don't show if no visible items
        if (visibleItems.isEmpty()) {
            return
        }

        // Create BlurPopupMenu
        val blurPopupMenu = BlurPopupMenu(context, anchor, Gravity.END)

        // Copy overflow items to BlurPopupMenu's menu
        // Create a map to track original items by their IDs
        val itemIdMap = mutableMapOf<Int, MenuItem>()
        visibleItems.forEach { originalItem ->
            val newItem = blurPopupMenu.menu.add(
                originalItem.groupId,
                originalItem.itemId,
                originalItem.order,
                originalItem.title
            )
            // Copy properties from original item
            newItem.icon = originalItem.icon
            newItem.isCheckable = originalItem.isCheckable
            newItem.isChecked = originalItem.isChecked
            newItem.isEnabled = originalItem.isEnabled
            newItem.isVisible = originalItem.isVisible
            
            // Store mapping for click handling
            itemIdMap[originalItem.itemId] = originalItem
        }

        // Set click listener that maps back to original menu items
        blurPopupMenu.setOnMenuItemClickListener { clickedItem ->
            val originalItem = itemIdMap[clickedItem.itemId] ?: clickedItem
            onMenuItemClickListener?.onMenuItemClick(originalItem) ?: false
        }

        // Show the popup menu
        blurPopupMenu.show()
        
        // Store reference for cleanup
        popupWindow = blurPopupMenu
    }

    private var popupWindow: BlurPopupMenu? = null

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Don't update menu display on every layout change to prevent infinite loops
        // Menu display is updated when menu is inflated or invalidated explicitly
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        popupWindow?.dismiss()
        popupWindow = null
        // Clear cached values to prevent memory leaks
        cachedTextColor = null
        cachedPrimaryColor = null
        cachedCursorColor = null
        cachedIconSize = null
        cachedMediumIconSize = null
        cachedSmallerMargin = null
        cachedNormalMargin = null
        cachedBackgroundDrawable = null
        requiresActionButtonMethod = null
    }
}
