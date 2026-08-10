package com.goodwy.commons.providercache.grouping

import com.goodwy.commons.providercache.entities.RecentGroupCallEntity
import com.goodwy.commons.providercache.entities.RecentGroupEntity
import com.goodwy.commons.providercache.entities.RecentGroupNumberEntity

data class RecentGroupingResult(
    val groups: List<RecentGroupEntity>,
    val memberships: List<RecentGroupCallEntity>,
    val numbers: List<RecentGroupNumberEntity>,
    val validation: RecentGroupingValidationResult,
)
