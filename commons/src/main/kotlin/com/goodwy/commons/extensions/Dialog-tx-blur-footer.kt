package com.goodwy.commons.extensions

import android.app.Dialog
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.android.common.R as CommonR

/**
 * [com.android.common] `layout_confirm_tx_blur` pins cancel to [CommonR.color.tx_main_letter] (black).
 * After [com.android.common.view.MDialog.setTheme] / similar `"dark"`, retint the footer cancel action.
 */
fun Dialog.applyTxBlurFooterForDarkTheme() {
    findViewById<TextView>(CommonR.id.btn_cancel)?.setTextColor(
        ContextCompat.getColor(context, CommonR.color.white),
    )
}
