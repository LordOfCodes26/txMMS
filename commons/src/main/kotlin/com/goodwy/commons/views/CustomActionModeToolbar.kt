package com.goodwy.commons.views

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
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
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.graphics.PorterDuff
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.core.content.ContextCompat
import java.lang.reflect.Method
import com.goodwy.commons.R
import com.goodwy.commons.databinding.CustomActionModeToolbarBinding
import com.goodwy.commons.databinding.PopupMenuBlurBinding
import com.goodwy.commons.extensions.applyColorFilter
import com.goodwy.commons.extensions.getContrastColor
import com.goodwy.commons.extensions.getProperBlurOverlayColor
import com.goodwy.commons.extensions.getProperTextColor
import eightbitlab.com.blurview.BlurTarget

/**
 * Custom action mode toolbar implementation similar to CustomToolbar
 * that provides customizable title and navigation icon for ActionMode.
 * 
 * This toolbar is designed to be used as a custom view for ActionMode,
 * allowing full control over the title appearance and behavior.
 */
class CustomActionModeToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var binding: CustomActionModeToolbarBinding? = null
    private var onTitleClickListener: OnClickListener? = null
    private var onNavigationClickListener: OnClickListener? = null
    
    private var _menu: Menu? = null
    private var menuInflater: MenuInflater? = null
    private var onMenuItemClickListener: MenuItem.OnMenuItemClickListener? = null
    private var popupWindow: PopupWindow? = null
    private var overflowItems: List<MenuItem> = emptyList()
    private var isUpdatingMenu = false
    private var requiresActionButtonMethod: Method? = null
    
    // Cached views to avoid repeated findViewById calls
    private var cachedActionButtonsContainer: LinearLayout? = null
    
    // Cached dimension values
    private var cachedIconSize: Int = -1
    private var cachedMargin: Int = -1
    private var cachedPadding: Int = -1
    private var cachedTextColor: Int = -1
    
    // Cached activity and blur target for popup menu
    private var cachedActivity: Activity? = null
    private var cachedBlurTarget: BlurTarget? = null
    
    // Menu update optimization flags
    private var menuNeedsUpdate = false
    private var pendingMenuUpdate: Runnable? = null
    
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
    
    init {
        // Inflate toolbar layout using ViewBinding
        val toolbarBinding = CustomActionModeToolbarBinding.inflate(LayoutInflater.from(context), this, false)
        binding = toolbarBinding
        
        // Copy layout parameters and properties from inflated layout
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        
        // Add the root view to this CustomActionModeToolbar
        addView(toolbarBinding.root)
        
        // Setup click listeners
        binding?.titleTextView?.setOnClickListener { view ->
            onTitleClickListener?.onClick(view)
        }
        binding?.menuButton?.setOnClickListener { showMenuPopup() }
        
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
        
        // Apply initial text color
        updateTextColor()
    }
    
    /**
     * Sets the click listener for the title TextView.
     * This is typically used to handle select all / deselect all functionality.
     */
    fun setOnTitleClickListener(listener: OnClickListener?) {
        onTitleClickListener = listener
    }
    
    /**
     * Sets the navigation content description from a resource ID.
     */
    fun setNavigationContentDescription(resId: Int) {
        binding?.navigationIconView?.contentDescription = context.getString(resId)
    }
    
    /**
     * Sets the navigation content description.
     */
    fun setNavigationContentDescription(description: CharSequence?) {
        binding?.navigationIconView?.contentDescription = description
    }
    
    /**
     * Sets the click listener for the navigation icon.
     */
    fun setNavigationOnClickListener(listener: OnClickListener?) {
        onNavigationClickListener = listener
        binding?.navigationIconView?.setOnClickListener(listener)
    }
    
    /**
     * Sets the text color of the title.
     */
    fun setTitleTextColor(color: Int) {
        binding?.titleTextView?.setTextColor(color)
    }
    
    /**
     * Sets the text color of the title using ColorStateList.
     */
    fun setTitleTextColor(colors: ColorStateList?) {
        binding?.titleTextView?.setTextColor(colors)
    }
    
    /**
     * Updates the text color based on the current theme.
     * This should be called when the theme changes.
     */
    fun updateTextColor() {
        // Cache text color for reuse
        cachedTextColor = context.getProperTextColor()
        binding?.titleTextView?.setTextColor(cachedTextColor)
    }
    
    /**
     * Updates the text color based on the background color for proper contrast.
     * This is typically used when the action mode toolbar has a custom background color.
     */
    fun updateTextColorForBackground(backgroundColor: Int) {
        val contrastColor = backgroundColor.getContrastColor()
        binding?.titleTextView?.setTextColor(contrastColor)
        // Also update navigation icon color if present
        binding?.navigationIconView?.drawable?.let { drawable ->
            val iconDrawable = drawable.mutate()
            iconDrawable.applyColorFilter(contrastColor)
            binding?.navigationIconView?.setImageDrawable(iconDrawable)
        }
    }
    
    /**
     * Updates all colors based on the background color for proper contrast.
     * This updates title, navigation icon, select all button, menu button, and action button colors.
     */
    fun updateColorsForBackground(backgroundColor: Int) {
        updateTextColorForBackground(backgroundColor)
        val contrastColor = backgroundColor.getContrastColor()
        
        // Update menu button icon color if present
        binding?.menuButton?.drawable?.let { drawable ->
            val iconDrawable = drawable.mutate()
            iconDrawable.applyColorFilter(contrastColor)
            binding?.menuButton?.setImageDrawable(iconDrawable)
        }
        
        // Update action button icon colors if present - use cached container
        val actionButtonsContainer = cachedActionButtonsContainer ?: 
            binding?.root?.findViewById<LinearLayout>(R.id.actionButtonsContainer)?.also { 
                cachedActionButtonsContainer = it 
            }
        actionButtonsContainer?.let { container ->
            val childCount = container.childCount
            for (i in 0 until childCount) {
                val child = container.getChildAt(i)
                if (child is ImageView) {
                    child.drawable?.let { drawable ->
                        val iconDrawable = drawable.mutate()
                        iconDrawable.setColorFilter(contrastColor, PorterDuff.Mode.SRC_IN)
                        child.setImageDrawable(iconDrawable)
                    }
                }
            }
        }
    }
    
    /**
     * Inflates a menu resource into the toolbar menu.
     */
    fun inflateMenu(resId: Int) {
        if (menuInflater == null) {
            menuInflater = MenuInflater(context)
        }
        menuInflater?.inflate(resId, menu)
        updateMenuDisplay()
    }
    
    /**
     * Sets the menu item click listener.
     */
    fun setOnMenuItemClickListener(listener: MenuItem.OnMenuItemClickListener?) {
        onMenuItemClickListener = listener
    }
    
    /**
     * Invalidates the menu, causing it to be re-displayed.
     */
    fun invalidateMenu() {
        updateMenuDisplay()
    }
    
    /**
     * Updates the select all button icon based on selection state.
     * If all items are selected, shows a checkmark icon; otherwise shows select all icon.
     * 
     * @param menuItemId The menu item ID for the select all button
     * @param allSelected True if all items are selected, false otherwise
     */
    fun updateSelectAllButtonIcon(menuItemId: Int, allSelected: Boolean) {
        val menu = _menu ?: return
        val menuItem = menu.findItem(menuItemId) ?: return
        
        // Get the appropriate icon based on selection state
        val iconRes = if (allSelected) {
            // When all are selected, use checkmark icon to indicate deselect action
            R.drawable.ic_check_vector
        } else {
            R.drawable.ic_select_all_vector
        }
        
        val icon = ContextCompat.getDrawable(context, iconRes)
        icon?.let {
            // Apply color filter
            if (cachedTextColor == -1) {
                cachedTextColor = context.getProperTextColor()
            }
            it.applyColorFilter(cachedTextColor)
            
            // Update the menu item icon
            menuItem.icon = it
            
            // Update the action button if it's already displayed
            val actionButtonsContainer = cachedActionButtonsContainer ?: 
                binding?.root?.findViewById<LinearLayout>(R.id.actionButtonsContainer)?.also { 
                    cachedActionButtonsContainer = it 
                }
            
            actionButtonsContainer?.let { container ->
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i)
                    if (child is ImageView && child.tag == menuItemId) {
                        // Update the icon on the action button
                        val iconDrawable = it.mutate()
                        iconDrawable.setColorFilter(cachedTextColor, PorterDuff.Mode.SRC_IN)
                        child.setImageDrawable(iconDrawable)
                        break
                    }
                }
            }
            
            // Force menu update to refresh the display
            invalidateMenu()
        }
    }
    
    /**
     * Updates the menu display, separating items into action buttons and overflow items.
     */
    private fun updateMenuDisplay() {
        // Prevent recursive calls
        if (isUpdatingMenu) {
            menuNeedsUpdate = true
            return
        }
        isUpdatingMenu = true
        menuNeedsUpdate = false
        
        try {
            val menu = _menu ?: return
            val binding = this.binding ?: return
            
            // Use cached container or find and cache it
            val actionButtonsContainer = cachedActionButtonsContainer ?: 
                binding.root.findViewById<LinearLayout>(R.id.actionButtonsContainer)?.also { 
                    cachedActionButtonsContainer = it 
                }
            
            // Clear existing action buttons if container exists
            actionButtonsContainer?.removeAllViews()
            
            // Separate menu items into action buttons and overflow items
            val actionItems = mutableListOf<MenuItem>()
            val overflowItemsList = mutableListOf<MenuItem>()
            val menuSize = menu.size()
            
            // Pre-check if we need to process items
            if (menuSize == 0) {
                binding.menuButton.visibility = View.GONE
                actionButtonsContainer?.visibility = View.GONE
                overflowItems = emptyList()
                return
            }
            
            for (i in 0 until menuSize) {
                val item = menu.getItem(i) ?: continue
                if (!item.isVisible) continue
                
                // Show as action button if requiresActionButton returns true and item has an icon
                if (requiresActionButton(item) && item.icon != null) {
                    actionItems.add(item)
                } else {
                    overflowItemsList.add(item)
                }
            }
            
            // Create action buttons for items that should be shown as actions
            if (actionButtonsContainer != null) {
                // Ensure container doesn't intercept touch events (only set once)
                if (actionButtonsContainer.descendantFocusability != ViewGroup.FOCUS_BLOCK_DESCENDANTS) {
                    actionButtonsContainer.isClickable = false
                    actionButtonsContainer.isFocusable = false
                    actionButtonsContainer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
                
                actionItems.forEach { item ->
                    val actionButton = createActionButton(item)
                    actionButtonsContainer.addView(actionButton)
                }
                
                // Show/hide action buttons container
                actionButtonsContainer.visibility = if (actionItems.isNotEmpty()) View.VISIBLE else View.GONE
            }
            
            // Show overflow menu button if there are overflow items
            val hasOverflowItems = overflowItemsList.isNotEmpty()
            binding.menuButton.visibility = if (hasOverflowItems) View.VISIBLE else View.GONE
            
            // Set default overflow icon if menu button is visible and no icon is set
            if (hasOverflowItems && binding.menuButton.drawable == null) {
                val overflowIcon = ContextCompat.getDrawable(context, com.android.common.R.drawable.ic_cmn_more)
                overflowIcon?.let {
                    // Use cached text color or get and cache it
                    if (cachedTextColor == -1) {
                        cachedTextColor = context.getProperTextColor()
                    }
                    it.applyColorFilter(cachedTextColor)
                    binding.menuButton.setImageDrawable(it)
                }
            }
            
            // Store overflow items for popup menu
            overflowItems = overflowItemsList
        } finally {
            isUpdatingMenu = false
            // Check if another update was requested during this update
            if (menuNeedsUpdate) {
                post { updateMenuDisplay() }
            }
        }
    }
    
    /**
     * Create an action button for a menu item with proper click handling
     */
    private fun createActionButton(item: MenuItem): ImageView {
        // Cache dimension values to avoid repeated resource lookups
        if (cachedIconSize == -1) {
            cachedIconSize = resources.getDimensionPixelSize(R.dimen.medium_icon_size)
            cachedMargin = resources.getDimensionPixelSize(R.dimen.normal_margin)
            cachedPadding = resources.getDimensionPixelSize(R.dimen.smaller_margin)
        }
        
        // Cache text color if not cached
        if (cachedTextColor == -1) {
            cachedTextColor = context.getProperTextColor()
        }
        
        val capturedItemId = item.itemId
        val capturedItem = item
        
        return ImageView(context).apply {
            // Set layout params (same size as menu button)
            layoutParams = LinearLayout.LayoutParams(cachedIconSize, cachedIconSize).apply {
                marginEnd = cachedMargin
            }
            
            // Set padding for the action button
            setPadding(cachedPadding, cachedPadding, cachedPadding, cachedPadding)
            
            // Set clickable, focusable, and enabled properties FIRST
            // This must be set before background for ripple to work properly
            isClickable = true
            isFocusable = true
            isEnabled = true
            
            // Get a fresh instance of the background drawable for ripple effect
            // Each view needs its own completely fresh instance for ripple to work properly
            // Don't cache or mutate - get a new drawable each time
            background = getActionButtonBackgroundDrawable()
            
            // Set icon with proper color
            item.icon?.let { icon ->
                val iconDrawable = icon.mutate()
                iconDrawable.setColorFilter(cachedTextColor, PorterDuff.Mode.SRC_IN)
                setImageDrawable(iconDrawable)
            }
            
            contentDescription = item.title
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            adjustViewBounds = true
            
            // Store the menu item ID for click handling
            tag = capturedItemId
            
            // Set click listener - optimized to avoid unnecessary lookups
            setOnClickListener {
                // Always get the current listener and menu from the toolbar instance
                val currentListener = this@CustomActionModeToolbar.onMenuItemClickListener
                if (currentListener == null) return@setOnClickListener
                
                val currentMenu = this@CustomActionModeToolbar._menu
                // Try to find the menu item from current menu first
                val menuItem = currentMenu?.findItem(capturedItemId) ?: capturedItem
                
                // Ensure the item is visible
                if (menuItem.isVisible) {
                    currentListener.onMenuItemClick(menuItem)
                }
            }
        }
    }
    
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
    
    /**
     * Gets the background drawable for action buttons with ripple effect.
     */
    private fun getActionButtonBackgroundDrawable(): Drawable? {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)
        return ContextCompat.getDrawable(context, typedValue.resourceId)
    }
    
    /**
     * Shows the popup menu with blur effect, similar to CustomToolbar.
     */
    private fun showMenuPopup() {
        val menu = _menu ?: return
        
        // Use cached activity and blur target if available
        val activity = cachedActivity ?: (context as? Activity)?.also { cachedActivity = it } ?: return
        val blurTarget = cachedBlurTarget ?: activity.findViewById<BlurTarget>(R.id.mainBlurTarget)?.also { 
            cachedBlurTarget = it 
        } ?: return
        
        // Create custom popup window with blur
        val inflater = LayoutInflater.from(context)
        val popupBinding = PopupMenuBlurBinding.inflate(inflater, null, false)
        
        // Setup rounded corners
        popupBinding.root.clipToOutline = true
        
        // Setup BlurView
        val blurView = popupBinding.blurView
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        // Get corner radius from resources to match the drawable
        val cornerRadius = context.resources.getDimension(R.dimen.material_dialog_corner_radius)
        
        // Setup rounded corners with proper clipping
        blurView.clipToOutline = true
        blurView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        
        blurView.setOverlayColor(context.getProperBlurOverlayColor())
        blurView.setupWith(blurTarget)
            .setFrameClearDrawable(windowBackground)
            .setBlurRadius(5f)
            .setBlurAutoUpdate(true)
        
        // Create menu items - only show overflow items (not action buttons)
        val menuContainer = popupBinding.menuContainer
        val visibleItems = if (overflowItems.isNotEmpty()) {
            overflowItems.filter { it.isVisible }
        } else {
            // Fallback: show all visible items if overflowItems is empty
            (0 until menu.size())
                .mapNotNull { menu.getItem(it) }
                .filter { it.isVisible }
        }
        
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
        
        // Ensure outline is updated after measurement
        blurView.invalidateOutline()
        
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
            val anchor = binding?.menuButton ?: this@CustomActionModeToolbar
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
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Force full width by using screen width
        val screenWidth = resources.displayMetrics.widthPixels
        val fullWidthSpec = View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.EXACTLY)
        super.onMeasure(fullWidthSpec, heightMeasureSpec)
    }
    
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Update menu display when layout changes - only if menu exists and has items
        // Use pending update to avoid multiple posts
        if (changed && _menu != null && _menu!!.size() > 0 && !isUpdatingMenu) {
            if (pendingMenuUpdate == null) {
                pendingMenuUpdate = Runnable {
                    updateMenuDisplay()
                    pendingMenuUpdate = null
                }
                post(pendingMenuUpdate!!)
            }
        }
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Cleanup popup window
        popupWindow?.dismiss()
        popupWindow = null
        
        // Clear pending updates
        pendingMenuUpdate?.let { removeCallbacks(it) }
        pendingMenuUpdate = null
        
        // Clear cached references
        cachedActionButtonsContainer = null
        cachedActivity = null
        cachedBlurTarget = null
        
        // Reset cached values
        cachedIconSize = -1
        cachedMargin = -1
        cachedPadding = -1
        cachedTextColor = -1
        menuNeedsUpdate = false
    }
    
    /**
     * Creates a CustomActionModeToolbar instance suitable for use as ActionMode custom view.
     * 
     * @param context The context to use
     * @param title The initial title text
     * @param onTitleClick The click listener for the title (typically for select all/deselect all)
     * @return A configured CustomActionModeToolbar instance
     */
    companion object {
        fun create(
            context: Context,
            title: CharSequence? = null,
            onTitleClick: (() -> Unit)? = null
        ): CustomActionModeToolbar {
            val toolbar = CustomActionModeToolbar(context)
            toolbar.title = title
            if (onTitleClick != null) {
                toolbar.setOnTitleClickListener { onTitleClick() }
            }
            return toolbar
        }
    }
}
