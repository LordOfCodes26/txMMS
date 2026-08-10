package com.goodwy.commons.adapters

data class BlockedCallItem(
    val callLogId: Long,
    val displayName: String?,
    val phoneNumber: String,
    val timestamp: Long,
    val simId: Int = -1,
    /** Telephony SIM tint; 0 means unresolved (adapter falls back like recents). */
    val simColor: Int = 0,
    val groupedCount: Int = 1,
    /** Every CallLog row represented by this list row (required when rows are grouped by number). */
    val allCallLogIds: List<Long> = emptyList(),
) {
    fun callLogIdsForDeletion(): List<Long> {
        val merged = allCallLogIds.filter { it > 0 }.distinct()
        if (merged.isNotEmpty()) return merged
        return listOfNotNull(callLogId.takeIf { it > 0 })
    }
}
