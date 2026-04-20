package com.goodwy.commons.adapters

import android.annotation.SuppressLint
import android.provider.CallLog
import android.util.TypedValue
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import com.android.common.helper.IconItem
import com.android.common.view.MRippleToolBar
import com.goodwy.commons.R
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.activities.BlockedItemsActivity
import com.goodwy.commons.databinding.ItemRecentCallBinding
import com.goodwy.commons.extensions.adjustAlpha
import com.goodwy.commons.extensions.getContrastColor
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getTextSize
import com.goodwy.commons.extensions.getTextSizeSmall
import com.goodwy.commons.extensions.setHeightAndWidth
import com.goodwy.commons.helpers.AvatarResolver
import com.goodwy.commons.helpers.PERMISSION_WRITE_CALL_LOG
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.models.RecyclerSelectionPayload
import com.goodwy.commons.views.MyRecyclerView
import eightbitlab.com.blurview.BlurTarget

private object BlockedCallItemDiffCallback : DiffUtil.ItemCallback<BlockedCallItem>() {
    override fun areItemsTheSame(oldItem: BlockedCallItem, newItem: BlockedCallItem) =
        selectionKey(oldItem) == selectionKey(newItem)

    override fun areContentsTheSame(oldItem: BlockedCallItem, newItem: BlockedCallItem) = oldItem == newItem
}

private fun selectionKey(item: BlockedCallItem): Int = when {
    item.callLogId > 0L -> (item.callLogId xor (item.callLogId shr 32)).toInt()
    else -> item.phoneNumber.hashCode() xor (item.timestamp xor (item.timestamp shr 32)).toInt()
}

