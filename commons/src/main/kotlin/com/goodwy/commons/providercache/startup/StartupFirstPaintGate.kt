package com.goodwy.commons.providercache.startup

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.goodwy.commons.providercache.ProviderCache
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class StartupSurface {
    RECENTS,
    CONTACTS,
}

enum class FirstPaintGateState {
    WAITING_FOR_SURFACE_REQUEST,
    WAITING_FOR_ADAPTER_SUBMIT,
    WAITING_FOR_FRAME_WITH_ROWS,
    PAINTED,
    DEGRADED_TIMEOUT,
}

/**
 * Recents first-paint gate for work that must not contend with the visible Recents list:
 *
 * - [markAdapterSubmitAccepted] records Recents adapter submit (tracing / legacy warm callbacks).
 * - [markFrameWithRows] / [markFrameEmpty] release heavy Recents-adjacent work (media, raw repair,
 *   deep validation).
 *
 * Contacts Room warm + sync are **not** gated here — they run in parallel with Recents.
 * Contacts adapter bind stays tab-gated in the UI controllers.
 *
 * Bridge/coordinator attachment alone does not release the heavy lane.
 */
object StartupFirstPaintGate {

    private const val TAG = "StartupFirstPaintGate"
    private const val SURFACE_REQUEST_TIMEOUT_MS = 5_000L
    private const val ADAPTER_SUBMIT_TIMEOUT_AFTER_SURFACE_MS = 8_000L
    private const val FRAME_TIMEOUT_AFTER_SUBMIT_MS = 3_000L

    private val recentsSurfaceRequested = AtomicBoolean(false)
    private val recentsFramePainted = AtomicBoolean(false)
    private val warmRecentsExpected = AtomicBoolean(false)
    private val contactsWorkDeferred = AtomicBoolean(false)
    private val recentsRepairDeferred = AtomicBoolean(false)
    private val contactsFullSnapshotDeferred = AtomicBoolean(false)
    private val mediaInitDeferred = AtomicBoolean(false)
    private val deepValidationDeferred = AtomicBoolean(false)
    private val adapterSubmitAccepted = AtomicBoolean(false)
    private val warmPreloadDispatched = AtomicBoolean(false)
    private val contactsUiFirstPainted = AtomicBoolean(false)

    private val warmPreloadCallbacks = CopyOnWriteArrayList<() -> Unit>()
    private val heavyWorkCallbacks = CopyOnWriteArrayList<() -> Unit>()
    private val surfaceRecoveryCallbacks = CopyOnWriteArrayList<() -> Unit>()
    private val contactsFirstPaintCallbacks = CopyOnWriteArrayList<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var state: FirstPaintGateState = FirstPaintGateState.WAITING_FOR_SURFACE_REQUEST

    @Volatile
    private var recentsPaintedAtMs: Long = 0L

    @Volatile
    private var surfaceRequestedAtMs: Long = 0L

    @Volatile
    private var adapterSubmitAtMs: Long = 0L

    private val firstBindPosition = AtomicInteger(-1)

    fun currentState(): FirstPaintGateState = state

    fun markWarmRecentsExpected() {
        warmRecentsExpected.set(true)
        StartupSessionLogger.log(domain = "RECENTS", stage = "WARM_PAINT_EXPECTED")
    }

    fun markSurfaceRequested(surface: StartupSurface) {
        when (surface) {
            StartupSurface.RECENTS -> {
                if (recentsSurfaceRequested.compareAndSet(false, true)) {
                    surfaceRequestedAtMs = System.currentTimeMillis()
                    if (state == FirstPaintGateState.WAITING_FOR_SURFACE_REQUEST) {
                        state = FirstPaintGateState.WAITING_FOR_ADAPTER_SUBMIT
                        logGateState("surface_requested")
                    }
                    StartupPriorityLogger.stage("RECENTS_SURFACE_ATTACH")
                    RecentsStartupTracer.stage("SURFACE_REQUESTED")
                    StartupSessionLogger.log(domain = "RECENTS", stage = "SURFACE_REQUESTED")
                }
            }
            StartupSurface.CONTACTS -> Unit
        }
    }

