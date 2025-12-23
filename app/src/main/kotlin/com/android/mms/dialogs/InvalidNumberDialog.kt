package com.android.mms.dialogs

import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.getAlertDialogBuilder
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

        blurView?.setOverlayColor(0xa3ffffff.toInt())
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(8f)
            ?.setBlurAutoUpdate(true)

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.goodwy.commons.R.string.ok) { _, _ -> }
            .apply {
                activity.setupDialogStuff(binding.root, this)
            }
    }
}
