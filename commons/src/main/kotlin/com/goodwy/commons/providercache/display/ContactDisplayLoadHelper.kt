package com.goodwy.commons.providercache.display

import com.goodwy.commons.helpers.AvatarIdentityResolver
import com.goodwy.commons.models.contacts.Contact

object ContactDisplayLoadHelper {

    data class MapTimings(
        val entityMs: Long,
        val textMs: Long,
        val avatarMs: Long,
        val sectionMs: Long,
    ) {
        val totalMs: Long get() = entityMs + textMs + avatarMs + sectionMs
    }

    /** Direct field copy — no AvatarIdentityResolver work (defer via [registerAvatarIdentities]). */
    fun mapListRow(row: ContactDisplayListRow): Contact =
        ContactDisplayBindComputer.copyContactFromListRow(row)

    fun mapListRows(rows: List<ContactDisplayListRow>): Pair<List<Contact>, MapTimings> {
        if (rows.isEmpty()) return emptyList<Contact>() to MapTimings(0, 0, 0, 0)
        val out = ArrayList<Contact>(rows.size)
        for (row in rows) {
            out += mapListRow(row)
        }
        return out to MapTimings(0, 0, 0, 0)
    }

    /** Idle/post-paint registration for Recents/search avatar identity — not on the first-paint path. */
    fun registerAvatarIdentities(contacts: List<Contact>) {
        for (contact in contacts) {
            AvatarIdentityResolver.register(
                contactId = contact.contactId,
                rawId = contact.id,
                displayBind = contact.displayBind,
            )
        }
    }
}
