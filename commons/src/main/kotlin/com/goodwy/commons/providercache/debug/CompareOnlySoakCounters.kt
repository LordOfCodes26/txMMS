package com.goodwy.commons.providercache.debug

import android.util.Log
import com.goodwy.commons.providercache.grouping.AffectedBuildCounters
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only COMPARE_ONLY soak counters. No production behavior.
 *
 * Incremented from dual-write validation and authority compare paths.
 */
object CompareOnlySoakCounters {

    private const val TAG = "CompareOnlySoak"

    /** Snapshot every Nth checksum compare; see [recordChecksumCompare]. */
    private const val CHECKSUM_LOG_INTERVAL = 100L

    /** Mismatches logged unsampled before [CHECKSUM_LOG_INTERVAL] takes over. */
    private const val CHECKSUM_MISMATCH_LOG_BURST = 5L

    private val compareTotal = AtomicLong(0)
    private val compareMismatch = AtomicLong(0)
    private val displayMismatch = AtomicLong(0)
    private val dualWriteTotal = AtomicLong(0)
    private val dualWriteMismatch = AtomicLong(0)

    private val checksumCompareTotal = AtomicLong(0)
    private val checksumMismatch = AtomicLong(0)
    private val noOpMutationCount = AtomicLong(0)
    private val displayOnlyMutationCount = AtomicLong(0)
    private val membershipChangedCount = AtomicLong(0)

    private val authoritativeDisplayMismatch = AtomicLong(0)
    private val classifiedCosmeticDifference = AtomicLong(0)

    fun recordAuthorityCompare(
        valid: Boolean,
        displayMismatchCount: Int,
        cosmeticMismatchCount: Int = 0,
    ) {
        compareTotal.incrementAndGet()
        if (!valid) compareMismatch.incrementAndGet()
        if (cosmeticMismatchCount > 0) {
            classifiedCosmeticDifference.addAndGet(cosmeticMismatchCount.toLong())
        }
        val authoritative = displayMismatchCount - cosmeticMismatchCount
        if (authoritative > 0) {
            authoritativeDisplayMismatch.addAndGet(authoritative.toLong())
            displayMismatch.addAndGet(authoritative.toLong())
        }
        logSnapshot("authorityCompare")
    }

    /**
     * Checksum compares run per mutation and reach four figures in a normal startup. Snapshotting
     * every one of them put ~2400 logcat lines (two tags each) on the pipeline's own worker
     * threads in 20 seconds.
     *
     * Exempting mismatches from the interval was wrong: a raw-mirror swap invalidates every group
     * at once, so the pathological run is *all* mismatch and the exemption applied to all of it
     * (608 of 608 compares logged in one startup). The first few mismatches carry the signal; after
     * that both healthy and unhealthy compares sample at [CHECKSUM_LOG_INTERVAL]. Counters stay
     * exact regardless, and per-group detail is in the validator's issue list.
     */
    fun recordChecksumCompare(valid: Boolean) {
        val total = checksumCompareTotal.incrementAndGet()
        val mismatches = if (valid) checksumMismatch.get() else checksumMismatch.incrementAndGet()
        val firstMismatches = !valid && mismatches <= CHECKSUM_MISMATCH_LOG_BURST
        if (firstMismatches || total % CHECKSUM_LOG_INTERVAL == 0L) {
            logSnapshot("checksumCompare")
        }
    }

    fun recordNoOpMutation() {
        noOpMutationCount.incrementAndGet()
    }

    fun recordDisplayOnlyMutation() {
        displayOnlyMutationCount.incrementAndGet()
    }

    fun recordMembershipChanged() {
        membershipChangedCount.incrementAndGet()
    }

    fun recordDualWrite(valid: Boolean, mismatchCount: Int) {
        dualWriteTotal.incrementAndGet()
        if (!valid && mismatchCount > 0) {
            dualWriteMismatch.addAndGet(mismatchCount.toLong())
        } else if (!valid) {
            dualWriteMismatch.incrementAndGet()
        }
        logSnapshot("dualWrite")
    }

    fun snapshot(): Snapshot = Snapshot(
        compareTotal = compareTotal.get(),
        compareMismatch = compareMismatch.get(),
        displayMismatch = displayMismatch.get(),
        dualWriteTotal = dualWriteTotal.get(),
        dualWriteMismatch = dualWriteMismatch.get(),
        incrementalFallbackCount = AffectedBuildCounters.affectedFullFallbackCount,
        checksumCompareTotal = checksumCompareTotal.get(),
        checksumMismatch = checksumMismatch.get(),
        noOpMutationCount = noOpMutationCount.get(),
        displayOnlyMutationCount = displayOnlyMutationCount.get(),
        membershipChangedCount = membershipChangedCount.get(),
    )

    fun dump(): String {
        val s = snapshot()
        return "compareOnlySoak compareTotal=${s.compareTotal} compareMismatch=${s.compareMismatch} " +
            "displayMismatch=${s.displayMismatch} dualWriteTotal=${s.dualWriteTotal} " +
            "dualWriteMismatch=${s.dualWriteMismatch} incrementalFallbackCount=${s.incrementalFallbackCount} " +
            "checksumCompareTotal=${s.checksumCompareTotal} checksumMismatch=${s.checksumMismatch} " +
            "noOpMutationCount=${s.noOpMutationCount} displayOnlyMutationCount=${s.displayOnlyMutationCount} " +
            "membershipChangedCount=${s.membershipChangedCount}"
    }

    fun reset() {
        compareTotal.set(0)
        compareMismatch.set(0)
        displayMismatch.set(0)
        dualWriteTotal.set(0)
        dualWriteMismatch.set(0)
        checksumCompareTotal.set(0)
        checksumMismatch.set(0)
        noOpMutationCount.set(0)
        displayOnlyMutationCount.set(0)
        membershipChangedCount.set(0)
    }

    data class Snapshot(
        val compareTotal: Long,
        val compareMismatch: Long,
        val displayMismatch: Long,
        val dualWriteTotal: Long,
        val dualWriteMismatch: Long,
        val incrementalFallbackCount: Long = 0L,
        val checksumCompareTotal: Long = 0L,
        val checksumMismatch: Long = 0L,
        val noOpMutationCount: Long = 0L,
        val displayOnlyMutationCount: Long = 0L,
        val membershipChangedCount: Long = 0L,
    ) {
        val passRate: Double
            get() = if (compareTotal == 0L) 1.0 else {
                (compareTotal - compareMismatch).toDouble() / compareTotal.toDouble()
            }
    }

    private fun logSnapshot(reason: String) {
        val line = "${dump()} reason=$reason"
        if (ProviderCacheDebugLogger.isEnabled) {
            ProviderCacheDebugLogger.log(line)
        }
        runCatching { Log.d(TAG, line) }
    }
}
