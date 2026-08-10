package com.goodwy.commons.providercache.filter

import com.goodwy.commons.models.contacts.Contact

/**
 * Hides contacts whose [Contact.source] (account name) is not in the visible set.
 *
 * Phone storage uses an empty account name (`""`), which is included in
 * [com.goodwy.commons.extensions.getVisibleContactSources] when phone storage is selected.
 * Do not special-case empty source — that would keep phone contacts visible even when filtered out.
 */
object ContactSourceAccountFilter {

    fun filter(contacts: List<Contact>, visibleSourceNames: Set<String>): List<Contact> {
        if (visibleSourceNames.isEmpty()) return contacts
        return contacts.filter { contact ->
            visibleSourceNames.contains(contact.source)
        }
    }
}
