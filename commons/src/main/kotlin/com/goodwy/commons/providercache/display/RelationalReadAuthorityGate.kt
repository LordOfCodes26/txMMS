package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.entities.CacheMetadataDomain

enum class RelationalReadBlockReason {
    VALIDATION_FAILED,
    REPAIR_REQUIRED,
    ENRICHMENT_INCOMPLETE,
    COMPARE_MISMATCH,
    NOT_DEBUG_BUILD,
}

data class RelationalReadGateResult(
    val allowed: Boolean,
    val effectiveMode: RelationalRecentsReadMode,
    val blockReason: RelationalReadBlockReason? = null,
)

object RelationalReadAuthorityGate {

    @Volatile
    var lastCompareValid: Boolean = false

    @Volatile
    var enrichmentComplete: Boolean = false

    fun markCompareResult(valid: Boolean) {
        lastCompareValid = valid
    }

    fun markEnrichmentComplete(complete: Boolean) {
        enrichmentComplete = complete
    }

    /** Soak counters — delegated to [com.goodwy.commons.providercache.debug.CompareOnlySoakCounters]. */
    fun soakSnapshot(): com.goodwy.commons.providercache.debug.CompareOnlySoakCounters.Snapshot =
        com.goodwy.commons.providercache.debug.CompareOnlySoakCounters.snapshot()

    fun soakDump(): String = com.goodwy.commons.providercache.debug.CompareOnlySoakCounters.dump()

    fun resetSoakCounters() {
        com.goodwy.commons.providercache.debug.CompareOnlySoakCounters.reset()
    }

    suspend fun evaluateRelationalDebug(
        database: ProviderCacheDatabase,
        mode: RecentGroupingMode,
        rowLimit: Int = 10_000,
        isDebugBuild: Boolean,
    ): RelationalReadGateResult {
        if (!isDebugBuild) {
            return blocked(RelationalReadBlockReason.NOT_DEBUG_BUILD)
        }
        if (!enrichmentComplete) {
            return blocked(RelationalReadBlockReason.ENRICHMENT_INCOMPLETE)
        }
        val metadata = database.cacheMetadataDao().getByDomain(CacheMetadataDomain.RECENTS_DISPLAY)
        if (metadata?.repairRequired == true) {
            return blocked(RelationalReadBlockReason.REPAIR_REQUIRED)
        }
        if (RelationalRecentsGroupingFlags.DUAL_WRITE_VALIDATION_ENABLED) {
            val dual = RecentGroupDualWriteValidator.validateDualWrite(database, mode, rowLimit)
            if (!dual.valid) {
                return blocked(RelationalReadBlockReason.VALIDATION_FAILED)
            }
        }
        val compare = RelationalRecentDisplaySnapshotBuilder.compareWithLegacyDisplay(
            database = database,
            mode = mode,
            rowLimit = rowLimit,
            includeDisplayFields = true,
        )
        lastCompareValid = compare.valid
        if (!compare.valid) {
            return blocked(RelationalReadBlockReason.COMPARE_MISMATCH)
        }
        return RelationalReadGateResult(
            allowed = true,
            effectiveMode = RelationalRecentsReadMode.RELATIONAL_DEBUG,
        )
    }

    fun trySetReadModeForDebug(
        requested: RelationalRecentsReadMode,
        gateResult: RelationalReadGateResult? = null,
    ): RelationalReadGateResult {
        if (requested != RelationalRecentsReadMode.RELATIONAL_DEBUG) {
            RelationalRecentsGroupingFlags.readMode = requested
            return RelationalReadGateResult(allowed = true, effectiveMode = requested)
        }
        val result = gateResult ?: RelationalReadGateResult(
            allowed = false,
            effectiveMode = RelationalRecentsReadMode.LEGACY_ONLY,
            blockReason = RelationalReadBlockReason.ENRICHMENT_INCOMPLETE,
        )
        if (!result.allowed) {
            RelationalRecentsGroupingFlags.readMode = RelationalRecentsReadMode.LEGACY_ONLY
            return result.copy(effectiveMode = RelationalRecentsReadMode.LEGACY_ONLY)
        }
        RelationalRecentsGroupingFlags.readMode = RelationalRecentsReadMode.RELATIONAL_DEBUG
        return result
    }

    private fun blocked(reason: RelationalReadBlockReason): RelationalReadGateResult =
        RelationalReadGateResult(
            allowed = false,
            effectiveMode = RelationalRecentsReadMode.LEGACY_ONLY,
            blockReason = reason,
        )
}
