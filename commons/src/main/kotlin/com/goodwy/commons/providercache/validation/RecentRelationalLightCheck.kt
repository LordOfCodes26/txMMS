package com.goodwy.commons.providercache.validation

import com.goodwy.commons.providercache.ProviderCacheDatabase

/** Cheap relational integrity probes for startup — no per-call enumeration. */
object RecentRelationalLightCheck {

    const val ISSUE_MEMBERSHIP_MISSING = "RELATIONAL_MEMBERSHIP_MISSING"

    data class Result(
        val rawEligibleCalls: Int,
        val recentGroups: Int,
        val membershipRows: Int,
        val issue: String? = null,
    ) {
        val needsRepair: Boolean get() = issue != null
    }

    suspend fun evaluate(database: ProviderCacheDatabase, groupingMode: Int): Result {
        val rawCount = database.callLogDao().getCount()
        val groupCount = database.recentGroupDao().countGroups(groupingMode)
        val membershipRows = database.recentGroupCallDao().countMemberships(groupingMode)
        val issue = when {
            rawCount > 0 && groupCount > 0 && membershipRows == 0 -> ISSUE_MEMBERSHIP_MISSING
            else -> null
        }
        return Result(
            rawEligibleCalls = rawCount,
            recentGroups = groupCount,
            membershipRows = membershipRows,
            issue = issue,
        )
    }
}
