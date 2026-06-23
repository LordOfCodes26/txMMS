package com.goodwy.commons.extensions

import android.app.Activity
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.android.common.R as CommonR
import com.android.common.dialogs.MConfirmDialog
import eightbitlab.com.blurview.BlurTarget

/** Two-button confirm; [onResult] receives true for confirm, false for cancel. */
fun Activity.showMConfirmBlurDialog(
    blurTarget: BlurTarget,
    message: String,
    confirmTitle: String,
    cancelTitle: String,
    cancelOnTouchOutside: Boolean = true,
    /** When true, matches dark blurred confirm styling (e.g. edit contact). */
    useDarkTheme: Boolean = false,
    onResult: (confirmed: Boolean) -> Unit,
) {
    val dialog = MConfirmDialog(this)
    dialog.bindBlurTarget(blurTarget)
    dialog.setContent(message)
    dialog.setConfirmTitle(confirmTitle)
    dialog.setCancelTitle(cancelTitle)
    dialog.setCanceledOnTouchOutside(cancelOnTouchOutside)
    if (useDarkTheme) {
        dialog.setTheme("dark")
        dialog.findViewById<TextView>(CommonR.id.tv_content)?.setTextColor(
            ContextCompat.getColor(this, CommonR.color.white)
        )
    }
    dialog.setOnCompleteListener { onResult(it) }
    dialog.show()
    trackOpenDialog(dialog)
    if (useDarkTheme) {
        dialog.applyTxBlurFooterForDarkTheme()
    }
}

/** Single primary action (no cancel button setup); [onConfirm] runs only when user confirms. */
fun Activity.showMConfirmBlurDialogSingle(
    blurTarget: BlurTarget,
    message: String,
    confirmTitle: String,
    cancelOnTouchOutside: Boolean = true,
    useDarkTheme: Boolean = false,
    onConfirm: () -> Unit,
) {
    val dialog = MConfirmDialog(this)
    dialog.bindBlurTarget(blurTarget)
    dialog.setContent(message)
    dialog.setConfirmTitle(confirmTitle)
    dialog.setCanceledOnTouchOutside(cancelOnTouchOutside)
    if (useDarkTheme) {
        dialog.setTheme("dark")
        dialog.findViewById<TextView>(CommonR.id.tv_content)?.setTextColor(
            ContextCompat.getColor(this, CommonR.color.white)
        )
    }
    dialog.setOnCompleteListener { if (it) onConfirm() }
    dialog.show()
    trackOpenDialog(dialog)
    if (useDarkTheme) {
        dialog.applyTxBlurFooterForDarkTheme()
    }
}
