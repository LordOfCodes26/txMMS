package com.goodwy.commons.providercache.validation.legacy

import com.goodwy.commons.helpers.SMT_PRIVATE

/**
 * Policy for private ([SMT_PRIVATE]) contacts in the provider-cache mirror.
 *
 * The Room mirror stores whatever [ContactsSyncManager] ingests from ContactsContract.
 * Private contacts are **not** a separate cache tier — visibility is enforced at:
 * - display-cache build ([ContactSourceAccountFilter] + visible sources)
 * - Room SQL push-down ([ContactRoomQueryFilters])
 * - app-layer secure-mode filters
 */
object PrivateContactsCachePolicy {

    /**
     * Returns whether a contact [source] should appear in UI-facing display cache rows.
     * Raw summaries may still exist in Room until the next sync/rebuild prunes them.
     */
    fun shouldIncludeInDisplayCache(source: String, visibleSources: Set<String>): Boolean =
        when (source) {
            SMT_PRIVATE -> SMT_PRIVATE in visibleSources
            else -> source in visibleSources
        }

    /** Private contacts are never read from legacy disk caches when Room is authoritative. */
    fun legacyDiskCacheMayContainPrivateContacts(): Boolean = true
}
