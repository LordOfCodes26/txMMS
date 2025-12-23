package com.goodwy.commons.dialogs

import android.app.Activity
import androidx.appcompat.app.AlertDialog
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
import com.goodwy.commons.compose.theme.AppThemeSurface
import com.goodwy.commons.databinding.DialogMessageBinding
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.setupDialogStuff
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

class PermissionRequiredDialog(
    val activity: Activity,
    textId: Int,
    blurTarget: BlurTarget,
    private val positiveActionCallback: () -> Unit,
    private val negativeActionCallback: (() -> Unit)? = null
) {
    private var dialog: AlertDialog? = null

    init {
        val view = DialogMessageBinding.inflate(activity.layoutInflater, null, false)
        view.message.text = activity.getString(textId)

        // Setup BlurView with the provided BlurTarget
        val blurView = view.blurView
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        blurView.setOverlayColor(0xa3ffffff.toInt())
        blurView.setupWith(blurTarget)
            .setFrameClearDrawable(windowBackground)
            .setBlurRadius(8f)
            .setBlurAutoUpdate(true)

        // Setup custom buttons inside BlurView
        val primaryColor = activity.getProperPrimaryColor()
        
        // Access buttons via findViewById in case binding hasn't been regenerated
        val positiveButton = view.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.positive_button)
        val negativeButton = view.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.negative_button)
        val buttonsContainer = view.root.findViewById<android.widget.LinearLayout>(R.id.buttons_container)
        
        // Ensure buttons container is visible first
        buttonsContainer?.visibility = android.view.View.VISIBLE
        
        if (positiveButton != null) {
            positiveButton.visibility = android.view.View.VISIBLE
            positiveButton.text = activity.resources.getString(R.string.grant_permission)
            positiveButton.setTextColor(primaryColor)
            positiveButton.setOnClickListener {
                dialog?.dismiss()
                positiveActionCallback()
            }
        }

        if (negativeButton != null) {
            negativeButton.visibility = android.view.View.VISIBLE
            negativeButton.text = activity.resources.getString(R.string.cancel)
            negativeButton.setTextColor(primaryColor)
            negativeButton.setOnClickListener {
                dialog?.dismiss()
                negativeActionCallback?.invoke()
            }
        }

        activity.getAlertDialogBuilder().apply {
            val title = activity.getString(R.string.permission_required)
            activity.setupDialogStuff(view.root, this, titleText = title) { alertDialog ->
                dialog = alertDialog
            }
        }
    }
}

@Composable
fun PermissionRequiredAlertDialog(
    alertDialogState: AlertDialogState,
    text: String,
    modifier: Modifier = Modifier,
    negativeActionCallback: (() -> Unit)? = null,
    positiveActionCallback: () -> Unit
) {
    AlertDialog(
        containerColor = dialogContainerColor,
        modifier = modifier
            .dialogBorder(),
        onDismissRequest = alertDialogState::hide,
        shape = dialogShape,
        tonalElevation = dialogElevation,
        dismissButton = {
            TextButton(onClick = {
                alertDialogState.hide()
                negativeActionCallback?.invoke()
            }) {
                Text(text = stringResource(id = R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                alertDialogState.hide()
                positiveActionCallback()
            }) {
                Text(text = stringResource(id = R.string.grant_permission))
            }
        },
        title = {
            Text(
                text = stringResource(id = R.string.permission_required),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                fontSize = 16.sp,
                text = text
            )
        }
    )
}

@Composable
@MyDevices
private fun PermissionRequiredAlertDialogPreview() {
    AppThemeSurface {
        PermissionRequiredAlertDialog(
            alertDialogState = rememberAlertDialogState(),
            text = "Test",
            negativeActionCallback = {}
        ) {}
    }
}
