package com.android.mms.dialogs

import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.dialogs.FilePickerDialog
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getCurrentFormattedDateTime
import com.goodwy.commons.extensions.getParentPath
import com.goodwy.commons.extensions.humanizePath
import com.goodwy.commons.extensions.internalStoragePath
import com.goodwy.commons.extensions.isAValidFilename
import com.goodwy.commons.extensions.setupDialogStuff
import com.goodwy.commons.extensions.showKeyboard
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.extensions.value
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.android.mms.R
import com.android.mms.databinding.DialogExportBlockedKeywordsBinding
import com.android.mms.extensions.config
import com.android.mms.helpers.BLOCKED_KEYWORDS_EXPORT_EXTENSION
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import java.io.File

class ExportBlockedKeywordsDialog(
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
            DialogExportBlockedKeywordsBinding.inflate(activity.layoutInflater, null, false).apply {
                // Setup BlurView
                val blurView = root.findViewById<eightbitlab.com.blurview.BlurView>(com.android.mms.R.id.blurView)
                val decorView = activity.window.decorView
                val windowBackground = decorView.background
                
                blurView?.setOverlayColor(0xa3ffffff.toInt())
                blurView?.setupWith(blurTarget)
                    ?.setFrameClearDrawable(windowBackground)
                    ?.setBlurRadius(8f)
                    ?.setBlurAutoUpdate(true)
                
                exportBlockedKeywordsFolder.text = activity.humanizePath(realPath)
                exportBlockedKeywordsFilename.setText("${activity.getString(R.string.blocked_keywords)}_${getCurrentFormattedDateTime()}")

                if (hidePath) {
                    exportBlockedKeywordsFolderLabel.beGone()
                    exportBlockedKeywordsFolder.beGone()
                } else {
                    exportBlockedKeywordsFolder.setOnClickListener {
                        FilePickerDialog(activity, realPath, false, showFAB = true, blurTarget = blurTarget) {
                            exportBlockedKeywordsFolder.text = activity.humanizePath(it)
                            realPath = it
                        }
                    }
                }
            }

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.goodwy.commons.R.string.ok, null)
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(
                    view = view.root,
                    dialog = this,
                    titleId = R.string.export_blocked_keywords
                ) { alertDialog ->
                    alertDialog.showKeyboard(view.exportBlockedKeywordsFilename)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val filename = view.exportBlockedKeywordsFilename.value
                        when {
                            filename.isEmpty() -> activity.toast(com.goodwy.commons.R.string.empty_name)
                            filename.isAValidFilename() -> {
                                val file =
                                    File(realPath, "$filename$BLOCKED_KEYWORDS_EXPORT_EXTENSION")
                                if (!hidePath && file.exists()) {
                                    activity.toast(com.goodwy.commons.R.string.name_taken)
                                    return@setOnClickListener
                                }

                                ensureBackgroundThread {
                                    config.lastBlockedKeywordExportPath =
                                        file.absolutePath.getParentPath()
                                    callback(file)
                                    alertDialog.dismiss()
                                }
                            }

                            else -> activity.toast(com.goodwy.commons.R.string.invalid_name)
                        }
                    }
                }
            }
    }
}
