package com.goodwy.commons.providercache.display

import android.util.Log
import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.entities.CacheMetadataDomain
import com.goodwy.commons.providercache.entities.CacheMetadataEntity

private const val TAG = "RecentDualWrite"

object RecentGroupDualWriteValidator {

    data class Result(
        val valid: Boolean,
        val oldGroupCount: Int,
        val newGroupCount: Int,
        val mismatches: List<Mismatch>,
        val semanticMismatches: List<ComparableRecentGroupMismatch> = emptyList(),
    ) {
        val needsRepair: Boolean get() = !valid
    }

    data class Mismatch(
        val groupKey: String,
        val oldCallIds: Set<Long>,
        val newCallIds: Set<Long>,
        val detail: String,
    )

    suspend fun validateDualWrite(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        rowLimit: Int = 10_000,
    ): Result {
        val legacy = ComparableRecentGroupDeriver.fromDisplayCache(database, mode, rowLimit)
        val relational = ComparableRecentGroupDeriver.fromRelational(database, mode, rowLimit)
        val semantic = RecentAuthorityComparator.compareSemanticGroups(mode, legacy, relational)
        val legacyMismatches = semantic.mismatches.map { mismatch ->
            Mismatch(
                groupKey = mismatch.semanticKey,
                oldCallIds = emptySet(),
                newCallIds = emptySet(),
                detail = "${mismatch.field} old=${mismatch.oldValue} new=${mismatch.newValue}",
            )
        }
        if (mode == RecentGroupingMode.BY_NUMBER) {
            legacyMismatches.forEach { mismatch ->
                Log.w(
                    TAG,
                    "recentDualWriteMismatch mode=BY_NUMBER semanticKey=${mismatch.groupKey} " +
                        "detail=${mismatch.detail}",
                )
            }
        }
        if (!semantic.valid) {
            RecentAuthorityMismatchStore.captureFromCompare(
                mode = mode,
                legacy = legacy,
                relational = relational,
                mismatches = semantic.mismatches,
            )
        }
        return Result(
            valid = semantic.valid,
            oldGroupCount = semantic.legacyGroupCount,
            newGroupCount = semantic.relationalGroupCount,
            mismatches = legacyMismatches,
            semanticMismatches = semantic.mismatches,
        ).also {
            com.goodwy.commons.providercache.debug.CompareOnlySoakCounters.recordDualWrite(
                valid = it.valid,
                mismatchCount = it.mismatches.size,
            )
        }
    }

    suspend fun markRepairRequiredIfInvalid(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        rowLimit: Int = 10_000,
    ): Result {
        if (RecentRelationalValidationDeferredState.shouldSkipAlignmentValidation()) {
            Log.d(
                TAG,
                "recentDualWriteValidation deferred mode=${mode.name} " +
                    "phase=${RecentRelationalValidationDeferredState.phase().name}",
            )
            return Result(
                valid = true,
                oldGroupCount = 0,
                newGroupCount = 0,
                mismatches = emptyList(),
                semanticMismatches = emptyList(),
            )
        }
        val result = validateDualWrite(database, mode, rowLimit)
        if (result.needsRepair) {
            val authority = RecentDisplayBuildAuthorityResolver.resolveForFullBuild(mode)
            // Soak/dual-write compare must not block warm paint when UI still reads LEGACY_SQL
            // (BY_NUMBER under LEGACY_ONLY). Only engine-authoritative display may mark repair.
            if (!RecentDisplayBuildAuthorityResolver.commitsEngineDisplay(authority)) {
                Log.w(
                    TAG,
                    "recentDualWriteMismatch compareOnly mode=${mode.name} authority=$authority " +
                        "(not marking repairRequired; UI reads non-engine display)",
                )
                return result
            }
            val now = System.currentTimeMillis()
            val metadataDao = database.cacheMetadataDao()
            val existing = metadataDao.getByDomain(CacheMetadataDomain.RECENTS_DISPLAY)
            metadataDao.upsert(
                (existing ?: CacheMetadataEntity(domain = CacheMetadataDomain.RECENTS_DISPLAY)).copy(
                    dirty = true,
                    repairRequired = true,
                    updatedAt = now,
                ),
            )
        }
        return result
    }
}