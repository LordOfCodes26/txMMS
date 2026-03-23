package com.android.mms.helpers

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import androidx.annotation.ColorInt
import androidx.core.view.updateLayoutParams
import com.android.mms.R
import me.thanel.swipeactionview.animation.SwipeActionViewAnimator

/** Progress (0–1) at end of phase 1 (uniform scale-in). Remainder is layout width growth + phase 3. */
private const val PHASE1_END = 0.42f

/** Starting uniform scale for phase 1. */
private const val SCALE_IN_MIN = 0.2f

/** When reveal progress exceeds this, phase 3 slides the pill icon to the opposite horizontal end.
 * Lower = longer phase 3 (more of the swipe maps to the cross-end slide). */
private const val PHASE3_START = 0.78f

/**
 * Rounded pill (stadium) matching an oval look; [cornerRadiusPx] should be height/2 for a full capsule.
 * Reuses one [GradientDrawable] so bounds/corners stay in sync when width changes.
 */
fun prepareSwipeMotionHostPillBackground(motionHost: View, @ColorInt color: Int, cornerRadiusPx: Float) {
    val d = (motionHost.background as? GradientDrawable)?.mutate() as? GradientDrawable
        ?: GradientDrawable().also { motionHost.background = it }
    d.shape = GradientDrawable.RECTANGLE
    if (d.color?.defaultColor != color) {
        d.setColor(color)
    }
    if (d.cornerRadius != cornerRadiusPx) {
        d.cornerRadius = cornerRadiusPx
    }
    motionHost.clipToOutline = true
    motionHost.outlineProvider = ViewOutlineProvider.BACKGROUND
}

private fun View.requestSwipePillLayout() {
    requestLayout()
}

/** Matches XML horizontal margins: if only one of start/end is set, use it for both so the slide target stays symmetric. */
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

/**
 * Three-step motion during swipe reveal:
 * 1. Phase 1 — uniform scale-in at fixed [baseWidthPx] (anchored to the seam toward the row).
 * 2. Phase 2 — scale 1; [LayoutParams.width] lerps from [baseWidthPx] toward min([maxWidthPx], strip cap).
 * 3. Phase 3 — when progress > [phase3Start]: [iconView] slides horizontally to the far end of the pill
 *    (left-strip icon → right; right-strip icon → left), using the icon’s layout margins for symmetric insets.
 *
 * [slideTowardContent] true for the left strip (right-swipe reveal); false for the right strip (left-swipe).
 */
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
                    if (width != baseWidthPx) {
                        width = baseWidthPx
                    }
                }
                val t = if (p1End > 0f) p / p1End else 1f
                val u = scaleInMin + (1f - scaleInMin) * t
                motionHost.scaleX = u
                motionHost.scaleY = u
            } else {
                val t = (p - p1End) / (1f - p1End)
                val w = (baseWidthPx + (targetMax - baseWidthPx) * t).toInt()
                motionHost.updateLayoutParams<ViewGroup.LayoutParams> {
                    if (width != w) {
                        width = w
                    }
                }
                motionHost.scaleX = 1f
                motionHost.scaleY = 1f
            }

            prepareSwipeMotionHostPillBackground(motionHost, pillColor, cornerR)

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
        if (width != baseW) {
            width = baseW
        }
    }
    prepareSwipeMotionHostPillBackground(motionHost, pillColor, heightPx / 2f)
    motionHost.requestSwipePillLayout()
    val w = motionHost.width
    val h = motionHost.height
    if (w > 0 && h > 0) {
        motionHost.pivotX = w / 2f
        motionHost.pivotY = h / 2f
    }
}

fun animateResetSwipeMotionHostVisuals(
    motionHost: View,
    swipeRoot: View,
    @ColorInt pillColor: Int,
    iconView: View,
    durationMs: Long = 150L,
) {
    val baseW = motionHost.resources.getDimensionPixelSize(R.dimen.swipe_icon_motion_host_width)
    val heightPx = motionHost.resources.getDimensionPixelSize(R.dimen.swipe_icon_motion_host_height)
    val interpolator = DecelerateInterpolator()

    motionHost.animate().cancel()
    iconView.animate().cancel()

    iconView.animate()
        .translationX(0f)
        .setDuration(durationMs)
        .setInterpolator(interpolator)
        .start()

    motionHost.animate()
        .translationX(0f)
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(durationMs)
        .setInterpolator(interpolator)
        .withEndAction {
            motionHost.updateLayoutParams<ViewGroup.LayoutParams> {
                if (width != baseW) {
                    width = baseW
                }
            }
            prepareSwipeMotionHostPillBackground(motionHost, pillColor, heightPx / 2f)
            motionHost.requestSwipePillLayout()
            swipeRoot.requestLayout()
            val w = motionHost.width
            val h = motionHost.height
            if (w > 0 && h > 0) {
                motionHost.pivotX = w / 2f
                motionHost.pivotY = h / 2f
            }
        }
        .start()
}
