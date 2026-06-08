package com.android.mms.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.layoutDirection
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.android.mms.R
import com.android.mms.activities.SimpleActivity
import com.android.mms.databinding.ItemSimMessageBinding
import com.android.mms.extensions.config
import com.android.mms.extensions.setPaddingBubble
import com.android.mms.helpers.BUBBLE_STYLE_IOS
import com.android.mms.helpers.BUBBLE_STYLE_IOS_NEW
import com.android.mms.helpers.BUBBLE_STYLE_ROUNDED
import com.android.mms.helpers.getBubbleDrawableOption
import com.android.mms.models.SimMessage
import com.goodwy.commons.extensions.applyColorFilter
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.formatDateOrTime
import com.goodwy.commons.extensions.formatTime
import com.goodwy.commons.extensions.getLetterBackgroundColors
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isRTLLayout
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.helpers.DARK_GREY
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class SimMessageAdapter(
    private val activity: SimpleActivity,
    private val messages: List<SimMessage>,
    private val onLongClick: (SimMessage, View) -> Unit
) : RecyclerView.Adapter<SimMessageAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSimMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    inner class ViewHolder(private val binding: ItemSimMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("SetTextI18n")
        fun bind(message: SimMessage) {
            val timeStr = formatTime(message.date)

            if (message.isIncoming) {
                binding.simMsgReceivedContainer.beVisible()
                binding.simMsgSentContainer.beGone()
                binding.simMsgBody.text = message.address + " : " + message.body
                binding.simMsgTime.text = timeStr
                setupReceivedBubble(binding.simMsgBubbleReceived)

            } else {
                binding.simMsgSentContainer.beVisible()
                binding.simMsgReceivedContainer.beGone()
                binding.simMsgBodySent.text = message.body
                binding.simMsgTimeSent.text = timeStr
                setupSendBubble(binding.simMsgBubbleSent)
            }

            binding.root.setOnLongClickListener {
                onLongClick(message, itemView)
                true
            }
        }

    }

    private fun formatTime(millis: Long): String {
        return (millis).formatDateOrTime(
            context = activity,
            hideTimeOnOtherDays = false,
            showCurrentYear = true,
            hideTodaysDate = false,
            dateFormat = "yyyy.M.d",
            timeFormat = "HH:mm"
        )

//        val date = Date(millis * 1000L)
//        val dateFormat = DateFormat.getDateFormat(activity)
//        val timeFormat = DateFormat.getTimeFormat(activity)
//        val now = System.currentTimeMillis()
//        val diff = now - millis
//        val oneDayMs = 24 * 60 * 60 * 1000L
//        return if (diff < oneDayMs) {
//            timeFormat.format(date)
//        } else {
//            "${dateFormat.format(date)} ${timeFormat.format(date)}"
//        }
    }

    @DrawableRes
    private fun getCompactBubbleRes(@DrawableRes baseRes: Int): Int {
        val baseName = runCatching { activity.resources.getResourceEntryName(baseRes) }.getOrNull() ?: return baseRes
        val compactName = when {
            baseName.startsWith("bubble_incoming_") -> baseName.replaceFirst("bubble_incoming_", "bubble_incoming_compact_")
            baseName.startsWith("bubble_outgoing_") -> baseName.replaceFirst("bubble_outgoing_", "bubble_outgoing_compact_")
            else -> return baseRes
        }
        val compactRes = activity.resources.getIdentifier(compactName, "drawable", activity.packageName)
        return if (compactRes != 0) compactRes else baseRes
    }

    private fun View.applyDrawablePadding(drawable: Drawable?) {
        if (drawable == null) return
        val padding = Rect()
        if (drawable.getPadding(padding)) {
            setPadding(padding.left, padding.top, padding.right, padding.bottom)
        }
    }

    private fun setupReceivedBubble(bubbleWrapper: LinearLayout) {
        val letterBackgroundColors = activity.getLetterBackgroundColors()
        val primaryOrSenderColor =
            if (activity.config.bubbleInContactColor) letterBackgroundColors[0].toInt()
            else activity.getProperPrimaryColor()
        val useSurfaceColor = activity.isDynamicTheme() && !activity.isSystemInDarkMode()
        val surfaceColor = if (useSurfaceColor) activity.getProperBackgroundColor() else activity.getSurfaceColor()
        val backgroundReceived = if (activity.config.bubbleInvertColor) primaryOrSenderColor else surfaceColor
        val selectedBubbleOption = getBubbleDrawableOption(activity.config.bubbleDrawableSet)
        val isRtl = activity.isRTLLayout
        val bubbleStyle = activity.config.bubbleStyle

        val bubbleReceived = if (selectedBubbleOption != null) {
            if (isRtl) getCompactBubbleRes(selectedBubbleOption.outgoingRes) else getCompactBubbleRes(selectedBubbleOption.incomingRes)
        } else {
            when (bubbleStyle) {
                BUBBLE_STYLE_IOS_NEW -> if (isRtl) R.drawable.item_sent_ios_new_background else R.drawable.item_received_ios_new_background
                BUBBLE_STYLE_IOS -> if (isRtl) R.drawable.item_sent_ios_background else R.drawable.item_received_ios_background
                BUBBLE_STYLE_ROUNDED -> if (isRtl) R.drawable.item_sent_rounded_background else R.drawable.item_received_rounded_background
                else -> if (isRtl) R.drawable.item_sent_background else R.drawable.item_received_background
            }
        }
        val bubbleDrawable = ResourcesCompat.getDrawable(activity.resources, bubbleReceived, activity.theme)
        bubbleWrapper.background = bubbleDrawable
        bubbleWrapper.minimumHeight = 0
        if (selectedBubbleOption == null) {
            bubbleWrapper.setPaddingBubble(activity, bubbleStyle)
            bubbleWrapper.background.applyColorFilter(backgroundReceived)
        } else {
            bubbleWrapper.applyDrawablePadding(bubbleDrawable)
        }
    }

    private fun setupSendBubble(bubbleWrapper: LinearLayout) {
        val letterBackgroundColors = activity.getLetterBackgroundColors()
        val primaryOrSenderColor = if (activity.config.bubbleInContactColor) letterBackgroundColors[0].toInt()
        else activity.getProperPrimaryColor()
        val useSurfaceColor = activity.isDynamicTheme() && !activity.isSystemInDarkMode()
        val surfaceColor = if (useSurfaceColor) activity.getProperBackgroundColor() else activity.getSurfaceColor()
        val backgroundReceived = if (activity.config.bubbleInvertColor) surfaceColor else primaryOrSenderColor
        val selectedBubbleOption = getBubbleDrawableOption(activity.config.bubbleDrawableSet)
        val isRtl = Locale.getDefault().layoutDirection == ViewCompat.LAYOUT_DIRECTION_RTL
        val bubbleStyle = activity.config.bubbleStyle

        val bubbleReceived = if (selectedBubbleOption != null) {
            if (isRtl) getCompactBubbleRes(selectedBubbleOption.incomingRes) else getCompactBubbleRes(selectedBubbleOption.outgoingRes)
        } else {
            when (bubbleStyle) {
                BUBBLE_STYLE_IOS_NEW -> if (isRtl) R.drawable.item_received_ios_new_background else R.drawable.item_sent_ios_new_background
                BUBBLE_STYLE_IOS -> if (isRtl) R.drawable.item_received_ios_background else R.drawable.item_sent_ios_background
                BUBBLE_STYLE_ROUNDED -> if (isRtl) R.drawable.item_received_rounded_background else R.drawable.item_sent_rounded_background
                else -> if (isRtl) R.drawable.item_received_background else R.drawable.item_sent_background
            }
        }
        val bubbleDrawable = AppCompatResources.getDrawable(activity, bubbleReceived)
        bubbleWrapper.background = bubbleDrawable
        bubbleWrapper.minimumHeight = 0
        if (selectedBubbleOption == null) {
            bubbleWrapper.setPaddingBubble(activity, bubbleStyle, false)
            bubbleWrapper.background.applyColorFilter(backgroundReceived)
        } else {
            // Respect 9-patch content padding when using custom bubble drawables.
            bubbleWrapper.applyDrawablePadding(bubbleDrawable)
        }
    }
}
