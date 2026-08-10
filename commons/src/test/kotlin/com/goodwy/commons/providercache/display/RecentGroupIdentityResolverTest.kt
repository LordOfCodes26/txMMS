package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.entities.ContactPhoneIndexEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentGroupIdentityResolverTest {

    @Test
    fun byNumber_groupKeyUsesCanonicalNumberPrefix() {
        val identity = RecentGroupIdentityResolver.resolve(
            call = testCall(normalized = "5551234", phone = "(555) 123-4"),
            mode = RecentGroupingMode.BY_NUMBER,
            context = RecentGroupIdentityResolver.Context(emptyMap(), emptyMap()),
        )
        assertEquals("number:5551234", identity.groupKey)
    }

    @Test
    fun byContact_resolvedContactUsesContactPrefix() {
        val identity = RecentGroupIdentityResolver.resolve(
            call = testCall(normalized = "5551234", phone = "5551234", contactId = 7),
            mode = RecentGroupingMode.BY_CONTACT,
            context = RecentGroupIdentityResolver.Context(
                summariesById = mapOf(
                    7 to com.goodwy.commons.providercache.entities.ContactSummaryEntity(
                        contactId = 7,
                        lookupKey = "k",
                        displayName = "Bob",
                        photoThumbnailUri = "",
                        hasPhoneNumber = true,
                        lastUpdatedTimestamp = 0L,
                    ),
                ),
                phoneIndexByDigits = emptyMap(),
            ),
        )
        assertEquals("contact:7", identity.groupKey)
        assertEquals(7L, identity.displayContactId)
    }

    @Test
    fun shortDialedNumber_doesNotSuffixMatchLongerContactHomeNumber() {
        // Real bug: dialed "22255" was matched to contact "061-92-2255".
        val index = RecentGroupIdentityResolver.buildPhoneIndexByDigits(
            listOf(
                ContactPhoneIndexEntity(
                    id = 1,
                    contactId = 42,
                    normalizedNumber = "061922255",
                    digits = "061922255",
                    phoneDigits = "061922255",
                ),
            ),
        )
        val rows = RecentGroupIdentityResolver.phoneIndexRowsForCanonical(
            canonical = "22255",
            context = RecentGroupIdentityResolver.Context(emptyMap(), index),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun sevenDigitTruncation_stillSuffixMatchesSingleContact() {
        val index = RecentGroupIdentityResolver.buildPhoneIndexByDigits(
            listOf(
                ContactPhoneIndexEntity(
                    id = 1,
                    contactId = 42,
                    normalizedNumber = "15551234567",
                    digits = "15551234567",
                    phoneDigits = "15551234567",
                ),
            ),
        )
        val rows = RecentGroupIdentityResolver.phoneIndexRowsForCanonical(
            canonical = "5551234567",
            context = RecentGroupIdentityResolver.Context(emptyMap(), index),
        )
        assertEquals(1, rows.size)
        assertEquals(42, rows[0].contactId)
    }

    private fun testCall(
        normalized: String,
        phone: String,
        contactId: Int? = null,
    ) = com.goodwy.commons.providercache.entities.CallLogEntity(
        callId = 1,
        phoneNumber = phone,
        cachedName = "Cached",
        cachedPhotoUri = "",
        startTS = 1000L,
        duration = 0,
        type = 1,
        simID = 0,
        normalizedNumber = normalized,
        contactID = contactId,
    )
}
