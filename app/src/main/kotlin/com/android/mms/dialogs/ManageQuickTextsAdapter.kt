package com.android.mms.dialogs

import android.annotation.SuppressLint
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.adapters.MyRecyclerViewAdapter
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.setupViewBackground
import com.goodwy.commons.interfaces.RefreshRecyclerViewListener
import com.goodwy.commons.views.MyRecyclerView
import com.android.common.helper.IconItem
import com.android.mms.R
import com.android.mms.activities.ManageQuickTextsActivity
import com.android.mms.databinding.ItemManageQuickTextBinding
import com.android.mms.extensions.config
import com.goodwy.commons.R as CommonsR

class ManageQuickTextsAdapter(
    activity: BaseSimpleActivity,
    var quickTexts: ArrayList<String>,
    val listener: RefreshRecyclerViewListener?,
    recyclerView: MyRecyclerView,
    itemClick: (Any) -> Unit,
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick) {
    init {
        setupDragListener(true)
    }

    override fun getActionMenuId() = R.menu.cab_action_menu_select

    override fun getMorePopupMenuId() = 0

    override fun prepareActionMode(menu: Menu) {
        // Select-all only in the top bar; delete uses the bottom ripple toolbar ([ManageQuickTextsActivity]).
    }

    override fun updateSelectAllButtonIconIfAvailable(selectableItemCount: Int, selectedCount: Int) {
        super.updateSelectAllButtonIconIfAvailable(selectableItemCount, selectedCount)
        (activity as? ManageQuickTextsActivity)?.refreshActionModeRippleToolbarIfNeeded()
    }

    override fun actionItemPressed(id: Int) {
        if (id == R.id.cab_select_all) {
            if (getSelectableItemCount() == selectedKeys.size) {
                (selectedKeys.clone() as HashSet<Int>).forEach { key ->
                    val position = getItemKeyPosition(key)
                    if (position != -1) {
                        toggleItemSelection(false, position, false)
                    }
                }
                updateTitle()
            } else {
                selectAll()
            }
            (activity as? ManageQuickTextsActivity)?.refreshActionModeRippleToolbarIfNeeded()
            return
        }

        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_delete -> deleteSelection()
        }
    }

    fun isActionModeActive(): Boolean = actModeCallback.isSelectable

    fun hasRippleToolbarSelection(): Boolean = selectedKeys.isNotEmpty()

    /**
     * Bottom toolbar for [com.android.common.view.MRippleToolBar] in [ManageQuickTextsActivity].
     */
    fun buildQuickTextsRippleToolbar(): Pair<ArrayList<IconItem>, ArrayList<Int>> {
        val items = ArrayList<IconItem>()
        val ids = ArrayList<Int>()
//        if (selectedKeys.isEmpty()) {
        if (quickTexts.isEmpty()) {
            return items to ids
        }
        items.add(
            IconItem().apply {
                icon = com.android.common.R.drawable.ic_cmn_delete_fill
                title = activity.getString(CommonsR.string.delete)
            },
        )
        ids.add(R.id.cab_delete)
        return items to ids
    }

    fun dispatchRippleToolbarAction(index: Int) {
        if (selectedKeys.isEmpty()) return
        val (_, actionIds) = buildQuickTextsRippleToolbar()
        val id = actionIds.getOrNull(index) ?: return
        actionItemPressed(id)
    }

    override fun getSelectableItemCount() = quickTexts.size

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int) = quickTexts.getOrNull(position)?.hashCode()

    override fun getItemKeyPosition(key: Int) = quickTexts.indexOfFirst { it.hashCode() == key }

    @SuppressLint("NotifyDataSetChanged")
    override fun onActionModeCreated() {
        val useSurfaceColor = activity.isDynamicTheme() && !activity.isSystemInDarkMode()
        val cabBackgroundColor =
            if (useSurfaceColor) activity.getSurfaceColor() else activity.getProperBackgroundColor()

        val actModeBar = actMode?.customView?.parent as? View
        actModeBar?.setBackgroundColor(cabBackgroundColor)

        val toolbar =
            (actMode?.customView as? com.goodwy.commons.views.CustomActionModeToolbar) ?: actBarToolbar
        toolbar?.updateTextColorForBackground(cabBackgroundColor)
        toolbar?.updateColorsForBackground(cabBackgroundColor)

        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onActionModeDestroyed() {
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemManageQuickTextBinding.inflate(layoutInflater, parent, false)
        return createViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val quickText = quickTexts[position]
        holder.bindView(quickText, allowSingleClick = true, allowLongClick = false) { itemView, _ ->
            setupView(itemView, quickText, holder)
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = quickTexts.size

    override fun onSelectionRefresh(holder: ViewHolder, position: Int) {
        val logicalPos = position - positionOffset
        if (logicalPos < 0) return
        val quickText = quickTexts.getOrNull(logicalPos) ?: return
        val key = quickText.hashCode()
        // Do not set row isSelected — it changes list item background; checkbox alone shows selection.
        holder.itemView.isSelected = false
        try {
            ItemManageQuickTextBinding.bind(holder.itemView).apply {
                manageQuickTextHolder.isSelected = false
                quickTextCheckbox.isChecked = selectedKeys.contains(key)
            }
        } catch (_: Exception) {
        }
    }

    private fun getSelectedItems() = quickTexts.filter { selectedKeys.contains(it.hashCode()) }

    private fun setupView(view: View, quickText: String, holder: ViewHolder) {
        ItemManageQuickTextBinding.bind(view).apply {
            root.setupViewBackground(activity)
            manageQuickTextHolder.isSelected = false
            manageQuickTextTitle.apply {
                text = quickText
                setTextColor(textColor)
            }

            val isInActionMode = actModeCallback.isSelectable
            overflowMenuIcon.beVisibleIf(!isInActionMode)
            quickTextCheckbox.beVisibleIf(isInActionMode)

            if (!isInActionMode) {
                overflowMenuIcon.drawable.apply {
                    mutate()
                    setTint(activity.getProperTextColor())
                }
                overflowMenuIcon.setOnClickListener {
                    itemClick(quickText)
                }
            } else {
                overflowMenuIcon.setOnClickListener(null)
            }

            quickTextCheckbox.apply {
                isChecked = selectedKeys.contains(quickText.hashCode())
                setOnClickListener {
                    if (isInActionMode) {
                        holder.itemView.performClick()
                    }
                }
            }
        }
    }

    private fun deleteSelection() {
        val deleteQuickTexts = HashSet<String>(selectedKeys.size)
        val positions = getSelectedItemPositions()

        getSelectedItems().forEach {
            deleteQuickTexts.add(it)
            activity.config.removeQuickText(it)
        }

        quickTexts.removeAll(deleteQuickTexts)
        removeSelectedItems(positions)
        if (quickTexts.isEmpty()) {
            listener?.refreshItems()
        }
    }
}
