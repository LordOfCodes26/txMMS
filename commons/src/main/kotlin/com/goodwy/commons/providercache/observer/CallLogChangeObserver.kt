package com.goodwy.commons.providercache.observer

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.helpers.PERMISSION_READ_CALL_LOG
import com.goodwy.commons.providercache.pipeline.RecentsPipelineOwnershipCounters
import com.goodwy.commons.providercache.sync.CallLogSyncManager

/**
 * Watches CallLog provider changes.
 *
 * When [RecentsPipelineOwnershipCounters.coordinatorAttached] is true, only notifies
 * [onChangeDebounced] — the coordinator owns incremental sync. Otherwise falls back to
 * scheduling sync through [syncManager] for compatibility.
 */
class CallLogChangeObserver(
    context: Context,
    private val syncManager: CallLogSyncManager,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val onChangeDebounced: (() -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private var registered = false

    private val debouncedRunnable = Runnable {
        if (RecentsPipelineOwnershipCounters.coordinatorAttached) {
            // Coordinator owns sync — notify only.
            onChangeDebounced?.invoke()
        } else {
            syncManager.scheduleIncrementalSync()
            onChangeDebounced?.invoke()
        }
    }

    fun register() {
        if (registered || !appContext.hasPermission(PERMISSION_READ_CALL_LOG)) return
        if (observer == null) {
            observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    if (selfChange) return
                    handler.removeCallbacks(debouncedRunnable)
                    handler.postDelayed(debouncedRunnable, debounceMs)
                }
            }
        }
        appContext.contentResolver.registerContentObserver(
            CallLog.Calls.CONTENT_URI,
            true,
            observer!!,
        )
        registered = true
    }

    fun unregister() {
        if (!registered) return
        handler.removeCallbacks(debouncedRunnable)
        observer?.let { appContext.contentResolver.unregisterContentObserver(it) }
        registered = false
    }

    companion object {
        /** Short debounce — sync runs in background; visible list stays unchanged until re-enter. */
        const val DEFAULT_DEBOUNCE_MS = 150L
    }
}
