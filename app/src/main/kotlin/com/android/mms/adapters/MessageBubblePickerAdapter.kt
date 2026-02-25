package com.android.mms.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.android.mms.databinding.ItemMessageBubblePickerBinding
import com.android.mms.helpers.BubbleDrawableOption

class MessageBubblePickerAdapter(
    private val items: List<BubbleDrawableOption>,
    selectedOptionId: Int,
    private val onOptionSelected: (BubbleDrawableOption) -> Unit
) : RecyclerView.Adapter<MessageBubblePickerAdapter.BubbleViewHolder>() {

    private var selectedPosition = items.indexOfFirst { it.id == selectedOptionId }.coerceAtLeast(0)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BubbleViewHolder {
        val binding = ItemMessageBubblePickerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BubbleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BubbleViewHolder, position: Int) {
        val item = items[position]
        with(holder.binding) {
            bubbleIncomingPreview.setImageResource(item.incomingRes)
            bubbleOutgoingPreview.setImageResource(item.outgoingRes)
            bubbleSelected.isChecked = position == selectedPosition

            val clickListener = {
                if (selectedPosition != position) {
                    val previous = selectedPosition
                    selectedPosition = position
                    if (previous != RecyclerView.NO_POSITION) {
                        notifyItemChanged(previous)
                    }
                    notifyItemChanged(position)
                    onOptionSelected(item)
                }
            }

            bubbleCard.setOnClickListener { clickListener() }
            bubbleSelected.setOnClickListener { clickListener() }
        }
    }

    override fun getItemCount(): Int = items.size

    class BubbleViewHolder(val binding: ItemMessageBubblePickerBinding) : RecyclerView.ViewHolder(binding.root)
}