    fun markAdapterSubmitAccepted(rowCount: Int) {
        if (!adapterSubmitAccepted.compareAndSet(false, true)) return
        adapterSubmitAtMs = System.currentTimeMillis()
        if (state == FirstPaintGateState.WAITING_FOR_ADAPTER_SUBMIT ||
            state == FirstPaintGateState.WAITING_FOR_SURFACE_REQUEST
        ) {
            state = FirstPaintGateState.WAITING_FOR_FRAME_WITH_ROWS
        }
        logGateState("submit_accepted rows=$rowCount")
        Log.d(TAG, "firstPaintGate submitAccepted rows=$rowCount surface=RECENTS")
        RecentsStartupTracer.stage("ADAPTER_SUBMIT_ACCEPTED", "rows=$rowCount")
        dispatchWarmPreloadCallbacks()
    }

    fun markFirstRowBound(position: Int) {
        if (firstBindPosition.compareAndSet(-1, position)) {
            val elapsed = elapsedSinceSurfaceRequestMs()
            Log.d(TAG, "firstPaintGate firstBind position=$position elapsedMs=$elapsed surface=RECENTS")
            RecentsStartupTracer.stage("FIRST_ROW_BOUND", "position=$position elapsedMs=$elapsed")
        }
    }

    fun markFrameWithRows(rowCount: Int) {
        if (rowCount <= 0) return
        if (!recentsFramePainted.compareAndSet(false, true)) return
        recentsPaintedAtMs = System.currentTimeMillis()
        state = FirstPaintGateState.PAINTED
        val elapsed = elapsedSinceSurfaceRequestMs()
        logGateState("frame_with_rows rows=$rowCount elapsedMs=$elapsed")
        Log.d(
            TAG,
            "firstPaintGate frameWithRows rows=$rowCount elapsedMs=$elapsed surface=RECENTS",
        )
        RecentsStartupTracer.stage("FIRST_FRAME_WITH_ROWS", "rows=$rowCount elapsedMs=$elapsed")
        StartupSessionLogger.log(
            domain = "RECENTS",
            stage = "FIRST_FRAME",
            extra = "rows=$rowCount elapsedSinceRequestMs=$elapsed",
        )
        // Recents-home only: Contacts-home waits for markContactsUiFirstPainted.
        if (!StartupWorkPriorityCoordinator.isContactsVisible()) {
            StartupPhotoBackfillGate.resumeBackfill()
            if (ProviderCache.isInitialized()) {
                ProviderCache.contactsSyncManager.scheduleIdlePhotoBackfillIfNeeded()
            }
        }
        // Ensure warm preload ran even if submit was skipped somehow.
        dispatchWarmPreloadCallbacks()
        dispatchHeavyWorkCallbacks()
        StartupWorkPriorityCoordinator.onRecentsFramePainted()
    }

    /**
     * Contacts tab list has submitted/bound rows. Required before photo backfill when Contacts
     * is the visible surface (Recents frame alone must not unlock provider photo floods).
     *
     * Off-tab pre-bind must **not** permanently pause probes — that raced with Recents
     * [resumeBackfill] and left Contacts/Recents lists on monograms forever.
     */
    fun markContactsUiFirstPainted(rowCount: Int) {
        if (!contactsUiFirstPainted.compareAndSet(false, true)) return
        Log.d(TAG, "firstPaintGate contactsUiFirstPainted rows=$rowCount")
        StartupSessionLogger.log(
            domain = "CONTACTS",
            stage = "FIRST_FRAME",
            extra = "rows=$rowCount",
        )
        dispatchContactsFirstPaintCallbacks()
        val contactsVisible = StartupWorkPriorityCoordinator.isContactsVisible()
        if (contactsVisible) {
            // Brief pause so the visible Contacts first paint is not flooded by probes.
            StartupPhotoBackfillGate.pauseBackfill("CONTACTS_FIRST_PAINT", remaining = -1)
            StartupPhotoBackfillGate.scheduleRetryWhenIdle(delayMs = 2_000L) {
                if (ProviderCache.isInitialized()) {
                    ProviderCache.contactsSyncManager.scheduleIdlePhotoBackfillIfNeeded()
                }
            }
        } else if (
            recentsFramePainted.get() ||
            state == FirstPaintGateState.PAINTED ||
            state == FirstPaintGateState.DEGRADED_TIMEOUT
        ) {
            // Recents already unlocked probes; keep them unlocked and kick idle backfill.
            StartupPhotoBackfillGate.resumeBackfill()
            if (ProviderCache.isInitialized()) {
                ProviderCache.contactsSyncManager.scheduleIdlePhotoBackfillIfNeeded()
            }
        }
        // Else: still waiting for Recents frame / Contacts-home paint via shouldDeferPhotoBackfill.
    }

