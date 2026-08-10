package com.goodwy.commons.providercache.filter

import com.goodwy.commons.models.contacts.Contact
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactSourceAccountFilterTest {

    @Test
    fun keepsContactsFromVisibleSources() {
        val contacts = listOf(
            contact(source = "Google"),
            contact(source = "SIM"),
        )
        val filtered = ContactSourceAccountFilter.filter(contacts, setOf("Google"))
        assertEquals(1, filtered.size)
        assertEquals("Google", filtered.first().source)
    }

    @Test
    fun hidesPhoneStorageWhenEmptySourceNotVisible() {
        val contacts = listOf(
            contact(source = ""),
            contact(source = "SIM"),
        )
        val filtered = ContactSourceAccountFilter.filter(contacts, setOf("SIM"))
        assertEquals(1, filtered.size)
        assertEquals("SIM", filtered.first().source)
    }

    @Test
    fun keepsPhoneStorageWhenEmptySourceIsVisible() {
        val contacts = listOf(
            contact(source = ""),
            contact(source = "SIM"),
        )
        val filtered = ContactSourceAccountFilter.filter(contacts, setOf("", "SIM"))
        assertEquals(2, filtered.size)
    }

    @Test
    fun emptyVisibleSetReturnsAllContacts() {
        val contacts = listOf(contact(source = "Google"), contact(source = "SIM"))
        assertEquals(2, ContactSourceAccountFilter.filter(contacts, emptySet()).size)
    }

    private fun contact(source: String): Contact = Contact(
        id = source.hashCode(),
        contactId = source.hashCode(),
        firstName = source.ifEmpty { "No source" },
        source = source,
    )
}
