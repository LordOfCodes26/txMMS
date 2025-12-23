package com.goodwy.commons.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.goodwy.commons.R
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.compose.alert_dialog.*
import com.goodwy.commons.compose.extensions.MyDevices
import com.goodwy.commons.compose.theme.AppThemeSurface
import com.goodwy.commons.databinding.DialogOpenDeviceSettingsBinding
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.openDeviceSettings
import com.goodwy.commons.extensions.setupDialogStuff
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

class OpenDeviceSettingsDialog(val activity: BaseSimpleActivity, message: String, blurTarget: BlurTarget) {

    init {
        activity.apply {
            val view = DialogOpenDeviceSettingsBinding.inflate(layoutInflater, null, false)
            view.openDeviceSettings.text = message

            // Setup BlurView with the provided BlurTarget
            val blurView = view.blurView
            val decorView = window.decorView
            val windowBackground = decorView.background
            
            blurView.setOverlayColor(0xa3ffffff.toInt())
            blurView.setupWith(blurTarget)
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(8f)
                .setBlurAutoUpdate(true)

            // Setup custom buttons inside BlurView
            val primaryColor = getProperPrimaryColor()
            val positiveButton = view.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.positive_button)
            val negativeButton = view.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.negative_button)
            val buttonsContainer = view.root.findViewById<android.widget.LinearLayout>(R.id.buttons_container)

            buttonsContainer?.visibility = android.view.View.VISIBLE

            if (positiveButton != null) {
                positiveButton.visibility = android.view.View.VISIBLE
                positiveButton.setTextColor(primaryColor)
                positiveButton.setOnClickListener {
                    openDeviceSettings()
                }
            }

            if (negativeButton != null) {
                negativeButton.visibility = android.view.View.VISIBLE
                negativeButton.setTextColor(primaryColor)
                negativeButton.setOnClickListener { /* dialog will be dismissed on cancel */ }
            }

            getAlertDialogBuilder().apply {
                setupDialogStuff(view.root, this, titleId = 0)
            }
        }
    }
}

@Composable
fun OpenDeviceSettingsAlertDialog(
    alertDialogState: AlertDialogState,
    message: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    AlertDialog(
        containerColor = dialogContainerColor,
        modifier = modifier
            .dialogBorder(),
        onDismissRequest = alertDialogState::hide,
        shape = dialogShape,
        tonalElevation = dialogElevation,
        text = {
            Text(
                fontSize = 16.sp,
                text = message,
                color = dialogTextColor
            )
        },
        dismissButton = {
            TextButton(onClick = alertDialogState::hide) {
                Text(text = stringResource(id = R.string.close))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.openDeviceSettings()
                alertDialogState.hide()
            }) {
                Text(text = stringResource(id = R.string.go_to_settings))
            }
        },
    )
}

@Composable
@MyDevices
private fun OpenDeviceSettingsAlertDialogPreview() {
    AppThemeSurface {
        OpenDeviceSettingsAlertDialog(
            alertDialogState = rememberAlertDialogState(),
            message = "Test dialog"
        )
    }
}
