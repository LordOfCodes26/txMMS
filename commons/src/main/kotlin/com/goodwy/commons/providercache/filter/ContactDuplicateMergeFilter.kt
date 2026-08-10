package com.goodwy.commons.providercache.filter

import com.goodwy.commons.models.contacts.Contact
import java.util.Locale

/**
 * Display-time duplicate collapse extracted from [com.goodwy.commons.helpers.ContactsHelper.getContacts].
 */
object ContactDuplicateMergeFilter {

    fun mergeKey(contact: Contact): String {
        val nameKey = contact.getNameToDisplay().lowercase(Locale.getDefault())
        val firstPhone = contact.phoneNumbers.firstOrNull()?.normalizedNumber?.trim().orEmpty()
        val firstEmail = contact.emails.firstOrNull()?.value?.trim().orEmpty()
        return "$nameKey\t$firstPhone\t$firstEmail"
    }

    /**
     * Collapses duplicates among [contacts] from visible sources.
     * Contacts from non-visible sources are kept as-is (not merged).
     */
    fun mergePage(
        contacts: List<Contact>,
        visibleSourceNames: Set<String>,
        mergeEnabled: Boolean,
        seenKeys: MutableSet<String>,
    ): List<Contact> {
        if (!mergeEnabled || contacts.isEmpty()) return contacts
        val visible = visibleSourceNames.toHashSet()
        val result = ArrayList<Contact>(contacts.size)
        for (contact in contacts) {
            if (contact.source.isNotEmpty() && !visible.contains(contact.source)) {
                result.add(contact)
                continue
            }
            val key = mergeKey(contact)
            if (seenKeys.add(key)) {
                result.add(contact)
            }
        }
        return result
    }

    /**
     * Full-list merge for batch operations (sync rebuild).
     */
    fun mergeAll(
        contacts: List<Contact>,
        visibleSourceNames: Set<String>,
        mergeEnabled: Boolean,
    ): List<Contact> {
        if (!mergeEnabled || contacts.isEmpty()) return contacts
        val visible = visibleSourceNames.toHashSet()
        val contactsToMerge = ArrayList<Contact>()
        val contactsToAdd = ArrayList<Contact>()
        for (contact in contacts) {
            if (visible.contains(contact.source)) {
                contactsToMerge.add(contact)
            } else {
                contactsToAdd.add(contact)
            }
        }
        val grouped = HashMap<String, ArrayList<Contact>>()
        for (contact in contactsToMerge) {
            grouped.getOrPut(mergeKey(contact)) { ArrayList() }.add(contact)
        }
        val merged = ArrayList<Contact>(grouped.size + contactsToAdd.size)
        for (group in grouped.values) {
            if (group.size == 1) {
                merged.add(group.first())
            } else {
                val best = group.maxByOrNull { c ->
                    val len = c.getStringToCompare().length
                    if (c.phoneNumbers.isNotEmpty()) len + 1000 else len
                } ?: group.first()
                merged.add(best)
            }
        }
        merged.addAll(contactsToAdd)
        return merged
    }
}
