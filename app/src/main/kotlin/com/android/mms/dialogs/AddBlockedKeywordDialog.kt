package com.android.mms.dialogs

import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getProperBlurOverlayColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.setupDialogStuff
import com.goodwy.commons.extensions.showKeyboard
import com.goodwy.commons.extensions.value
import com.android.mms.databinding.DialogAddBlockedKeywordBinding
import com.android.mms.extensions.config
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

class AddBlockedKeywordDialog(val activity: BaseSimpleActivity, private val originalKeyword: String? = null, val blurTarget: BlurTarget, val callback: () -> Unit) {
    init {
        val binding = DialogAddBlockedKeywordBinding.inflate(activity.layoutInflater).apply {
            // Setup BlurView
            val blurView = root.findViewById<eightbitlab.com.blurview.BlurView>(com.android.mms.R.id.blurView)
            val decorView = activity.window.decorView
            val windowBackground = decorView.background
            
            blurView?.setOverlayColor(activity.getProperBlurOverlayColor())
            blurView?.setupWith(blurTarget)
                ?.setFrameClearDrawable(windowBackground)
                ?.setBlurRadius(5f)
                ?.setBlurAutoUpdate(true)
            
            if (originalKeyword != null) {
                addBlockedKeywordEdittext.setText(originalKeyword)
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
                val newBlockedKeyword = binding.addBlockedKeywordEdittext.value
                if (originalKeyword != null && newBlockedKeyword != originalKeyword) {
                    activity.config.removeBlockedKeyword(originalKeyword)
                }

                if (newBlockedKeyword.isNotEmpty()) {
                    activity.config.addBlockedKeyword(newBlockedKeyword)
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
                    dialog.showKeyboard(binding.addBlockedKeywordEdittext)
                }
            }
    }
}
