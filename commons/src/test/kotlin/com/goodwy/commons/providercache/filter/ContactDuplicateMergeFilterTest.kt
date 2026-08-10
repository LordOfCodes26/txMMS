package com.goodwy.commons.providercache.filter

import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.models.PhoneNumber
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactDuplicateMergeFilterTest {

    @Test
    fun mergePageHidesDuplicateWhenSeenKeyAlreadyPresent() {
        val seen = mutableSetOf<String>()
        val contact = sampleContact(name = "John", phone = "5551234", source = "Google")
        assertEquals(1, merge(contact, seen).size)
        assertEquals(0, merge(contact, seen).size)
    }

    @Test
    fun clearingSeenKeysAllowsDuplicateAgain() {
        val seen = mutableSetOf<String>()
        val contact = sampleContact(name = "John", phone = "5551234", source = "Google")
        merge(contact, seen)
        merge(contact, seen)
        seen.clear()
        assertEquals(1, merge(contact, seen).size)
    }

    @Test
    fun contactsFromHiddenSourcesAreNotMerged() {
        val seen = mutableSetOf<String>()
        val hidden = sampleContact(name = "John", phone = "5551234", source = "Hidden")
        val visible = sampleContact(name = "John", phone = "5551234", source = "Google")
        val result = ContactDuplicateMergeFilter.mergePage(
            contacts = listOf(hidden, visible),
            visibleSourceNames = setOf("Google"),
            mergeEnabled = true,
            seenKeys = seen,
        )
        assertEquals(2, result.size)
    }

    private fun merge(contact: Contact, seen: MutableSet<String>): List<Contact> =
        ContactDuplicateMergeFilter.mergePage(
            contacts = listOf(contact),
            visibleSourceNames = setOf("Google"),
            mergeEnabled = true,
            seenKeys = seen,
        )

    private fun sampleContact(name: String, phone: String, source: String): Contact = Contact(
        id = name.hashCode(),
        contactId = name.hashCode(),
        firstName = name,
        source = source,
        phoneNumbers = arrayListOf(PhoneNumber(phone, 0, "", phone, true)),
    )
}
