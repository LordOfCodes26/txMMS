package com.goodwy.commons.providercache.startup

import android.util.Log
import com.goodwy.commons.providercache.ProviderCache
import com.goodwy.commons.providercache.ProviderCacheUserInteractionGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Blocks provider photo probes during cold start, tab switches, search, and display-cache rebuilds.
 * Photo metadata is backfilled later when the app is idle.
 */
object StartupPhotoBackfillGate {

    private val negativePhotoCache = ConcurrentHashMap.newKeySet<Int>()
    private const val MAX_NEGATIVE_CACHE = 10_000
    private const val SKIP_LOG_COALESCE_MS = 1_000L
    private const val DEFAULT_RETRY_DELAY_MS = 2_000L

    val paused = AtomicBoolean(false)
    val retryScheduled = AtomicBoolean(false)

    /** Increments each time [scheduleRetryWhenIdle] accepts a new retry request (for tests). */
    val scheduledRetryCount = AtomicInteger(0)

    @Volatile
    private var lastSkippedLogMs: Long = 0L

    @Volatile
    private var skippedCount: Int = 0

    @Volatile
    private var pauseReason: String = ""

    fun allowProviderPhotoProbe(logOnSkip: Boolean = false): Boolean {
        if (paused.get()) {
            if (logOnSkip) logSkipped(pauseReason.ifBlank { "PAUSED" })
            return false
        }
        val blockReason = startupBusyReason()
        if (blockReason != null) {
            if (logOnSkip) logSkipped(blockReason)
            return false
        }
        if (StartupOrchestrator.tabSwitching) {
            if (logOnSkip) logSkipped("TAB_SWITCHING")
            return false
        }
        if (StartupOrchestrator.searchActive) {
            if (logOnSkip) logSkipped("SEARCH_ACTIVE")
            return false
        }
        if (ProviderCacheUserInteractionGate.isUserInteracting()) {
            if (logOnSkip) logSkipped("USER_INTERACTING")
            return false
        }
        return true
    }

    fun allowPhotoBackfill(): Boolean {
        if (!allowProviderPhotoProbe(logOnSkip = false)) return false
        if (StartupOrchestrator.coldStart &&
            StartupOrchestrator.currentPhase() != StartupOrchestrator.Phase.IDLE_PHOTO_BACKFILL &&
            StartupOrchestrator.currentPhase() != StartupOrchestrator.Phase.COMPLETE
        ) {
            return false
        }
        return true
    }

    fun pauseBackfill(reason: String, remaining: Int = -1) {
        pauseReason = reason
        if (paused.compareAndSet(false, true)) {
            Log.d(
                TAG,
                "startupPhotoBackfill paused reason=$reason remaining=$remaining skippedCount=$skippedCount",
            )
            StartupSessionLogger.log(
                domain = "CONTACTS",
                stage = "PHOTO_BACKFILL_PAUSED",
                extra = "reason=$reason remaining=$remaining skippedCount=$skippedCount",
            )
        }
    }

    fun scheduleRetryWhenIdle(
        delayMs: Long = DEFAULT_RETRY_DELAY_MS,
        onRetry: () -> Unit,
    ) {
        if (!retryScheduled.compareAndSet(false, true)) return
        scheduledRetryCount.incrementAndGet()
        Log.d(TAG, "startupPhotoBackfill retryScheduled delayMs=$delayMs reason=$pauseReason")
        StartupSessionLogger.log(
            domain = "CONTACTS",
            stage = "PHOTO_BACKFILL_RETRY_SCHEDULED",
            extra = "delayMs=$delayMs reason=$pauseReason",
        )
        if (ProviderCache.isInitialized()) {
            ProviderCache.ioScope().launch {
                delay(delayMs)
                retryScheduled.set(false)
                resumeBackfill()
                onRetry()
            }
        } else {
            // Avoid leaving retryScheduled stuck when ProviderCache is not yet up (e.g. unit tests).
            retryScheduled.set(false)
        }
    }

    fun resumeBackfill() {
        if (paused.compareAndSet(true, false)) {
            Log.d(TAG, "startupPhotoBackfill resumed")
            StartupSessionLogger.log(domain = "CONTACTS", stage = "PHOTO_BACKFILL_RESUMED")
        }
    }

    fun logPhotoBackfillStart(count: Int) {
        Log.d(
            TAG,
            "photoBackfillStart idle=${!ProviderCacheUserInteractionGate.isUserInteracting()} count=$count",
        )
    }

    fun logPhotoBackfillEnd(durationMs: Long, queried: Int, skippedNoPhoto: Int) {
        Log.d(
            TAG,
            "startupPhotoBackfill batchComplete durationMs=$durationMs processed=$queried skippedNoPhoto=$skippedNoPhoto",
        )
        skippedCount = 0
    }

    fun hasNegativePhotoResult(contactId: Int): Boolean = negativePhotoCache.contains(contactId)

    fun recordNoPhoto(contactId: Int) {
        if (contactId <= 0) return
        if (negativePhotoCache.size >= MAX_NEGATIVE_CACHE) return
        negativePhotoCache.add(contactId)
    }

    private fun startupBusyReason(): String? = when {
        StartupOrchestrator.startupSyncRunning -> "STARTUP_BUSY"
        StartupOrchestrator.displayCacheRebuildRunning -> "STARTUP_BUSY"
        StartupOrchestrator.isColdStartCacheBuilding() -> "STARTUP_BUSY"
        StartupFirstPaintGate.shouldDeferPhotoBackfill() -> "STARTUP_BUSY"
        StartupFirstPaintGate.shouldDeferContactsStartupWork() -> "STARTUP_BUSY"
        else -> null
    }

    private fun logSkipped(reason: String) {
        skippedCount++
        val now = System.currentTimeMillis()
        if (now - lastSkippedLogMs >= SKIP_LOG_COALESCE_MS) {
            Log.d(TAG, "providerPhotoQuery skipped reason=$reason skippedCount=$skippedCount")
            lastSkippedLogMs = now
        }
    }

    private const val TAG = "StartupPhotoBackfill"
}