class BlockedCallsListAdapter(
    activity: BaseSimpleActivity,
    recyclerView: MyRecyclerView,
    private val isMultiSimSupported: Boolean,
    private val onOpenItem: (BlockedCallItem) -> Unit,
    onListRefresh: () -> Unit = {},
) : MyRecyclerViewListAdapter<BlockedCallItem>(
    activity = activity,
    recyclerView = recyclerView,
    diffUtil = BlockedCallItemDiffCallback,
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
                    title = activity.getString(R.string.delete)
                    icon = R.drawable.ic_delete_outline
                },
            )
        }
        ripple.setTabs(activity, items, blurTarget)
        ripple.setOnClickedListener { index ->
            if (index == 0) deleteSelectedFromCallLog()
        }
        ripple.visibility = View.VISIBLE
    }

    private fun getSelectedCalls(): List<BlockedCallItem> =
        currentList.filter { selectedKeys.contains(selectionKey(it)) }

    private fun deleteSelectedFromCallLog() {
        val ids = getSelectedCalls().flatMap { it.callLogIdsForDeletion() }.distinct()
        if (ids.isEmpty()) return
        activity.handlePermission(PERMISSION_WRITE_CALL_LOG) { granted ->
            if (!granted) return@handlePermission
            ensureBackgroundThread {
                ids.forEach { id ->
                    try {
                        activity.contentResolver.delete(
                            CallLog.Calls.CONTENT_URI,
                            "${CallLog.Calls._ID} = ?",
                            arrayOf(id.toString()),
                        )
                    } catch (_: Exception) {
                    }
                }
                activity.runOnUiThread {
                    finishActMode()
                    onRefresh.invoke()
                }
            }
        }
    }

    override fun updateTitle() {
        super.updateTitle()
        (activity as? BlockedItemsActivity)?.refreshActionModeRippleToolbarIfNeeded()
    }

    override fun getItemId(position: Int): Long {
        val item = currentList.getOrNull(position) ?: return position.toLong()
        return if (item.callLogId > 0) item.callLogId else selectionKey(item).toLong()
    }

    override fun getActionMenuId() = R.menu.cab_blocked_list
    override fun getMorePopupMenuId() = 0

    override fun prepareActionMode(menu: Menu) {}

    override fun actionItemPressed(id: Int) {
        if (id == R.id.cab_select_all) {
            if (getSelectableItemCount() == selectedKeys.size) {
                (HashSet(selectedKeys)).forEach { key ->
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
        currentList.getOrNull(position)?.let { selectionKey(it) }

    override fun getItemKeyPosition(key: Int) =
        currentList.indexOfFirst { selectionKey(it) == key }

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
        val binding = ItemRecentCallBinding.inflate(layoutInflater, parent, false)
        return BlockedCallViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (holder is BlockedCallViewHolder) {
            holder.bind(currentList[position])
        }
        bindViewHolder(holder)
    }

    private inner class BlockedCallViewHolder(
        val binding: ItemRecentCallBinding,
    ) : ViewHolder(binding.root) {
        fun bind(blockedCall: BlockedCallItem) = bindView(
            item = blockedCall,
            allowSingleClick = true,
            allowLongClick = currentList.isNotEmpty(),
        ) { _, _ ->
            refreshContactThumbnailScale()
            val context = binding.root.context
            val primaryTextColor = context.getProperBackgroundColor().getContrastColor()
            val secondaryTextColor = primaryTextColor.adjustAlpha(0.65f)
            val normalTextSize = context.getTextSize()
            val smallTextSize = context.getTextSizeSmall()
            val displayName = blockedCall.displayName?.takeIf { it.isNotBlank() }
            val displayNumber = blockedCall.phoneNumber.takeIf { it.isNotBlank() } ?: context.getString(R.string.unknown)
            val inSelection = actModeCallback.isSelectable
            val key = selectionKey(blockedCall)
            val isRowSelected = selectedKeys.contains(key)

            binding.itemRecentsName.text = displayName ?: displayNumber
            binding.itemRecentsName.setTextColor(primaryTextColor)
            binding.itemRecentsName.setTextSize(TypedValue.COMPLEX_UNIT_PX, normalTextSize)
            binding.itemRecentsName.isVisible = true

            binding.itemRecentsNumber.text = displayNumber
            binding.itemRecentsNumber.setTextColor(secondaryTextColor)
            binding.itemRecentsNumber.setTextSize(TypedValue.COMPLEX_UNIT_PX, smallTextSize)
            binding.itemRecentsNumber.isVisible = true

            binding.itemRecentsImage.isVisible = true
            val avatarPx =
                (activity.resources.getDimensionPixelSize(R.dimen.call_icon_size) * contactThumbnailsSize).toInt()
            binding.itemRecentsImage.setHeightAndWidth(avatarPx)
            binding.itemRecentsImage.bind(
                AvatarResolver.resolve(
                    photoUri = null,
                    displayName = displayName ?: displayNumber,
                    preferProfileIconForPhoneIdentity = true,
                ),
                previewMode = true,
            )

            binding.itemRecentsType.isVisible = true
            binding.itemRecentsType.setImageResource(R.drawable.ic_block_vector)
            binding.itemRecentsType.drawable?.setTint(secondaryTextColor)

            if (blockedCall.groupedCount > 1) {
                binding.itemRecentsCallCount.text = "(${blockedCall.groupedCount})"
                binding.itemRecentsCallCount.setTextColor(secondaryTextColor)
                binding.itemRecentsCallCount.setTextSize(TypedValue.COMPLEX_UNIT_PX, smallTextSize)
                binding.itemRecentsCallCount.isVisible = true
            } else {
                binding.itemRecentsCallCount.text = ""
                binding.itemRecentsCallCount.isVisible = false
            }

            val showSim = isMultiSimSupported && blockedCall.simId > 0
            binding.itemRecentsSimImage.isVisible = showSim
            binding.itemRecentsSimId.isVisible = showSim
            binding.itemRecentsSimId.text = if (showSim) blockedCall.simId.toString() else ""
            binding.itemRecentsDateTime.text = android.text.format.DateUtils.getRelativeTimeSpanString(
                blockedCall.timestamp,
                System.currentTimeMillis(),
                android.text.format.DateUtils.MINUTE_IN_MILLIS,
            )
            binding.itemRecentsDateTime.setTextColor(secondaryTextColor)
            binding.itemRecentsDateTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, smallTextSize)
            binding.itemRecentsDateTime.isVisible = true

            binding.itemRecentsCheckbox.isVisible = inSelection
            binding.itemRecentsCheckbox.isChecked = isRowSelected

            binding.itemRecentsInfo.isVisible = !inSelection
            binding.itemRecentsInfoHolder.isVisible = true
            binding.overflowMenuAnchor.isVisible = !inSelection
            binding.divider.isVisible = true

            binding.itemRecentsInfoHolder.setOnClickListener {
                if (!inSelection) onOpenItem(blockedCall)
            }
            binding.overflowMenuAnchor.setOnClickListener {
                if (!inSelection) onOpenItem(blockedCall)
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        val payload = payloads.firstOrNull()
        if (payload is RecyclerSelectionPayload && holder is BlockedCallViewHolder) {
            holder.binding.itemRecentsCheckbox.isChecked = payload.selected
            holder.itemView.isSelected = payload.selected
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }
}

