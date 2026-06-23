package com.android.mms.extensions

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.res.Resources
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.view.View
import androidx.annotation.DrawableRes
import androidx.core.animation.doOnStart
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import com.goodwy.commons.extensions.isRTLLayout
import com.android.mms.R
import com.android.mms.helpers.BUBBLE_STYLE_IOS
import com.android.mms.helpers.BUBBLE_STYLE_IOS_NEW

fun View.showWithAnimation(duration: Long = 250L) {
    if (!isVisible) {
        ObjectAnimator.ofFloat(
            this, "alpha", 0f, 1f
        ).apply {
            this.duration = duration
            doOnStart { visibility = View.VISIBLE }
        }.start()
    }
}

fun Activity.getBubbleContentPadding(bubbleStyle: Int, isReceived: Boolean = true): Rect {
    val isRtl = isRTLLayout
    val paddingTop = resources.getDimensionPixelOffset(com.goodwy.commons.R.dimen.medium_margin)
    val paddingBottomDefault = resources.getDimensionPixelOffset(R.dimen.bubble_padding_bottom)
    val paddingBottomIosNew = resources.getDimensionPixelOffset(com.goodwy.commons.R.dimen.ten_dpi)
    val paddingHorizontal = resources.getDimensionPixelOffset(R.dimen.bubble_padding_bottom)
    val paddingLeftDefault = if (isRtl) {
        resources.getDimensionPixelOffset(R.dimen.bubble_padding_right_ios)
    } else {
        resources.getDimensionPixelOffset(R.dimen.bubble_padding_bottom)
    }
    val paddingRightDefault = if (isRtl) {
        resources.getDimensionPixelOffset(R.dimen.bubble_padding_bottom)
    } else {
        resources.getDimensionPixelOffset(R.dimen.bubble_padding_right_ios)
    }

    return if (isReceived) {
        when (bubbleStyle) {
            BUBBLE_STYLE_IOS -> Rect(paddingRightDefault, paddingTop, paddingLeftDefault, paddingBottomDefault)
            BUBBLE_STYLE_IOS_NEW -> Rect(paddingRightDefault, paddingTop, paddingLeftDefault, paddingBottomIosNew)
            else -> Rect(paddingHorizontal, paddingTop, paddingHorizontal, paddingBottomIosNew)
        }
    } else {
        when (bubbleStyle) {
            BUBBLE_STYLE_IOS -> Rect(paddingLeftDefault, paddingTop, paddingRightDefault, paddingBottomDefault)
            BUBBLE_STYLE_IOS_NEW -> Rect(paddingLeftDefault, paddingTop, paddingRightDefault, paddingBottomIosNew)
            else -> Rect(paddingHorizontal, paddingTop, paddingHorizontal, paddingBottomIosNew)
        }
    }
}

fun View.setPaddingBubble(activity: Activity, bubbleStyle: Int, isReceived: Boolean = true) {
    val padding = activity.getBubbleContentPadding(bubbleStyle, isReceived)
    setPadding(padding.left, padding.top, padding.right, padding.bottom)
}

@DrawableRes
fun Resources.getThreadBubbleRes(@DrawableRes previewRes: Int): Int {
    val baseName = runCatching { getResourceEntryName(previewRes) }.getOrNull() ?: return previewRes
    val compactName = when {
        baseName.startsWith("bubble_incoming_") -> baseName.replaceFirst("bubble_incoming_", "bubble_incoming_compact_")
        baseName.startsWith("bubble_outgoing_") -> baseName.replaceFirst("bubble_outgoing_", "bubble_outgoing_compact_")
        else -> return previewRes
    }
    val compactRes = getIdentifier(compactName, "drawable", getResourcePackageName(previewRes))
    return if (compactRes != 0) compactRes else previewRes
}

fun View.applyCustomBubbleBackground(@DrawableRes previewDrawableRes: Int) {
    minimumHeight = 0
    minimumWidth = 0
    val threadBubbleRes = resources.getThreadBubbleRes(previewDrawableRes)
    val bubbleBackground = ResourcesCompat.getDrawable(resources, threadBubbleRes, context.theme)?.mutate()?.withZeroMinimumSize()
    background = bubbleBackground
    val contentPadding = Rect()
    if (bubbleBackground?.getPadding(contentPadding) == true) {
        setPadding(contentPadding.left, contentPadding.top, contentPadding.right, contentPadding.bottom)
    } else {
        setPadding(0, 0, 0, 0)
    }
}

private fun Drawable.withZeroMinimumSize(): Drawable = object : DrawableWrapper(this) {
    override fun getMinimumWidth(): Int = 0

    override fun getMinimumHeight(): Int = 0
}
