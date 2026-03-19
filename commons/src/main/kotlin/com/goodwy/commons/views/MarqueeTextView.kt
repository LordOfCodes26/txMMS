package com.goodwy.commons.views

import android.content.Context
import android.util.AttributeSet

/**
 * TextView that reliably runs [android.text.TextUtils.TruncateAt.MARQUEE] ellipsize.
 * Standard [TextView] marquee only runs when focused or selected; inside toolbars /
 * collapsing layouts that often fails. Reporting focused makes the framework start
 * the marquee without stealing actual window focus.
 */
class MarqueeTextView : MyTextView {
    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle)

    override fun isFocused(): Boolean = true
}
