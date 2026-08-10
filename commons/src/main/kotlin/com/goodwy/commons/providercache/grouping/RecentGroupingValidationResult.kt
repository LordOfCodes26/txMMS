package com.goodwy.commons.providercache.grouping

/**
 * Post-build invariant checks for [RecentGroupingEngine] output.
 */
data class RecentGroupingValidationResult(
    val valid: Boolean,
    val eligibleCallCount: Int,
    val groupCount: Int,
    val membershipCount: Int,
    val failures: List<String> = emptyList(),
) {
    companion object {
        fun empty(): RecentGroupingValidationResult = RecentGroupingValidationResult(
            valid = true,
            eligibleCallCount = 0,
            groupCount = 0,
            membershipCount = 0,
        )
    }
}
