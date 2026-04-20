package com.goodwy.commons.adapters

data class BlockedCallItem(
    val callLogId: Long,
    val displayName: String?,
    val phoneNumber: String,
    val timestamp: Long,
    val simId: Int = -1,
    val groupedCount: Int = 1,
    /** Every CallLog row represented by this list row (required when rows are grouped by contact). */
    val allCallLogIds: List<Long> = emptyList(),
) {
    fun callLogIdsForDeletion(): List<Long> {
        val merged = allCallLogIds.filter { it > 0 }.distinct()
        if (merged.isNotEmpty()) return merged
        return listOfNotNull(callLogId.takeIf { it > 0 })
    }
}
