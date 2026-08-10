package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.entities.CallLogEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentGroupMemberContactTest {

    @Test
    fun coalesce_shortDialSuffix_doesNotMergeIntoLongerContactNumber() {
        // Identity suffix match requires ≥7 digits; a 5-digit dial must not coalesce.
        val grouped = linkedMapOf(
            "contact:42" to mutableListOf(
                call(1, contactId = 42, name = "Alice", number = "061922255", normalized = "061922255"),
            ),
            "number:22255" to mutableListOf(
                call(2, contactId = null, name = "", number = "22255", normalized = "22255"),
            ),
        )
        val result = RecentGroupMemberContact.coalesceNumberBucketsIntoContact(grouped)
        assertTrue(result.containsKey("contact:42"))
        assertTrue(result.containsKey("number:22255"))
        assertEquals(1, result["contact:42"]?.size)
        assertEquals(1, result["number:22255"]?.size)
    }

    @Test
    fun coalesce_exactDigits_mergesUnresolvedIntoContact() {
        val grouped = linkedMapOf(
            "contact:42" to mutableListOf(
                call(1, contactId = 42, name = "Alice", number = "5551234", normalized = "5551234"),
            ),
            "number:5551234" to mutableListOf(
                call(2, contactId = null, name = "", number = "5551234", normalized = "5551234"),
            ),
        )
        val result = RecentGroupMemberContact.coalesceNumberBucketsIntoContact(grouped)
        assertEquals(setOf("contact:42"), result.keys)
        assertEquals(2, result["contact:42"]?.size)
    }

    @Test
    fun pickContactDonor_prefersMemberWithContactId() {
        val members = listOf(
            call(1, contactId = null, name = ""),
            call(2, contactId = 42, name = "Alice"),
        )
        val donor = RecentGroupMemberContact.pickContactDonor(members)
        assertEquals(2, donor?.callId)
        assertEquals(42, donor?.contactID)
    }

    @Test
    fun pickContactDonor_fallsBackToNonPhoneCachedName() {
        val members = listOf(
            call(1, contactId = null, name = ""),
            call(2, contactId = null, name = "Bob"),
        )
        val donor = RecentGroupMemberContact.pickContactDonor(members)
        assertEquals(2, donor?.callId)
        assertEquals("Bob", donor?.cachedName)
    }

    @Test
    fun pickContactDonor_emptyWhenNoContactMetadata() {
        val members = listOf(
            call(1, contactId = null, name = "5551234"),
            call(2, contactId = null, name = ""),
        )
        assertNull(RecentGroupMemberContact.pickContactDonor(members))
    }

    @Test
    fun pickContactDonor_prefersContactIdOverEarlierCachedName() {
        // Newest head (id=3) has no metadata; older sibling with contactId wins over name-only.
        val members = listOf(
            call(3, contactId = null, name = ""),
            call(2, contactId = null, name = "NameOnly"),
            call(1, contactId = 99, name = "Saved"),
        )
        val donor = RecentGroupMemberContact.pickContactDonor(members)
        assertEquals(1, donor?.callId)
        assertEquals(99, donor?.contactID)
        assertEquals("Saved", donor?.cachedName)
    }

    private fun call(
        id: Int,
        contactId: Int?,
        name: String,
        number: String = "5551234",
        normalized: String = number,
    ) = CallLogEntity(
        callId = id,
        phoneNumber = number,
        cachedName = name,
        cachedPhotoUri = "",
        startTS = id * 1000L,
        duration = 0,
        type = 1,
        simID = 0,
        normalizedNumber = normalized,
        contactID = contactId,
    )
}
