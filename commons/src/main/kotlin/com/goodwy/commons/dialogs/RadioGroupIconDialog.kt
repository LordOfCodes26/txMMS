package com.goodwy.commons.dialogs

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.R
import com.goodwy.commons.databinding.DialogRadioGroupBinding
import com.goodwy.commons.databinding.RadioButtonIconBinding
import com.goodwy.commons.extensions.*
import com.goodwy.commons.models.RadioItem
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

class RadioGroupIconDialog(
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
                RadioButtonIconBinding.inflate(activity.layoutInflater, this, false).apply {
                    dialogRadioButton.apply {
                        text = items[i].title
                        isChecked = items[i].id == checkedItemId
                        id = i
                        setOnClickListener { itemSelected(i) }
                    }
                    dialogRadioButtonIcon.apply {
                        val drawable = items[i].drawable
                        val icon = items[i].icon
                        if (drawable != null) {
                            setImageDrawable(drawable)
                        } else if (icon != null) {
                            setImageResource(icon)
                            setColorFilter(activity.getProperTextColor())
                        }
                    }

                    if (items[i].id == checkedItemId) {
                        selectedItemId = i
                    }
                    addView(root)
                }
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
                    scrollY = view.dialogRadioGroup.bottom - height
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
