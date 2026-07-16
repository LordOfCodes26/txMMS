package com.goodwy.commons.views

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper

/**
 * EditText that reports backspace/delete when the field is already empty.
 *
 * Soft keyboards usually call [InputConnection.deleteSurroundingText] instead of dispatching
 * [KeyEvent.KEYCODE_DEL], so [OnKeyListener] alone does not remove chips on real devices.
 */
class ChipsEditText : MyEditText {
    var onEmptyDeleteListener: (() -> Boolean)? = null

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle)

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(connection, true) {
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL) {
                    if (tryHandleEmptyDelete()) {
                        return true
                    }
                }
                return super.sendKeyEvent(event)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && afterLength == 0 && tryHandleEmptyDelete()) {
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && afterLength == 0 && tryHandleEmptyDelete()) {
                    return true
                }
                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }
        }
    }

    private fun tryHandleEmptyDelete(): Boolean {
        if (!text.isNullOrEmpty()) return false
        return onEmptyDeleteListener?.invoke() == true
    }
}
