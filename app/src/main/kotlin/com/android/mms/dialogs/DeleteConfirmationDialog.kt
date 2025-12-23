package com.android.mms.dialogs

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.extensions.beGoneIf
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.setupDialogStuff
import com.android.mms.databinding.DialogDeleteConfirmationBinding
import eightbitlab.com.blurview.BlurTarget

class DeleteConfirmationDialog(
    private val activity: Activity,
    private val message: String,
    private val showSkipRecycleBinOption: Boolean,
    blurTarget: BlurTarget,
    private val callback: (skipRecycleBin: Boolean) -> Unit
) {

    private var dialog: AlertDialog? = null
    val binding = DialogDeleteConfirmationBinding.inflate(activity.layoutInflater)

    init {
        // Setup BlurView
        val blurView = binding.root.findViewById<eightbitlab.com.blurview.BlurView>(com.android.mms.R.id.blurView)
        val decorView = activity.window.decorView
        val windowBackground = decorView.background

        blurView?.setOverlayColor(0xa3ffffff.toInt())
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(8f)
            ?.setBlurAutoUpdate(true)

        binding.deleteRememberTitle.text = message
        binding.skipTheRecycleBinCheckbox.beGoneIf(!showSkipRecycleBinOption)
        activity.getAlertDialogBuilder()
            .setPositiveButton(com.goodwy.commons.R.string.yes) { _, _ -> dialogConfirmed() }
            .setNegativeButton(com.goodwy.commons.R.string.no, null)
            .apply {
                activity.setupDialogStuff(binding.root, this) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }

    private fun dialogConfirmed() {
        dialog?.dismiss()
        callback(binding.skipTheRecycleBinCheckbox.isChecked)
    }
}
