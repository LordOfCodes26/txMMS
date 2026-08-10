package com.goodwy.commons.helpers

import com.goodwy.commons.models.contacts.ContactDisplayBind
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps aggregated [contactId] to [rawContactId] and precomputed contact-list avatar bind.
 * Recents and search use the same identity/version as the contacts display cache.
 */
object AvatarIdentityResolver {

    private val rawIdByContactId = ConcurrentHashMap<Int, Int>()
    private val displayBindByContactId = ConcurrentHashMap<Int, ContactDisplayBind>()

    fun register(contactId: Int, rawId: Int, displayBind: ContactDisplayBind? = null) {
        if (contactId <= 0 || rawId <= 0) return
        rawIdByContactId[contactId] = rawId
        if (displayBind != null) {
            displayBindByContactId[contactId] = displayBind
        }
    }

    fun registerAll(entries: Map<Int, Pair<Int, ContactDisplayBind?>>) {
        entries.forEach { (contactId, pair) ->
            register(contactId, pair.first, pair.second)
        }
    }

    fun resolveRawId(contactId: Int, fallback: Int = 0): Int {
        if (contactId <= 0) return fallback
        return rawIdByContactId[contactId] ?: contactId
    }

    fun displayBindFor(contactId: Int): ContactDisplayBind? =
        if (contactId <= 0) null else displayBindByContactId[contactId]

    fun clearAll() {
        rawIdByContactId.clear()
        displayBindByContactId.clear()
    }

    fun removeContact(contactId: Int) {
        if (contactId <= 0) return
        rawIdByContactId.remove(contactId)
        displayBindByContactId.remove(contactId)
    }

    fun registerFromDisplayEntities(entities: Collection<com.goodwy.commons.providercache.entities.ContactDisplayCacheEntity>) {
        entities.forEach { entity ->
            val displayBind = AvatarBindComputer.enrichContactDisplayBind(
                com.goodwy.commons.providercache.display.ContactDisplayBindComputer.toDisplayBind(entity),
            )
            register(
                contactId = entity.contactId,
                rawId = entity.rawId,
                displayBind = displayBind,
            )
        }
    }
}
