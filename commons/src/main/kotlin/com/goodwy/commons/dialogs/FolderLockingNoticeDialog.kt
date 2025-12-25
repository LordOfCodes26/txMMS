package com.goodwy.commons.dialogs

import android.app.Activity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.goodwy.commons.R
import com.goodwy.commons.compose.alert_dialog.*
import com.goodwy.commons.compose.extensions.MyDevices
import com.goodwy.commons.compose.extensions.andThen
import com.goodwy.commons.compose.theme.AppThemeSurface
import com.goodwy.commons.databinding.DialogTextviewBinding
import com.goodwy.commons.extensions.*
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

class FolderLockingNoticeDialog(val activity: Activity, blurTarget: BlurTarget, val callback: () -> Unit) {
    init {
        val view = DialogTextviewBinding.inflate(activity.layoutInflater, null, false).apply {
            textView.text = activity.getString(R.string.lock_folder_notice)
        }

        // Setup BlurView with the provided BlurTarget
        val blurView = view.blurView
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        blurView.setOverlayColor(activity.getProperBlurOverlayColor())
        blurView.setupWith(blurTarget)
            .setFrameClearDrawable(windowBackground)
            .setBlurRadius(8f)
            .setBlurAutoUpdate(true)

        // Setup title inside BlurView
        val titleTextView = view.root.findViewById<com.goodwy.commons.views.MyTextView>(R.id.dialog_title)
        titleTextView?.apply {
            beVisible()
            text = activity.getString(R.string.disclaimer)
        }

        // Setup custom buttons inside BlurView
        val primaryColor = activity.getProperPrimaryColor()
        val positiveButton = view.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.positive_button)
        val negativeButton = view.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.negative_button)
        val buttonsContainer = view.root.findViewById<android.widget.LinearLayout>(R.id.buttons_container)

        buttonsContainer?.visibility = android.view.View.VISIBLE

        if (positiveButton != null) {
            positiveButton.visibility = android.view.View.VISIBLE
            positiveButton.setTextColor(primaryColor)
            positiveButton.setOnClickListener { dialogConfirmed() }
        }

        if (negativeButton != null) {
            negativeButton.beVisible()
            negativeButton.setTextColor(primaryColor)
            negativeButton.setOnClickListener { /* dialog will be dismissed on cancel */ }
        }

        activity.getAlertDialogBuilder().apply {
            // Pass empty titleText to prevent setupDialogStuff from adding title outside BlurView
            activity.setupDialogStuff(view.root, this, titleText = "") { alertDialog ->
                // Store dialog reference for dismissal
            }
        }
    }

    private fun dialogConfirmed() {
        activity.baseConfig.wasFolderLockingNoticeShown = true
        callback()
    }
}

@Composable
fun FolderLockingNoticeAlertDialog(
    alertDialogState: AlertDialogState,
    modifier: Modifier = Modifier,
    callback: () -> Unit
) {
    AlertDialog(
        modifier = modifier.dialogBorder(),
        shape = dialogShape,
        containerColor = dialogContainerColor,
        tonalElevation = dialogElevation,
        onDismissRequest = alertDialogState::hide,
        confirmButton = {
            TextButton(
                onClick = alertDialogState::hide andThen callback
            ) {
                Text(text = stringResource(id = R.string.ok))
            }
        },
        dismissButton = {
            TextButton(
                onClick = alertDialogState::hide
            ) {
                Text(text = stringResource(id = R.string.cancel))
            }
        },
        title = {
            Text(
                text = stringResource(id = R.string.disclaimer),
                color = dialogTextColor,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = stringResource(id = R.string.lock_folder_notice),
                color = dialogTextColor,
            )
        }
    )
}

@Composable
@MyDevices
private fun FolderLockingNoticeAlertDialogPreview() {
    AppThemeSurface {
        FolderLockingNoticeAlertDialog(
            alertDialogState = rememberAlertDialogState(),
            callback = {},
        )
    }
}
