package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.entities.CallLogEntity
import com.goodwy.commons.providercache.entities.ContactPhoneIndexEntity
import com.goodwy.commons.providercache.entities.ContactSummaryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentGroupRelationalBuilderTest {

    private val summary = ContactSummaryEntity(
        contactId = 42,
        lookupKey = "lk-42",
        displayName = "Alice",
        photoThumbnailUri = "",
        hasPhoneNumber = true,
        lastUpdatedTimestamp = 0L,
    )

    private val phoneIndex = ContactPhoneIndexEntity(
        id = 1L,
        contactId = 42,
        normalizedNumber = "+15551234",
        digits = "5551234",
        phoneDigits = "5551234",
    )

    private val context = RecentGroupIdentityResolver.Context(
        summariesById = mapOf(42 to summary),
        phoneIndexByDigits = RecentGroupIdentityResolver.buildPhoneIndexByDigits(listOf(phoneIndex)),
    )

    @Test
    fun byNumber_repeatedCallsSameNumber_groupTogether() {
        val calls = listOf(
            call(1, "5551234", ts = 3000L),
            call(2, "5551234", ts = 2000L),
            call(3, "5551234", ts = 1000L),
        )
        val result = RecentGroupRelationalBuilder.build(calls, RecentGroupingMode.BY_NUMBER, context)
        assertEquals(1, result.groups.size)
        assertEquals("number:5551234", result.groups.single().groupKey)
        assertEquals(3, result.groups.single().callCount)
        assertEquals(3, result.calls.size)
        assertEquals(1L, result.groups.single().latestCallId)
    }

    @Test
    fun byContact_truncatedCallLogNumber_matchesContactBySuffix() {
        // Suffix match requires ≥ MIN_SUFFIX_MATCH_DIGITS (7); shorter truncations stay number:.
        val fullIndex = phoneIndex.copy(
            normalizedNumber = "+1555125549",
            digits = "1555125549",
            phoneDigits = "1555125549",
        )
        val ctx = context.copy(
            phoneIndexByDigits = RecentGroupIdentityResolver.buildPhoneIndexByDigits(listOf(fullIndex)),
        )
        val calls = listOf(
            call(1, "555125549", ts = 2000L, normalized = "555125549"),
            call(2, "1555125549", ts = 1000L, normalized = "1555125549", contactId = 42),
        )
        val result = RecentGroupRelationalBuilder.build(calls, RecentGroupingMode.BY_CONTACT, ctx)
        assertEquals(1, result.groups.size)
        assertEquals("contact:42", result.groups.single().groupKey)
    }

    @Test
    fun byNumber_formattingVariants_normalizeTogether() {
        val calls = listOf(
            call(1, "(555) 123-4", normalized = "5551234", ts = 2000L),
            call(2, "555-1234", normalized = "5551234", ts = 1000L),
        )
        val result = RecentGroupRelationalBuilder.build(calls, RecentGroupingMode.BY_NUMBER, context)
        assertEquals(1, result.groups.size)
        assertEquals(2, result.calls.size)
    }

    @Test
    fun byContact_knownContact_groupsByContactKey() {
        val calls = listOf(
            call(1, "5551234", contactId = 42, ts = 2000L, cachedName = "Alice"),
            call(2, "5559999", contactId = 42, ts = 1000L, normalized = "5559999", cachedName = "Alice"),
        )
        val index = phoneIndex.copy(id = 2L, normalizedNumber = "5559999", digits = "5559999", phoneDigits = "5559999")
        val ctx = context.copy(
            phoneIndexByDigits = RecentGroupIdentityResolver.buildPhoneIndexByDigits(
                listOf(phoneIndex, index),
            ),
        )
        val result = RecentGroupRelationalBuilder.build(calls, RecentGroupingMode.BY_CONTACT, ctx)
        assertEquals(1, result.groups.size)
        assertEquals("contact:42", result.groups.single().groupKey)
        assertEquals(42L, result.groups.single().displayContactId)
        assertEquals(2, result.calls.size)
        // Head number must be the latest dialed number, not an older sibling.
        assertEquals("5551234", result.groups.single().primaryNumber)
        assertEquals(1L, result.groups.single().latestCallId)
    }

    @Test
    fun byContact_newerNumberBecomesPrimaryNumber() {
        val older = phoneIndex.copy(
            id = 1L,
            normalizedNumber = "1915882855",
            digits = "1915882855",
            phoneDigits = "1915882855",
        )
        val newer = phoneIndex.copy(
            id = 2L,
            normalizedNumber = "1957007653",
            digits = "1957007653",
            phoneDigits = "1957007653",
        )
        val ctx = context.copy(
            phoneIndexByDigits = RecentGroupIdentityResolver.buildPhoneIndexByDigits(listOf(older, newer)),
        )
        val calls = listOf(
            call(10, "1957007653", contactId = 42, ts = 5_000L, normalized = "1957007653", cachedName = "Test123"),
            call(9, "1915882855", contactId = 42, ts = 4_000L, normalized = "1915882855", cachedName = "Test123"),
            call(8, "1915882855", contactId = 42, ts = 3_000L, normalized = "1915882855", cachedName = "Test123"),
        )
        val result = RecentGroupRelationalBuilder.build(calls, RecentGroupingMode.BY_CONTACT, ctx)
        assertEquals(1, result.groups.size)
        assertEquals("contact:42", result.groups.single().groupKey)
        assertEquals(3, result.groups.single().callCount)
        assertEquals("1957007653", result.groups.single().primaryNumber)
        assertEquals(10L, result.groups.single().latestCallId)
    }

    @Test
    fun byContact_sameDisplayNameDifferentContactIds_doNotMerge() {
        val summary100 = summary.copy(contactId = 100, lookupKey = "lk-100", displayName = "John Smith")
        val summary200 = summary.copy(contactId = 200, lookupKey = "lk-200", displayName = "JOHN SMITH")
        val index100 = phoneIndex.copy(id = 1L, contactId = 100, normalizedNumber = "5551000", digits = "5551000", phoneDigits = "5551000")
        val index200 = phoneIndex.copy(id = 2L, contactId = 200, normalizedNumber = "5552000", digits = "5552000", phoneDigits = "5552000")
        val ctx = RecentGroupIdentityResolver.Context(
            summariesById = mapOf(100 to summary100, 200 to summary200),
            phoneIndexByDigits = RecentGroupIdentityResolver.buildPhoneIndexByDigits(listOf(index100, index200)),
        )
        val calls = listOf(
            call(1, "5551000", contactId = 100, ts = 3000L, normalized = "5551000", cachedName = "John Smith"),
            call(2, "5552000", contactId = 200, ts = 2000L, normalized = "5552000", cachedName = "john smith"),
        )
        val result = RecentGroupRelationalBuilder.build(calls, RecentGroupingMode.BY_CONTACT, ctx)
        // Two different contacts sharing a display name must stay separate — group identity is the
        // aggregate contact id, never the display name.
        assertEquals(2, result.groups.size)
        val groupKeys = result.groups.map { it.groupKey }.toSet()
        assertEquals(setOf("contact:100", "contact:200"), groupKeys)
        assertEquals(2, result.calls.size)
    }

    @Test
    fun byNumber_latestWithoutContact_promotesSiblingContactId() {
        val calls = listOf(
            call(1, "5551234", ts = 3000L, contactId = null, cachedName = ""),
            call(2, "5551234", ts = 2000L, contactId = 42, cachedName = "Alice"),
        )
        val result = RecentGroupRelationalBuilder.build(calls, RecentGroupingMode.BY_NUMBER, context)
        assertEquals(1, result.groups.size)
        assertEquals(1L, result.groups.single().latestCallId)
        assertEquals(42L, result.groups.single().displayContactId)
    }

    @Test
    fun byContact_latestUnresolved_coalescesIntoSiblingContactGroup() {
        // No phone-index: latest stays number:, older still resolves via contactID in summaries.
        val ctx = context.copy(phoneIndexByDigits = emptyMap())
        val calls = listOf(
            call(1, "5551234", ts = 3000L, contactId = null, cachedName = ""),
            call(2, "5551234", ts = 2000L, contactId = 42, cachedName = "Alice"),
        )
        val result = RecentGroupRelationalBuilder.build(calls, RecentGroupingMode.BY_CONTACT, ctx)
        assertEquals(1, result.groups.size)
        assertEquals("contact:42", result.groups.single().groupKey)
        assertEquals(42L, result.groups.single().displayContactId)
        assertEquals(2, result.groups.single().callCount)
    }

    @Test
    fun byContact_unknownCall_fallsBackToNumberGroup() {
        val calls = listOf(call(9, "9998887777", ts = 1000L, normalized = "9998887777"))
        val result = RecentGroupRelationalBuilder.build(calls, RecentGroupingMode.BY_CONTACT, context)
        assertEquals("number:9998887777", result.groups.single().groupKey)
        assertEquals(null, result.groups.single().displayContactId)
    }

    @Test
    fun invariant_eachCallAssignedExactlyOnce() {
        val calls = (1..5).map { id ->
            call(id, "555000$id", ts = id * 1000L, normalized = "555000$id")
        }
        val result = RecentGroupRelationalBuilder.build(calls, RecentGroupingMode.BY_NUMBER, context)
        assertEquals(calls.size, result.calls.size)
        assertEquals(calls.size, result.calls.map { it.callId }.toSet().size)
        assertEquals(calls.size, result.groups.sumOf { it.callCount })
    }

    @Test
    fun sort_latestTimestampDescThenCallIdDesc() {
        val calls = listOf(
            call(1, "111", ts = 1000L),
            call(2, "222", ts = 2000L),
        )
        val result = RecentGroupRelationalBuilder.build(calls, RecentGroupingMode.BY_NUMBER, context)
        assertTrue(result.groups.first().latestTimestamp >= result.groups.last().latestTimestamp)
    }

    private fun call(
        id: Int,
        number: String,
        ts: Long,
        normalized: String = number.filter { it.isDigit() }.ifEmpty { number },
        contactId: Int? = null,
        cachedName: String = "",
    ) = CallLogEntity(
        callId = id,
        phoneNumber = number,
        cachedName = cachedName,
        cachedPhotoUri = "",
        startTS = ts,
        duration = 0,
        type = 1,
        simID = 0,
        normalizedNumber = normalized,
        contactID = contactId,
    )
}
