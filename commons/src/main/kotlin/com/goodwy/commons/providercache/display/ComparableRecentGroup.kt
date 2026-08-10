package com.goodwy.commons.providercache.display

/**
 * Stable semantic snapshot of one recents group for dual-write / authority comparison.
 * Excludes relative-time text and other bind-time-only fields.
 */
data class ComparableRecentGroup(
    val semanticKey: String,
    val callIds: Set<Long>,
    val displayContactId: Long?,
    val normalizedNumbers: Set<String>,
    val callCount: Int,
    val latestCallId: Long,
    val latestTimestamp: Long,
    val primaryNumber: String,
    val displayOrder: Int = -1,
) {
    companion object {
        fun semanticKeyForContact(aggregateContactId: Long): String =
            CanonicalPhoneNumberResolver.contactGroupKey(aggregateContactId)

        fun semanticKeyForNumber(canonicalDigits: String): String =
            CanonicalPhoneNumberResolver.numberGroupKey(canonicalDigits)

        fun semanticKeyFromStoredGroupKey(groupKey: String, displayContactId: Long?): String {
            if (groupKey.startsWith("contact:") ||
                groupKey.startsWith("number:") ||
                groupKey.startsWith("name:")
            ) {
                return groupKey
            }
            return when {
                displayContactId != null && displayContactId > 0L ->
                    semanticKeyForContact(displayContactId)
                else -> semanticKeyForNumber(groupKey)
            }
        }
    }
}

enum class ComparableRecentGroupField {
    /** Authority: membership call ids. */
    CALL_IDS,
    /** Authority: contact identity. */
    CONTACT_ID,
    /** Authority: normalized phone numbers in the group. */
    NUMBERS,
    /** Authority: call count. */
    COUNT,
    /** Authority: latest call id. */
    LATEST_CALL,
    /** Authority: latest timestamp. */
    LATEST_TS,
    /** Authority: primary phone number. */
    PRIMARY_NUMBER,
    /**
     * Cosmetic only — not compared for authority / dualWriteMismatch.
     * Retained for debug logs that may still reference the enum name.
     */
    DISPLAY_ORDER,
    /** Authority: group present on one side only. */
    GROUP_EXISTENCE,
}

data class ComparableRecentGroupMismatch(
    val mode: RecentGroupingMode,
    val semanticKey: String,
    val field: ComparableRecentGroupField,
    val oldValue: String,
    val newValue: String,
)
