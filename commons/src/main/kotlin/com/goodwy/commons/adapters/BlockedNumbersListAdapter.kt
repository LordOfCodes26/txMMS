package com.goodwy.commons.adapters

import android.annotation.SuppressLint
import android.util.TypedValue
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import com.android.common.helper.IconItem
import com.android.common.view.MRippleToolBar
import com.goodwy.commons.R
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.activities.BlockedItemsActivity
import com.goodwy.commons.databinding.ItemContactWithNumberBinding
import com.goodwy.commons.extensions.adjustAlpha
import com.goodwy.commons.extensions.getContrastColor
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getTextSize
import com.goodwy.commons.extensions.getTextSizeSmall
import com.goodwy.commons.extensions.removeBlockedNumberEntry
import com.goodwy.commons.extensions.setHeightAndWidth
import com.goodwy.commons.helpers.AvatarResolver
import com.goodwy.commons.models.BlockedNumber
import com.goodwy.commons.models.RecyclerSelectionPayload
import com.goodwy.commons.views.MyRecyclerView
import eightbitlab.com.blurview.BlurTarget

private object BlockedNumberDiffCallback : DiffUtil.ItemCallback<BlockedNumber>() {
    override fun areItemsTheSame(oldItem: BlockedNumber, newItem: BlockedNumber) = oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: BlockedNumber, newItem: BlockedNumber) = oldItem == newItem
}

private fun blockedNumberKey(item: BlockedNumber): Int =
    (item.id xor (item.id shr 32)).toInt()