    fun contactsUiFirstPaintCompleted(): Boolean = contactsUiFirstPainted.get()

    /**
     * Block provider photo backfill until the visible surface has painted.
     * Contacts-home: wait for Contacts list first paint.
     * Recents-home: wait for Recents first frame (or degraded timeout).
     */
    fun shouldDeferPhotoBackfill(): Boolean {
        if (StartupWorkPriorityCoordinator.isContactsVisible()) {
            return !contactsUiFirstPainted.get()
        }
        if (warmRecentsExpected.get()) {
            return !recentsFramePainted.get() &&
                state != FirstPaintGateState.PAINTED &&
                state != FirstPaintGateState.DEGRADED_TIMEOUT
        }
        return shouldDeferHeavyStartupWork()
    }

    fun shouldDeferMediaInit(): Boolean {
        if (StartupWorkPriorityCoordinator.isContactsVisible()) {
            return !contactsUiFirstPainted.get()
        }
        return shouldDeferHeavyStartupWork()
    }

    /** Media / dialpad heavy UI — wait for visible surface first paint (Contacts or Recents). */
    fun onMediaInitAllowed(callback: () -> Unit) {
        if (!shouldDeferMediaInit()) {
            callback()
            return
        }
        if (StartupWorkPriorityCoordinator.isContactsVisible()) {
            contactsFirstPaintCallbacks.add(callback)
            return
        }
        onRecentsFirstPaintOrTimeout(callback)
    }

    private fun dispatchContactsFirstPaintCallbacks() {
        if (contactsFirstPaintCallbacks.isEmpty()) return
        val pending = contactsFirstPaintCallbacks.toList()
        contactsFirstPaintCallbacks.clear()
        mainHandler.post {
            pending.forEach { runCatching { it.invoke() } }
        }
    }

    fun markFrameEmpty() {
        if (!recentsFramePainted.compareAndSet(false, true)) return
        recentsPaintedAtMs = System.currentTimeMillis()
        state = FirstPaintGateState.PAINTED
        logGateState("frame_empty")
        Log.d(TAG, "firstPaintGate frameWithRows rows=0 surface=RECENTS authoritative_empty")
        RecentsStartupTracer.stage("FIRST_FRAME_EMPTY")
        dispatchWarmPreloadCallbacks()
        dispatchHeavyWorkCallbacks()
        StartupWorkPriorityCoordinator.onRecentsFramePainted()
    }

    /** @deprecated Use [markFrameWithRows] after RecyclerView pre-draw confirmation. */
    fun markSurfacePainted(surface: StartupSurface, rowCount: Int = 0) {
        when (surface) {
            StartupSurface.RECENTS -> {
                if (rowCount > 0) markFrameWithRows(rowCount)
                else markFrameEmpty()
            }
            StartupSurface.CONTACTS -> Unit
        }
    }

    fun recentsFirstPaintCompleted(): Boolean = recentsFramePainted.get()

    fun adapterSubmitAccepted(): Boolean = adapterSubmitAccepted.get()

    fun shouldDeferHeavyStartupWork(): Boolean =
        state == FirstPaintGateState.WAITING_FOR_SURFACE_REQUEST ||
            state == FirstPaintGateState.WAITING_FOR_ADAPTER_SUBMIT ||
            state == FirstPaintGateState.WAITING_FOR_FRAME_WITH_ROWS

