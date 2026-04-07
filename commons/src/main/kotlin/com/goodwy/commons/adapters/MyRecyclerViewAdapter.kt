package com.goodwy.commons.adapters

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.*
import android.view.ViewParent
import android.widget.ImageView
import androidx.appcompat.app.ActionBar
import androidx.appcompat.widget.ActionMenuView
import androidx.recyclerview.widget.RecyclerView
import com.goodwy.commons.R
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.CONTACT_THUMBNAILS_SIZE_EXTRA_LARGE
import com.goodwy.commons.helpers.CONTACT_THUMBNAILS_SIZE_LARGE
import com.goodwy.commons.helpers.CONTACT_THUMBNAILS_SIZE_SMALL
import com.goodwy.commons.interfaces.ActionModeToolbarHost
import com.goodwy.commons.interfaces.MyActionModeCallback
import com.goodwy.commons.models.RecyclerSelectionRefreshPayload
import com.goodwy.commons.views.BottomPaddingDecoration
import com.goodwy.commons.views.CustomActionModeToolbar
import com.goodwy.commons.views.MyDividerDecoration
import com.goodwy.commons.views.MyRecyclerView
import kotlin.math.max
import kotlin.math.min

abstract class MyRecyclerViewAdapter(
    val activity: BaseSimpleActivity,
    val recyclerView: MyRecyclerView,
    val itemClick: (Any) -> Unit,
) :
    RecyclerView.Adapter<MyRecyclerViewAdapter.ViewHolder>() {
    protected val baseConfig = activity.baseConfig
    protected val resources = activity.resources!!
    protected val layoutInflater = activity.layoutInflater
    protected val textCursorColor = activity.getProperTextCursorColor()
    protected var accentColor = activity.getProperAccentColor()
    protected var textColor = activity.getProperTextColor()
    protected var backgroundColor = activity.getProperBackgroundColor()
    protected var surfaceColor = activity.getSurfaceColor()
    protected var properPrimaryColor = activity.getProperPrimaryColor()
    protected var contrastColor = properPrimaryColor.getContrastColor()
    protected var contactThumbnailsSize = contactThumbnailsSize()
    protected var actModeCallback: MyActionModeCallback
    protected var selectedKeys = LinkedHashSet<Int>()
    protected var positionOffset = 0
    protected var actMode: ActionMode? = null

    protected var actBarToolbar: CustomActionModeToolbar? = null
    private var isUsingHostActionModeToolbar = false
    protected var lastLongPressedItem = -1
    private var originalStatusBarColor: Int? = null

    private var isDividersVisible = false
    private var dividerDecoration: MyDividerDecoration? = null
    private var bottomPaddingDecoration: BottomPaddingDecoration? = null

    abstract fun getActionMenuId(): Int
    abstract fun getMorePopupMenuId(): Int

    /** Id of the "more" menu item that opens the popup (e.g. R.id.more). Return 0 if no more menu. */
    open fun getMoreItemId(): Int = 0

    /** Called when user selects an item from the more popup menu. Override in app to handle (e.g. delegate to activity). */
    open fun onMorePopupMenuItemClick(item: MenuItem): Boolean = false

    abstract fun prepareActionMode(menu: Menu)

    abstract fun actionItemPressed(id: Int)

    abstract fun getSelectableItemCount(): Int

    abstract fun getIsItemSelectable(position: Int): Boolean

    abstract fun getItemSelectionKey(position: Int): Int?

    abstract fun getItemKeyPosition(key: Int): Int

    abstract fun onActionModeCreated()

    abstract fun onActionModeDestroyed()

    protected fun isOneItemSelected() = selectedKeys.size == 1

    /**
     * Configures the given toolbar for action mode (menu, listeners, colors).
     * When actionMode is null we're using the host's toolbar (e.g. MainSearchMenu).
     */
    @SuppressLint("UseCompatLoadingForDrawables")
    private fun setupActionModeToolbar(toolbar: CustomActionModeToolbar, actionMode: ActionMode?) {
        toolbar.setOnTitleClickListener {
            if (getSelectableItemCount() == selectedKeys.size) {
                finishActMode()
            } else {
                selectAll()
            }
        }
        toolbar.inflateMenu(getActionMenuId())
        toolbar.setOnMenuItemClickListener { item ->
            actionItemPressed(item.itemId)
            true
        }
        if (getMoreItemId() != 0 && getMorePopupMenuId() != 0) {
            val blurTarget = (activity as? ActionModeToolbarHost)?.getBlurTargetView()
            toolbar.setPopupForMoreItem(
                getMoreItemId(), getMorePopupMenuId(), blurTarget,
                object : MenuItem.OnMenuItemClickListener {
                    override fun onMenuItemClick(item: MenuItem): Boolean {
                        return onMorePopupMenuItemClick(item)
                    }
                })
        }
        toolbar.setNavigationOnClickListener { finishActMode() }
        toolbar.setNavigationContentDescription(android.R.string.cancel)

        val cabBackgroundColor = activity.getSurfaceColor()
        if (actionMode != null) {
            val actModeBar = actionMode.customView?.parent as? View
            actModeBar?.setBackgroundColor(cabBackgroundColor)
        } else {
            toolbar.setBackgroundColor(Color.TRANSPARENT)
        }

        toolbar.updateTextColorForBackground(cabBackgroundColor)
        toolbar.updateColorsForBackground(cabBackgroundColor)

        if (actionMode != null && activity is com.goodwy.commons.activities.EdgeToEdgeActivity) {
            originalStatusBarColor = activity.window.statusBarColor
            activity.window.statusBarColor = Color.TRANSPARENT
            activity.window.setSystemBarsAppearance(Color.TRANSPARENT)
        }

        prepareActionMode(toolbar.actionMenu)
        toolbar.invalidateMenu()
    }

    private fun destroyActionModeCleanup() {
        actModeCallback.isSelectable = false
        (selectedKeys.clone() as HashSet<Int>).forEach {
            val position = getItemKeyPosition(it)
            if (position != -1) {
                toggleItemSelection(false, position, false)
            }
        }
        selectedKeys.clear()
        actBarToolbar?.title = ""

        if (!isUsingHostActionModeToolbar && activity is com.goodwy.commons.activities.EdgeToEdgeActivity) {
            activity.window.decorView.post {
                activity.window.statusBarColor = Color.TRANSPARENT
                activity.window.setSystemBarsAppearance(Color.TRANSPARENT)
            }
            originalStatusBarColor = null
        }

        actMode = null
        lastLongPressedItem = -1
        onActionModeDestroyed()
    }

    private fun startActionModeWithHost() {
        val host = activity as? ActionModeToolbarHost ?: return
        if (getActionMenuId() == 0) return

        actBarToolbar = host.getActionModeToolbar()
        setupActionModeToolbar(actBarToolbar!!, actionMode = null)
        host.showActionModeToolbar()
        actModeCallback.isSelectable = true
        actMode = null
        isUsingHostActionModeToolbar = true
        onActionModeCreated()
        updateTitle()
    }

    init {
        actModeCallback = object : MyActionModeCallback() {
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                actionItemPressed(item.itemId)
                return true
            }

            @SuppressLint("UseCompatLoadingForDrawables")
            override fun onCreateActionMode(actionMode: ActionMode, menu: Menu?): Boolean {
                if (getActionMenuId() == 0) {
                    return true
                }

                selectedKeys.clear()
                isSelectable = true
                actMode = actionMode

                actBarToolbar = CustomActionModeToolbar(activity)
                actionMode.customView = actBarToolbar
                setupActionModeToolbar(actBarToolbar!!, actionMode)

                onActionModeCreated()

                actBarToolbar?.onGlobalLayout {
                    val defaultCloseButton = activity.findViewById<View>(androidx.appcompat.R.id.action_mode_close_button)
                    defaultCloseButton?.visibility = View.GONE
                    var currentParent: ViewParent? = actBarToolbar?.parent
                    while (currentParent != null && currentParent is ViewGroup) {
                        val parentView = currentParent as ViewGroup
                        val params = parentView.layoutParams
                        if (params != null) {
                            params.width = ViewGroup.LayoutParams.MATCH_PARENT
                            parentView.layoutParams = params
                        }
                        parentView.setPadding(0, parentView.paddingTop, 0, parentView.paddingBottom)
                        currentParent = parentView.parent
                    }
                    val params = actBarToolbar?.layoutParams
                    if (params != null) {
                        params.width = ViewGroup.LayoutParams.MATCH_PARENT
                        actBarToolbar?.layoutParams = params
                    }
                    actBarToolbar?.requestLayout()
                }

                return true
            }

            override fun onPrepareActionMode(actionMode: ActionMode, menu: Menu): Boolean {
                actBarToolbar?.let { toolbar ->
                    prepareActionMode(toolbar.actionMenu)
                    toolbar.invalidateMenu()
                }
                return true
            }

            override fun onDestroyActionMode(actionMode: ActionMode) {
                destroyActionModeCleanup()
                actBarToolbar = null
            }
        }
    }

    protected open fun toggleItemSelection(select: Boolean, pos: Int, updateTitle: Boolean = true) {
        if (select && !getIsItemSelectable(pos)) {
            return
        }

        val itemKey = getItemSelectionKey(pos) ?: return
        if ((select && selectedKeys.contains(itemKey)) || (!select && !selectedKeys.contains(itemKey))) {
            return
        }

        if (select) {
            selectedKeys.add(itemKey)
        } else {
            selectedKeys.remove(itemKey)
        }

        notifyItemChanged(pos + positionOffset, RecyclerSelectionRefreshPayload)

        if (updateTitle) {
            updateTitle()
        }

        // Don't finish action mode when all items are deselected
        // if (selectedKeys.isEmpty()) {
        //     finishActMode()
        // }
    }

    open fun updateTitle() {
        val selectableItemCount = getSelectableItemCount()
        val selectedCount = min(selectedKeys.size, selectableItemCount)
        val oldTitle = actBarToolbar?.title
//        val newTitle = resources.getString(com.goodwy.commons.R.string.action_mode_selection_title, selectedCount, selectableItemCount)
        val newTitle = resources.getString(com.goodwy.commons.R.string.action_mode_selection_title, selectedCount)
        if (oldTitle != newTitle) {
            actBarToolbar?.title = newTitle
            actMode?.invalidate()
        }
        
        // Update select all button icon based on selection state
        // Note: Subclasses should override this to provide the correct menu item ID
        updateSelectAllButtonIconIfAvailable(selectableItemCount, selectedCount)
    }
    
    /**
     * Updates the select all button icon if the menu item ID is available.
     * Subclasses can override this to provide the correct menu item ID.
     */
    protected open fun updateSelectAllButtonIconIfAvailable(selectableItemCount: Int, selectedCount: Int) {
        // Try to find select all menu item by title
        val allSelected = selectableItemCount > 0 && selectedCount == selectableItemCount
        actBarToolbar?.actionMenu?.let { menu ->
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                if (item?.title?.toString()?.contains("select", ignoreCase = true) == true ||
                    item?.title?.toString()?.contains("全选", ignoreCase = false) == true ||
                    item?.title?.toString()?.contains("전체", ignoreCase = false) == true) {
                    actBarToolbar?.updateSelectAllButtonIcon(item.itemId, allSelected)
                    break
                }
            }
        }
    }

    fun itemLongClicked(position: Int) {
        recyclerView.setDragSelectActive(position)
        lastLongPressedItem = if (lastLongPressedItem == -1) {
            position
        } else {
            val min = min(lastLongPressedItem, position)
            val max = max(lastLongPressedItem, position)
            for (i in min..max) {
                toggleItemSelection(true, i, false)
            }
            updateTitle()
            position
        }
    }

    protected fun getSelectedItemPositions(sortDescending: Boolean = true): ArrayList<Int> {
        val positions = ArrayList<Int>()
        val keys = selectedKeys.toList()
        keys.forEach {
            val position = getItemKeyPosition(it)
            if (position != -1) {
                positions.add(position)
            }
        }

        if (sortDescending) {
            positions.sortDescending()
        }
        return positions
    }

    protected open fun selectAll() {
        val cnt = itemCount - positionOffset
        for (i in 0 until cnt) {
            toggleItemSelection(true, i, false)
        }
        lastLongPressedItem = -1
        updateTitle()
    }

    protected fun setupDragListener(enable: Boolean) {
        if (enable) {
            recyclerView.setupDragListener(object : MyRecyclerView.MyDragListener {
                override fun selectItem(position: Int) {
                    toggleItemSelection(true, position, true)
                }

                override fun selectRange(initialSelection: Int, lastDraggedIndex: Int, minReached: Int, maxReached: Int) {
                    selectItemRange(
                        initialSelection,
                        max(0, lastDraggedIndex - positionOffset),
                        max(0, minReached - positionOffset),
                        maxReached - positionOffset
                    )
                    if (minReached != maxReached) {
                        lastLongPressedItem = -1
                    }
                }
            })
        } else {
            recyclerView.setupDragListener(null)
        }
    }

    protected fun selectItemRange(from: Int, to: Int, min: Int, max: Int) {
        if (from == to) {
            (min..max).filter { it != from }.forEach { toggleItemSelection(false, it, true) }
            return
        }

        if (to < from) {
            for (i in to..from) {
                toggleItemSelection(true, i, true)
            }

            if (min > -1 && min < to) {
                (min until to).filter { it != from }.forEach { toggleItemSelection(false, it, true) }
            }

            if (max > -1) {
                for (i in from + 1..max) {
                    toggleItemSelection(false, i, true)
                }
            }
        } else {
            for (i in from..to) {
                toggleItemSelection(true, i, true)
            }

            if (max > -1 && max > to) {
                (to + 1..max).filter { it != from }.forEach { toggleItemSelection(false, it, true) }
            }

            if (min > -1) {
                for (i in min until from) {
                    toggleItemSelection(false, i, true)
                }
            }
        }
    }

    fun setupZoomListener(zoomListener: MyRecyclerView.MyZoomListener?) {
        recyclerView.setupZoomListener(zoomListener)
    }

