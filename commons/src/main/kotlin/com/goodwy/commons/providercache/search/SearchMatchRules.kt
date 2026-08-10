package com.goodwy.commons.providercache.search

import com.goodwy.commons.providercache.filter.T9Mapper

/** Optional dialpad locale / IME context for [SearchMode.DIALPAD] name matching. */
data class DialpadSearchContext(
    val language: String,
    val inputMethod: String?,
    val queryLower: String,
    val queryLowerNoSpaces: String,
    val previewQuery: String = "",
) {
    fun toMatchParams(): DialpadContactSearch.MatchParams = DialpadContactSearch.MatchParams(
        language = language,
        inputMethod = inputMethod,
        queryLower = queryLower,
        queryLowerNoSpaces = queryLowerNoSpaces,
        previewQuery = previewQuery,
    )
}

/**
 * Unified query interpretation for all search modes.
 * Replaces ad-hoc [ToolbarContactSearch] / [DialpadContactSearch] branching at call sites.
 */
object SearchMatchRules {

    enum class QueryKind {
        NUMERIC_ONLY,
        LETTERS,
        MIXED,
    }

    fun classifyQuery(query: String): QueryKind {
        val hasLetter = query.any { it.isLetter() }
        val hasDigit = query.any { it.isDigit() }
        return when {
            hasLetter && hasDigit -> QueryKind.MIXED
            hasLetter -> QueryKind.LETTERS
            hasDigit && query.all { it.isDigit() } -> QueryKind.NUMERIC_ONLY
            hasDigit -> QueryKind.MIXED
            else -> QueryKind.LETTERS
        }
    }

    fun enableT9NameMatch(query: String, mode: SearchMode): Boolean = when (mode) {
        SearchMode.TOOLBAR, SearchMode.CONTACTS_TAB -> false
        SearchMode.DIALPAD -> DialpadContactSearch.enableNameSearch(query)
    }

    fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    fun likePrefix(raw: String): String =
        if (raw.isEmpty()) "" else "${escapeLike(raw)}%"

    fun likeContains(raw: String): String =
        if (raw.isEmpty()) "" else "%${escapeLike(raw)}%"

    fun letterQueryText(query: String): String = query.filter { it.isLetter() }

    fun digitQueryText(query: String): String = query.filter { it.isDigit() }

    fun preferIndexedPrefixForDigits(digits: String): Boolean = digits.length >= 2

    fun matches(
        displayName: String,
        searchName: String,
        t9Key: String,
        phoneDigits: String,
        phoneDigitsJoined: String,
        displayNumberDigitsJoined: String,
        query: String,
        mode: SearchMode,
        dialpadParams: DialpadSearchContext?,
    ): Boolean {
        val kind = classifyQuery(query)
        return when (mode) {
            SearchMode.CONTACTS_TAB, SearchMode.TOOLBAR -> matchesToolbar(
                displayName = displayName,
                searchName = searchName,
                t9Key = t9Key,
                phoneDigits = phoneDigits,
                phoneDigitsJoined = phoneDigitsJoined,
                displayNumberDigitsJoined = displayNumberDigitsJoined,
                query = query,
                kind = kind,
            )
            SearchMode.DIALPAD -> matchesDialpad(
                displayName = displayName,
                searchName = searchName,
                t9Key = t9Key,
                phoneDigits = phoneDigits,
                phoneDigitsJoined = phoneDigitsJoined,
                displayNumberDigitsJoined = displayNumberDigitsJoined,
                query = query,
                kind = kind,
                dialpadParams = dialpadParams,
            )
        }
    }

    private fun matchesToolbar(
        displayName: String,
        searchName: String,
        t9Key: String,
        phoneDigits: String,
        phoneDigitsJoined: String,
        displayNumberDigitsJoined: String,
        query: String,
        kind: QueryKind,
    ): Boolean = when (kind) {
        QueryKind.NUMERIC_ONLY, QueryKind.LETTERS, QueryKind.MIXED -> {
            val nameOk = searchName.contains(query.lowercase()) ||
                displayName.contains(query, ignoreCase = true)
            val digits = digitQueryText(query)
            // Mirror [ToolbarContactSearch.shouldMatchPhoneDigits]: letter+digit queries must not
            // phone-match on stripped digits (e.g. "123a" → "123").
            val phoneOk = digits.isNotEmpty() &&
                !(kind == QueryKind.MIXED && query.any { it.isLetter() }) &&
                matchesPhoneOnly(
                    phoneDigits, phoneDigitsJoined, displayNumberDigitsJoined, digits,
                )
            nameOk || phoneOk
        }
    }

    private fun matchesDialpad(
        displayName: String,
        searchName: String,
        t9Key: String,
        phoneDigits: String,
        phoneDigitsJoined: String,
        displayNumberDigitsJoined: String,
        query: String,
        kind: QueryKind,
        dialpadParams: DialpadSearchContext?,
    ): Boolean {
        val digitQuery = DialpadContactSearch.digitQueryText(query)
        val enableName = enableT9NameMatch(query, SearchMode.DIALPAD)
        if (digitQuery.isNotEmpty()) {
            if (matchesPhoneOnly(phoneDigits, phoneDigitsJoined, displayNumberDigitsJoined, digitQuery)) {
                return true
            }
        }
        if (!enableName) return false
        if (kind == QueryKind.LETTERS || kind == QueryKind.MIXED) {
            if (matchesNameAndT9(displayName, searchName, t9Key, query)) return true
        }
        if (digitQuery.length >= 2) {
            if (t9Key.contains(T9Mapper.toT9Digits(query), ignoreCase = true)) return true
            if (dialpadParams != null && DialpadContactSearch.matchesName(displayName, dialpadParams.toMatchParams())) {
                return true
            }
        }
        return false
    }

    private fun matchesPhoneOnly(
        phoneDigits: String,
        phoneDigitsJoined: String,
        displayNumberDigitsJoined: String,
        digits: String,
    ): Boolean {
        if (digits.isEmpty()) return false
        return phoneDigits.contains(digits) ||
            phoneDigitsJoined.contains(digits) ||
            displayNumberDigitsJoined.contains(digits)
    }

    private fun matchesNameAndT9(
        displayName: String,
        searchName: String,
        t9Key: String,
        query: String,
    ): Boolean {
        val lowerQuery = query.lowercase()
        if (searchName.contains(lowerQuery) || displayName.contains(query, ignoreCase = true)) {
            return true
        }
        val t9Query = T9Mapper.toT9Digits(query)
        return t9Query.isNotEmpty() && t9Key.contains(t9Query, ignoreCase = true)
    }
}
