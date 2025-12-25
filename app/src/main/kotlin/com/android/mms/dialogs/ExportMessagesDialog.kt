package com.android.mms.dialogs

import android.annotation.SuppressLint
import android.net.Uri
import android.provider.DocumentsContract
import androidx.appcompat.app.AlertDialog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getCurrentFormattedDateTime
import com.goodwy.commons.extensions.getProperBlurOverlayColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.isAValidFilename
import com.goodwy.commons.extensions.setupDialogStuff
import com.goodwy.commons.extensions.showErrorToast
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.extensions.value
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.android.mms.R
import com.android.mms.activities.SimpleActivity
import com.android.mms.databinding.DialogExportMessagesBinding
import com.android.mms.extensions.config
import com.android.mms.helpers.MessagesReader
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

class ExportMessagesDialog(
    private val activity: SimpleActivity,
    private val blurTarget: BlurTarget,
    private val callback: (fileName: String) -> Unit,
) {
    private val config = activity.config
    private var dialog: AlertDialog? = null

    @SuppressLint("SetTextI18n")
    private val binding = DialogExportMessagesBinding.inflate(activity.layoutInflater).apply {
        // Setup BlurView
        val blurView = root.findViewById<eightbitlab.com.blurview.BlurView>(com.android.mms.R.id.blurView)
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        blurView?.setOverlayColor(activity.getProperBlurOverlayColor())
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(8f)
            ?.setBlurAutoUpdate(true)
        
        exportSmsCheckbox.isChecked = config.exportSms
        exportMmsCheckbox.isChecked = config.exportMms
        exportMessagesFilename.setText(
            "${activity.getString(R.string.messages)}_${getCurrentFormattedDateTime()}"
        )
    }

    init {
        // Setup custom title view inside BlurView
        val titleTextView = binding.root.findViewById<com.goodwy.commons.views.MyTextView>(com.goodwy.commons.R.id.dialog_title)
        titleTextView?.apply {
            visibility = android.view.View.VISIBLE
            setText(R.string.export_messages)
        }

        // Setup custom buttons inside BlurView
        val primaryColor = activity.getProperPrimaryColor()
        val buttonsContainer = binding.root.findViewById<android.widget.LinearLayout>(com.goodwy.commons.R.id.buttons_container)
        val positiveButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(com.goodwy.commons.R.id.positive_button)
        val negativeButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(com.goodwy.commons.R.id.negative_button)

        buttonsContainer?.visibility = android.view.View.VISIBLE

        positiveButton?.apply {
            visibility = android.view.View.VISIBLE
            text = activity.resources.getString(com.goodwy.commons.R.string.ok)
            setTextColor(primaryColor)
            setOnClickListener {
                config.exportSms = binding.exportSmsCheckbox.isChecked
                config.exportMms = binding.exportMmsCheckbox.isChecked
                val filename = binding.exportMessagesFilename.value
                when {
                    filename.isEmpty() -> activity.toast(com.goodwy.commons.R.string.empty_name)
                    filename.isAValidFilename() -> callback(filename)

                    else -> activity.toast(com.goodwy.commons.R.string.invalid_name)
                }
            }
        }

        negativeButton?.apply {
            visibility = android.view.View.VISIBLE
            text = activity.resources.getString(com.goodwy.commons.R.string.cancel)
            setTextColor(primaryColor)
            setOnClickListener {
                dialog?.dismiss()
            }
        }

        activity.getAlertDialogBuilder()
            .apply {
                activity.setupDialogStuff(
                    view = binding.root,
                    dialog = this,
                    titleId = 0
                ) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }

    fun exportMessages(uri: Uri) {
        dialog!!.apply {
            setCanceledOnTouchOutside(false)
            val positiveButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(com.goodwy.commons.R.id.positive_button)
            val negativeButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(com.goodwy.commons.R.id.negative_button)
            arrayOf(
                binding.exportMmsCheckbox,
                binding.exportSmsCheckbox,
                positiveButton,
                negativeButton
            ).forEach {
                it?.isEnabled = false
                it?.alpha = 0.6f
            }

            binding.exportProgress.setIndicatorColor(activity.getProperPrimaryColor())
            binding.exportProgress.post {
                binding.exportProgress.show()
            }
            export(uri)
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun export(uri: Uri) {
        ensureBackgroundThread {
            var success = false
            try {
                MessagesReader(activity).getMessagesToExport(
                    getSms = config.exportSms,
                    getMms = config.exportMms
                ) { messagesToExport ->
                    if (messagesToExport.isEmpty()) {
                        activity.toast(com.goodwy.commons.R.string.no_entries_for_exporting)
                        dismiss()
                        return@getMessagesToExport
                    }
                    val json = Json { encodeDefaults = true }
                    activity.contentResolver.openOutputStream(uri)!!.buffered()
                        .use { outputStream ->
                            json.encodeToStream(messagesToExport, outputStream)
                        }
                    success = true
                    activity.toast(com.goodwy.commons.R.string.exporting_successful)
                }
            } catch (e: Throwable) {
                activity.showErrorToast(e.toString())
            } finally {
                if (!success) {
                    // delete the file to avoid leaving behind an empty/corrupt file
                    try {
                        DocumentsContract.deleteDocument(activity.contentResolver, uri)
                    } catch (_: Exception) {
                        // ignored because we don't want to show two error messages
                    }
                }

                dismiss()
            }
        }
    }

    private fun dismiss() = dialog?.dismiss()
}
