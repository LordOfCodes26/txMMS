package com.goodwy.commons.providercache.grouping

import android.util.Log
import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.display.RecentGroupDualWriteValidator
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.entities.CacheMetadataDomain
import com.goodwy.commons.providercache.validation.RecentDisplayCacheValidator

private const val TAG = "RecentRepairCompletion"

/**
 * Rules for clearing [CacheMetadataDomain.RECENTS_DISPLAY] repairRequired after v15 repair.
 */
object RecentGroupingRepairCompletion {

    data class Result(
        val canClearRepairRequired: Boolean,
        val failures: List<String>,
    )

    suspend fun evaluate(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        rowLimit: Int = 10_000,
    ): Result {
        val failures = mutableListOf<String>()
        val rawCount = database.callLogDao().getCount()
        val relationalCount = database.recentGroupDao().countGroups(mode.dbValue)

        if (rawCount > 0 && relationalCount == 0) {
            failures += "relationalGroupsEmpty raw=$rawCount"
        }

        val relationalValidation = RecentDisplayCacheValidator.validate(
            database = database,
            groupByContact = mode.dbValue,
            rowLimit = rowLimit,
        )
        if (!relationalValidation.isValid) {
            failures += relationalValidation.issues.take(10).map { "${it.reason}:${it.identityKey}:${it.detail}" }
        }

        val dualWrite = RecentGroupDualWriteValidator.validateDualWrite(database, mode, rowLimit)
        if (!dualWrite.valid) {
            failures += dualWrite.semanticMismatches.take(10).map {
                "${it.field}:${it.semanticKey}"
            }
            if (failures.isEmpty()) {
                failures += dualWrite.mismatches.take(5).map { it.detail }
            }
        }

        val displayCount = database.recentDisplayCacheDao().getCount(mode.dbValue)
        if (rawCount > 0 && displayCount == 0) {
            failures += "displayCacheEmpty"
        }
        if (relationalCount > 0 && displayCount == 0) {
            failures += "displayMissingForRelationalGroups"
        }

        val totals = relationalValidation.relationalTotals
        if (rawCount > 0 && totals.membershipRows != rawCount) {
            failures += "membershipMismatch raw=$rawCount membership=${totals.membershipRows}"
        }
        if (totals.multiplyAssignedCalls > 0) {
            failures += "multiplyAssigned=${totals.multiplyAssignedCalls}"
        }
        if (totals.unassignedCalls > 0) {
            failures += "unassigned=${totals.unassignedCalls}"
        }
        val checksumFailures = relationalValidation.issues.count {
            it.reason == RecentDisplayCacheValidator.IssueReason.MEMBERSHIP_CHECKSUM_MISMATCH
        }
        if (checksumFailures > 0) {
            failures += "membershipChecksumMismatch=$checksumFailures"
        }

        val canClear = failures.isEmpty()
        if (!canClear) {
            Log.w(TAG, "repairValidationFailure mode=${mode.name} failures=${failures.joinToString(";")}")
        } else {
            Log.d(TAG, "repairValidationSuccess mode=${mode.name} groups=$relationalCount raw=$rawCount")
        }
        return Result(canClearRepairRequired = canClear, failures = failures)
    }

    suspend fun clearRepairRequiredIfValid(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        rowLimit: Int = 10_000,
    ): Result {
        val evaluation = evaluate(database, mode, rowLimit)
        if (evaluation.canClearRepairRequired) {
            val metadataDao = database.cacheMetadataDao()
            val existing = metadataDao.getByDomain(CacheMetadataDomain.RECENTS_DISPLAY)
            if (existing != null) {
                metadataDao.upsert(
                    existing.copy(
                        repairRequired = false,
                        dirty = false,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        return evaluation
    }
}
