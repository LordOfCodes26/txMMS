package com.goodwy.commons.providercache.search

import com.goodwy.commons.providercache.entities.ContactDisplayCacheEntity
import com.goodwy.commons.providercache.filter.T9Mapper

/**
 * In-memory ranking for verified display-cache search hits.
 *
 * ORDER BY:
 * - exact name match
 * - name startsWith query
 * - word-boundary startsWith
 * - phone startsWith digits
 * - phone contains digits
 * - name contains query
 * - T9 match
 * - sortKey ASC
 * - contactId ASC
 */
object SearchRanking {

    data class RankedHit(
        val contactId: Int,
        val reason: SearchRankingReason,
        val sortKey: String,
    )

    fun rank(
        entity: ContactDisplayCacheEntity,
        query: String,
        mode: SearchMode,
        phoneDigitsJoined: String,
        displayNumberDigitsJoined: String,
        dialpadParams: DialpadSearchContext?,
    ): RankedHit? {
        if (!SearchMatchRules.matches(
                displayName = entity.displayName,
                searchName = entity.searchName,
                t9Key = entity.t9Key,
                phoneDigits = entity.phoneDigits,
                phoneDigitsJoined = phoneDigitsJoined,
                displayNumberDigitsJoined = displayNumberDigitsJoined,
                query = query,
                mode = mode,
                dialpadParams = dialpadParams,
            )
        ) {
            return null
        }
        val reason = primaryReason(
            entity = entity,
            query = query,
            mode = mode,
            phoneDigitsJoined = phoneDigitsJoined,
            displayNumberDigitsJoined = displayNumberDigitsJoined,
            dialpadParams = dialpadParams,
        ) ?: return null
        return RankedHit(
            contactId = entity.contactId,
            reason = reason,
            sortKey = entity.sortKey,
        )
    }

    fun compare(a: RankedHit, b: RankedHit): Int {
        val reasonCmp = a.reason.ordinal.compareTo(b.reason.ordinal)
        if (reasonCmp != 0) return reasonCmp
        val sortCmp = a.sortKey.compareTo(b.sortKey, ignoreCase = true)
        if (sortCmp != 0) return sortCmp
        return a.contactId.compareTo(b.contactId)
    }

    private fun primaryReason(
        entity: ContactDisplayCacheEntity,
        query: String,
        mode: SearchMode,
        phoneDigitsJoined: String,
        displayNumberDigitsJoined: String,
        dialpadParams: DialpadSearchContext?,
    ): SearchRankingReason? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null
        val lowerQuery = trimmed.lowercase()
        val displayName = entity.displayName
        val searchName = entity.searchName.ifEmpty { displayName.lowercase() }
        val digits = digitQueryText(trimmed)

        if (searchName == lowerQuery || displayName.equals(trimmed, ignoreCase = true)) {
            return SearchRankingReason.EXACT
        }
        if (searchName.startsWith(lowerQuery) || displayName.startsWith(trimmed, ignoreCase = true)) {
            return SearchRankingReason.STARTS_WITH
        }
        if (wordStartsWith(searchName, lowerQuery) || wordStartsWith(displayName.lowercase(), lowerQuery)) {
            return SearchRankingReason.WORD_START
        }
        if (digits.isNotEmpty()) {
            val phoneBlob = phoneDigitsJoined.ifEmpty { entity.phoneDigits }
            val displayBlob = displayNumberDigitsJoined
            if (phoneBlob.startsWith(digits) || displayBlob.startsWith(digits)) {
                return SearchRankingReason.PHONE_PREFIX
            }
            if (phoneBlob.contains(digits) || displayBlob.contains(digits) || entity.phoneDigits.contains(digits)) {
                return SearchRankingReason.PHONE_CONTAINS
            }
        }
        if (searchName.contains(lowerQuery) || displayName.contains(trimmed, ignoreCase = true)) {
            return SearchRankingReason.CONTAINS
        }
        if (matchesT9(entity, trimmed, mode, dialpadParams)) {
            return SearchRankingReason.T9
        }
        return SearchRankingReason.CONTAINS
    }

    private fun matchesT9(
        entity: ContactDisplayCacheEntity,
        query: String,
        mode: SearchMode,
        dialpadParams: DialpadSearchContext?,
    ): Boolean {
        if (!SearchMatchRules.enableT9NameMatch(query, mode)) return false
        val t9Query = T9Mapper.toT9Digits(query)
        if (t9Query.isEmpty()) return false
        if (entity.t9Key.contains(t9Query, ignoreCase = true)) return true
        if (dialpadParams != null && mode == SearchMode.DIALPAD) {
            return DialpadContactSearch.matchesName(entity.displayName, dialpadParams.toMatchParams())
        }
        return T9Mapper.toT9Digits(entity.displayName).contains(t9Query)
    }

    private fun wordStartsWith(source: String, query: String): Boolean =
        source.split(Regex("\\s+")).any { it.startsWith(query, ignoreCase = true) }

    private fun digitQueryText(query: String): String = query.filter { it.isDigit() }
}