    /**
     * Contacts data warm runs in parallel with Recents — never deferred behind Recents submit.
     * UI adapter bind is gated separately by Contacts tab visibility.
     */
    fun shouldDeferContactsWarmPreload(): Boolean = false

    /**
     * Contacts repair/sync runs in parallel with Recents — never deferred behind Recents frame.
     */
    fun shouldDeferContactsHeavyWork(): Boolean = false

    /** @deprecated Prefer [shouldDeferContactsHeavyWork] for repair/sync. */
    fun shouldDeferContactsFullWork(): Boolean = shouldDeferContactsHeavyWork()

    fun shouldDeferContactsStartupWork(): Boolean = shouldDeferContactsHeavyWork()

    /**
     * Full Contacts adapter snapshot is no longer deferred behind Recents; UI controllers
     * defer bind when the Contacts tab is hidden.
     */
    @Suppress("UNUSED_PARAMETER")
    fun shouldDeferContactsFullDisplaySnapshot(recentsTabVisible: Boolean): Boolean = false

    fun shouldDeferRecentsRawRepair(warmDisplayAvailable: Boolean): Boolean =
        warmDisplayAvailable && shouldDeferHeavyStartupWork()

    fun shouldDeferDeepRecentsValidation(): Boolean = shouldDeferHeavyStartupWork()

    fun logContactsFullSnapshotDeferred() {
        if (contactsFullSnapshotDeferred.compareAndSet(false, true)) {
            StartupPriorityLogger.deferred("CONTACTS_FULL_SNAPSHOT", "RECENTS_SUBMIT_PENDING")
            logContactsWorkDeferred("CONTACTS_FULL_DISPLAY_SNAPSHOT")
        }
    }

    fun logMediaInitDeferred(source: String) {
        if (mediaInitDeferred.compareAndSet(false, true)) {
            StartupPriorityLogger.deferred("MEDIA_AUDIO_INIT", source)
            Log.d(TAG, "mediaStartup deferred reason=WAITING_FOR_RECENTS_FRAME source=$source")
        }
    }

    fun logMediaInitResumed(trigger: String) {
        Log.d(TAG, "mediaStartup resumed trigger=$trigger")
        StartupPriorityLogger.resumed("MEDIA_AUDIO_INIT", elapsedSincePaintMs())
    }

    fun logDeepValidationDeferred(scope: String) {
        if (deepValidationDeferred.compareAndSet(false, true)) {
            StartupSessionLogger.log(
                domain = "RECENTS",
                stage = "VALIDATION_DEFERRED",
                extra = "scope=$scope reason=FIRST_PAINT",
            )
        }
    }

    /** Legacy warm-preload waiter — Contacts data no longer waits; runs [callback] immediately. */
    fun onRecentsSubmitDispatched(callback: () -> Unit) {
        callback()
    }

    /** Heavy startup work — released at Recents first frame (or empty / degraded timeout). */
    fun onRecentsFirstPaintOrTimeout(callback: () -> Unit) {
        if (recentsFramePainted.get() || !shouldDeferHeavyStartupWork()) {
            callback()
            return
        }
        // Contacts-as-home must not wait on a hidden Recents frame.
        if (StartupWorkPriorityCoordinator.isContactsVisible()) {
            callback()
            return
        }
        heavyWorkCallbacks.add(callback)
    }

    fun onRecentsSurfaceRecoveryOrTimeout(callback: () -> Unit) {
        if (recentsFramePainted.get()) {
            callback()
            return
        }
        surfaceRecoveryCallbacks.add(callback)
    }

    suspend fun awaitActualPaint(
        requestTimeoutMs: Long = SURFACE_REQUEST_TIMEOUT_MS,
        frameTimeoutMs: Long = FRAME_TIMEOUT_AFTER_SUBMIT_MS,
    ): Boolean {
        if (recentsFramePainted.get()) return true
        awaitSurfaceRequestThenPaint(requestTimeoutMs, frameTimeoutMs)
        return recentsFramePainted.get()
    }

