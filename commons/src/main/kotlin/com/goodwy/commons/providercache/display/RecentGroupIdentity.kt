package com.goodwy.commons.providercache.display

data class RecentGroupIdentity(
    val mode: RecentGroupingMode,
    val groupKey: String,
    val displayContactId: Long?,
    val normalizedNumbers: Set<String>,
)
