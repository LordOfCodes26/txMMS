package com.goodwy.commons.providercache.startup

import android.util.Log

/**
 * Tracks the visible startup surface and kicks off shared data-pipeline warm work.
 *
 * Recents and Contacts Room/display warm + sync run in parallel regardless of which tab is home.
 * Adapter bind/reconcile stays tab-gated in the UI controllers.
 */
object StartupWorkPriorityCoordinator {

    enum class VisibleSurface {
        RECENTS,
        CONTACTS,
        DIALPAD,
        NONE,
    }

    @Volatile
    private var visibleSurface: VisibleSurface = VisibleSurface.NONE

    fun setVisibleSurface(surface: VisibleSurface) {
        if (visibleSurface == surface) return
        visibleSurface = surface
        Log.d(TAG, "startupPriority visibleSurface=$surface")
        StartupPriorityLogger.stage("VISIBLE_SURFACE", "surface=$surface")
        // Any concrete surface: start Contacts Room→RAM list warm (do not wait for Recents QUERY).
        if (surface != VisibleSurface.NONE &&
            com.goodwy.commons.providercache.ProviderCache.isInitialized()
        ) {
            runCatching {
                com.goodwy.commons.providercache.ProviderCache.contactsRepository
                    .scheduleEnsureDisplayRowsWarmed()
            }
        }
    }

    fun currentVisibleSurface(): VisibleSurface = visibleSurface

    fun isContactsVisible(): Boolean = visibleSurface == VisibleSurface.CONTACTS

    /** Contacts data warm is no longer deferred behind Recents submit. */
    fun shouldDeferHiddenContactsWork(): Boolean = false

    /** Contacts sync/repair is no longer deferred behind Recents first frame. */
    fun shouldDeferHiddenContactsHeavyWork(): Boolean = false

    /** Recents bridge always starts at setup regardless of home tab. */
    fun shouldDeferRecentsOnlyAssumptions(): Boolean = false

    fun onRecentsFramePainted() {
        StartupPriorityLogger.resumed("HIDDEN_CONTACTS_WORK", 0L)
    }

    fun resetForTests() {
        visibleSurface = VisibleSurface.NONE
    }

    private const val TAG = "StartupWorkPriority"
}
