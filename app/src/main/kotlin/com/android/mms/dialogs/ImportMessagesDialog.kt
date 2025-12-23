package com.android.mms.dialogs

import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.setupDialogStuff
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.android.mms.R
import com.android.mms.activities.SimpleActivity
import com.android.mms.databinding.DialogImportMessagesBinding
import com.android.mms.extensions.config
import com.android.mms.helpers.MessagesImporter
import com.android.mms.models.ImportResult
import com.android.mms.models.MessagesBackup
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

class ImportMessagesDialog(
    private val activity: SimpleActivity,
    private val messages: List<MessagesBackup>,
    private val blurTarget: BlurTarget,
) {

    private val config = activity.config

    init {
        var ignoreClicks = false
        val binding = DialogImportMessagesBinding.inflate(activity.layoutInflater).apply {
            // Setup BlurView
            val blurView = root.findViewById<eightbitlab.com.blurview.BlurView>(com.android.mms.R.id.blurView)
            val decorView = activity.window.decorView
            val windowBackground = decorView.background
            
            blurView?.setOverlayColor(0xa3ffffff.toInt())
            blurView?.setupWith(blurTarget)
                ?.setFrameClearDrawable(windowBackground)
                ?.setBlurRadius(8f)
                ?.setBlurAutoUpdate(true)
            
            importSmsCheckbox.isChecked = config.importSms
            importMmsCheckbox.isChecked = config.importMms
        }

        binding.importProgress.setIndicatorColor(activity.getProperPrimaryColor())

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.goodwy.commons.R.string.ok, null)
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(
                    view = binding.root,
                    dialog = this,
                    titleId = R.string.import_messages
                ) { alertDialog ->
                    val positiveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    val negativeButton = alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    positiveButton.setOnClickListener {
                        if (ignoreClicks) {
                            return@setOnClickListener
                        }

                        if (!binding.importSmsCheckbox.isChecked && !binding.importMmsCheckbox.isChecked) {
                            activity.toast(R.string.no_option_selected)
                            return@setOnClickListener
                        }

                        ignoreClicks = true
                        activity.toast(com.goodwy.commons.R.string.importing)
                        config.importSms = binding.importSmsCheckbox.isChecked
                        config.importMms = binding.importMmsCheckbox.isChecked

                        alertDialog.setCanceledOnTouchOutside(false)
                        binding.importProgress.show()
                        arrayOf(
                            binding.importMmsCheckbox,
                            binding.importSmsCheckbox,
                            positiveButton,
                            negativeButton
                        ).forEach {
                            it.isEnabled = false
                            it.alpha = 0.6f
                        }

                        ensureBackgroundThread {
                            MessagesImporter(activity).restoreMessages(messages) {
                                handleParseResult(it)
                                alertDialog.dismiss()
                            }
                        }
                    }
                }
            }
    }

    private fun handleParseResult(result: ImportResult) {
        activity.toast(
            when (result) {
                ImportResult.IMPORT_OK -> com.goodwy.commons.R.string.importing_successful
                ImportResult.IMPORT_PARTIAL -> com.goodwy.commons.R.string.importing_some_entries_failed
                ImportResult.IMPORT_FAIL -> com.goodwy.commons.R.string.importing_failed
                else -> com.goodwy.commons.R.string.no_items_found
            }
        )
    }
}
