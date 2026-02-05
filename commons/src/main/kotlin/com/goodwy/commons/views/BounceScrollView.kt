package com.goodwy.commons.views

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.Interpolator
import androidx.annotation.FloatRange
import androidx.core.widget.NestedScrollView
import com.goodwy.commons.R

class BounceScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    companion object {
        private const val DEFAULT_DAMPING_COEFFICIENT = 4.0f
        private const val DEFAULT_SCROLL_THRESHOLD = 20
        private const val DEFAULT_BOUNCE_DELAY = 400L
    }

    var isHorizontal: Boolean = false
        set(value) { field = value }
        get() = field

    var damping: Float = DEFAULT_DAMPING_COEFFICIENT
        set(value) { if (value > 0) field = value }
        @FloatRange(from = 0.0, to = 100.0) get() = field

    var incrementalDamping: Boolean = true
        set(value) { field = value }
        get() = field

    var bounceDelay: Long = DEFAULT_BOUNCE_DELAY
        set(value) { if (value >= 0) field = value }
        get() = field

    var triggerOverScrollThreshold: Int = DEFAULT_SCROLL_THRESHOLD
        set(value) { if (value >= 0) field = value }
        get() = field

    var disableBounce: Boolean = false
        set(value) { field = value }
        get() = field

    private var isTouching = false
    private var isDraggingDown = false

    private var interpolator: Interpolator = DefaultQuartOutInterpolator()
    private var childView: View? = null
    private var start = 0f
    private var preDelta = 0
    private var overScrolledDistance = 0
    private var animator: ObjectAnimator? = null

    var onScrollListener: OnScrollListener? = null
    var onOverScrollListener: OnOverScrollListener? = null

    init {
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER

        attrs?.let { attributeSet ->
            context.obtainStyledAttributes(attributeSet, R.styleable.BounceScrollView, 0, 0).apply {
                damping = getFloat(R.styleable.BounceScrollView_damping, DEFAULT_DAMPING_COEFFICIENT)
                isHorizontal = getInt(R.styleable.BounceScrollView_scrollOrientation, 0) == 1
                incrementalDamping = getBoolean(R.styleable.BounceScrollView_incrementalDamping, true)
                bounceDelay = getInt(R.styleable.BounceScrollView_bounceDelay, DEFAULT_BOUNCE_DELAY.toInt()).toLong()
                triggerOverScrollThreshold = getInt(R.styleable.BounceScrollView_triggerOverScrollThreshold, DEFAULT_SCROLL_THRESHOLD)
                disableBounce = getBoolean(R.styleable.BounceScrollView_disableBounce, false)
                isNestedScrollingEnabled = getBoolean(R.styleable.BounceScrollView_nestedScrollingEnabled, true)
                recycle()
            }
        }
    }

    override fun canScrollVertically(direction: Int): Boolean = !isHorizontal

    override fun canScrollHorizontally(direction: Int): Boolean = isHorizontal

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (childView == null && childCount > 0 || childView != getChildAt(0)) {
            childView = getChildAt(0)
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val child = childView
        if (child == null || disableBounce) return super.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                start = if (isHorizontal) ev.x else ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                val now = if (isHorizontal) ev.x else ev.y
                val delta = start - now
                val dampingDelta = (delta / calculateDamping(child)).toInt()
                start = now

                val onePointerTouch = when {
                    preDelta <= 0 && dampingDelta > 0 -> false
                    preDelta >= 0 && dampingDelta < 0 -> false
                    else -> true
                }
                preDelta = dampingDelta

                if (onePointerTouch && canMove(dampingDelta, child)) {
                    overScrolledDistance += dampingDelta
                    if (isHorizontal) {
                        child.translationX = -overScrolledDistance.toFloat()
                    } else {
                        child.translationY = -overScrolledDistance.toFloat()
                    }
                    isDraggingDown = overScrolledDistance <= 0
                    onOverScrollListener?.onOverScrolling(
                        overScrolledDistance <= 0,
                        kotlin.math.abs(overScrolledDistance)
                    )
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                preDelta = 0
                overScrolledDistance = 0

                cancelAnimator()
                animator = if (isHorizontal) {
                    ObjectAnimator.ofFloat(child, View.TRANSLATION_X, 0f)
                } else {
                    ObjectAnimator.ofFloat(child, View.TRANSLATION_Y, 0f)
                }
                animator?.apply {
                    duration = bounceDelay
                    setInterpolator(interpolator)
                    onOverScrollListener?.let { listener ->
                        addUpdateListener { animation ->
                            val value = (animation.animatedValue as Float)
                            listener.onOverScrolling(value <= 0, kotlin.math.abs(value.toInt()))
                        }
                    }
                    start()
                }
            }
        }

        when (ev.action) {
            MotionEvent.ACTION_MOVE -> isTouching = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isTouching = false
        }
        return super.onTouchEvent(ev)
    }

    fun isTouching(): Boolean = isTouching

    fun isDraggingDown(): Boolean = isDraggingDown

    private fun calculateDamping(child: View): Float {
        val ratio = if (isHorizontal) {
            kotlin.math.abs(child.translationX) / child.measuredWidth
        } else {
            kotlin.math.abs(child.translationY) / child.measuredHeight
        } + 0.2f

        return if (incrementalDamping) {
            damping / (1.0f - ratio.pow(2))
        } else {
            damping
        }
    }

    private fun Float.pow(n: Int): Float = Math.pow(this.toDouble(), n.toDouble()).toFloat()

    private fun canMove(delta: Int, child: View): Boolean =
        if (delta < 0) canMoveFromStart() else canMoveFromEnd(child)

    private fun canMoveFromStart(): Boolean =
        if (isHorizontal) scrollX == 0 else scrollY == 0

    private fun canMoveFromEnd(child: View): Boolean {
        return if (isHorizontal) {
            val offset = (child.measuredWidth - width).coerceAtLeast(0)
            scrollX == offset
        } else {
            val offset = (child.measuredHeight - height).coerceAtLeast(0)
            scrollY == offset
        }
    }

    private fun cancelAnimator() {
        animator?.takeIf { it.isRunning }?.cancel()
    }

    override fun onScrollChanged(scrollX: Int, scrollY: Int, oldX: Int, oldY: Int) {
        super.onScrollChanged(scrollX, scrollY, oldX, oldY)
        onScrollListener?.onScrolling(scrollX, scrollY)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAnimator()
    }

    fun setBounceInterpolator(interpolator: Interpolator) {
        this.interpolator = interpolator
    }

    interface OnScrollListener {
        fun onScrolling(scrollX: Int, scrollY: Int)
    }

    interface OnOverScrollListener {
        /**
         * @param fromStart LTR, the left is start; RTL, the right is start.
         */
        fun onOverScrolling(fromStart: Boolean, overScrolledDistance: Int)
    }

    private class DefaultQuartOutInterpolator : Interpolator {
        override fun getInterpolation(input: Float): Float =
            (1.0f - Math.pow((1 - input).toDouble(), 4.0).toFloat())
    }
}
