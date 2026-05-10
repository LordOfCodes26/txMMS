package com.goodwy.commons.views

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.widget.NestedScrollView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * NestedScrollView with the same rubber-band bounce effect as [MyRecyclerView].
 *
 * When the user pulls beyond the top or bottom edge the view translates elastically;
 * releasing snaps it back via a spring animation matching MyRecyclerView's parameters
 * (dampingRatio=0.7, stiffness=MEDIUM).  A fling that hits an edge also gets a short
 * spring kick so the transition feels natural.
 */
class BouncyNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : NestedScrollView(context, attrs, defStyleAttr) {

    private val springAnim = SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, 0f).apply {
        spring = SpringForce(0f).apply {
            dampingRatio = 0.7f
            stiffness = SpringForce.STIFFNESS_MEDIUM
        }
    }

    private var lastY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            springAnim.cancel()
            lastY = ev.y
        }
        return super.onInterceptTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                springAnim.cancel()
                lastY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - lastY
                lastY = ev.y
                val atTop = !canScrollVertically(-1)
                val atBottom = !canScrollVertically(1)
                if ((atTop && dy > 0) || (atBottom && dy < 0)) {
                    val maxOffset = if (height > 0) height * 0.15f else 100f
                    val translationDelta = dy * 0.25f
                    translationY = (translationY + translationDelta).coerceIn(-maxOffset, maxOffset)
                    spring.cancel()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (translationY != 0f) {
                    springAnim.start()
                }
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun fling(velocityY: Int) {
        super.fling(velocityY)
        if (velocityY == 0) return
        post {
            val atTop = !canScrollVertically(-1)
            val atBottom = !canScrollVertically(1)
            val sign = when {
                atTop && velocityY < 0 -> 1f
                atBottom && velocityY > 0 -> -1f
                else -> 0f
            }
            if (sign != 0f) {
                springAnim.setStartVelocity(sign * kotlin.math.abs(velocityY.toFloat()) * 0.15f)
                springAnim.start()
            }
        }
    }

    // Convenience alias so the cancel call inside ACTION_MOVE compiles without a qualifier clash.
    private val spring get() = springAnim
}
