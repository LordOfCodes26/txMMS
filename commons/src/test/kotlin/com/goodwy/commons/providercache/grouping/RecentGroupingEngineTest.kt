package com.goodwy.commons.providercache.grouping

import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.entities.CallLogEntity
import com.goodwy.commons.providercache.entities.ContactPhoneIndexEntity
import com.goodwy.commons.providercache.entities.ContactSummaryEntity
import com.goodwy.commons.providercache.entities.RecentGroupCallEntity
import com.goodwy.commons.providercache.entities.RecentGroupEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentGroupingInvariantValidatorTest {

    @Test
    fun validGroupKey_requiresPrefix() {
        assertTrue(RecentGroupingInvariantValidator.isValidGroupKey("contact:42"))
        assertTrue(RecentGroupingInvariantValidator.isValidGroupKey("number:5551234"))
        assertFalse(RecentGroupingInvariantValidator.isValidGroupKey("5551234"))
        assertFalse(RecentGroupingInvariantValidator.isValidGroupKey("name:test"))
    }

    @Test
    fun validate_detectsCountMismatch() {
        val calls = listOf(call(1, "5551000", 100L))
        val groups = listOf(
            RecentGroupEntity(
                groupingMode = 0,
                groupKey = "number:5551000",
                displayContactId = null,
                latestCallId = 1,
                latestTimestamp = 100L,
                callCount = 2,
                primaryNumber = "5551000",
                displayOrder = 0,
            ),
        )
        val memberships = listOf(
            RecentGroupCallEntity(groupingMode = 0, groupKey = "number:5551000", callId = 1),
        )
        val result = RecentGroupingInvariantValidator.validate(
            mode = RecentGroupingMode.BY_NUMBER,
            eligibleCalls = calls,
            groups = groups,
            memberships = memberships,
        )
        assertFalse(result.valid)
    }

    private fun call(id: Int, number: String, ts: Long) = CallLogEntity(
        callId = id,
        phoneNumber = number,
        normalizedNumber = number,
        cachedName = "",
        cachedPhotoUri = "",
        startTS = ts,
        duration = 0,
        type = 1,
        simID = -1,
    )
}

class DefaultRecentGroupingEngineTest {

    @Test
    fun byContact_sameContactTwoNumbers_oneGroup() {
        val snapshot = snapshotWithContact(42, listOf("5551000", "5552000"))
        val calls = listOf(
            call(1, "5551000", 200L, contactId = 42),
            call(2, "5552000", 100L, contactId = 42),
        )
        val result = kotlinx.coroutines.runBlocking {
            DefaultRecentGroupingEngine.build(
                mode = RecentGroupingMode.BY_CONTACT,
                calls = calls,
                contactSnapshot = snapshot,
            )
        }
        assertEquals(1, result.groups.size)
        assertEquals("contact:42", result.groups.first().groupKey)
        assertEquals(2, result.groups.first().callCount)
        assertTrue(result.validation.valid)
    }

    @Test
    fun byNumber_twoNumbers_twoGroups() {
        val snapshot = snapshotWithContact(42, listOf("5551000"))
        val calls = listOf(
            call(1, "5551000", 200L, contactId = 42),
            call(2, "5552000", 100L, contactId = null),
        )
        val result = kotlinx.coroutines.runBlocking {
            DefaultRecentGroupingEngine.build(
                mode = RecentGroupingMode.BY_NUMBER,
                calls = calls,
                contactSnapshot = snapshot,
            )
        }
        assertEquals(2, result.groups.size)
        assertTrue(result.groups.all { it.groupKey.startsWith("number:") })
    }

    private fun snapshotWithContact(contactId: Int, numbers: List<String>): RecentContactResolutionSnapshot {
        val summary = ContactSummaryEntity(
            contactId = contactId,
            displayName = "Test",
            lookupKey = "lk",
            photoThumbnailUri = "",
            hasPhoneNumber = true,
            lastUpdatedTimestamp = 0L,
        )
        val phoneIndex = numbers.mapIndexed { index, number ->
            ContactPhoneIndexEntity(
                id = (index + 1).toLong(),
                contactId = contactId,
                normalizedNumber = number,
                phoneDigits = number,
                digits = number,
            )
        }
        val byCanonical = phoneIndex.groupBy { it.normalizedNumber }
        return RecentContactResolutionSnapshot(
            contactsById = mapOf(contactId.toLong() to summary),
            phoneIndexByCanonicalNumber = byCanonical,
            displayByContactId = emptyMap(),
            visibleContactIds = setOf(contactId.toLong()),
            starredContactIds = emptySet(),
            contactsWithValidPhoto = emptySet(),
        )
    }

    private fun call(id: Int, number: String, ts: Long, contactId: Int?) = CallLogEntity(
        callId = id,
        phoneNumber = number,
        normalizedNumber = number,
        cachedName = "Test",
        cachedPhotoUri = "",
        startTS = ts,
        duration = 0,
        type = 1,
        simID = -1,
        contactID = contactId,
    )
}
