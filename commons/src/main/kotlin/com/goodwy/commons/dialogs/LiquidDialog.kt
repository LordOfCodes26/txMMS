package com.goodwy.commons.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.goodwy.commons.compose.alert_dialog.AlertDialogState
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle

@Composable
fun LiquidDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    message: String,
    positiveButtonText: String? = null,
    negativeButtonText: String? = null,
    onPositiveClick: (() -> Unit)? = null,
    onNegativeClick: (() -> Unit)? = null,
    dismissOnClickOutside: Boolean = true,
    dismissOnBackPress: Boolean = true
) {
    val isLightTheme = !isSystemInDarkTheme()
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val accentColor =
        if (isLightTheme) Color(0xFF0088FF)
        else Color(0xFF0091FF)
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.6f)
        else Color(0xFF121212).copy(0.4f)

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = dismissOnClickOutside,
            dismissOnBackPress = dismissOnBackPress
        )
    ) {
        // Capture backdrop from the window behind the dialog
        val backdrop = rememberLayerBackdrop()

        Column(
            modifier
                .padding(40f.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { ContinuousRoundedRectangle(48f.dp) },
                    effects = {
                        colorControls(
                            brightness = if (isLightTheme) 0.2f else 0f,
                            saturation = 1.5f
                        )
                        blur(if (isLightTheme) 16f.dp.toPx() else 8f.dp.toPx())
                        lens(24f.dp.toPx(), 48f.dp.toPx(), depthEffect = true)
                    },
                    highlight = { Highlight.Plain },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .fillMaxWidth()
        ) {
            if (title != null) {
                BasicText(
                    title,
                    Modifier.padding(28f.dp, 24f.dp, 28f.dp, 12f.dp),
                    style = TextStyle(contentColor, 24f.sp, FontWeight.Medium)
                )
            }

            BasicText(
                message,
                Modifier
                    .then(
                        if (isLightTheme) {
                            // plus darker
                            Modifier
                        } else {
                            // plus lighter
                            Modifier.graphicsLayer(blendMode = BlendMode.Plus)
                        }
                    )
                    .padding(24f.dp, 12f.dp, 24f.dp, 12f.dp),
                style = TextStyle(contentColor.copy(0.68f), 15f.sp)
            )

            if (positiveButtonText != null || negativeButtonText != null) {
                Row(
                    Modifier
                        .padding(24f.dp, 12f.dp, 24f.dp, 24f.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16f.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (negativeButtonText != null) {
                        Row(
                            Modifier
                                .clip(ContinuousCapsule)
                                .background(containerColor.copy(0.2f))
                                .clickable {
                                    onNegativeClick?.invoke()
                                    onDismissRequest()
                                }
                                .height(48f.dp)
                                .weight(1f)
                                .padding(horizontal = 16f.dp),
                            horizontalArrangement = Arrangement.spacedBy(4f.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText(
                                negativeButtonText,
                                style = TextStyle(contentColor, 16f.sp)
                            )
                        }
                    }
                    if (positiveButtonText != null) {
                        Row(
                            Modifier
                                .clip(ContinuousCapsule)
                                .background(accentColor)
                                .clickable {
                                    onPositiveClick?.invoke()
                                    onDismissRequest()
                                }
                                .height(48f.dp)
                                .weight(1f)
                                .padding(horizontal = 16f.dp),
                            horizontalArrangement = Arrangement.spacedBy(4f.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText(
                                positiveButtonText,
                                style = TextStyle(Color.White, 16f.sp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * LiquidDialog with AlertDialogState for state management
 */
@Composable
fun LiquidAlertDialog(
    alertDialogState: AlertDialogState,
    modifier: Modifier = Modifier,
    title: String? = null,
    message: String,
    positiveButtonText: String? = null,
    negativeButtonText: String? = null,
    onPositiveClick: (() -> Unit)? = null,
    onNegativeClick: (() -> Unit)? = null,
    dismissOnClickOutside: Boolean = true,
    dismissOnBackPress: Boolean = true
) {
    if (alertDialogState.isShown) {
        LiquidDialog(
            onDismissRequest = {
                alertDialogState.hide()
            },
            modifier = modifier,
            title = title,
            message = message,
            positiveButtonText = positiveButtonText,
            negativeButtonText = negativeButtonText,
            onPositiveClick = onPositiveClick,
            onNegativeClick = onNegativeClick,
            dismissOnClickOutside = dismissOnClickOutside,
            dismissOnBackPress = dismissOnBackPress
        )
    }
}

