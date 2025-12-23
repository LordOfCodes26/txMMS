package com.android.mms.dialogs

import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.copyToClipboard
import com.goodwy.commons.extensions.getAlertDialogBuilder
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

        blurView?.setOverlayColor(0xa3ffffff.toInt())
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(8f)
            ?.setBlurAutoUpdate(true)

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.goodwy.commons.R.string.ok) { _, _ -> { } }
            .setNeutralButton(com.goodwy.commons.R.string.copy) { _, _ -> activity.copyToClipboard(text) }
            .apply {
                activity.setupDialogStuff(binding.root, this)
            }
    }
}