    suspend fun awaitSurfaceRequestThenPaint(
        requestTimeoutMs: Long = SURFACE_REQUEST_TIMEOUT_MS,
        frameTimeoutMs: Long = FRAME_TIMEOUT_AFTER_SUBMIT_MS,
    ): Boolean {
        if (recentsFramePainted.get()) return true
        if (abortAwaitForContactsVisible()) return false

        val requestDeadline = System.currentTimeMillis() + requestTimeoutMs
        while (System.currentTimeMillis() < requestDeadline) {
            if (abortAwaitForContactsVisible()) return false
            if (recentsSurfaceRequested.get()) break
            delay(16L)
        }
        if (!recentsSurfaceRequested.get()) {
            if (abortAwaitForContactsVisible()) return false
            markDegradedTimeout("requestTimeout", pendingWork = "surface_request")
            return false
        }

        val submitDeadline = surfaceRequestedAtMs + ADAPTER_SUBMIT_TIMEOUT_AFTER_SURFACE_MS
        while (System.currentTimeMillis() < submitDeadline) {
            if (abortAwaitForContactsVisible()) return false
            if (adapterSubmitAccepted.get() || recentsFramePainted.get()) break
            delay(16L)
        }
        if (recentsFramePainted.get()) return true
        if (!adapterSubmitAccepted.get()) {
            if (abortAwaitForContactsVisible()) return false
            markDegradedTimeout("adapterSubmitTimeout", pendingWork = "adapter_submit")
            return false
        }

        val frameDeadline = adapterSubmitAtMs + frameTimeoutMs
        while (System.currentTimeMillis() < frameDeadline) {
            if (abortAwaitForContactsVisible()) return false
            if (recentsFramePainted.get()) return true
            delay(16L)
        }
        if (!recentsFramePainted.get()) {
            if (abortAwaitForContactsVisible()) return false
            markDegradedTimeout("frameTimeout surface=RECENTS", pendingWork = "frame_with_rows")
        }
        return recentsFramePainted.get()
    }

    private fun abortAwaitForContactsVisible(): Boolean {
        if (!StartupWorkPriorityCoordinator.isContactsVisible()) return false
        Log.d(TAG, "awaitPaint aborted reason=CONTACTS_VISIBLE")
        return true
    }

    /** @deprecated Prefer [awaitActualPaint]. */
    suspend fun awaitVisibleSurfacePaintOrTimeout(timeoutMs: Long = FRAME_TIMEOUT_AFTER_SUBMIT_MS): Boolean =
        awaitActualPaint(frameTimeoutMs = timeoutMs)

    fun logContactsWorkDeferred(work: String) {
        if (contactsWorkDeferred.compareAndSet(false, true)) {
            StartupSessionLogger.log(
                domain = "CONTACTS",
                stage = "WORK_DEFERRED",
                extra = "work=$work reason=RECENTS_FIRST_PAINT",
            )
            Log.d(TAG, "startupWorkDeferred work=$work reason=RECENTS_FIRST_PAINT")
        }
    }

    fun logContactsWorkResumed(work: String) {
        if (contactsWorkDeferred.get()) {
            val elapsed = elapsedSincePaintMs().coerceAtLeast(
                if (adapterSubmitAtMs > 0L) System.currentTimeMillis() - adapterSubmitAtMs else 0L,
            )
            StartupPriorityLogger.resumed(work, elapsed)
            StartupSessionLogger.log(
                domain = "CONTACTS",
                stage = "WORK_RESUMED",
                extra = "work=$work elapsedMs=$elapsed",
            )
            Log.d(TAG, "startupWorkResumed work=$work elapsedMs=$elapsed")
        }
    }

    fun logRecentsRepairDeferred() {
        if (recentsRepairDeferred.compareAndSet(false, true)) {
            StartupSessionLogger.log(
                domain = "RECENTS",
                stage = "RAW_REPAIR_DEFERRED",
                extra = "reason=RECENTS_FIRST_PAINT",
            )
        }
    }

