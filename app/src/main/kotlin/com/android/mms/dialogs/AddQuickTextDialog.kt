package com.android.mms.dialogs

import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getProperBlurOverlayColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.setupDialogStuff
import com.goodwy.commons.extensions.showKeyboard
import com.goodwy.commons.extensions.value
import com.android.mms.databinding.DialogAddQuickTextBinding
import com.android.mms.extensions.config
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

class AddQuickTextDialog(val activity: BaseSimpleActivity, private val originalText: String? = null, val blurTarget: BlurTarget, val callback: () -> Unit) {
    init {
        val binding = DialogAddQuickTextBinding.inflate(activity.layoutInflater).apply {
            // Setup BlurView
            val blurView = root.findViewById<eightbitlab.com.blurview.BlurView>(com.android.mms.R.id.blurView)
            val decorView = activity.window.decorView
            val windowBackground = decorView.background
            
            blurView?.setOverlayColor(activity.getProperBlurOverlayColor())
            blurView?.setupWith(blurTarget)
                ?.setFrameClearDrawable(windowBackground)
                ?.setBlurRadius(8f)
                ?.setBlurAutoUpdate(true)
            
            if (originalText != null) {
                addQuickTextEdittext.setText(originalText)
            }
        }

        // Setup custom buttons inside BlurView
        val primaryColor = activity.getProperPrimaryColor()
        val buttonsContainer = binding.root.findViewById<android.widget.LinearLayout>(com.goodwy.commons.R.id.buttons_container)
        val positiveButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(com.goodwy.commons.R.id.positive_button)
        val negativeButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(com.goodwy.commons.R.id.negative_button)

        buttonsContainer?.visibility = android.view.View.VISIBLE

        var alertDialog: AlertDialog? = null

        positiveButton?.apply {
            visibility = android.view.View.VISIBLE
            text = activity.resources.getString(com.goodwy.commons.R.string.ok)
            setTextColor(primaryColor)
            setOnClickListener {
                val newQuickText = binding.addQuickTextEdittext.value
                if (originalText != null && newQuickText != originalText) {
                    activity.config.removeQuickText(originalText)
                }

                if (newQuickText.isNotEmpty()) {
                    activity.config.addQuickText(newQuickText)
                }

                callback()
                alertDialog?.dismiss()
            }
        }

        negativeButton?.apply {
            visibility = android.view.View.VISIBLE
            text = activity.resources.getString(com.goodwy.commons.R.string.cancel)
            setTextColor(primaryColor)
            setOnClickListener {
                alertDialog?.dismiss()
            }
        }

        activity.getAlertDialogBuilder()
            .apply {
                activity.setupDialogStuff(binding.root, this, titleId = 0) { dialog ->
                    alertDialog = dialog
                    dialog.showKeyboard(binding.addQuickTextEdittext)
                }
            }
    }
}

