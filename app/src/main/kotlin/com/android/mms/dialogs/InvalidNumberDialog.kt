package com.android.mms.dialogs

import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getProperBlurOverlayColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.setupDialogStuff
import com.android.mms.databinding.DialogInvalidNumberBinding
import eightbitlab.com.blurview.BlurTarget

class InvalidNumberDialog(val activity: BaseSimpleActivity, val text: String,
                          private val blurTarget: BlurTarget) {
    init {
        val binding = DialogInvalidNumberBinding.inflate(activity.layoutInflater).apply {
            dialogInvalidNumberDesc.text = text
        }

        // Setup BlurView
        val blurView = binding.root.findViewById<eightbitlab.com.blurview.BlurView>(com.android.mms.R.id.blurView)
        val decorView = activity.window.decorView
        val windowBackground = decorView.background

        blurView?.setOverlayColor(activity.getProperBlurOverlayColor())
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(5f)
            ?.setBlurAutoUpdate(true)

        // Setup custom button inside BlurView
        val primaryColor = activity.getProperPrimaryColor()
        val buttonsContainer = binding.root.findViewById<android.widget.LinearLayout>(com.goodwy.commons.R.id.buttons_container)
        val positiveButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(com.goodwy.commons.R.id.positive_button)

        buttonsContainer?.visibility = android.view.View.VISIBLE

        positiveButton?.apply {
            visibility = android.view.View.VISIBLE
            text = activity.resources.getString(com.goodwy.commons.R.string.ok)
            setTextColor(primaryColor)
            setOnClickListener {
                // Dialog will be dismissed by setupDialogStuff
            }
        }

        activity.getAlertDialogBuilder()
            .apply {
                activity.setupDialogStuff(binding.root, this, titleId = 0)
            }
    }
}
