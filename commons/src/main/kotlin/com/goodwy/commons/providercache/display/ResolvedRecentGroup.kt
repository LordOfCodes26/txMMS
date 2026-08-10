package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.entities.CallLogEntity

/**
 * Grouping-resolved recents row before display bind enrichment.
 */
data class ResolvedRecentGroup(
    val groupKey: String,
    val latestCallId: Int,
    val callCount: Int,
    val groupedCallIds: String,
    val normalizedNumber: String,
    val displayContactId: Int?,
    val latestRaw: CallLogEntity,
    val memberRaws: List<CallLogEntity>,
    val displayOrder: Int,
)
