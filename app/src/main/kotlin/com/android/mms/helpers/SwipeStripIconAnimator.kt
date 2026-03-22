package com.android.mms.helpers

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.annotation.ColorInt
import androidx.core.view.updateLayoutParams
import com.android.mms.R
import me.thanel.swipeactionview.animation.SwipeActionViewAnimator

/** Progress (0–1) at end of phase 1 (uniform scale-in). Remainder is layout width growth + phase 3. */
private const val PHASE1_END = 0.42f

/** Starting uniform scale for phase 1. */
private const val SCALE_IN_MIN = 0.2f

/** When reveal progress exceeds this, phase 3 slides the pill icon to the opposite horizontal end. */
private const val PHASE3_START = 0.78f

fun prepareSwipeMotionHostPillBackground(motionHost: View, @ColorInt color: Int, cornerRadiusPx: Float) {
    val d = (motionHost.background as? GradientDrawable)?.mutate() as? GradientDrawable
        ?: GradientDrawable().also { motionHost.background = it }
    d.shape = GradientDrawable.RECTANGLE
    d.setColor(color)
    d.cornerRadius = cornerRadiusPx
    motionHost.clipToOutline = true
    motionHost.outlineProvider = ViewOutlineProvider.BACKGROUND
}

private fun View.requestSwipePillLayout() {
    forceLayout()
    requestLayout()
    (parent as? View)?.requestLayout()
}

private fun horizontalIconInsets(iconView: View, motionHost: View): Pair<Int, Int> {
    val fallback = motionHost.resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.normal_margin)
    val lp = iconView.layoutParams as? ViewGroup.MarginLayoutParams ?: return fallback to fallback
    val ms = lp.marginStart
    val me = lp.marginEnd
    return when {
        ms > 0 && me > 0 -> ms to me
        ms > 0 -> ms to ms
        me > 0 -> me to me
        else -> fallback to fallback
    }
}

fun swipeStripMotionHostAnimator(
    motionHost: View,
    holder: View,
    swipeRoot: View,
    marginPx: Float,
    slideTowardContent: Boolean,
    baseWidthPx: Int,
    maxWidthPx: Int,
    @ColorInt pillColor: Int,
    iconView: View,
    phase1EndProgress: Float = PHASE1_END,
    scaleInMin: Float = SCALE_IN_MIN,
    phase3Start: Float = PHASE3_START,
): SwipeActionViewAnimator =
    object : SwipeActionViewAnimator {
        override fun onUpdateSwipeProgress(view: View, progress: Float, minActivationProgress: Float) {
            if (view !== holder) return
            val wParent = holder.width
            if (wParent <= 0 || baseWidthPx <= 0) return

            val stripCap = (wParent - 2 * marginPx).toInt()
            val targetMax = minOf(maxWidthPx, stripCap).coerceAtLeast(baseWidthPx)

            val p = progress.coerceIn(0f, 1f)
            val p1End = phase1EndProgress.coerceIn(0.05f, 0.95f)
            val p3 = phase3Start.coerceIn(0.60f, 0.999f)

            val heightPx = motionHost.height.takeIf { it > 0 }
                ?: motionHost.resources.getDimensionPixelSize(R.dimen.swipe_icon_motion_host_height)
            val cornerR = heightPx / 2f

            if (p <= p1End) {
                motionHost.updateLayoutParams<ViewGroup.LayoutParams> {
                    width = baseWidthPx
                }
                val t = if (p1End > 0f) p / p1End else 1f
                val u = scaleInMin + (1f - scaleInMin) * t
                motionHost.scaleX = u
                motionHost.scaleY = u
            } else {
                val t = (p - p1End) / (1f - p1End)
                val w = (baseWidthPx + (targetMax - baseWidthPx) * t).toInt()
                motionHost.updateLayoutParams<ViewGroup.LayoutParams> {
                    width = w
                }
                motionHost.scaleX = 1f
                motionHost.scaleY = 1f
            }

            prepareSwipeMotionHostPillBackground(motionHost, pillColor, cornerR)
            motionHost.requestSwipePillLayout()
            swipeRoot.requestSwipePillLayout()

            val layoutW = motionHost.width.takeIf { it > 0 } ?: baseWidthPx
            val hHost = motionHost.height.takeIf { it > 0 } ?: heightPx
            motionHost.pivotY = hHost / 2f
            motionHost.pivotX = if (slideTowardContent) 0f else layoutW.toFloat()
            motionHost.translationX = 0f

            val iconW = iconView.width
            val hostW = motionHost.width
            if (p <= p3 || hostW <= 0 || iconW <= 0) {
                iconView.translationX = 0f
            } else {
                val t3 = (p - p3) / (1f - p3)
                val (insetStart, insetEnd) = horizontalIconInsets(iconView, motionHost)
                val pl = motionHost.paddingLeft
                val pr = motionHost.paddingRight
                val targetLeft =
                    if (slideTowardContent) {
                        hostW - pr - insetEnd - iconW
                    } else {
                        pl + insetStart
                    }
                iconView.translationX = t3 * (targetLeft - iconView.left)
            }
        }
    }

fun resetSwipeMotionHostVisuals(motionHost: View, swipeRoot: View, @ColorInt pillColor: Int, iconView: View) {
    motionHost.animate().cancel()
    iconView.animate().cancel()
    motionHost.translationX = 0f
    iconView.translationX = 0f
    motionHost.scaleX = 1f
    motionHost.scaleY = 1f
    val baseW = motionHost.resources.getDimensionPixelSize(R.dimen.swipe_icon_motion_host_width)
    val heightPx = motionHost.resources.getDimensionPixelSize(R.dimen.swipe_icon_motion_host_height)
    motionHost.updateLayoutParams<ViewGroup.LayoutParams> {
        width = baseW
    }
    prepareSwipeMotionHostPillBackground(motionHost, pillColor, heightPx / 2f)
    motionHost.requestSwipePillLayout()
    swipeRoot.requestSwipePillLayout()
    val w = motionHost.width
    val h = motionHost.height
    if (w > 0 && h > 0) {
        motionHost.pivotX = w / 2f
        motionHost.pivotY = h / 2f
    }
}
