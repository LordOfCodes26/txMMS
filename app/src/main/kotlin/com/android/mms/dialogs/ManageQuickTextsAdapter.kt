package com.android.mms.dialogs

import android.view.*
import android.widget.PopupMenu
import androidx.appcompat.view.ContextThemeWrapper
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.adapters.MyRecyclerViewAdapter
import com.goodwy.commons.extensions.copyToClipboard
import com.goodwy.commons.extensions.getPopupMenuTheme
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.setupViewBackground
import com.goodwy.commons.interfaces.RefreshRecyclerViewListener
import com.goodwy.commons.views.MyRecyclerView
import com.android.mms.R
import com.android.mms.databinding.ItemManageQuickTextBinding
import com.android.mms.extensions.config

class ManageQuickTextsAdapter(
    activity: BaseSimpleActivity, var quickTexts: ArrayList<String>, val listener: RefreshRecyclerViewListener?,
    recyclerView: MyRecyclerView, itemClick: (Any) -> Unit
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick) {
    init {
        setupDragListener(true)
    }

    override fun getActionMenuId() = R.menu.cab_quick_texts

    override fun prepareActionMode(menu: Menu) {
        menu.apply {
            findItem(R.id.cab_copy_text).isVisible = isOneItemSelected()
        }
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_copy_text -> copyTextToClipboard()
            R.id.cab_delete -> deleteSelection()
        }
    }

    override fun getSelectableItemCount() = quickTexts.size

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int) = quickTexts.getOrNull(position)?.hashCode()

    override fun getItemKeyPosition(key: Int) = quickTexts.indexOfFirst { it.hashCode() == key }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemManageQuickTextBinding.inflate(layoutInflater, parent, false)
        return createViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val quickText = quickTexts[position]
        holder.bindView(quickText, allowSingleClick = true, allowLongClick = true) { itemView, _ ->
            setupView(itemView, quickText)
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = quickTexts.size

    private fun getSelectedItems() = quickTexts.filter { selectedKeys.contains(it.hashCode()) }

    private fun setupView(view: View, quickText: String) {
        ItemManageQuickTextBinding.bind(view).apply {
            root.setupViewBackground(activity)
            manageQuickTextHolder.isSelected = selectedKeys.contains(quickText.hashCode())
            manageQuickTextTitle.apply {
                text = quickText
                setTextColor(textColor)
            }

            overflowMenuIcon.drawable.apply {
                mutate()
                setTint(activity.getProperTextColor())
            }

            overflowMenuIcon.setOnClickListener {
                showPopupMenu(overflowMenuAnchor, quickText)
            }
        }
    }

    private fun showPopupMenu(view: View, quickText: String) {
        finishActMode()
        val theme = activity.getPopupMenuTheme()
        val contextTheme = ContextThemeWrapper(activity, theme)

        PopupMenu(contextTheme, view, Gravity.END).apply {
            inflate(getActionMenuId())
            setOnMenuItemClickListener { item ->
                val quickTextId = quickText.hashCode()
                when (item.itemId) {
                    R.id.cab_copy_text -> {
                        executeItemMenuOperation(quickTextId) {
                            copyTextToClipboard()
                        }
                    }

                    R.id.cab_delete -> {
                        executeItemMenuOperation(quickTextId) {
                            deleteSelection()
                        }
                    }
                }
                true
            }
            show()
        }
    }

    private fun executeItemMenuOperation(quickTextId: Int, callback: () -> Unit) {
        selectedKeys.add(quickTextId)
        callback()
        selectedKeys.remove(quickTextId)
    }

    private fun copyTextToClipboard() {
        val selectedText = getSelectedItems().firstOrNull() ?: return
        activity.copyToClipboard(selectedText)
        finishActMode()
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

