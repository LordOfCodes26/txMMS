package com.goodwy.commons.providercache.search

import com.goodwy.commons.extensions.normalizeString
import com.goodwy.commons.providercache.filter.T9Mapper
import java.text.Normalizer

/**
 * Dialpad/T9 contact search semantics for Room display-cache queries.
 * Unlike [ToolbarContactSearch], purely numeric dialpad queries may match contact names via
 * word-start T9 when the digit sequence length is at least 2.
 */
object DialpadContactSearch {

    const val LOG_TAG = "dialpadSearch"

    private const val INPUT_METHOD_KO = "ko"

    data class MatchParams(
        val language: String,
        val inputMethod: String?,
        val queryLower: String,
        val queryLowerNoSpaces: String,
        val previewQuery: String = "",
    )

    fun isNumericDialpadQuery(query: String): Boolean =
        query.all { it.isDigit() || it == '+' || it == '-' || it == ' ' || it == '(' || it == ')' }

    fun digitQueryText(query: String): String =
        if (isNumericDialpadQuery(query)) query.filter { it.isDigit() || it == '+' } else ""

    /** Name/T9 search is enabled for letter queries; for numeric-only, require at least 2 digits. */
    fun enableNameSearch(query: String): Boolean {
        if (!isNumericDialpadQuery(query)) return true
        return digitQueryText(query).length >= 2
    }

    fun matchesName(displayName: String, params: MatchParams): Boolean {
        if (displayName.isEmpty()) return false
        val normalized = displayName.normalizeString().uppercase()
        return matchesDialpadQuery(
            source = normalized,
            language = params.language,
            queryLower = params.queryLower,
            queryLowerNoSpaces = params.queryLowerNoSpaces,
            previewQuery = params.previewQuery,
            inputMethod = params.inputMethod,
        )
    }

    fun matchesDialpadQuery(
        source: String,
        language: String,
        queryLower: String,
        queryLowerNoSpaces: String,
        previewQuery: String = "",
        inputMethod: String? = null,
    ): Boolean {
        val useKorean = inputMethod == INPUT_METHOD_KO || language.startsWith("ko")
        if (previewQuery.isNotEmpty()) {
            val normalizedSource = Normalizer.normalize(source, Normalizer.Form.NFC)
            val normalizedPreview = Normalizer.normalize(previewQuery, Normalizer.Form.NFC)
            if (normalizedSource.split(" ").any { it.startsWith(normalizedPreview, ignoreCase = true) }) {
                return true
            }
        }

        if (useKorean) {
            if (!containsKorean(source)) return false
            val korean = convertKoreanTextToKeySequence(source)
            return candidateMatches(korean, queryLower, queryLowerNoSpaces)
        }

        val converted = T9Mapper.toT9Digits(source)
        return candidateMatches(converted, queryLower, queryLowerNoSpaces)
    }

    private fun candidateMatches(candidate: String, queryLower: String, queryLowerNoSpaces: String): Boolean {
        if (candidate.isEmpty()) return false
        if (candidate.split(" ").any { it.startsWith(queryLower, ignoreCase = true) }) return true
        val candidateNoSpaces = candidate.replace(" ", "")
        return candidateNoSpaces.contains(queryLowerNoSpaces, ignoreCase = true)
    }

    private fun containsKorean(source: String): Boolean =
        source.any {
            it in '\uAC00'..'\uD7A3' ||
                it in '\u3131'..'\u3163' ||
                it in '\u1100'..'\u11FF'
        }

    private val koreanKeyToChars = arrayOf(
        "+", "ㄱㅋ", "ㅂㅍ", "ㅏㅓ", "ㄴㄷㅌ", "ㅅㅈㅊ", "ㅣㅡ", "ㄹㅁ", "ㅇㅎ", "ㅗㅜ", "가/A", "양상",
    )

    private val koreanCharToDialpadKey: Map<Char, Char> by lazy {
        buildMap {
            koreanKeyToChars.forEachIndexed { index, chars ->
                val key = when (index) {
                    in 0..9 -> ('0'.code + index).toChar()
                    10 -> '*'
                    else -> '#'
                }
                chars.forEach { ch ->
                    if (!ch.isWhitespace() && ch != '/') put(ch, key)
                }
            }
        }
    }

    private val koreanChoseongTable = charArrayOf(
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ',
        'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ',
    )

    private val koreanChoseongJamoToCompat: Map<Char, Char> = mapOf(
        '\u1100' to 'ㄱ', '\u1101' to 'ㄲ', '\u1102' to 'ㄴ', '\u1103' to 'ㄷ', '\u1104' to 'ㄸ',
        '\u1105' to 'ㄹ', '\u1106' to 'ㅁ', '\u1107' to 'ㅂ', '\u1108' to 'ㅃ', '\u1109' to 'ㅅ',
        '\u110A' to 'ㅆ', '\u110B' to 'ㅇ', '\u110C' to 'ㅈ', '\u110D' to 'ㅉ', '\u110E' to 'ㅊ',
        '\u110F' to 'ㅋ', '\u1110' to 'ㅌ', '\u1111' to 'ㅍ', '\u1112' to 'ㅎ',
    )

    private fun convertKoreanTextToKeySequence(input: String): String {
        if (input.isEmpty()) return input
        return buildString(input.length) {
            input.forEach { char ->
                val chosen = when {
                    char in '\uAC00'..'\uD7A3' -> {
                        val choseongIndex = (char.code - 0xAC00) / 588
                        koreanChoseongTable.getOrNull(choseongIndex)
                    }
                    char in '\u1100'..'\u1112' -> {
                        val choseongIndex = char.code - 0x1100
                        koreanChoseongTable.getOrNull(choseongIndex)
                    }
                    else -> char
                }
                append(
                    when (chosen) {
                        'ㄲ' -> koreanCharToDialpadKey['ㄱ'] ?: chosen
                        'ㄸ' -> koreanCharToDialpadKey['ㄷ'] ?: chosen
                        'ㅃ' -> koreanCharToDialpadKey['ㅂ'] ?: chosen
                        'ㅆ' -> koreanCharToDialpadKey['ㅅ'] ?: chosen
                        'ㅉ' -> koreanCharToDialpadKey['ㅈ'] ?: chosen
                        in '\u1100'..'\u1112' -> {
                            val compat = koreanChoseongJamoToCompat[chosen]
                            compat?.let { koreanCharToDialpadKey[it] } ?: chosen
                        }
                        else -> koreanCharToDialpadKey[chosen] ?: chosen
                    },
                )
            }
        }
    }

}
