package com.android.mms.extensions

import android.view.View
import androidx.fragment.app.Fragment
import com.android.common.view.MRippleToolBar


private const val MRIPPLE_TAB_ID_BASE = 0x10000
private const val RIPPLE_TAB_ENABLE_ALPHA = 1f
private const val RIPPLE_TAB_DISABLE_ALPHA = 0.5f

fun MRippleToolBar.setRippleTabEnabledWidthAlpha(index: Int, enabled: Boolean) {
    setEnable(index, enabled)
    findViewById<View>(MRIPPLE_TAB_ID_BASE + index)?.alpha =
        if(enabled) RIPPLE_TAB_ENABLE_ALPHA else RIPPLE_TAB_DISABLE_ALPHA
}
