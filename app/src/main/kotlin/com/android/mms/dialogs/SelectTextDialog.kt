package com.android.mms.dialogs

import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.copyToClipboard
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getProperBlurOverlayColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.setupDialogStuff
import com.android.mms.databinding.DialogSelectTextBinding
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import com.android.mms.R

// helper dialog for selecting just a part of a message, not copying the whole into clipboard
class SelectTextDialog(val activity: BaseSimpleActivity, val text: String, blurTarget: BlurTarget) {
    init {
        val binding = DialogSelectTextBinding.inflate(activity.layoutInflater).apply {
            dialogSelectTextValue.text = text
        }
        // Setup BlurView with the provided BlurTarget
        val blurView = binding.root.findViewById<BlurView>(R.id.blurView)
        val decorView = activity.window.decorView
        val windowBackground = decorView.background

        blurView?.setOverlayColor(activity.getProperBlurOverlayColor())
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(5f)
            ?.setBlurAutoUpdate(true)

        // Setup custom buttons inside BlurView
        val primaryColor = activity.getProperPrimaryColor()
        val buttonsContainer = binding.root.findViewById<android.widget.LinearLayout>(com.goodwy.commons.R.id.buttons_container)
        val positiveButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(com.goodwy.commons.R.id.positive_button)
        val neutralButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.neutral_button)

        buttonsContainer?.visibility = android.view.View.VISIBLE

        positiveButton?.apply {
            visibility = android.view.View.VISIBLE
            text = activity.resources.getString(com.goodwy.commons.R.string.ok)
            setTextColor(primaryColor)
            setOnClickListener {
                // Dialog will be dismissed by setupDialogStuff
            }
        }

        neutralButton?.apply {
            visibility = android.view.View.VISIBLE
            this.text = activity.resources.getString(com.goodwy.commons.R.string.copy)
            setTextColor(primaryColor)
            setOnClickListener {
                activity.copyToClipboard(this@SelectTextDialog.text)
            }
        }

        activity.getAlertDialogBuilder()
            .apply {
                activity.setupDialogStuff(binding.root, this, titleId = 0)
            }
    }
}
