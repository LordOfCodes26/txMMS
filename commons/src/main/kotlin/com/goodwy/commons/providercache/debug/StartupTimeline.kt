package com.goodwy.commons.providercache.debug

import android.os.SystemClock
import android.util.Log
import com.goodwy.commons.providercache.display.CacheDomain
import java.util.concurrent.ConcurrentHashMap

/**
 * Debug-only cold-start timeline. Records phase milestones without changing production behavior.
 *
 * Log line: `startupTimeline domain={} elapsed={} state={}`
 */
object StartupTimeline {

    private const val TAG = "StartupTimeline"

    enum class State {
        PROCESS_START,
        STARTUP_OWNER_ACQUIRE,
        RAW_SYNC_START,
        RAW_SYNC_END,
        DISPLAY_BUILD_START,
        DISPLAY_BUILD_END,
        AUTHORITY_READY,
        FIRST_UI_PUBLISH,
    }

    @Volatile
    private var processStartElapsedMs: Long = 0L

    private val firstUiPublishByDomain = ConcurrentHashMap<String, Boolean>()
    private val lastStateByDomain = ConcurrentHashMap<String, State>()
    private val events = ConcurrentHashMap.newKeySet<String>()

    private fun nowElapsed(): Long =
        runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.currentTimeMillis() }

    fun markProcessStart() {
        if (processStartElapsedMs == 0L) {
            processStartElapsedMs = nowElapsed()
        }
        record("ALL", State.PROCESS_START)
    }

    fun markStartupOwnerAcquire(domain: CacheDomain, owner: String) {
        record(domain.name, State.STARTUP_OWNER_ACQUIRE, detail = owner)
    }

    fun markRawSyncStart(domain: CacheDomain) {
        record(domain.name, State.RAW_SYNC_START)
    }

    fun markRawSyncEnd(domain: CacheDomain) {
        record(domain.name, State.RAW_SYNC_END)
    }

    fun markDisplayBuildStart(domain: CacheDomain) {
        record(domain.name, State.DISPLAY_BUILD_START)
    }

    fun markDisplayBuildEnd(domain: CacheDomain) {
        record(domain.name, State.DISPLAY_BUILD_END)
    }

    fun markAuthorityReady(domain: CacheDomain, readiness: String) {
        record(domain.name, State.AUTHORITY_READY, detail = readiness)
    }

    fun markFirstUiPublish(domain: CacheDomain, rows: Int) {
        val previous = firstUiPublishByDomain.putIfAbsent(domain.name, true)
        if (previous == true) return
        record(domain.name, State.FIRST_UI_PUBLISH, detail = "rows=$rows")
    }

    fun elapsedMs(): Long {
        val start = processStartElapsedMs
        if (start == 0L) return 0L
        return nowElapsed() - start
    }

    fun lastState(domain: CacheDomain): State? = lastStateByDomain[domain.name]

    fun dump(): String {
        val elapsed = elapsedMs()
        val contacts = lastStateByDomain[CacheDomain.CONTACTS.name]?.name ?: "NONE"
        val recents = lastStateByDomain[CacheDomain.RECENTS.name]?.name ?: "NONE"
        return "startupTimelineDump elapsed=$elapsed contactsState=$contacts recentsState=$recents events=${events.size}"
    }

    fun resetForDebug() {
        processStartElapsedMs = 0L
        firstUiPublishByDomain.clear()
        lastStateByDomain.clear()
        events.clear()
    }

    private fun record(domain: String, state: State, detail: String = "") {
        if (processStartElapsedMs == 0L) {
            processStartElapsedMs = nowElapsed()
        }
        lastStateByDomain[domain] = state
        val elapsed = elapsedMs()
        val key = "$domain:$state:$detail"
        events += key
        val line = if (detail.isEmpty()) {
            "startupTimeline domain=$domain elapsed=$elapsed state=$state"
        } else {
            "startupTimeline domain=$domain elapsed=$elapsed state=$state detail=$detail"
        }
        if (ProviderCacheDebugLogger.isEnabled) {
            ProviderCacheDebugLogger.log(line)
        }
        runCatching { Log.d(TAG, line) }
    }
}
