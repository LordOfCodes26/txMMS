package com.goodwy.commons.dialogs

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import com.goodwy.commons.R
import com.goodwy.commons.compose.alert_dialog.AlertDialogState
import com.goodwy.commons.compose.alert_dialog.DialogSurface
import com.goodwy.commons.compose.alert_dialog.dialogTextColor
import com.goodwy.commons.compose.alert_dialog.rememberAlertDialogState
import com.goodwy.commons.compose.components.RadioGroupDialogComponent
import com.goodwy.commons.compose.extensions.BooleanPreviewParameterProvider
import com.goodwy.commons.compose.extensions.MyDevices
import com.goodwy.commons.compose.theme.AppThemeSurface
import com.goodwy.commons.compose.theme.SimpleTheme
import com.goodwy.commons.databinding.DialogRadioGroupBinding
import com.goodwy.commons.extensions.*
import com.goodwy.commons.models.RadioItem
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

class RadioGroupDialog(
    val activity: Activity,
    val items: ArrayList<RadioItem>,
    val checkedItemId: Int = -1,
    val titleId: Int = 0,
    showOKButton: Boolean = false,
    val defaultItemId: Int? = null,
    val cancelCallback: (() -> Unit)? = null,
    blurTarget: BlurTarget,
    val callback: (newValue: Any) -> Unit
) {
    private var dialog: AlertDialog? = null
    private var wasInit = false
    private var selectedItemId = -1

    init {
        val view = DialogRadioGroupBinding.inflate(activity.layoutInflater, null, false)
        view.dialogRadioGroup.apply {
            for (i in 0 until items.size) {
                val radioButton = (activity.layoutInflater.inflate(R.layout.radio_button, null) as RadioButton).apply {
                    text = items[i].title
                    isChecked = items[i].id == checkedItemId
                    id = i
                    setOnClickListener { itemSelected(i) }
                }

                if (items[i].id == checkedItemId) {
                    selectedItemId = i
                }

                addView(radioButton, RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
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
        if (titleId != 0) {
            titleTextView?.apply {
                beVisible()
                text = activity.resources.getString(titleId)
            }
        } else {
            titleTextView?.beGone()
        }

        // Setup custom buttons inside BlurView
        val primaryColor = activity.getProperPrimaryColor()
        val positiveButton = view.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.positive_button)
        val neutralButton = view.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.neutral_button)
        val buttonsContainer = view.root.findViewById<android.widget.LinearLayout>(R.id.buttons_container)

        // Setup positive button (OK)
        if (selectedItemId != -1 && showOKButton) {
            buttonsContainer?.visibility = android.view.View.VISIBLE
            if (positiveButton != null) {
                positiveButton.visibility = android.view.View.VISIBLE
                positiveButton.setTextColor(primaryColor)
                positiveButton.setOnClickListener { itemSelected(selectedItemId) }
            }
        }

        // Setup neutral button (Default)
        if (defaultItemId != null) {
            buttonsContainer?.visibility = android.view.View.VISIBLE
            if (neutralButton != null) {
                neutralButton.visibility = android.view.View.VISIBLE
                neutralButton.setTextColor(primaryColor)
                neutralButton.setOnClickListener {
                    val checkedId = items.indexOfFirst { it.id == defaultItemId }
                    itemSelected(checkedId)
                }
            }
        }

        val builder = activity.getAlertDialogBuilder()
                .setOnCancelListener { cancelCallback?.invoke() }

        builder.apply {
            // Pass empty titleText to prevent setupDialogStuff from adding title outside BlurView
            activity.setupDialogStuff(view.root, this, titleText = "") { alertDialog ->
                dialog = alertDialog
            }
        }

        if (selectedItemId != -1) {
            view.dialogRadioHolder.apply {
                onGlobalLayout {
                    scrollY = view.dialogRadioGroup.findViewById<View>(selectedItemId).bottom - height
                }
            }
        }

        wasInit = true
    }

    private fun itemSelected(checkedId: Int) {
        if (wasInit) {
            callback(items[checkedId].value)
            dialog?.dismiss()
        }
    }
}


@Composable
fun RadioGroupAlertDialog(
    alertDialogState: AlertDialogState,
    items: ImmutableList<RadioItem>,
    modifier: Modifier = Modifier,
    selectedItemId: Int = -1,
    titleId: Int = 0,
    showOKButton: Boolean = false,
    cancelCallback: (() -> Unit)? = null,
    callback: (newValue: Any) -> Unit
) {
    val groupTitles by remember {
        derivedStateOf { items.map { it.title } }
    }
    val (selected, setSelected) = remember { mutableStateOf(items.firstOrNull { it.id == selectedItemId }?.title) }
    val shouldShowOkButton = selectedItemId != -1 && showOKButton
    AlertDialog(
        onDismissRequest = {
            cancelCallback?.invoke()
            alertDialogState.hide()
        },
    ) {
        DialogSurface {
            Box {
                Column(
                    modifier = modifier
                        .padding(bottom = if (shouldShowOkButton) 64.dp else 18.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (titleId != 0) {
                        Text(
                            text = stringResource(id = titleId),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp, bottom = SimpleTheme.dimens.padding.medium)
                                .padding(horizontal = 24.dp),
                            color = dialogTextColor,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    RadioGroupDialogComponent(
                        items = groupTitles,
                        selected = selected,
                        setSelected = { selectedTitle ->
                            setSelected(selectedTitle)
                            callback(getSelectedValue(items, selectedTitle))
                            alertDialogState.hide()
                        },
                        modifier = Modifier.padding(
                            vertical = SimpleTheme.dimens.padding.extraLarge,
                        )
                    )
                }
                if (shouldShowOkButton) {
                    TextButton(
                        onClick = {
                            callback(getSelectedValue(items, selected))
                            alertDialogState.hide()
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                top = SimpleTheme.dimens.padding.extraLarge,
                                bottom = SimpleTheme.dimens.padding.extraLarge,
                                end = SimpleTheme.dimens.padding.extraLarge
                            )
                    ) {
                        Text(text = stringResource(id = R.string.ok))
                    }
                }
            }
        }
    }

}

private fun getSelectedValue(
    items: ImmutableList<RadioItem>,
    selected: String?
) = items.first { it.title == selected }.value

@Composable
@MyDevices
private fun RadioGroupDialogAlertDialogPreview(@PreviewParameter(BooleanPreviewParameterProvider::class) showOKButton: Boolean) {
    AppThemeSurface {
        RadioGroupAlertDialog(
            alertDialogState = rememberAlertDialogState(),
            items = listOf(
                RadioItem(1, "Test"),
                RadioItem(2, "Test 2"),
                RadioItem(3, "Test 3"),
            ).toImmutableList(),
            selectedItemId = 1,
            titleId = R.string.title,
            showOKButton = showOKButton,
            cancelCallback = {}
        ) {}
    }
}
