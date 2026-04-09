package com.android.mms.emoji

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.View.OnClickListener
import android.view.View.OnTouchListener

/**
 * Long-press repeat (CH350 [RepeatListener](com.chonha.totaldial.ui.listener.chat.RepeatListener)) for backspace.
 */
class RepeatListener(
    private val initialInterval: Int,
    private val normalInterval: Int,
    private val clickListener: OnClickListener,
) : OnTouchListener {
    private val handler = Handler(Looper.getMainLooper())
    private var downView: View? = null

    var soundType: Int = -1

    private val handlerRunnable = object : Runnable {
        override fun run() {
            handler.postDelayed(this, normalInterval.toLong())
            val v = downView ?: return
            clickListener.onClick(v)
        }
    }

    init {
        require(initialInterval >= 0 && normalInterval >= 0) { "negative interval" }
    }

    override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
        when (motionEvent.action) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(handlerRunnable)
                handler.postDelayed(handlerRunnable, initialInterval.toLong())
                downView = view
                view.isPressed = true
                clickListener.onClick(view)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(handlerRunnable)
                downView?.isPressed = false
                downView = null
                return true
            }
        }
        return false
    }
}
