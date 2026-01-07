package com.android.mms.helpers

import com.goodwy.commons.helpers.ExportResult
import java.io.OutputStream

object QuickTextsExporter {

    fun exportQuickTexts(
        quickTexts: ArrayList<String>,
        outputStream: OutputStream?,
        callback: (result: ExportResult) -> Unit,
    ) {
        if (outputStream == null) {
            callback.invoke(ExportResult.EXPORT_FAIL)
            return
        }

        try {
            outputStream.bufferedWriter().use { out ->
                out.write(quickTexts.joinToString(QUICK_TEXTS_EXPORT_DELIMITER) {
                    it
                })
            }
            callback.invoke(ExportResult.EXPORT_OK)
        } catch (e: Exception) {
            callback.invoke(ExportResult.EXPORT_FAIL)
        }
    }
}

