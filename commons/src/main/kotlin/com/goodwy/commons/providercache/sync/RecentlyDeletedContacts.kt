package com.goodwy.commons.providercache.sync

import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived record of contacts the app has just deleted, so a sync cannot put them back.
 *
 * Deleting a contact purges it from Room, but a sync that started *before* the delete is holding a
 * provider snapshot that still contains it. `ContactsSyncManager` joins in-flight syncs rather than
 * cancelling them, so that stale snapshot runs to completion and its `insertSummaries` lands after
 * the purge — the row reappears, and the user sees a contact they deleted come back on resume.
 *
 * A fresh read cannot cause this: the list reads `Contacts.CONTENT_URI`, which the provider clears
 * as soon as the last raw contact is deleted. Only a snapshot taken before the delete can, which is
 * why the window this covers is short.
 *
 * Both id spaces are recorded because the delete path learns them at different moments: raw ids are
 * known at once, aggregate ids only after resolving each chunk. A sync entity is suppressed if
 * either matches.
 *
 * Deliberately not a global sync suppression. Blocking sync for the whole purge would also drop
 * unrelated contact updates that happen to land in the same window; this refuses exactly the rows
 * that were deleted and lets everything else through.
 */
object RecentlyDeletedContacts {

    /**
     * How long a deleted id stays refused.
     *
     * Long enough to outlast an in-flight sync, short enough that re-creating the same contact is
     * not blocked in practice. If a provider ever reuses an aggregate id within this window the
     * re-add would be skipped until it expires, which is why [forget] exists for paths that know
     * they are creating a contact.
     */
    private const val TTL_MS = 15_000L

    private val deletedContactIds = ConcurrentHashMap<Int, Long>()
    private val deletedRawIds = ConcurrentHashMap<Int, Long>()

    fun rememberContactIds(contactIds: Collection<Int>) {
        if (contactIds.isEmpty()) return
        val now = System.currentTimeMillis()
        contactIds.forEach { if (it > 0) deletedContactIds[it] = now }
        prune(now)
    }

    fun rememberRawIds(rawIds: Collection<Int>) {
        if (rawIds.isEmpty()) return
        val now = System.currentTimeMillis()
        rawIds.forEach { if (it > 0) deletedRawIds[it] = now }
        prune(now)
    }

    /** True when a sync must not write this row back. */
    fun isSuppressed(contactId: Int, primaryRawId: Int): Boolean {
        val now = System.currentTimeMillis()
        return isFresh(deletedContactIds[contactId], now) || isFresh(deletedRawIds[primaryRawId], now)
    }

    /** Drops the record, for callers that are deliberately re-creating a contact. */
    fun forget(contactIds: Collection<Int> = emptyList(), rawIds: Collection<Int> = emptyList()) {
        contactIds.forEach { deletedContactIds.remove(it) }
        rawIds.forEach { deletedRawIds.remove(it) }
    }

    fun clear() {
        deletedContactIds.clear()
        deletedRawIds.clear()
    }

    private fun isFresh(stampedAt: Long?, now: Long): Boolean =
        stampedAt != null && now - stampedAt < TTL_MS

    private fun prune(now: Long) {
        deletedContactIds.entries.removeAll { now - it.value >= TTL_MS }
        deletedRawIds.entries.removeAll { now - it.value >= TTL_MS }
    }
}
