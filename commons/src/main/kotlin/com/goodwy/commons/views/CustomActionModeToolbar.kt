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
import android.widget.LinearLayout
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.content.ContextCompat
import com.android.common.view.MPopup
import java.lang.reflect.Method
import com.goodwy.commons.R
import com.goodwy.commons.databinding.CustomActionModeToolbarBinding
import com.goodwy.commons.extensions.applyColorFilter
import com.goodwy.commons.extensions.getContrastColor
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

        /**
         * Creates a CustomActionModeToolbar instance suitable for use as ActionMode custom view.
         *
         * @param context The context to use
         * @param title The initial title text
         * @param onTitleClick The click listener for the title (typically for select all/deselect all)
         * @return A configured CustomActionModeToolbar instance
         */
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

    private var binding: CustomActionModeToolbarBinding? = null
    private var onTitleClickListener: OnClickListener? = null
    private var onNavigationClickListener: OnClickListener? = null

    private var _menu: Menu? = null
    private var _action_menu: Menu? = null
    private var menuInflater: MenuInflater? = null
    private var actionMenuInflater: MenuInflater? = null
    private var hasInflatedActionBarMenu = false
    private var onMenuItemClickListener: MenuItem.OnMenuItemClickListener? = null
    private var overflowMPopup: MPopup? = null
    private var overflowItems: List<MenuItem> = emptyList()
    private var isUpdatingMenu = false

    private var cachedTextColor: Int = -1

    // Menu update optimization flags
    private var menuNeedsUpdate = false
    private var pendingMenuUpdate: Runnable? = null

    // Navigation (back) icon when using MActionBar - store for getter and color updates
    private var navigationIconDrawable: Drawable? = null

    private fun navigationActionBarView(): View? = binding?.root?.findViewById(R.id.navigationIconView)

    private fun navigationActionBarMenu(): Menu? {
        val actionBar = navigationActionBarView() ?: return null
        return getMenuViaReflection(actionBar)
    }

    /**
     * Inflates [R.menu.cab_navigation_only] into the navigation icon view (back button).
     * Called from init; call again to re-inflate the navigation menu.
     */
    fun inflateNavigationIconViewMenu() {
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

        val showNav = (menu?.size() ?: 0) > 0 || navigationIconDrawable != null
        actionBarView.visibility = if (showNav) View.VISIBLE else View.GONE
        bindNavigationActionBarClickListener()
    }

    private fun bindNavigationActionBarClickListener() {
        val actionBar = navigationActionBarView() ?: return
        setOnMenuItemClickListenerViaReflection(actionBar, MenuItem.OnMenuItemClickListener {
            onNavigationClickListener?.onClick(actionBar)
            true
        })
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

    /** Menu inflated into the action bar (when using [inflateMenu]). Like [CustomToolbar.actionMenu]. */
    val actionMenu: Menu
        get() {
            if (_action_menu == null) {
                _action_menu = MenuBuilder(context)
            }
            return _action_menu!!
        }

    // Overflow (more) icon when using MActionBar
    private var overflowIconDrawable: Drawable? = null

    private fun menuActionBarView(): View? = binding?.root?.findViewById(R.id.actionbar)

    private fun menuActionBarMenu(): Menu? {
        val actionBar = menuActionBarView() ?: return null
        return getMenuViaReflection(actionBar)
    }

    private fun inflateMenuActionBarMenu() {
        binding?.actionbar?.inflateMenu(R.menu.cab_more_only)
    }

    private fun applyOverflowIcon(drawable: Drawable?) {
        overflowIconDrawable = drawable?.mutate()
        val actionBarView = menuActionBarView() ?: return
        val menu = menuActionBarMenu()
        if (menu != null) {
            val fallbackItem = if (menu.size() > 0) menu.getItem(0) else null
            val moreItem = menu.findItem(R.id.cab_more) ?: fallbackItem
            if (moreItem != null) {
                if (overflowIconDrawable != null) {
                    moreItem.icon = overflowIconDrawable
                }
                moreItem.isVisible = true
            }
        }
        bindMenuActionBarClickListener()
    }

    private fun bindMenuActionBarClickListener() {
        val actionBar = menuActionBarView() ?: return
        val listener = if (hasInflatedActionBarMenu) {
            MenuItem.OnMenuItemClickListener { item ->
                onMenuItemClickListener?.onMenuItemClick(item) ?: false
            }
        } else {
            MenuItem.OnMenuItemClickListener {
                showMenuPopup()
                true
            }
        }
        setOnMenuItemClickListenerViaReflection(actionBar, listener)
    }

    var overflowIcon: Drawable?
        get() = overflowIconDrawable
        set(value) {
            applyOverflowIcon(value)
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

        // Inflate nav icon menu so back button can be shown inside MActionBar
        inflateNavigationIconViewMenu()
        // Inflate more/overflow menu so More button is inside MActionBar
        inflateMenuActionBarMenu()
        bindMenuActionBarClickListener()

        // Setup click listeners
        binding?.titleTextView?.setOnClickListener { view ->
            onTitleClickListener?.onClick(view)
        }

        // Read attributes from XML
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
        navigationActionBarView()?.contentDescription = context.getString(resId)
    }

    /**
     * Sets the navigation content description.
     */
    fun setNavigationContentDescription(description: CharSequence?) {
        navigationActionBarView()?.contentDescription = description
    }

    /** Returns the navigation icon view (e.g. back button), or null if not available. */
    fun getNavigationIconView(): View? = navigationActionBarView()

    /** Returns the action bar (menu) view, or null if not available. */
    fun getActionBar(): View? = menuActionBarView()

    /**
     * Sets the click listener for the navigation icon (handled via MActionBar menu item).
     */
    fun setNavigationOnClickListener(listener: OnClickListener?) {
        onNavigationClickListener = listener
        bindNavigationActionBarClickListener()
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
    private fun showMorePopupMenu(
        anchor: View,
        listener: MenuItem.OnMenuItemClickListener
    ) {
        val sourceMenu = _menu ?: return
        val items = buildList {
            for (i in 0 until sourceMenu.size()) {
                add(sourceMenu.getItem(i))
            }
        }
        overflowMPopup = showOverflowMPopup(anchor, items, listener)
    }

    private fun mPopupMenuField(popup: MPopup): Menu? =
        runCatching {
            val f = MPopup::class.java.getDeclaredField("menu")
            f.isAccessible = true
            f.get(popup) as Menu
        }.getOrNull()

    private fun stripMenuIconsForMPopup(targetMenu: Menu) {
        for (index in 0 until targetMenu.size()) {
            val item = targetMenu.getItem(index)
            item.icon = null
            item.subMenu?.let { sub -> stripMenuIconsForMPopup(sub) }
        }
    }

    /**
     * Right-side overflow using txCommon [MPopup] (blur + rounded menu), same visuals as [BlurPopupMenu].
     */
    private fun showOverflowMPopup(
        anchor: View,
        sourceItems: List<MenuItem>,
        itemClickHandler: MenuItem.OnMenuItemClickListener
    ): MPopup? {
        val activity = context as? Activity ?: return null
        val blurTarget = activity.findViewById<BlurTarget>(R.id.mainBlurTarget) ?: return null
        overflowMPopup?.dismiss()
        val popup = MPopup(activity, anchor, Gravity.END)
        val targetMenu = mPopupMenuField(popup) ?: return null
        if (targetMenu is MenuBuilder) {
            targetMenu.clear()
        }
        val idToOriginal = mutableMapOf<Int, MenuItem>()
        sourceItems.forEach { original ->
            if (!original.isVisible) return@forEach
            val newItem = targetMenu.add(original.groupId, original.itemId, original.order, original.title)
            newItem.isCheckable = original.isCheckable
            newItem.isChecked = original.isChecked
            newItem.isEnabled = original.isEnabled
            newItem.isVisible = original.isVisible
            idToOriginal[original.itemId] = original
        }
        stripMenuIconsForMPopup(targetMenu)
        popup.setBlurTarget(blurTarget)
        popup.setOnMenuItemClickListener { clicked ->
            val original = idToOriginal[clicked.itemId] ?: clicked
            itemClickHandler.onMenuItemClick(original)
            true
        }
        val pullUpPx = activity.resources.getDimensionPixelSize(R.dimen.mactionbar_popup_vertical_overlap)
        clearMpopupAnchorOffset(popup)
        popup.show()
        if (pullUpPx > 0) {
            applyMpopupAnchorAdjustments(popup, activity, horizontalEndInsetPx = 0, verticalPullUpPx = pullUpPx)
        }
        return popup
    }
    fun setPopupForMoreItem(
        moreItemId: Int,
        menuResId: Int,
        blurTargetView: View?,
        listener: MenuItem.OnMenuItemClickListener?
    ): Boolean {
        val actionBar = binding?.actionbar ?: return false
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

        val popupAnchor = actionBar as? View ?: return false
        val fallbackClickListener = MenuItem.OnMenuItemClickListener { clickedItem ->
            if (clickedItem.itemId == moreItemId) {
                showMorePopupMenu(popupAnchor, listener)
                true
            } else {
                onMenuItemClickListener?.onMenuItemClick(clickedItem) == true
            }
        }
        return CustomActionModeToolbar.Companion.setOnMenuItemClickListenerViaReflection(actionBar, fallbackClickListener)
    }

    /**
     * Updates the text color based on the background color for proper contrast.
     * This is typically used when the action mode toolbar has a custom background color.
     */
    fun updateTextColorForBackground(backgroundColor: Int) {
        val contrastColor = backgroundColor.getContrastColor()
        binding?.titleTextView?.setTextColor(contrastColor)
        // Update navigation icon color (MActionBar menu item)
        navigationActionBarMenu()?.findItem(R.id.cab_remove)?.let { navItem ->
            navItem.icon?.let { drawable ->
                val iconDrawable = drawable.mutate()
                iconDrawable.applyColorFilter(contrastColor)
                navItem.icon = iconDrawable
            }
        }
    }

    /**
     * Updates all colors based on the background color for proper contrast.
     * This updates title, navigation icon, select all button, menu button, and action button colors.
     */
    fun updateColorsForBackground(backgroundColor: Int) {
        updateTextColorForBackground(backgroundColor)
        val contrastColor = backgroundColor.getContrastColor()

        // Update overflow (More) MActionBar menu item icon color
        menuActionBarMenu()?.findItem(R.id.cab_more)?.let { moreItem ->
            moreItem.icon?.let { drawable ->
                val iconDrawable = drawable.mutate()
                iconDrawable.applyColorFilter(contrastColor)
                moreItem.icon = iconDrawable
            }
        }
    }

    /**
     * Inflates a menu resource into the action bar (menuButton), like [CustomToolbar.inflateMenu].
     * Menu items are shown on the action bar and clicks are forwarded to [onMenuItemClickListener].
     */
//    fun inflateMenu(actionMenuResId: Int) {
//        val actionBar = menuActionBarView() ?: return
//        menuActionBarMenu()?.let { liveMenu ->
//            if (liveMenu is MenuBuilder) {
//                liveMenu.clear()
//            }
//        }
//        binding?.menuButton?.inflateMenu(actionMenuResId)
//        hasInflatedActionBarMenu = true
//        if (actionMenuInflater == null) {
//            actionMenuInflater = MenuInflater(context)
//        }
//        _action_menu = MenuBuilder(context).also { actionMenuInflater?.inflate(actionMenuResId, it) }
//        bindMenuActionBarClickListener()
//        updateMenuDisplay()
//    }

    fun inflateMenu(actionMenuResId: Int) {
        getLiveActionBarMenu()?.let { liveMenu ->
            if (liveMenu is MenuBuilder) liveMenu.clear()
        }
        binding?.actionbar?.inflateMenu(actionMenuResId)
        hasInflatedActionBarMenu = true
        if (actionMenuInflater == null) actionMenuInflater = MenuInflater(context)
        _action_menu = MenuBuilder(context).also { actionMenuInflater?.inflate(actionMenuResId, it) }
        updateMenuDisplay()
    }

    private fun getLiveActionBarMenu(): Menu? {
        val actionBar = menuActionBarView() ?: return null
        return getMenuViaReflection(actionBar)
    }

    /**
     * Sets the menu item click listener.
     */
    fun setOnMenuItemClickListener(listener: MenuItem.OnMenuItemClickListener?) {
        onMenuItemClickListener = listener
        bindMenuActionBarClickListener()
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
        val menu = if (hasInflatedActionBarMenu) menuActionBarMenu() ?: _action_menu else _menu
        val menuItem = menu?.findItem(menuItemId) ?: return

        val iconRes = if (allSelected) {
            com.android.common.R.drawable.ic_cmn_multi_unselect
        } else {
            com.android.common.R.drawable.ic_cmn_select_none
        }

        val icon = ContextCompat.getDrawable(context, iconRes)
        icon?.let {
            if (cachedTextColor == -1) {
                cachedTextColor = context.getProperTextColor()
            }
            it.applyColorFilter(cachedTextColor)
            menuItem.icon = it
            invalidateMenu()
        }
    }

    /**
     * Updates the menu display. When [hasInflatedActionBarMenu] is true, visibility follows
     * [_action_menu]; otherwise all items are shown in the overflow popup.
     */
    private fun updateMenuDisplay() {
        if (isUpdatingMenu) {
            menuNeedsUpdate = true
            return
        }
        isUpdatingMenu = true
        menuNeedsUpdate = false

        try {
            if (hasInflatedActionBarMenu) {
                // Keep live MActionBar menu in sync with the mutable action menu model.
                // Adapters update visibility/enabled flags on actionMenu, so mirror those flags here.
                val sourceMenu = _action_menu
                val liveMenu = menuActionBarMenu()
                if (sourceMenu != null && liveMenu != null) {
                    for (i in 0 until liveMenu.size()) {
                        val liveItem = liveMenu.getItem(i) ?: continue
                        val sourceItem = sourceMenu.findItem(liveItem.itemId) ?: continue
                        liveItem.isVisible = sourceItem.isVisible
                        liveItem.isEnabled = sourceItem.isEnabled
                        liveItem.isCheckable = sourceItem.isCheckable
                        liveItem.isChecked = sourceItem.isChecked
                        liveItem.title = sourceItem.title
                    }
                }

                val shouldShow = sourceMenu?.let { menu ->
                    (0 until menu.size()).any { menu.getItem(it).isVisible }
                } ?: true
                menuActionBarView()?.visibility = if (shouldShow) View.VISIBLE else View.GONE
                return
            }

            val menu = _menu ?: return
            val overflowItemsList = mutableListOf<MenuItem>()
            val menuSize = menu.size()

            if (menuSize == 0) {
                menuActionBarView()?.visibility = View.GONE
                overflowItems = emptyList()
                return
            }

            for (i in 0 until menuSize) {
                val item = menu.getItem(i) ?: continue
                if (item.isVisible) overflowItemsList.add(item)
            }

            val hasOverflowItems = overflowItemsList.isNotEmpty()
            menuActionBarView()?.visibility = if (hasOverflowItems) View.VISIBLE else View.GONE

            if (hasOverflowItems && overflowIconDrawable == null) {
                val defaultIcon = ContextCompat.getDrawable(context, R.drawable.ic_three_dots_vector)
                defaultIcon?.let {
                    if (cachedTextColor == -1) {
                        cachedTextColor = context.getProperTextColor()
                    }
                    it.applyColorFilter(cachedTextColor)
                    overflowIcon = it
                }
            }

            overflowItems = overflowItemsList
        } finally {
            isUpdatingMenu = false
            if (menuNeedsUpdate) {
                post { updateMenuDisplay() }
            }
        }
    }

    /**
     * Shows the right-side overflow using [MPopup] (blur), anchored to the action bar.
     */
    private fun showMenuPopup() {
        val menu = _menu ?: return
        val anchor = menuActionBarView() ?: return

        val visibleItems = if (overflowItems.isNotEmpty()) {
            overflowItems.filter { it.isVisible }
        } else {
            val menuSize = menu.size()
            buildList {
                for (i in 0 until menuSize) {
                    val item = menu.getItem(i) ?: continue
                    if (item.isVisible) add(item)
                }
            }
        }

        if (visibleItems.isEmpty()) {
            return
        }

        overflowMPopup = showOverflowMPopup(
            anchor,
            visibleItems,
            MenuItem.OnMenuItemClickListener { clickedItem ->
                onMenuItemClickListener?.onMenuItemClick(clickedItem) ?: false
            }
        )
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
        overflowMPopup?.dismiss()
        overflowMPopup = null

        // Clear pending updates
        pendingMenuUpdate?.let { removeCallbacks(it) }
        pendingMenuUpdate = null

        cachedTextColor = -1
        menuNeedsUpdate = false
    }
}
