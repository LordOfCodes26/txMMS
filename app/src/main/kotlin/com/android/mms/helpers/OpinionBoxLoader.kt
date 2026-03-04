package com.tx.feedback.util

import android.content.Context
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Loads opinion titles and numbers from a single file: /system/etc/opinion_box.info
 * Format: title[en]|title[ko]|title[zh]|number (one row per line)
 * <pre>
 * Call and SMS|전화 및 통보문|电话及短信|555
 * Signal and Mobile data|신호 및 자료접속|信号及数据连接|999
 * ...
</pre> *
 * Push with: adb push opinion_box.info /system/etc/
 */
object OpinionBoxLoader {
    const val SYSTEM_PATH: String = "/system/etc/opinion_box.info"
    private var cachedTitles: Array<String?>? = null
    private var cachedNumbers: Array<String?>? = null
    private var cachedLocale: String? = null

    fun getTitles(context: Context?): Array<String?>? {
        ensureLoaded(context)
        return cachedTitles
    }

    fun getNumbers(context: Context?): Array<String?>? {
        ensureLoaded(context)
        return cachedNumbers
    }

    private const val COL_EN = 0
    private const val COL_KO = 1
    private const val COL_ZH = 2
    private const val COL_NUMBER = 3

    private fun ensureLoaded(context: Context?) {
        val lang = Locale.getDefault().getLanguage()
        if (cachedTitles != null && lang == cachedLocale) {
            return
        }
        cachedLocale = lang

        val rows = parseFile()
        val titles: MutableList<String?> = ArrayList<String?>()
        val numbers: MutableList<String?> = ArrayList<String?>()

        val titleCol = if (lang == "ko") COL_KO else (if (lang == "zh") COL_ZH else COL_EN)
        for (parts in rows) {
            if (parts!!.size > COL_NUMBER) {
                titles.add(parts[titleCol]!!.trim { it <= ' ' })
                numbers.add(parts[COL_NUMBER]!!.trim { it <= ' ' })
            }
        }
        cachedTitles = titles.toTypedArray<String?>()
        cachedNumbers = numbers.toTypedArray<String?>()
    }

    private fun parseFile(): MutableList<Array<String?>?> {
        val rows: MutableList<Array<String?>?> = ArrayList<Array<String?>?>()
        val file = File(SYSTEM_PATH)

        try {
            BufferedReader(
                InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8)
            ).use { reader ->
                var line: String?
                while ((reader.readLine().also { line = it }) != null) {
                    line = line!!.trim { it <= ' ' }
                    if (line.isEmpty()) continue
                    val parts: Array<String?> = line.split("\\|".toRegex()).toTypedArray()
                    if (parts.size > COL_NUMBER) {
                        rows.add(parts)
                    }
                }
            }
        } catch (e: IOException) {
            throw RuntimeException("Failed to load " + SYSTEM_PATH, e)
        }
        return rows
    }
}
