package com.goodwy.commons.extensions

import android.app.Activity
import com.android.common.dialogs.MConfirmDialog
import eightbitlab.com.blurview.BlurTarget

/** Two-button confirm; [onResult] receives true for confirm, false for cancel. */
fun Activity.showMConfirmBlurDialog(
    blurTarget: BlurTarget,
    message: String,
    confirmTitle: String,
    cancelTitle: String,
    cancelOnTouchOutside: Boolean = true,
    onResult: (confirmed: Boolean) -> Unit,
) {
    val dialog = MConfirmDialog(this)
    dialog.bindBlurTarget(blurTarget)
    dialog.setContent(message)
    dialog.setConfirmTitle(confirmTitle)
    dialog.setCancelTitle(cancelTitle)
    dialog.setCanceledOnTouchOutside(cancelOnTouchOutside)
    dialog.setOnCompleteListener { onResult(it) }
    dialog.show()
}

/** Single primary action (no cancel button setup); [onConfirm] runs only when user confirms. */
fun Activity.showMConfirmBlurDialogSingle(
    blurTarget: BlurTarget,
    message: String,
    confirmTitle: String,
    cancelOnTouchOutside: Boolean = true,
    onConfirm: () -> Unit,
) {
    val dialog = MConfirmDialog(this)
    dialog.bindBlurTarget(blurTarget)
    dialog.setContent(message)
    dialog.setConfirmTitle(confirmTitle)
    dialog.setCanceledOnTouchOutside(cancelOnTouchOutside)
    dialog.setOnCompleteListener { if (it) onConfirm() }
    dialog.show()
}
