package com.goodwy.commons.views

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

/**
 * ScrollView that respects [maxHeightPx] when [layout_height] is [wrap_content].
 *
 * Standard ScrollView measures its child with UNSPECIFIED height, so the child
 * grows to full content height and the ScrollView expands past maxHeight.
 * This view clamps the measured height to maxHeight so content scrolls instead.
 *
 * Note: [android.view.View] applies android:maxHeight via [setMaxHeight] during
 * inflation but does not expose a public getter, so we read the attribute here.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    /** Resolved android:maxHeight in pixels; [Int.MAX_VALUE] means no cap. */
    private val maxHeightPx: Int = run {
        if (attrs == null) return@run Int.MAX_VALUE
        val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.maxHeight))
        // Not in XML -> type 0; getDimensionPixelSize then returns default without throwing
        val value = if (a.hasValue(0)) a.getDimensionPixelSize(0, 0) else Int.MAX_VALUE
        a.recycle()
        if (value <= 0) Int.MAX_VALUE else value
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val maxH = maxHeightPx
        if (maxH in 1 until Int.MAX_VALUE && measuredHeight > maxH) {
            setMeasuredDimension(measuredWidth, maxH)
        }
    }
}
