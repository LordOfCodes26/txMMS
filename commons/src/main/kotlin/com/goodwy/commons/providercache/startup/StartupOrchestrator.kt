package com.goodwy.commons.providercache.startup

import android.util.Log
import com.goodwy.commons.providercache.debug.StartupTimeline
import com.goodwy.commons.providercache.display.CacheDomain
import com.goodwy.commons.providercache.display.DisplayCacheReadiness
import com.goodwy.commons.providercache.display.DisplayCacheReadinessTracker
import com.goodwy.commons.providercache.display.StartupDomainOwner

/**
 * Coordinates cold-start phases after clearing app storage so UI, sync, display-cache rebuilds,
 * and photo backfill do not compete on the main thread during first launch.
 */
object StartupOrchestrator {

    enum class Phase {
        UI_SHELL,
        RAW_SYNC,
        DISPLAY_CACHE,
        IDLE_PHOTO_BACKFILL,
        COMPLETE,
    }

    @Volatile
    private var phase = Phase.UI_SHELL

    @Volatile
    var coldStart: Boolean = false
        private set

    @Volatile
    var startupSyncRunning: Boolean = false
        private set

    @Volatile
    var callLogsSyncDone: Boolean = false
        private set

    @Volatile
    var contactsSyncDone: Boolean = false
        private set

    @Volatile
    var recentsDisplayCacheReady: Boolean = false
        private set

    @Volatile
    var contactsDisplayCacheReady: Boolean = false
        private set

    @Volatile
    var displayCacheRebuildRunning: Boolean = false
        private set

    @Volatile
    private var pendingRecentsResyncAfterContacts: Boolean = false

    @Volatile
    private var pendingRecentsResyncListener: (() -> Unit)? = null

    @Volatile
    var tabSwitching: Boolean = false

    @Volatile
    var searchActive: Boolean = false

    fun currentPhase(): Phase = phase

    fun markColdStart() {
        if (coldStart) return
        val preserveRecentsDisplay = DisplayCacheReadinessTracker.recentsDisplayReadinessIsSeeded()
        coldStart = true
        phase = Phase.UI_SHELL
        callLogsSyncDone = false
        contactsSyncDone = false
        recentsDisplayCacheReady = preserveRecentsDisplay &&
            DisplayCacheReadinessTracker.recentsReadiness() == DisplayCacheReadiness.READY_WITH_DATA
        contactsDisplayCacheReady = false
        pendingRecentsResyncAfterContacts = false
        phaseStartedAtMs = System.currentTimeMillis()
        if (preserveRecentsDisplay) {
            DisplayCacheReadinessTracker.resetRecentsRawOnly()
        } else {
            DisplayCacheReadinessTracker.resetForColdStart()
        }
        StartupDomainOwner.nextStartupGeneration()
        StartupTimeline.markProcessStart()
        com.goodwy.commons.providercache.startup.StartupSessionLogger.beginSession()
        logPhase(Phase.UI_SHELL)
    }

    /** Marks startup complete when a domain reaches a terminal readiness (including permission error). */
    fun markPermissionBlockedStartup() {
        if (!coldStart) return
        phase = Phase.COMPLETE
        coldStart = false
        startupSyncRunning = false
        Log.d(TAG, "startupPhase phase=COMPLETE reason=ERROR_PERMISSION")
    }

    fun beginRawSync() {
        if (!coldStart) return
        startupSyncRunning = true
        StartupTimeline.markRawSyncStart(CacheDomain.CONTACTS)
        StartupTimeline.markRawSyncStart(CacheDomain.RECENTS)
        if (phase == Phase.UI_SHELL) {
            phase = Phase.RAW_SYNC
            logPhase(Phase.RAW_SYNC)
        }
    }

    fun onCallLogsRawSyncComplete() {
        // Always mark done: clear-all / warm incremental sync also reach empty Room and must be
        // allowed to claim READY_EMPTY (debug assertion) without requiring an active cold start.
        callLogsSyncDone = true
        if (!coldStart) return
        StartupTimeline.markRawSyncEnd(CacheDomain.RECENTS)
        maybeLogRawSyncComplete()
    }

    fun onContactsRawSyncComplete() {
        contactsSyncDone = true
        if (!coldStart) return
        StartupTimeline.markRawSyncEnd(CacheDomain.CONTACTS)
        maybeLogRawSyncComplete()
    }

    private fun maybeLogRawSyncComplete() {
        if (!callLogsSyncDone || !contactsSyncDone) return
        startupSyncRunning = false
        Log.d(
            TAG,
            "startupPhase phase=RAW_SYNC callLogsDone=true contactsDone=true",
        )
        if (phase == Phase.RAW_SYNC) {
            phase = Phase.DISPLAY_CACHE
            logPhase(Phase.DISPLAY_CACHE)
        }
    }

