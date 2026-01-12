package com.goodwy.commons.views

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.content.ContextCompat
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
    private var onSelectAllClickListener: OnClickListener? = null
    
    private var _menu: Menu? = null
    private var menuInflater: MenuInflater? = null
    private var onMenuItemClickListener: MenuItem.OnMenuItemClickListener? = null
    private var popupWindow: PopupWindow? = null
    
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
    
    var selectAllIcon: Drawable?
        get() = binding?.selectAllButton?.drawable
        set(value) {
            binding?.selectAllButton?.apply {
                setImageDrawable(value)
                visibility = if (value != null) View.VISIBLE else View.GONE
            }
        }
    
    var isSelectAllVisible: Boolean
        get() = binding?.selectAllButton?.visibility == View.VISIBLE
        set(value) {
            binding?.selectAllButton?.visibility = if (value) View.VISIBLE else View.GONE
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
        binding?.selectAllButton?.setOnClickListener { view ->
            onSelectAllClickListener?.onClick(view)
        }
        binding?.menuButton?.setOnClickListener { showMenuPopup() }
        
        // Initialize select all button icon if not set
        if (binding?.selectAllButton?.drawable == null) {
            val selectAllIcon = ContextCompat.getDrawable(context, R.drawable.ic_select_all_vector)
            selectAllIcon?.let {
                binding?.selectAllButton?.setImageDrawable(it)
            }
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
     * Sets the click listener for the select all button.
     */
    fun setOnSelectAllClickListener(listener: OnClickListener?) {
        onSelectAllClickListener = listener
        binding?.selectAllButton?.setOnClickListener(listener)
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
        val textColor = context.getProperTextColor()
        binding?.titleTextView?.setTextColor(textColor)
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
     * This updates title, navigation icon, select all button, and menu button colors.
     */
    fun updateColorsForBackground(backgroundColor: Int) {
        updateTextColorForBackground(backgroundColor)
        val contrastColor = backgroundColor.getContrastColor()
        
        // Update select all button icon color if present
        binding?.selectAllButton?.drawable?.let { drawable ->
            val iconDrawable = drawable.mutate()
            iconDrawable.applyColorFilter(contrastColor)
            binding?.selectAllButton?.setImageDrawable(iconDrawable)
        }
        
        // Update menu button icon color if present
        binding?.menuButton?.drawable?.let { drawable ->
            val iconDrawable = drawable.mutate()
            iconDrawable.applyColorFilter(contrastColor)
            binding?.menuButton?.setImageDrawable(iconDrawable)
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
     * Updates the menu display, showing or hiding the menu button based on visible items.
     */
    private fun updateMenuDisplay() {
        val menu = _menu ?: return
        
        // Don't show menu items as icons - show all items in overflow menu only
        val hasVisibleItems = (0 until menu.size())
            .mapNotNull { menu.getItem(it) }
            .any { it.isVisible }
        
        binding?.menuButton?.visibility = if (hasVisibleItems) View.VISIBLE else View.GONE
        
        // Set default overflow icon if menu button is visible and no icon is set
        if (hasVisibleItems && binding?.menuButton?.drawable == null) {
            val overflowIcon = ContextCompat.getDrawable(context, R.drawable.ic_three_dots_vector)
            overflowIcon?.let {
                val textColor = context.getProperTextColor()
                it.applyColorFilter(textColor)
                binding?.menuButton?.setImageDrawable(it)
            }
        }
    }
    
    /**
     * Shows the popup menu with blur effect, similar to CustomToolbar.
     */
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
