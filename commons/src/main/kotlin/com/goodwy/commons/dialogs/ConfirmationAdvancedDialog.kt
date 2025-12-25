package com.goodwy.commons.dialogs

import android.app.Activity
import android.text.Html
import android.text.method.LinkMovementMethod
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.goodwy.commons.R
import com.goodwy.commons.compose.alert_dialog.*
import com.goodwy.commons.compose.extensions.MyDevices
import com.goodwy.commons.compose.theme.AppThemeSurface
import com.goodwy.commons.databinding.DialogMessageBinding
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getProperBlurOverlayColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.setupDialogStuff
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

// similar fo ConfirmationDialog, but has a callback for negative button too
class ConfirmationAdvancedDialog(
    activity: Activity, message: String = "", messageId: Int = R.string.proceed_with_deletion, positive: Int = R.string.yes,
    negative: Int = R.string.no, val cancelOnTouchOutside: Boolean = true, blurTarget: BlurTarget, val fromHtml: Boolean = false, val callback: (result: Boolean) -> Unit
) {
    private var dialog: AlertDialog? = null

    init {
        val view = DialogMessageBinding.inflate(activity.layoutInflater, null, false)
        var text = message.ifEmpty { activity.resources.getString(messageId) }
        view.message.text = if (fromHtml) Html.fromHtml(text) else text
        if (fromHtml) view.message.movementMethod = LinkMovementMethod.getInstance()

        // Setup BlurView with the provided BlurTarget
        val blurView = view.blurView
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        blurView.setOverlayColor(activity.getProperBlurOverlayColor())
        blurView.setupWith(blurTarget)
            .setFrameClearDrawable(windowBackground) // Optional: makes background opaque when there's transparent space
            .setBlurRadius(8f) // Blur radius - adjust as needed (typical range: 1-25)
            .setBlurAutoUpdate(true)

        // Setup custom buttons inside BlurView
        val primaryColor = activity.getProperPrimaryColor()
        
        // Access buttons via findViewById in case binding hasn't been regenerated
        val positiveButton = view.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.positive_button)
        val negativeButton = view.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.negative_button)
        val buttonsContainer = view.root.findViewById<android.view.ViewGroup>(R.id.buttons_container)
        
        positiveButton?.apply {
            visibility = android.view.View.VISIBLE
            text = activity.resources.getString(positive)
            setTextColor(primaryColor)
            setOnClickListener { positivePressed() }
        }

        if (negative != 0) {
            negativeButton?.apply {
                visibility = android.view.View.VISIBLE
                text = activity.resources.getString(negative)
                setTextColor(primaryColor)
                setOnClickListener { negativePressed() }
            }
        } else {
            negativeButton?.visibility = android.view.View.GONE
        }
        
        // Ensure buttons container is visible
        buttonsContainer?.visibility = android.view.View.VISIBLE

        val builder = activity.getAlertDialogBuilder()

        if (!cancelOnTouchOutside) {
            builder.setOnCancelListener { negativePressed() }
        }

        builder.apply {
            activity.setupDialogStuff(view.root, this, cancelOnTouchOutside = cancelOnTouchOutside) { alertDialog ->
                dialog = alertDialog
            }
        }
    }

    private fun positivePressed() {
        callback(true)
        dialog?.dismiss()
    }

    private fun negativePressed() {
        callback(false)
        dialog?.dismiss()
    }
}

@Composable
fun ConfirmationAdvancedAlertDialog(
    alertDialogState: AlertDialogState,
    modifier: Modifier = Modifier,
    message: String = "",
    messageId: Int? = R.string.proceed_with_deletion,
    positive: Int? = R.string.yes,
    negative: Int? = R.string.no,
    cancelOnTouchOutside: Boolean = true,
    callback: (result: Boolean) -> Unit
) {

    androidx.compose.material3.AlertDialog(
        containerColor = dialogContainerColor,
        modifier = modifier
            .dialogBorder(),
        properties = DialogProperties(dismissOnClickOutside = cancelOnTouchOutside),
        onDismissRequest = {
            alertDialogState.hide()
            callback(false)
        },
        shape = dialogShape,
        tonalElevation = dialogElevation,
        dismissButton = {
            if (negative != null) {
                TextButton(onClick = {
                    alertDialogState.hide()
                    callback(false)
                }) {
                    Text(text = stringResource(id = negative))
                }
            }
        },
        confirmButton = {
            if (positive != null) {
                TextButton(onClick = {
                    alertDialogState.hide()
                    callback(true)
                }) {
                    Text(text = stringResource(id = positive))
                }
            }
        },
        text = {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = message.ifEmpty { messageId?.let { stringResource(id = it) }.orEmpty() },
                fontSize = 16.sp,
                color = dialogTextColor,
            )
        }
    )
}

@Composable
@MyDevices
private fun ConfirmationAdvancedAlertDialogPreview() {
    AppThemeSurface {
        ConfirmationAdvancedAlertDialog(
            alertDialogState = rememberAlertDialogState()
        ) {}
    }
}
