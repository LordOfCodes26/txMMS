package com.goodwy.commons.views

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import com.android.common.view.MRadioButton
import com.goodwy.commons.extensions.adjustAlpha

open class MyCompatRadioButton : MRadioButton {
    constructor(context: Context) : super(context) {
        init(null)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(attrs)
    }

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle) {
        init(attrs)
    }

    private fun init(attrs: AttributeSet?) {
        attrs?.let {
            val typedArray = context.obtainStyledAttributes(it, intArrayOf(android.R.attr.buttonTint))
            try {
                val buttonTintList = typedArray.getColorStateList(0)
                if (buttonTintList != null) {
                    this.buttonTintList = buttonTintList
                }
            } finally {
                typedArray.recycle()
            }
        }
    }

    @SuppressLint("RestrictedApi")
    fun setColors(textColor: Int, accentColor: Int, backgroundColor: Int) {
        setTextColor(textColor)
        val colorStateList = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_checked)
            ),
            intArrayOf(textColor.adjustAlpha(0.6f), accentColor)
        )
        buttonTintList = colorStateList
    }
}
