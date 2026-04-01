package com.goodwy.commons.extensions

import android.annotation.SuppressLint
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.widget.EditText
import androidx.core.graphics.ColorUtils
import com.goodwy.commons.R
import com.goodwy.commons.helpers.isQPlus

val EditText.value: String get() = text.toString().trim()

fun EditText.onTextChangeListener(onTextChangedAction: (newText: String) -> Unit) = addTextChangedListener(object : TextWatcher {
    override fun afterTextChanged(s: Editable?) {
        onTextChangedAction(s.toString())
    }

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
})

@SuppressLint("DiscouragedPrivateApi")
fun EditText.applySearchFieldCursorColor() {
    val color = context.getSearchFieldCursorColor()
    if (!isQPlus()) return
    textCursorDrawable = resources.getColoredDrawableWithColor(R.drawable.cursor_text_vertical, color)
    if (!context.baseConfig.isMiui) {
        setTextSelectHandle(resources.getColoredDrawableWithColor(R.drawable.ic_drop_vector, color))
        setTextSelectHandleLeft(resources.getColoredDrawableWithColor(R.drawable.ic_drop_left_vector, color))
        setTextSelectHandleRight(resources.getColoredDrawableWithColor(R.drawable.ic_drop_right_vector, color))
    }
}

fun EditText.highlightText(highlightText: String, color: Int) {
    val content = text.toString()
    var indexOf = content.indexOf(highlightText, 0, true)
    val wordToSpan = text
    var offset = 0

    while (offset < content.length && indexOf != -1) {
        indexOf = content.indexOf(highlightText, offset, true)

        if (indexOf == -1) {
            break
        } else {
            val spanBgColor = BackgroundColorSpan(ColorUtils.setAlphaComponent(color, 128))
            val spanFlag = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            wordToSpan.setSpan(spanBgColor, indexOf, indexOf + highlightText.length, spanFlag)
        }

        offset = indexOf + 1
    }
}
