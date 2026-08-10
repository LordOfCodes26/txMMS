package com.goodwy.commons.providercache.display

import java.util.Locale

/**
 * Single canonical phone normalization for recents grouping.
 * Must stay aligned with [com.goodwy.commons.providercache.dao.CallLogGroupKeySql].
 */
object CanonicalPhoneNumberResolver {

    /**
     * Minimum digit length for suffix / overlap matching when linking dialed numbers to contacts.
     * Aligned with [com.goodwy.commons.providercache.dao.CallLogDao] backfill (`LENGTH >= 7`) and
     * [RecentGroupIdentityResolver] phone-index suffix match. Shorter overlaps must not merge groups.
     */
    const val MIN_SUFFIX_MATCH_DIGITS = 7

    fun canonicalDigits(normalizedNumber: String, phoneNumber: String): String =
        RecentGroupKey.fromNormalizedNumber(normalizedNumber, phoneNumber)

    fun numberGroupKey(canonicalDigits: String): String = "number:$canonicalDigits"

    fun contactGroupKey(aggregateContactId: Long): String = "contact:$aggregateContactId"

    fun isNumberGroupKey(groupKey: String): Boolean = groupKey.startsWith("number:")

    fun isContactGroupKey(groupKey: String): Boolean = groupKey.startsWith("contact:")

    /**
     * Normalizes a contact display name for BY_CONTACT grouping.
     * "John Smith" / "JOHN SMITH" / "john smith" → `johnsmith`.
     */
    fun normalizeNameKey(displayName: String): String =
        displayName.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun nameGroupKey(nameKey: String): String = "name:$nameKey"

    fun isNameGroupKey(groupKey: String): Boolean = groupKey.startsWith("name:")

    /**
     * Maps a legacy digit-only [recent_display_cache.group_key] to the v15 relational key.
     * An unprefixed legacy key can only be an unresolved-caller fallback in either mode (a resolved
     * BY_CONTACT row always got a `contact:` key even under the legacy resolver), so both modes wrap
     * unprefixed values as `number:`. `name:`-prefixed legacy keys pass through unchanged — those are
     * stale rows from the old name-based resolver and are reconciled by repair, not by this function.
     */
    fun legacyDisplayGroupKey(groupKey: String, @Suppress("UNUSED_PARAMETER") mode: RecentGroupingMode): String {
        if (groupKey.startsWith("number:") ||
            groupKey.startsWith("contact:") ||
            groupKey.startsWith("name:")
        ) {
            return groupKey
        }
        return numberGroupKey(groupKey)
    }
}
