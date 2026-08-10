package com.goodwy.commons.providercache.filter

/**
 * Maps letters to phone-keypad digits for T9 contact search.
 */
object T9Mapper {

    private val LETTER_TO_DIGIT = mapOf(
        'a' to '2', 'b' to '2', 'c' to '2',
        'd' to '3', 'e' to '3', 'f' to '3',
        'g' to '4', 'h' to '4', 'i' to '4',
        'j' to '5', 'k' to '5', 'l' to '5',
        'm' to '6', 'n' to '6', 'o' to '6',
        'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
        't' to '8', 'u' to '8', 'v' to '8',
        'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9',
        'ä' to '2', 'å' to '2', 'á' to '2', 'à' to '2', 'â' to '2', 'ã' to '2',
        'ë' to '3', 'é' to '3', 'è' to '3', 'ê' to '3',
        'ï' to '4', 'í' to '4', 'ì' to '4', 'î' to '4',
        'ö' to '6', 'ó' to '6', 'ò' to '6', 'ô' to '6', 'õ' to '6',
        'ü' to '8', 'ú' to '8', 'ù' to '8', 'û' to '8',
        'ñ' to '6', 'ç' to '2',
    )

    /** Converts [text] to a digit string using standard T9 mapping; keeps existing digits. */
    fun toT9Digits(text: String): String {
        if (text.isEmpty()) return ""
        val out = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch.isDigit() -> out.append(ch)
                else -> {
                    val mapped = LETTER_TO_DIGIT[ch.lowercaseChar()]
                    if (mapped != null) out.append(mapped)
                }
            }
        }
        return out.toString()
    }

    /** Digits extracted from [text] (phone numbers, extensions, etc.). */
    fun extractDigits(text: String): String = text.filter { it.isDigit() }
}
