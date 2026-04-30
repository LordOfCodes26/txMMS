package com.goodwy.commons.dialogs

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.RadioGroup
import com.android.common.view.MDialog
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
import com.goodwy.commons.databinding.RadioGroupItemBinding
import com.goodwy.commons.databinding.RadioButtonIconBinding
import com.goodwy.commons.extensions.*
import com.goodwy.commons.models.RadioItem
import eightbitlab.com.blurview.BlurTarget

class RadioGroupDialog(
    val activity: Activity,
    val items: ArrayList<RadioItem>,
    val checkedItemId: Int = -1,
    val titleId: Int = 0,
    showOKButton: Boolean = false,
    val requireConfirmButton: Boolean = false,
    val defaultItemId: Int? = null,
    val cancelCallback: (() -> Unit)? = null,
    blurTarget: BlurTarget,
    val callback: (newValue: Any) -> Unit
) {
    private var dialog: MDialog? = null
    /** Selected row index in [items], not [RadioItem.id]. */
    private var selectedItemId = -1

    init {
        val view = DialogRadioGroupBinding.inflate(activity.layoutInflater, null, false)
        var refreshConfirmUi: (() -> Unit)? = null
        view.dialogRadioGroup.apply {
            for (i in 0 until items.size) {
                val item = items[i]
                val radioGroup = this@apply
                if (item.icon != null || item.drawable != null) {
                    RadioButtonIconBinding.inflate(activity.layoutInflater, radioGroup, false).apply {
                        dialogRadioButton.apply {
                            text = item.title
                            isChecked = item.id == checkedItemId
                            id = i
                            setOnClickListener {
                                radioGroup.check(id)
                                if (requireConfirmButton) {
                                    selectedItemId = i
                                    refreshConfirmUi?.invoke()
                                } else {
                                    itemSelected(i)
                                }
                            }
                        }
                        dialogRadioButtonIcon.apply {
                            val d = item.drawable
                            val ic = item.icon
                            if (d != null) {
                                setImageDrawable(d)
                            } else if (ic != null) {
                                setImageResource(ic)
                                setColorFilter(activity.getProperTextColor())
                            }
                        }
                        if (item.id == checkedItemId) {
                            selectedItemId = i
                        }
                        addView(root)
                    }
                } else {
                    RadioGroupItemBinding.inflate(activity.layoutInflater, radioGroup, false).apply {
                        dialogRadioItemTitle.apply {
                            text = item.title
                            setTextColor(activity.getProperTextColor())
                        }
                        dialogRadioButton.apply {
                            isChecked = item.id == checkedItemId
                            id = i
                        }
                        if (item.id == checkedItemId) {
                            selectedItemId = i
                        }
                        val onRowClick = View.OnClickListener {
                            radioGroup.check(dialogRadioButton.id)
                            if (requireConfirmButton) {
                                selectedItemId = i
                                refreshConfirmUi?.invoke()
                            } else {
                                itemSelected(i)
                            }
                        }
                        root.setOnClickListener(onRowClick)
                        // Nested radio: taps on the circle don't reach the row; route through RadioGroup explicitly.
                        dialogRadioButton.setOnClickListener(onRowClick)
                        radioGroup.addView(root, RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    }
                }
            }
            if (requireConfirmButton && selectedItemId == -1 && items.isNotEmpty()) {
                // Default to first item so confirm is immediately available in explicit-confirm flows.
                selectedItemId = 0
            }
            // RadioButtons are nested under row views; setting isChecked during inflation does not
            // update RadioGroup's mCheckedId. Without this, check() on tap never unchecks the prior row.
            if (selectedItemId != -1) {
                check(selectedItemId)
            }
        }

        val blurView = view.blurView

        // Setup title inside BlurView
        val titleTextView = view.root.findViewById<TextView>(R.id.dialog_title)
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
        val cancelButton = view.root.findViewById<TextView>(com.android.common.R.id.btn_cancel)
        val negativeButton = view.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.negative_button)
        val confirmButton = view.root.findViewById<TextView>(com.android.common.R.id.btn_confirm)
        val positiveButton = view.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.positive_button)
        /** TX layout uses [com.android.common.R.id.btn_confirm]; older layouts use [R.id.positive_button]. */
        val confirmTarget: View? = confirmButton ?: positiveButton
        val neutralButton = view.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.neutral_button)
        val buttonsContainer = view.root.findViewById<android.widget.LinearLayout>(R.id.buttons_container)
        val confirmTitle = activity.getString(R.string.ok)

        when (confirmTarget) {
            is TextView -> confirmTarget.text = confirmTitle
            is com.google.android.material.button.MaterialButton -> confirmTarget.text = confirmTitle
        }

        refreshConfirmUi = {
            if (requireConfirmButton) {
                buttonsContainer?.visibility = View.VISIBLE
                confirmTarget?.apply {
                    visibility = if (selectedItemId != -1) View.VISIBLE else View.GONE
                    if (this is TextView) {
                        setTextColor(primaryColor)
                    } else if (this is com.google.android.material.button.MaterialButton) {
                        setTextColor(primaryColor)
                    }
                }
            }
        }

        // Confirm / OK appearance — click listeners attached after [dialog] is created (see below)
        if (requireConfirmButton) {
            buttonsContainer?.visibility = View.VISIBLE
            confirmTarget?.apply {
                if (this is TextView) {
                    setTextColor(primaryColor)
                } else if (this is com.google.android.material.button.MaterialButton) {
                    setTextColor(primaryColor)
                }
                visibility = if (selectedItemId != -1) View.VISIBLE else View.GONE
            }
        } else if (selectedItemId != -1 && showOKButton) {
            buttonsContainer?.visibility = View.VISIBLE
            confirmTarget?.apply {
                visibility = View.VISIBLE
                if (this is TextView) {
                    setTextColor(primaryColor)
                } else if (this is com.google.android.material.button.MaterialButton) {
                    setTextColor(primaryColor)
                }
            }
        }

        // Setup neutral button (Default)
        if (defaultItemId != null) {
            buttonsContainer?.visibility = View.VISIBLE
            neutralButton?.apply {
                visibility = View.VISIBLE
                setTextColor(primaryColor)
                setOnClickListener {
                    val checkedId = items.indexOfFirst { it.id == defaultItemId }
                    if (checkedId < 0) {
                        return@setOnClickListener
                    }
                    view.dialogRadioGroup.check(checkedId)
                    selectedItemId = checkedId
                    if (requireConfirmButton) {
                        refreshConfirmUi?.invoke()
                    } else {
                        itemSelected(checkedId)
                    }
                }
            }
        }

        activity.setupMDialogStuff(
            view = view.root,
            blurView = blurView,
            blurTarget = blurTarget,
            titleText = "",
            cancelListener = { cancelCallback?.invoke() }
        ) { mDialog ->
            dialog = mDialog
            val cancelTarget: View? = cancelButton ?: negativeButton
            cancelTarget?.setOnClickListener {
                mDialog.dismiss()
            }
            confirmTarget?.apply {
                when {
                    requireConfirmButton -> setOnClickListener {
                        val fromGroup = view.dialogRadioGroup.checkedRadioButtonId
                        val index = when {
                            fromGroup in items.indices -> fromGroup
                            selectedItemId in items.indices -> selectedItemId
                            else -> return@setOnClickListener
                        }
                        callback(items[index].value)
                        mDialog.dismiss()
                    }
                    selectedItemId != -1 && showOKButton -> setOnClickListener {
                        if (selectedItemId in items.indices) {
                            callback(items[selectedItemId].value)
                            mDialog.dismiss()
                        }
                    }
                }
            }
        }

        if (selectedItemId != -1) {
            view.dialogRadioHolder.apply {
                onGlobalLayout {
                    scrollY = view.dialogRadioGroup.findViewById<View>(selectedItemId).bottom - height
                }
            }
        }

    }

    private fun itemSelected(checkedId: Int) {
        if (checkedId !in items.indices) {
            return
        }
        callback(items[checkedId].value)
        dialog?.dismiss()
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
