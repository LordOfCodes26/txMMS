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
import android.graphics.PorterDuff
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import com.goodwy.commons.views.MyEditText
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.view.MenuCompat
import com.goodwy.commons.R
import com.goodwy.commons.databinding.CustomToolbarBinding
import com.goodwy.commons.databinding.CustomToolbarSearchContainerBinding
import com.goodwy.commons.databinding.PopupMenuBlurBinding
import com.goodwy.commons.extensions.adjustAlpha
import com.goodwy.commons.extensions.applyColorFilter
import com.goodwy.commons.extensions.getProperBlurOverlayColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getProperTextCursorColor
import com.goodwy.commons.extensions.onTextChangeListener
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

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
        binding?.menuButton?.setOnClickListener { showMenuPopup() }
        binding?.searchIconButton?.setOnClickListener { expandSearch() }
        
        // Apply initial colors
        updateSearchIconButtonColor()
        
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
    
    private fun setupSearchContainer() {
        if (searchBinding != null) return
        
        // Find the included search container view by ID
        val searchContainerView = binding?.root?.findViewById<ViewGroup>(R.id.searchContainer)
        
        if (searchContainerView != null) {
            // Create binding for search container using bind()
            searchBinding = CustomToolbarSearchContainerBinding.bind(searchContainerView)
            
            // Setup click listeners
            searchBinding?.searchBackButton?.setOnClickListener {
                collapseSearch()
                onSearchBackClickListener?.onClick(it)
            }
            
            // Setup search EditText
            searchBinding?.searchEditText?.let { editText ->
                val textColor = context.getProperTextColor()
                val primaryColor = context.getProperPrimaryColor()
                val cursorColor = context.getProperTextCursorColor()
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
                    
                    if (clearDrawable != null) {
                        val x = event.x
                        val y = event.y
                        
                        // Calculate the position of drawableEnd
                        // drawableEnd is positioned at the end: width - paddingEnd - drawableWidth - drawablePadding
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
                        if (event.action == android.view.MotionEvent.ACTION_UP && 
                            x >= left && x <= right && y >= top && y <= bottom) {
                            editText.setText("")
                            editText.requestFocus()
                            return@setOnTouchListener true
                        }
                    }
                    false
                }
                
                // Initially hide clear button
                updateClearButton(editText, false)
            }
            
            // Apply initial colors
            updateSearchButtonColors()
        }
    }
    
    private fun updateSearchIconColors(editText: MyEditText) {
        val textColor = context.getProperTextColor()
        val iconColor = textColor.adjustAlpha(0.4f)
        val iconSize = (18 * resources.displayMetrics.density + 0.5f).toInt()
        
        val currentDrawables = editText.compoundDrawables
        val searchIcon = currentDrawables[0] ?: ContextCompat.getDrawable(context, R.drawable.ic_search_vector)
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
            val iconSize = (18 * resources.displayMetrics.density + 0.5f).toInt()
            val clearIcon = ContextCompat.getDrawable(context, R.drawable.ic_clear_round)
            
            clearIcon?.let {
                val drawable = it.mutate()
                // Set bounds for the drawable - this determines its size
                drawable.setBounds(0, 0, iconSize, iconSize)
                val textColor = context.getProperTextColor()
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
        searchBinding?.let { search ->
            val textColor = context.getProperTextColor()
            
            // Update back button
            search.searchBackButton.let { button ->
                val backIcon = ContextCompat.getDrawable(context, R.drawable.ic_chevron_left_vector)
                backIcon?.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
                button.setImageDrawable(backIcon)
            }
            
            // Clear button is now drawableEnd, handled in updateClearButton
        }
    }
    
    private fun updateSearchIconButtonColor() {
        binding?.searchIconButton?.let { button ->
            val textColor = context.getProperTextColor()
            val searchIcon = ContextCompat.getDrawable(context, R.drawable.ic_search_vector)
            searchIcon?.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
            button.setImageDrawable(searchIcon)
        }
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
    
    fun expandSearch() {
        if (isSearchExpanded) return
        
        setupSearchContainer()
        isSearchExpanded = true
        
        // Hide title and search icon button (keep menu button visible)
        binding?.titleTextView?.visibility = View.GONE
        binding?.searchIconButton?.visibility = View.GONE
        
        // Show search container and back button
        searchBinding?.root?.visibility = View.VISIBLE
        searchBinding?.searchBackButton?.visibility = View.VISIBLE
        
        // Ensure EditText is visible
        searchBinding?.searchEditText?.visibility = View.VISIBLE
        
        // Animate search field expansion
        post {
            // Measure the actual available space by getting positions
            val menuButton = binding?.menuButton
            val searchContainer = searchBinding?.root
            
            if (menuButton != null && searchContainer != null) {
                // Get the location of menu button and search container
                val menuButtonLocation = IntArray(2)
                menuButton.getLocationOnScreen(menuButtonLocation)
                
                val searchContainerLocation = IntArray(2)
                searchContainer.getLocationOnScreen(searchContainerLocation)
                
                // Calculate available width: distance from search container start to menu button start
                // Account for the back button and its margin
                val backButtonWidth = searchBinding?.searchBackButton?.width 
                    ?: resources.getDimensionPixelSize(R.dimen.medium_icon_size)
                val backButtonMargin = resources.getDimensionPixelSize(R.dimen.smaller_margin) * 2
                val editTextMargin = resources.getDimensionPixelSize(R.dimen.smaller_margin) * 2
                
                // Available width = distance to menu button - back button - margins
                val distanceToMenuButton = menuButtonLocation[0] - searchContainerLocation[0]
                val availableWidth = distanceToMenuButton - backButtonWidth - backButtonMargin - editTextMargin
                
                val targetWidth = availableWidth.coerceAtLeast(100) // Minimum 100dp width
                
                // Animate the EditText width directly (no RelativeLayout wrapper anymore)
                val searchEditText = searchBinding?.searchEditText
                
                searchEditText?.apply {
                    val layoutParams = this.layoutParams
                    if (layoutParams != null) {
                        // Set initial width to 0 for smooth expansion
                        layoutParams.width = 0
                        this.layoutParams = layoutParams
                        requestLayout()
                        
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
                                searchBinding?.root?.requestLayout()
                            }
                            start()
                        }
                    }
                }
            } else {
                // Fallback calculation if measurement fails
                val toolbarWidth = width
                val menuButtonWidth = binding?.menuButton?.width 
                    ?: resources.getDimensionPixelSize(R.dimen.medium_icon_size)
                val backButtonWidth = searchBinding?.searchBackButton?.width 
                    ?: resources.getDimensionPixelSize(R.dimen.medium_icon_size)
                val toolbarPadding = paddingStart + paddingEnd
                val margins = resources.getDimensionPixelSize(R.dimen.normal_margin) + 
                             resources.getDimensionPixelSize(R.dimen.smaller_margin) * 2
                
                val availableWidth = toolbarWidth - toolbarPadding - backButtonWidth - menuButtonWidth - margins
                val targetWidth = availableWidth.coerceAtLeast(100)
                
                val searchEditText = searchBinding?.searchEditText
                
                searchEditText?.apply {
                    val layoutParams = this.layoutParams
                    if (layoutParams != null) {
                        layoutParams.width = 0
                        this.layoutParams = layoutParams
                        requestLayout()
                        
                        ValueAnimator.ofInt(0, targetWidth).apply {
                            duration = 300
                            interpolator = DecelerateInterpolator(1.5f)
                            addUpdateListener { animator ->
                                val params = searchEditText.layoutParams
                                if (params != null) {
                                    params.width = animator.animatedValue as Int
                                    searchEditText.layoutParams = params
                                }
                                searchBinding?.root?.requestLayout()
                            }
                            start()
                        }
                    }
                }
            }
            
            // Show keyboard after delay
            postDelayed({
                searchBinding?.searchEditText?.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(searchBinding?.searchEditText, InputMethodManager.SHOW_IMPLICIT)
            }, 150)
        }
    }
    
    fun collapseSearch() {
        if (!isSearchExpanded) return
        
        isSearchExpanded = false
        
        // Animate search field collapse
        // Since we removed the RelativeLayout wrapper, animate the EditText directly
        val searchEditText = searchBinding?.searchEditText
        val currentWidth = searchEditText?.width ?: 0
        
        searchEditText?.apply {
            val layoutParams = this.layoutParams
            if (layoutParams != null) {
                ValueAnimator.ofInt(currentWidth, 0).apply {
                    duration = 100
                    interpolator = AccelerateInterpolator(1.2f)
                    addUpdateListener { animator ->
                        val params = searchEditText.layoutParams
                        if (params != null) {
                            params.width = animator.animatedValue as Int
                            searchEditText.layoutParams = params
                        }
                        searchBinding?.root?.requestLayout()
                    }
                    doOnEnd {
                        // Hide search container and back button
                        searchBinding?.root?.visibility = View.GONE
                        searchBinding?.searchBackButton?.visibility = View.GONE
                        
                        // Show title and search icon button (menu button was already visible)
                        binding?.titleTextView?.visibility = 
                            if (binding?.titleTextView?.text?.isNotEmpty() == true) View.VISIBLE else View.GONE
                        binding?.searchIconButton?.visibility = View.VISIBLE
                        
                        // Clear search text
                        searchBinding?.searchEditText?.setText("")
                        
                        // Hide keyboard
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(searchBinding?.searchEditText?.windowToken, 0)
                    }
                    start()
                }
            }
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
        searchBinding?.let { search ->
            val textColor = context.getProperTextColor()
            val primaryColor = context.getProperPrimaryColor()
            val cursorColor = context.getProperTextCursorColor()
            
            search.searchEditText?.setColors(textColor, primaryColor, cursorColor)
            
            search.searchEditText?.let { editText ->
                updateSearchIconColors(editText)
                val hasText = editText.text?.isNotEmpty() == true
                updateClearButton(editText, hasText)
            }
            
            // Update button colors
            updateSearchButtonColors()
        }
        
        // Update search icon button color
        updateSearchIconButtonColor()
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
    
    fun setSearchIconVisible(visible: Boolean) {
        binding?.searchIconButton?.visibility = if (visible) View.VISIBLE else View.GONE
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
    }
    
    fun invalidateMenu() {
        updateMenuDisplay()
    }
    
    private fun updateMenuDisplay() {
        val menu = _menu ?: return
        
        // Don't show menu items as icons - show all items in overflow menu only
        val hasVisibleItems = (0 until menu.size())
            .mapNotNull { menu.getItem(it) }
            .any { it.isVisible }
        
        binding?.menuButton?.visibility = if (hasVisibleItems) View.VISIBLE else View.GONE
        
        // Keep menu button visible even when search is expanded
    }
    
    private fun showMenuPopup() {
        val menu = _menu ?: return
        val activity = context as? Activity ?: return
        val blurTarget = activity.findViewById<BlurTarget>(R.id.mainBlurTarget) ?: return
        
        // Create custom popup window with blur
        val inflater = LayoutInflater.from(context)
        val popupBinding = PopupMenuBlurBinding.inflate(inflater, null, false)
        
        // Setup rounded corners
        popupBinding.root.clipToOutline = true
        
        // Setup BlurView
        val blurView = popupBinding.blurView
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        blurView.setOverlayColor(context.getProperBlurOverlayColor())
        blurView.setupWith(blurTarget)
            .setFrameClearDrawable(windowBackground)
            .setBlurRadius(8f)
            .setBlurAutoUpdate(true)
        
        // Create menu items
        val menuContainer = popupBinding.menuContainer
        val visibleItems = (0 until menu.size())
            .mapNotNull { menu.getItem(it) }
            .filter { it.isVisible }
        
        visibleItems.forEach { item ->
            val menuItemView = inflater.inflate(R.layout.item_popup_menu, menuContainer, false)
            val titleView = menuItemView.findViewById<TextView>(R.id.menu_item_title)
            
            titleView.text = item.title
            
            menuItemView.setOnClickListener {
                onMenuItemClickListener?.onMenuItemClick(item) ?: false
                popupWindow?.dismiss()
            }
            
            menuContainer.addView(menuItemView)
        }
        
        // Measure the content
        popupBinding.root.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        
        // Create and show popup window
        popupWindow = PopupWindow(
            popupBinding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 8f
            setBackgroundDrawable(null)
            isOutsideTouchable = true
            isFocusable = true
            
            // Calculate position
            val anchor = binding?.menuButton ?: this@CustomToolbar
            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            val rootLocation = IntArray(2)
            (context as? Activity)?.window?.decorView?.rootView?.getLocationOnScreen(rootLocation)
            
            // Add right margin (16dp converted to pixels)
            val rightMargin = (16 * resources.displayMetrics.density + 0.5f).toInt()
            val screenWidth = resources.displayMetrics.widthPixels
            val popupWidth = popupBinding.root.measuredWidth
            
            // Calculate x position with right margin from screen edge
            val x = screenWidth - popupWidth - rightMargin
            val y = location[1] + anchor.height
            
            showAtLocation(
                (context as? Activity)?.window?.decorView?.rootView,
                Gravity.NO_GRAVITY,
                x - rootLocation[0],
                y - rootLocation[1]
            )
        }
    }
    
    private var popupWindow: PopupWindow? = null
    
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Update menu display when layout changes
        if (_menu != null && _menu!!.size() > 0) {
            post { updateMenuDisplay() }
        }
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        popupWindow?.dismiss()
        popupWindow = null
    }
}
