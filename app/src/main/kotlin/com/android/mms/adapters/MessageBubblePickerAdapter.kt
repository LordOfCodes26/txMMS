package com.android.mms.adapters

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView
import com.android.mms.R
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
            bindBubblePreview(
                textView = bubbleIncomingPreview,
                drawableRes = item.incomingRes,
                previewTextRes = R.string.bubble_preview_incoming,
                minHeightDp = item.minHeightDp
            )
            bindBubblePreview(
                textView = bubbleOutgoingPreview,
                drawableRes = item.outgoingRes,
                previewTextRes = R.string.bubble_preview_outgoing,
                minHeightDp = item.minHeightDp
            )
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

    private fun bindBubblePreview(
        textView: TextView,
        drawableRes: Int,
        previewTextRes: Int,
        minHeightDp: Int
    ) {
        val bubbleDrawable = AppCompatResources.getDrawable(textView.context, drawableRes)
        textView.background = bubbleDrawable
        textView.text = textView.context.getString(previewTextRes)
        textView.minimumHeight = (minHeightDp * textView.resources.displayMetrics.density).toInt()
        applyDrawablePadding(textView, bubbleDrawable)
    }

    private fun applyDrawablePadding(view: TextView, drawable: android.graphics.drawable.Drawable?) {
        if (drawable == null) return
        val defaultPadding = (12 * view.resources.displayMetrics.density).toInt()
        val padding = Rect()
        if (drawable.getPadding(padding)) {
            view.setPadding(
                padding.left.coerceAtLeast(defaultPadding),
                padding.top,
                padding.right.coerceAtLeast(defaultPadding),
                padding.bottom
            )
        } else {
            view.setPadding(defaultPadding, defaultPadding / 2, defaultPadding, defaultPadding / 2)
        }
    }

    class BubbleViewHolder(val binding: ItemMessageBubblePickerBinding) : RecyclerView.ViewHolder(binding.root)
}
