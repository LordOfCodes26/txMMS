package com.android.mms.dialogs

import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.dialogs.FilePickerDialog
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getCurrentFormattedDateTime
import com.goodwy.commons.extensions.getParentPath
import com.goodwy.commons.extensions.getProperBlurOverlayColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.humanizePath
import com.goodwy.commons.extensions.internalStoragePath
import com.goodwy.commons.extensions.isAValidFilename
import com.goodwy.commons.extensions.setupDialogStuff
import com.goodwy.commons.extensions.showKeyboard
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.extensions.value
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.android.mms.R
import com.android.mms.databinding.DialogExportQuickTextsBinding
import com.android.mms.extensions.config
import com.android.mms.helpers.QUICK_TEXTS_EXPORT_EXTENSION
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import java.io.File

class ExportQuickTextsDialog(
    val activity: BaseSimpleActivity,
    val path: String,
    val hidePath: Boolean,
    val blurTarget: BlurTarget,
    callback: (file: File) -> Unit,
) {
    private var realPath = path.ifEmpty { activity.internalStoragePath }
    private val config = activity.config

    init {
        val view =
            DialogExportQuickTextsBinding.inflate(activity.layoutInflater, null, false).apply {
                // Setup BlurView
                val blurView = root.findViewById<eightbitlab.com.blurview.BlurView>(com.android.mms.R.id.blurView)
                val decorView = activity.window.decorView
                val windowBackground = decorView.background
                
                blurView?.setOverlayColor(activity.getProperBlurOverlayColor())
                blurView?.setupWith(blurTarget)
                    ?.setFrameClearDrawable(windowBackground)
                    ?.setBlurRadius(5f)
                    ?.setBlurAutoUpdate(true)
                
                exportQuickTextsFolder.text = activity.humanizePath(realPath)
                exportQuickTextsFilename.setText("${activity.getString(R.string.quick_texts)}_${getCurrentFormattedDateTime()}")

                if (hidePath) {
                    exportQuickTextsFolderLabel.beGone()
                    exportQuickTextsFolder.beGone()
                } else {
                    exportQuickTextsFolder.setOnClickListener {
                        FilePickerDialog(activity, realPath, false, showFAB = true, blurTarget = blurTarget) {
                            exportQuickTextsFolder.text = activity.humanizePath(it)
                            realPath = it
                        }
                    }
                }
            }

        // Setup custom title view inside BlurView
        val titleTextView = view.root.findViewById<com.goodwy.commons.views.MyTextView>(com.goodwy.commons.R.id.dialog_title)
        titleTextView?.apply {
            visibility = android.view.View.VISIBLE
            setText(R.string.export_quick_texts)
        }

        // Setup custom buttons inside BlurView
        val primaryColor = activity.getProperPrimaryColor()
        val buttonsContainer = view.root.findViewById<android.widget.LinearLayout>(com.goodwy.commons.R.id.buttons_container)
        val positiveButton = view.root.findViewById<com.google.android.material.button.MaterialButton>(com.goodwy.commons.R.id.positive_button)
        val negativeButton = view.root.findViewById<com.google.android.material.button.MaterialButton>(com.goodwy.commons.R.id.negative_button)

        buttonsContainer?.visibility = android.view.View.VISIBLE
        var alertDialog: AlertDialog? = null

        positiveButton?.apply {
            visibility = android.view.View.VISIBLE
            text = activity.resources.getString(com.goodwy.commons.R.string.ok)
            setTextColor(primaryColor)
            setOnClickListener {
                val filename = view.exportQuickTextsFilename.value
                when {
                    filename.isEmpty() -> activity.toast(com.goodwy.commons.R.string.empty_name)
                    filename.isAValidFilename() -> {
                        val file =
                            File(realPath, "$filename$QUICK_TEXTS_EXPORT_EXTENSION")
                        if (!hidePath && file.exists()) {
                            activity.toast(com.goodwy.commons.R.string.name_taken)
                            return@setOnClickListener
                        }

                        ensureBackgroundThread {
                            config.lastQuickTextExportPath =
                                file.absolutePath.getParentPath()
                            callback(file)
                        }
                    }

                    else -> activity.toast(com.goodwy.commons.R.string.invalid_name)
                }
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
                activity.setupDialogStuff(
                    view = view.root,
                    dialog = this,
                    titleId = 0
                ) { dialog ->
                    alertDialog = dialog
                    dialog.showKeyboard(view.exportQuickTextsFilename)
                }
            }
    }
}