    fun onDisplayCacheRebuildStarted() {
        displayCacheRebuildRunning = true
        StartupTimeline.markDisplayBuildStart(CacheDomain.CONTACTS)
        StartupTimeline.markDisplayBuildStart(CacheDomain.RECENTS)
    }

    fun onDisplayCacheRebuildEnded() {
        displayCacheRebuildRunning = false
        StartupTimeline.markDisplayBuildEnd(CacheDomain.CONTACTS)
        StartupTimeline.markDisplayBuildEnd(CacheDomain.RECENTS)
        maybeEnterIdlePhotoBackfill()
    }

    fun onRecentsDisplayCacheReady(rows: Int) {
        if (!coldStart) return
        recentsDisplayCacheReady = true
        StartupTimeline.markAuthorityReady(CacheDomain.RECENTS, "rows=$rows")
        Log.d(TAG, "startupPhase phase=DISPLAY_CACHE recentsRows=$rows")
        maybeEnterIdlePhotoBackfill()
        if (consumePendingRecentsResyncAfterContacts()) {
            pendingRecentsResyncListener?.invoke()
        }
    }

    fun onContactsDisplayCacheReady(rows: Int) {
        if (!coldStart) return
        contactsDisplayCacheReady = true
        StartupTimeline.markAuthorityReady(CacheDomain.CONTACTS, "rows=$rows")
        Log.d(TAG, "startupPhase phase=DISPLAY_CACHE contactsRows=$rows")
        maybeEnterIdlePhotoBackfill()
    }

    private fun maybeEnterIdlePhotoBackfill() {
        if (!coldStart) return
        if (displayCacheRebuildRunning) return
        if (!recentsDisplayCacheReady || !contactsDisplayCacheReady) return
        if (phase == Phase.IDLE_PHOTO_BACKFILL || phase == Phase.COMPLETE) return
        phase = Phase.IDLE_PHOTO_BACKFILL
        logPhase(Phase.IDLE_PHOTO_BACKFILL)
        com.goodwy.commons.providercache.ProviderCacheUserInteractionGate.deferPhotoBackfill()
    }

    fun markStartupComplete() {
        if (!coldStart) return
        phase = Phase.COMPLETE
        coldStart = false
        pendingRecentsResyncAfterContacts = false
    }

    fun isColdStartCacheBuilding(): Boolean =
        coldStart && (!contactsDisplayCacheReady || startupSyncRunning || displayCacheRebuildRunning)

    /**
     * When contacts finish before the first recents display cache exists, defer the names
     * resync until [onRecentsDisplayCacheReady] so we do not race an empty cache rebuild.
     */
    fun shouldDeferRecentsResyncAfterContacts(): Boolean =
        coldStart && !recentsDisplayCacheReady

    fun markPendingRecentsResyncAfterContacts() {
        pendingRecentsResyncAfterContacts = true
    }

    fun consumePendingRecentsResyncAfterContacts(): Boolean {
        val pending = pendingRecentsResyncAfterContacts
        pendingRecentsResyncAfterContacts = false
        return pending
    }

    fun setPendingRecentsResyncListener(listener: (() -> Unit)?) {
        pendingRecentsResyncListener = listener
    }

    /** @deprecated Prefer full-list snapshot; main Contacts tab no longer uses first-page-only. */
    fun shouldLoadContactsFirstPageOnly(reason: com.goodwy.commons.providercache.display.ContactDisplayLoadReason): Boolean {
        return false
    }

    private fun logPhase(phase: Phase) {
        val elapsed = System.currentTimeMillis() - phaseStartedAtMs
        if (elapsed > phaseTimeoutMs(phase)) {
            Log.w(TAG, "startupPhaseTimeout phase=$phase elapsedMs=$elapsed limitMs=${phaseTimeoutMs(phase)}")
        }
        phaseStartedAtMs = System.currentTimeMillis()
        Log.d(TAG, "startupPhase phase=$phase")
    }

    private fun phaseTimeoutMs(phase: Phase): Long = when (phase) {
        Phase.UI_SHELL -> 5_000L
        Phase.RAW_SYNC -> 120_000L
        Phase.DISPLAY_CACHE -> 120_000L
        Phase.IDLE_PHOTO_BACKFILL -> 300_000L
        Phase.COMPLETE -> Long.MAX_VALUE
    }

    @Volatile
    private var phaseStartedAtMs: Long = 0L

    private const val TAG = "StartupOrchestrator"
}
