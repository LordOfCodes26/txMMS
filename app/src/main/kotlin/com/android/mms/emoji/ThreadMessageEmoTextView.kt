package com.android.mms.emoji

import android.content.Context
import android.util.AttributeSet
import com.chutils.emo.views.EmoTextView

/**
 * Conversation message body. [EmoTextView] schedules the next animation frame from every
 * [onDraw]; pinch-to-zoom font-size changes invalidate the view on each scale tick and would
 * otherwise stack those delayed invalidates so playback stays faster after the gesture.
 */
class ThreadMessageEmoTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : EmoTextView(context, attrs, defStyleAttr) {

    private var animTickPosted = false
    private val animTick = Runnable {
        animTickPosted = false
        if (isAttachedToWindow) {
            super.invalidate()
        }
    }

    override fun postInvalidateDelayed(delayMilliseconds: Long) {
        if (animTickPosted) {
            return
        }
        animTickPosted = true
        postDelayed(animTick, delayMilliseconds)
    }

    override fun deactivateEmoView() {
        cancelAnimTick()
        super.deactivateEmoView()
    }

    override fun onDetachedFromWindow() {
        cancelAnimTick()
        super.onDetachedFromWindow()
    }

    private fun cancelAnimTick() {
        removeCallbacks(animTick)
        animTickPosted = false
    }
}
