package com.goodwy.commons.providercache.search

/**
 * Query interpretation for Recents-tab toolbar / Contacts-tab text search.
 * Matches display name contains OR phone digits contains. No T9 name matching.
 */
object ToolbarContactSearch {

    const val LOG_TAG = "toolbarSearch"

    enum class Mode {
        NUMERIC_DIGITS_ONLY,
        LETTERS,
        MIXED,
    }

    fun classifyMode(query: String): Mode {
        val hasLetter = query.any { it.isLetter() }
        val hasDigit = query.any { it.isDigit() }
        return when {
            hasLetter && hasDigit -> Mode.MIXED
            hasLetter -> Mode.LETTERS
            hasDigit && query.all { it.isDigit() } -> Mode.NUMERIC_DIGITS_ONLY
            hasDigit -> Mode.MIXED
            else -> Mode.LETTERS
        }
    }

    fun sqlWhereLabel(mode: Mode): String = "name_or_phone"

    fun logQuery(query: String, mode: Mode) {
        android.util.Log.d(LOG_TAG, "toolbarSearch query=$query mode=$mode sqlWhere=${sqlWhereLabel(mode)}")
    }

    fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    fun likePrefix(raw: String): String =
        if (raw.isEmpty()) "" else "${escapeLike(raw)}%"

    fun likeContains(raw: String): String =
        if (raw.isEmpty()) "" else "%${escapeLike(raw)}%"

    fun letterQueryText(query: String): String = query.filter { it.isLetter() }

    fun digitQueryText(query: String): String = query.filter { it.isDigit() }

    /**
     * Whether toolbar/contacts SQL should OR-match [digitQueryText] against phone fields.
     *
     * Letter+digit queries (e.g. `"123a"`, `"KT5"`) must not — stripping to `"123"` / `"5"` floods
     * results with unrelated phone hits and buries the intended name match.
     * Digit-only and punctuation+digit queries (e.g. `"55"`, `"+1555"`) still match phones.
     */
    fun shouldMatchPhoneDigits(query: String, mode: Mode = classifyMode(query)): Boolean {
        if (digitQueryText(query).isEmpty()) return false
        if (mode == Mode.MIXED && query.any { it.isLetter() }) return false
        return true
    }

    /** Multi-digit dialpad input can use an indexed prefix scan on [phone_digits]. */
    fun preferIndexedPrefixForDigits(digits: String): Boolean = digits.length >= 2

    fun matchesDisplayName(displayName: String, query: String, mode: Mode): Boolean {
        if (displayName.isEmpty() || query.isBlank()) return false
        return displayName.contains(query, ignoreCase = true)
    }

    /**
     * Digit-only substring match on phone fields. Uses [phoneDigits] and [displayNumberDigits] only;
     * [normalizedNumbers] is ignored so E.164 strings like "+821501…" cannot false-match "15" across
     * country-code boundaries when the dialable number does not contain the query.
     */
    fun matchesPhoneFields(
        phoneDigits: String,
        normalizedNumbers: String,
        displayNumberDigits: String,
        query: String,
        mode: Mode,
    ): Boolean {
        if (!shouldMatchPhoneDigits(query, mode)) return false
        val digits = digitQueryText(query)
        return phoneDigits.contains(digits) || displayNumberDigits.contains(digits)
    }

    fun matchesToolbarContact(
        displayName: String,
        phoneDigits: String,
        normalizedNumbers: String,
        displayNumberDigits: String,
        query: String,
    ): Boolean {
        if (query.isBlank()) return false
        val mode = classifyMode(query)
        val nameOk = matchesDisplayName(displayName, query, mode)
        val phoneOk = matchesPhoneFields(
            phoneDigits, normalizedNumbers, displayNumberDigits, query, mode,
        )
        return nameOk || phoneOk
    }
}
