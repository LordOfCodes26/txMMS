package com.android.mms.helpers

import android.app.Activity
import com.goodwy.commons.extensions.showErrorToast
import com.android.mms.extensions.config

import java.io.File

class QuickTextsImporter(
    private val activity: Activity,
) {
    enum class ImportResult {
        IMPORT_FAIL, IMPORT_OK
    }

    fun importQuickTexts(path: String): ImportResult {
        return try {
            val inputStream = File(path).inputStream()
            val texts = inputStream.bufferedReader().use {
                val content = it.readText().trimEnd().split(QUICK_TEXTS_EXPORT_DELIMITER)
                content
            }
            if (texts.isNotEmpty()) {
                texts.forEach { text: String ->
                    activity.config.addQuickText(text)
                }
                ImportResult.IMPORT_OK
            } else {
                ImportResult.IMPORT_FAIL
            }

        } catch (e: Exception) {
            activity.showErrorToast(e)
            ImportResult.IMPORT_FAIL
        }
    }
}

