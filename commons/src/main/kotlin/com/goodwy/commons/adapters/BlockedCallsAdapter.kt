package com.goodwy.commons.adapters

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.goodwy.commons.R
import com.goodwy.commons.databinding.ItemRecentCallBinding
import com.goodwy.commons.extensions.adjustAlpha
import com.goodwy.commons.extensions.getContrastColor
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getTextSize
import com.goodwy.commons.extensions.getTextSizeSmall
import com.goodwy.commons.helpers.AvatarResolver

data class BlockedCallItem(
    val callLogId: Long,
    val displayName: String?,
    val phoneNumber: String,
    val timestamp: Long,
    val simId: Int = -1,
    val groupedCount: Int = 1,
)

class BlockedCallsAdapter(
    private val isMultiSimSupported: Boolean,
    private val onItemClick: ((BlockedCallItem) -> Unit)? = null,
) : RecyclerView.Adapter<BlockedCallsAdapter.BlockedCallViewHolder>() {

    private val items = mutableListOf<BlockedCallItem>()

    fun submitList(blockedCalls: List<BlockedCallItem>) {
        items.clear()
        items.addAll(blockedCalls)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BlockedCallViewHolder {
        val binding = ItemRecentCallBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BlockedCallViewHolder(binding, isMultiSimSupported, onItemClick)
    }

    override fun onBindViewHolder(holder: BlockedCallViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    class BlockedCallViewHolder(
        private val binding: ItemRecentCallBinding,
        private val isMultiSimSupported: Boolean,
        private val onItemClick: ((BlockedCallItem) -> Unit)?,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(blockedCall: BlockedCallItem) {
            val context = binding.root.context
            val primaryTextColor = context.getProperBackgroundColor().getContrastColor()
            val secondaryTextColor = primaryTextColor.adjustAlpha(0.65f)
            val normalTextSize = context.getTextSize()
            val smallTextSize = context.getTextSizeSmall()
            val displayName = blockedCall.displayName?.takeIf { it.isNotBlank() }
            val displayNumber = blockedCall.phoneNumber.takeIf { it.isNotBlank() } ?: context.getString(R.string.unknown)

            binding.itemRecentsName.text = displayName ?: displayNumber
            binding.itemRecentsName.setTextColor(primaryTextColor)
            binding.itemRecentsName.setTextSize(TypedValue.COMPLEX_UNIT_PX, normalTextSize)
            binding.itemRecentsName.isVisible = true

            binding.itemRecentsNumber.text = displayNumber
            binding.itemRecentsNumber.setTextColor(secondaryTextColor)
            binding.itemRecentsNumber.setTextSize(TypedValue.COMPLEX_UNIT_PX, smallTextSize)
            binding.itemRecentsNumber.isVisible = true

            // Match the recent-calls look by keeping avatar and call type icon visible.
            binding.itemRecentsImage.isVisible = true
            binding.itemRecentsImage.bind(
                AvatarResolver.resolve(
                    photoUri = null,
                    displayName = displayName ?: displayNumber,
                    preferProfileIconForPhoneIdentity = true
                ),
                previewMode = true
            )

            binding.itemRecentsType.isVisible = true
            binding.itemRecentsType.setImageResource(R.drawable.ic_block_vector)
            binding.itemRecentsType.drawable?.setTint(secondaryTextColor)

            // Show grouped count like RecentCallsAdapter.
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
                android.text.format.DateUtils.MINUTE_IN_MILLIS
            )
            binding.itemRecentsDateTime.setTextColor(secondaryTextColor)
            binding.itemRecentsDateTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, smallTextSize)
            binding.itemRecentsDateTime.isVisible = true
            binding.itemRecentsInfo.isVisible = true
            binding.itemRecentsCheckbox.isVisible = false
            binding.itemRecentsInfoHolder.isVisible = true
            binding.overflowMenuAnchor.isVisible = true
            binding.divider.isVisible = true

            binding.root.setOnClickListener { onItemClick?.invoke(blockedCall) }
            binding.itemRecentsInfoHolder.setOnClickListener { onItemClick?.invoke(blockedCall) }
            binding.overflowMenuAnchor.setOnClickListener { onItemClick?.invoke(blockedCall) }
        }
    }
}