//    fun addVerticalDividers(add: Boolean) {
//        if (recyclerView.itemDecorationCount > 0) {
//            recyclerView.removeItemDecorationAt(0)
//        }
//
//        if (add) {
//            DividerItemDecoration(activity, DividerItemDecoration.VERTICAL).apply {
//                ContextCompat.getDrawable(activity, R.drawable.divider)?.let {
//                    setDrawable(it)
//                }
//                recyclerView.addItemDecoration(this)
//            }
//        }
//    }

    /**
     * Vertical separator between elements.
     *
     * @param visible Enabled.
     * @param paddingStartDp Left padding in Dp.
     * @param paddingEndDp Right padding in Dp.
     * @param dividerHeightDp Height of the divider in Dp.
     */
    fun setVerticalDividers(
        visible: Boolean,
        paddingStartDp: Int = 0,
        paddingEndDp: Int = 0,
        dividerHeightDp: Int = 1,
        color: Int = 0x33AAAAAA, // activity.getDividerColor(),
    ) {
        // Remove the old separator
        dividerDecoration?.let {
            recyclerView.removeItemDecoration(it)
            dividerDecoration = null
        }

        if (visible) {
            // Create or reuse a separator
            val decoration = MyDividerDecoration().apply {
                setConfiguration(
                    paddingStartDp = paddingStartDp,
                    paddingEndDp = paddingEndDp,
                    dividerHeightDp = dividerHeightDp,
                    color = color,
                    context = activity
                )
                setVisible(true)
            }

            recyclerView.addItemDecoration(decoration)
            dividerDecoration = decoration
            isDividersVisible = true
        } else {
            isDividersVisible = false
        }
    }

    /**
     * Creates the bottom margin of the adapter.
     * If there is no scrollbar in the Adapter, it is better to use:
     * ```
     *         android:scrollbars=“vertical”
     *         android:clipToPadding=“false”
     *         android:paddingBottom=“128dp”
     * ```
     *
     * @param bottomPaddingDp Height of the setback in Dp.
     */
    fun addBottomPadding(bottomPaddingDp: Int) {
        // Remove the old indent if there is one
        bottomPaddingDecoration?.let {
            recyclerView.removeItemDecoration(it)
            bottomPaddingDecoration = null
        }

        if (bottomPaddingDp > 0) {
            // Convert dp to pixels
            val paddingPx = bottomPaddingDp.dpToPx(activity)

            // Create and add decoration
            BottomPaddingDecoration(paddingPx).apply {
                recyclerView.addItemDecoration(this)
                bottomPaddingDecoration = this
            }

            // Updating display
            recyclerView.invalidateItemDecorations()
        }
    }

    fun finishActMode() {
        if (isUsingHostActionModeToolbar) {
            (activity as? ActionModeToolbarHost)?.hideActionModeToolbar()
            destroyActionModeCleanup()
            isUsingHostActionModeToolbar = false
            actBarToolbar = null
        } else {
            actMode?.finish()
        }
    }

    fun startActMode() {
        if (actMode == null && !actModeCallback.isSelectable) {
            if (activity is ActionModeToolbarHost) {
                startActionModeWithHost()
            } else {
                activity.startActionMode(actModeCallback)
            }
        }
    }

    fun updateTextColor(textColor: Int) {
        this.textColor = textColor
        notifyDataSetChanged()
    }

    fun updatePrimaryColor() {
        properPrimaryColor = activity.getProperPrimaryColor()
        contrastColor = properPrimaryColor.getContrastColor()
        accentColor = activity.getProperAccentColor()
    }

    fun updateBackgroundColor(backgroundColor: Int) {
        this.backgroundColor = backgroundColor
        surfaceColor = activity.getSurfaceColor()
        notifyDataSetChanged()
    }

    private fun contactThumbnailsSize(): Float {
        return when (activity.baseConfig.contactThumbnailsSize) {
            CONTACT_THUMBNAILS_SIZE_SMALL -> 0.9F
            CONTACT_THUMBNAILS_SIZE_LARGE -> 1.15F
            CONTACT_THUMBNAILS_SIZE_EXTRA_LARGE -> 1.3F
            else -> 1.0F
        }
    }

    protected fun createViewHolder(layoutType: Int, parent: ViewGroup?): ViewHolder {
        val view = layoutInflater.inflate(layoutType, parent, false)
        return ViewHolder(view)
    }

    protected fun createViewHolder(view: View): ViewHolder {
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.any { it is RecyclerSelectionRefreshPayload }) {
            onSelectionRefresh(holder, position)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    /**
     * Partial bind after selection changes (payload [RecyclerSelectionRefreshPayload]).
     * Override in adapters that use checkboxes instead of [View.isSelected] on the row root.
     */
    protected open fun onSelectionRefresh(holder: ViewHolder, position: Int) {
        val logicalPos = position - positionOffset
        if (logicalPos < 0) return
        val key = getItemSelectionKey(logicalPos) ?: return
        holder.itemView.isSelected = selectedKeys.contains(key)
    }

    protected fun bindViewHolder(holder: ViewHolder) {
        holder.itemView.tag = holder
    }

    protected fun removeSelectedItems(positions: ArrayList<Int>) {
        positions.forEach {
            notifyItemRemoved(it)
        }
        finishActMode()
    }

    open inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bindView(any: Any, allowSingleClick: Boolean, allowLongClick: Boolean, callback: (itemView: View, adapterPosition: Int) -> Unit): View {
            return itemView.apply {
                callback(this, adapterPosition)

                if (allowSingleClick) {
                    setOnClickListener { viewClicked(any) }
                    setOnLongClickListener { if (allowLongClick) viewLongClicked() else viewClicked(any); true }
                } else {
                    setOnClickListener(null)
                    setOnLongClickListener(null)
                }
            }
        }

        fun viewClicked(any: Any) {
            if (actModeCallback.isSelectable) {
                val currentPosition = adapterPosition - positionOffset
                val isSelected = selectedKeys.contains(getItemSelectionKey(currentPosition))
                toggleItemSelection(!isSelected, currentPosition, true)
            } else {
                itemClick.invoke(any)
            }
            lastLongPressedItem = -1
        }

        fun viewLongClicked() {
            val currentPosition = adapterPosition - positionOffset
            if (!getIsItemSelectable(currentPosition)) return
            if (!actModeCallback.isSelectable) {
                if (activity is ActionModeToolbarHost) {
                    startActionModeWithHost()
                } else {
                    activity.startActionMode(actModeCallback)
                }
            }

            toggleItemSelection(true, currentPosition, true)
            itemLongClicked(currentPosition)
        }
    }

    // Cleaning resources
    fun cleanup() {
        dividerDecoration?.let {
            recyclerView.removeItemDecoration(it)
            dividerDecoration = null
        }
        MyDividerDecoration.clearCache()

        bottomPaddingDecoration?.let {
            recyclerView.removeItemDecoration(it)
            bottomPaddingDecoration = null
        }
    }
}