class BlockedNumbersListAdapter(
    activity: BaseSimpleActivity,
    recyclerView: MyRecyclerView,
    private val onOpenItem: (BlockedNumber) -> Unit,
    onListRefresh: () -> Unit = {},
) : MyRecyclerViewListAdapter<BlockedNumber>(
    activity = activity,
    recyclerView = recyclerView,
    diffUtil = BlockedNumberDiffCallback,
    itemClick = { item -> onOpenItem(item) },
    onRefresh = onListRefresh,
) {

    init {
        setupDragListener(false)
        setHasStableIds(true)
        recyclerView.itemAnimator?.changeDuration = 0
    }

    fun isActionModeActive(): Boolean = actModeCallback.isSelectable

    fun bindRippleToolbar(ripple: MRippleToolBar, blurTarget: BlurTarget) {
        val items = ArrayList<IconItem>().apply {
            add(
                IconItem().apply {
                    title = activity.getString(R.string.unblock)
                    icon = R.drawable.ic_delete_outline
                },
            )
        }
        ripple.setTabs(activity, items, blurTarget)
        ripple.setOnClickedListener { index ->
            if (index == 0) unblockSelectedNumbers()
        }
        ripple.visibility = View.VISIBLE
    }

    private fun getSelectedNumbers(): List<BlockedNumber> =
        currentList.filter { selectedKeys.contains(blockedNumberKey(it)) }

    private fun unblockSelectedNumbers() {
        val numbers = getSelectedNumbers()
        if (numbers.isEmpty()) return
        numbers.forEach { activity.removeBlockedNumberEntry(it) }
        finishActMode()
        onRefresh.invoke()
    }

    override fun updateTitle() {
        super.updateTitle()
        (activity as? BlockedItemsActivity)?.refreshActionModeRippleToolbarIfNeeded()
    }

    override fun getItemId(position: Int): Long = currentList.getOrNull(position)?.id ?: position.toLong()

    override fun getActionMenuId() = R.menu.cab_blocked_list
    override fun getMorePopupMenuId() = 0

    override fun prepareActionMode(menu: Menu) {}

    override fun actionItemPressed(id: Int) {
        if (id == R.id.cab_select_all) {
            if (getSelectableItemCount() == selectedKeys.size) {
                HashSet(selectedKeys).forEach { key ->
                    val position = getItemKeyPosition(key)
                    if (position != -1) toggleItemSelection(false, position, false)
                }
                updateTitle()
            } else {
                selectAll()
            }
        }
    }

    override fun getSelectableItemCount() = itemCount

    override fun getIsItemSelectable(position: Int) = position in currentList.indices

    override fun getItemSelectionKey(position: Int): Int? =
        currentList.getOrNull(position)?.let { blockedNumberKey(it) }

    override fun getItemKeyPosition(key: Int) =
        currentList.indexOfFirst { blockedNumberKey(it) == key }

    @SuppressLint("NotifyDataSetChanged")
    override fun onActionModeCreated() {
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onActionModeDestroyed() {
        notifyDataSetChanged()
    }

    override fun selectAll() {
        for (i in currentList.indices) {
            toggleItemSelection(true, i, false)
        }
        updateTitle()
    }

    override fun getItemViewType(position: Int) = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContactWithNumberBinding.inflate(layoutInflater, parent, false)
        return BlockedNumberViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (holder is BlockedNumberViewHolder) {
            holder.bind(currentList[position])
        }
        bindViewHolder(holder)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        val payload = payloads.firstOrNull()
        if (payload is RecyclerSelectionPayload && holder is BlockedNumberViewHolder) {
            val inSelection = actModeCallback.isSelectable
            holder.binding.itemContactInfoHolder.isVisible = inSelection
            holder.binding.itemContactCheckbox.isVisible = inSelection
            holder.binding.itemContactCheckbox.isChecked = payload.selected
            holder.itemView.isSelected = payload.selected
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private inner class BlockedNumberViewHolder(
        val binding: ItemContactWithNumberBinding,
    ) : ViewHolder(binding.root) {
        fun bind(blockedNumber: BlockedNumber) = bindView(
            item = blockedNumber,
            allowSingleClick = true,
            allowLongClick = currentList.isNotEmpty(),
        ) { _, _ ->
            refreshContactThumbnailScale()
            val context = binding.root.context
            val primaryTextColor = context.getProperBackgroundColor().getContrastColor()
            val secondaryTextColor = primaryTextColor.adjustAlpha(0.65f)
            val normalTextSize = context.getTextSize()
            val smallTextSize = context.getTextSizeSmall()

            val displayName = blockedNumber.contactName?.takeIf { it.isNotBlank() }
            val displayNumber = blockedNumber.number.takeIf { it.isNotBlank() }
                ?: blockedNumber.normalizedNumber.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.unknown)

            val inSelection = actModeCallback.isSelectable
            val key = blockedNumberKey(blockedNumber)
            val isRowSelected = selectedKeys.contains(key)

            binding.itemContactName.text = displayName ?: displayNumber
            binding.itemContactName.setTextColor(primaryTextColor)
            binding.itemContactName.setTextSize(TypedValue.COMPLEX_UNIT_PX, normalTextSize)
            binding.itemContactName.isVisible = true

            binding.itemContactNumber.text = displayNumber
            binding.itemContactNumber.setTextColor(secondaryTextColor)
            binding.itemContactNumber.setTextSize(TypedValue.COMPLEX_UNIT_PX, smallTextSize)
            binding.itemContactNumber.isVisible = displayName != null

            val nameLp = binding.itemContactName.layoutParams as ConstraintLayout.LayoutParams
            val holderId = binding.itemContactHolder.id
            if (displayName != null) {
                nameLp.bottomToTop = binding.itemContactNumber.id
                nameLp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
                nameLp.verticalBias = 0f
            } else {
                nameLp.bottomToTop = ConstraintLayout.LayoutParams.UNSET
                nameLp.bottomToBottom = holderId
                nameLp.verticalBias = 0.5f
            }
            binding.itemContactName.layoutParams = nameLp

            binding.itemContactImage.isVisible = true
            val avatarPx =
                (activity.resources.getDimensionPixelSize(R.dimen.call_icon_size) * contactThumbnailsSize).toInt()
            binding.itemContactImage.setHeightAndWidth(avatarPx)
            binding.itemContactImage.bind(
                AvatarResolver.resolve(
                    photoUri = null,
                    displayName = displayName ?: displayNumber,
                    preferProfileIconForPhoneIdentity = true,
                ),
                previewMode = true,
            )

            binding.itemContactInfoHolder.isVisible = inSelection
            binding.itemContactCheckbox.isVisible = inSelection
            binding.itemContactCheckbox.isChecked = isRowSelected
            binding.divider.isVisible = true
        }
    }
}
