package com.tx.feedback.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.Locale
import com.tx.Space

/**
 * Loads opinion titles and numbers from a single JSON file: file:/data/misc/txspace/common/opinion_box.json
 *
 *
 * Expected JSON: array of objects. Each object has localized titles and a destination number:
 * <pre>`[
 * { "en": "...", "ko": "...", "zh": "...", "number": "555" },
 * ...
 * ]
`</pre> *
 * `number` may be a JSON string or number. `zh-CN` is accepted as a fallback when `zh` is absent.
 *
 *
 * Push with: adb push opinion_box.json file:/data/misc/txspace/common
 */
object OpinionBoxLoader {
    const val SYSTEM_PATH: String = "/system/etc/opinion_box.json"
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

    private fun ensureLoaded(context: Context?) {
        val lang = Locale.getDefault().getLanguage()
        if (cachedTitles != null && lang == cachedLocale) {
            return
        }
        cachedLocale = lang

        var cf = Space.getCommonDir()
        cf = File(cf, "config")
        val file = File(cf, "opinion_box.json")
        val rows = parseJsonFile(file)

//        val rows = parseJsonFile(File(SYSTEM_PATH))

        val titles: MutableList<String?> = ArrayList<String?>()
        val numbers: MutableList<String?> = ArrayList<String?>()

        for (o in rows) {
            val title = titleForLocale(o, lang)
            val number = numberFromJson(o)
            if (!number.isEmpty()) {
                titles.add(title)
                numbers.add(number)
            }
        }
        cachedTitles = titles.toTypedArray<String?>()
        cachedNumbers = numbers.toTypedArray<String?>()
    }

    private fun titleForLocale(o: JSONObject, lang: String?): String {
        if ("ko" == lang) {
            val v = o.optString("ko", "").trim { it <= ' ' }
            if (!v.isEmpty()) return v
        } else if ("zh" == lang) {
            var v = o.optString("zh", "").trim { it <= ' ' }
            if (!v.isEmpty()) return v
            v = o.optString("zh-CN", "").trim { it <= ' ' }
            if (!v.isEmpty()) return v
        }
        val en = o.optString("en", "").trim { it <= ' ' }
        return if (en.isEmpty()) o.optString("ko", "").trim { it <= ' ' } else en
    }

    private fun numberFromJson(o: JSONObject): String {
        if (!o.has("number") || o.isNull("number")) {
            return ""
        }
        val `val` = o.opt("number")
        if (`val` is Number) {
            val n = `val`.toLong()
            return n.toString()
        }
        return `val`.toString().trim { it <= ' ' }
    }

    @Throws(IOException::class)
    private fun readUtf8File(file: File): String {
        val sb = StringBuilder()
        BufferedReader(
            InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8)
        ).use { reader ->
            val buf = CharArray(4096)
            var n: Int
            while ((reader.read(buf).also { n = it }) != -1) {
                sb.append(buf, 0, n)
            }
        }
        return sb.toString().trim { it <= ' ' }
    }

    private fun parseJsonFile(file: File): MutableList<JSONObject> {
        val rows: MutableList<JSONObject> = ArrayList<JSONObject>()
        try {
            val text = readUtf8File(file)
            if (text.isEmpty()) {
                return rows
            }
            val root = JSONArray(text)
            for (i in 0..<root.length()) {
                val item = root.optJSONObject(i)
                if (item != null) {
                    rows.add(item)
                }
            }
        } catch (e: IOException) {
            throw RuntimeException("Failed to load or parse JSON " + SYSTEM_PATH, e)
        } catch (e: JSONException) {
            throw RuntimeException("Failed to load or parse JSON " + SYSTEM_PATH, e)
        }
        return rows
    }
}
