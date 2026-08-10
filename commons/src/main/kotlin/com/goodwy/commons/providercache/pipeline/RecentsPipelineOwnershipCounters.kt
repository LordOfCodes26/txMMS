package com.goodwy.commons.providercache.pipeline

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug counters for RecentsPipelineCoordinator ownership QA.
 * With coordinator attached, direct and duplicate ownership counters must stay at 0 in production flows.
 */
object RecentsPipelineOwnershipCounters {
    val directSyncWhileCoordinatorAttached = AtomicInteger(0)
    val bridgeSyncStartAttempt = AtomicInteger(0)
    val directBridgeCatchUpCall = AtomicInteger(0)
    val directBridgeObserverCall = AtomicInteger(0)
    val duplicateSyncGeneration = AtomicInteger(0)
    val duplicateAuthoritativePublishForVersion = AtomicInteger(0)
    val stalePreviewDiscarded = AtomicInteger(0)
    val coordinatorCompatibilityFallback = AtomicInteger(0)
    val coalescedSyncRequestCount = AtomicInteger(0)
    val followUpSyncScheduled = AtomicInteger(0)

    val eventQueueDelayMsMax = AtomicLong(0)
    val observerToSyncLatencyMsMax = AtomicLong(0)
    val syncGenerationDurationMsMax = AtomicLong(0)
    val displayCommitToPublishLatencyMsMax = AtomicLong(0)

    @Volatile
    var coordinatorAttached: Boolean = false

    /**
     * Illegal [CallLogSyncOwnership.RECENTS_UI] sync bypass while coordinator is attached.
     * Startup / background / debug sync must not call this.
     */
    fun noteDirectSyncAttempt() {
        if (coordinatorAttached) {
            directSyncWhileCoordinatorAttached.incrementAndGet()
            android.util.Log.w(TAG, "directSyncWhileCoordinatorAttached count=$directSyncWhileCoordinatorAttached")
        }
    }

    fun noteBridgeSyncStart() {
        if (coordinatorAttached) {
            bridgeSyncStartAttempt.incrementAndGet()
            android.util.Log.w(TAG, "bridgeSyncStartAttempt count=$bridgeSyncStartAttempt")
        }
    }

    fun noteDirectBridgeCatchUp() {
        if (coordinatorAttached) {
            directBridgeCatchUpCall.incrementAndGet()
            android.util.Log.w(TAG, "directBridgeCatchUpCall count=$directBridgeCatchUpCall")
        }
    }

    fun noteDirectBridgeObserver() {
        if (coordinatorAttached) {
            directBridgeObserverCall.incrementAndGet()
            android.util.Log.w(TAG, "directBridgeObserverCall count=$directBridgeObserverCall")
        }
    }

    fun noteEventQueueDelay(delayMs: Long) {
        eventQueueDelayMsMax.updateAndGet { maxOf(it, delayMs) }
        if (delayMs > 100L) {
            android.util.Log.w(TAG, "recentsPipeline eventQueueDelayMs=$delayMs")
        }
    }

    fun reset() {
        directSyncWhileCoordinatorAttached.set(0)
        bridgeSyncStartAttempt.set(0)
        directBridgeCatchUpCall.set(0)
        directBridgeObserverCall.set(0)
        duplicateSyncGeneration.set(0)
        duplicateAuthoritativePublishForVersion.set(0)
        stalePreviewDiscarded.set(0)
        coordinatorCompatibilityFallback.set(0)
        coalescedSyncRequestCount.set(0)
        followUpSyncScheduled.set(0)
        eventQueueDelayMsMax.set(0)
        observerToSyncLatencyMsMax.set(0)
        syncGenerationDurationMsMax.set(0)
        displayCommitToPublishLatencyMsMax.set(0)
    }

    fun snapshot(): Map<String, Long> = mapOf(
        "directSyncWhileCoordinatorAttached" to directSyncWhileCoordinatorAttached.get().toLong(),
        "bridgeSyncStartAttempt" to bridgeSyncStartAttempt.get().toLong(),
        "directBridgeCatchUpCall" to directBridgeCatchUpCall.get().toLong(),
        "directBridgeObserverCall" to directBridgeObserverCall.get().toLong(),
        "duplicateSyncGeneration" to duplicateSyncGeneration.get().toLong(),
        "duplicateAuthoritativePublishForVersion" to duplicateAuthoritativePublishForVersion.get().toLong(),
        "stalePreviewDiscarded" to stalePreviewDiscarded.get().toLong(),
        "coordinatorCompatibilityFallback" to coordinatorCompatibilityFallback.get().toLong(),
        "coalescedSyncRequestCount" to coalescedSyncRequestCount.get().toLong(),
        "followUpSyncScheduled" to followUpSyncScheduled.get().toLong(),
    )

    private const val TAG = "RecentsPipelineOwn"
}