    fun resetForTests() {
        recentsSurfaceRequested.set(false)
        recentsFramePainted.set(false)
        warmRecentsExpected.set(false)
        contactsWorkDeferred.set(false)
        recentsRepairDeferred.set(false)
        contactsFullSnapshotDeferred.set(false)
        mediaInitDeferred.set(false)
        deepValidationDeferred.set(false)
        adapterSubmitAccepted.set(false)
        warmPreloadDispatched.set(false)
        contactsUiFirstPainted.set(false)
        firstBindPosition.set(-1)
        recentsPaintedAtMs = 0L
        surfaceRequestedAtMs = 0L
        adapterSubmitAtMs = 0L
        state = FirstPaintGateState.WAITING_FOR_SURFACE_REQUEST
        warmPreloadCallbacks.clear()
        heavyWorkCallbacks.clear()
        surfaceRecoveryCallbacks.clear()
        contactsFirstPaintCallbacks.clear()
        StartupWorkPriorityCoordinator.resetForTests()
        RecentsStartupTracer.resetForTests()
    }

    private fun markDegradedTimeout(reason: String, pendingWork: String) {
        if (state == FirstPaintGateState.PAINTED) return
        state = FirstPaintGateState.DEGRADED_TIMEOUT
        logGateState(reason)
        Log.w(
            TAG,
            "firstPaintGate degradedTimeout pendingWork=$pendingWork painted=${recentsFramePainted.get()}",
        )
        dispatchSurfaceRecoveryCallbacks()
        // Do not leave Contacts/media stalled forever on timeout.
        dispatchWarmPreloadCallbacks()
        dispatchHeavyWorkCallbacks()
        dispatchContactsFirstPaintCallbacks()
    }

    private fun logGateState(extra: String) {
        StartupSessionLogger.log(
            domain = "RECENTS",
            stage = "FIRST_PAINT_STATE",
            extra = "state=${state.name} $extra",
        )
        Log.d(TAG, "firstPaintGate state=${state.name} surface=RECENTS $extra")
    }

    private fun dispatchWarmPreloadCallbacks() {
        warmPreloadDispatched.set(true)
        val callbacks = warmPreloadCallbacks.toList()
        warmPreloadCallbacks.clear()
        if (callbacks.isEmpty()) return
        Log.d(TAG, "firstPaintGate warmPreloadDispatch count=${callbacks.size}")
        RecentsStartupTracer.stage("WARM_PRELOAD_DISPATCH", "count=${callbacks.size}")
        mainHandler.post {
            callbacks.forEach { runCatching(it) }
        }
    }

    private fun dispatchHeavyWorkCallbacks() {
        val callbacks = heavyWorkCallbacks.toList()
        heavyWorkCallbacks.clear()
        if (callbacks.isEmpty()) return
        mainHandler.post {
            callbacks.forEach { runCatching(it) }
        }
    }

    private fun dispatchSurfaceRecoveryCallbacks() {
        val callbacks = surfaceRecoveryCallbacks.toList()
        surfaceRecoveryCallbacks.clear()
        if (callbacks.isEmpty()) return
        mainHandler.post {
            callbacks.forEach { runCatching(it) }
        }
    }

    private fun elapsedSinceSurfaceRequestMs(): Long =
        if (surfaceRequestedAtMs > 0L) System.currentTimeMillis() - surfaceRequestedAtMs else 0L

    private fun elapsedSincePaintMs(): Long =
        if (recentsPaintedAtMs > 0L) System.currentTimeMillis() - recentsPaintedAtMs else 0L
}

/** Structured startup priority logging. */
object StartupPriorityLogger {
    fun stage(stage: String, extra: String = "") {
        StartupSessionLogger.log(domain = "PRIORITY", stage = stage, extra = extra)
    }

    fun deferred(work: String, reason: String) {
        StartupSessionLogger.log(
            domain = "PRIORITY",
            stage = "WORK_DEFERRED",
            extra = "work=$work reason=$reason",
        )
    }

    fun resumed(work: String, elapsedMs: Long) {
        StartupSessionLogger.log(
            domain = "PRIORITY",
            stage = "WORK_RESUMED",
            extra = "work=$work elapsedMs=$elapsedMs after=RECENTS_FIRST_FRAME",
        )
    }
}
