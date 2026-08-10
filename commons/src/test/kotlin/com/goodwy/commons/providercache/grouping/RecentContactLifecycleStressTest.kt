package com.goodwy.commons.providercache.grouping

import com.goodwy.commons.providercache.display.AffectedRecentGroups
import com.goodwy.commons.providercache.display.CanonicalPhoneNumberResolver
import com.goodwy.commons.providercache.display.RecentGroupMutationPlanner
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.entities.CallLogEntity
import com.goodwy.commons.providercache.entities.ContactPhoneIndexEntity
import com.goodwy.commons.providercache.entities.ContactSummaryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Delete / merge / split / shared-number stress scenarios at engine level.
 */
class RecentContactLifecycleStressTest {

    private val engine = DefaultRecentGroupingEngine

    @Test
    fun deleteContactWithTwoNumbers_splitsToNumberGroups() = runBlocking {
        val calls = listOf(
            call(1, "5551000", 300L, contactId = 5),
            call(2, "5552000", 200L, contactId = 5),
        )
        val snapshot = snapshotWithoutContact(5)
        val affected = AffectedRecentGroups(
            oldGroupKeys = setOf("contact:5"),
            newGroupKeys = setOf("number:5551000", "number:5552000"),
            affectedNumbers = setOf("5551000", "5552000"),
            affectedContactIds = setOf(5L),
        )
        val result = engine.rebuildAffected(
            RecentGroupingMode.BY_CONTACT,
            affected,
            calls,
            snapshot,
        )
        assertTrue(result.validation.valid)
        assertEquals(2, result.groups.size)
        assertEquals(2, result.groups.sumOf { it.callCount })
    }

    @Test
    fun mergeTwoContacts_combinesMembership() = runBlocking {
        val calls = listOf(
            call(1, "5551000", 300L, contactId = 10),
            call(2, "5552000", 200L, contactId = 10),
        )
        val snapshot = snapshotWithContact(10, listOf("5551000", "5552000"))
        val full = engine.build(RecentGroupingMode.BY_CONTACT, calls, snapshot)
        assertTrue(full.validation.valid)
        assertEquals(1, full.groups.size)
        assertEquals(2, full.groups.single().callCount)
    }

    @Test
    fun linkTwoUnsavedLocalNumbers_toContactWithE164_oneGroupNoOrphans() = runBlocking {
        // Call log stored dialed local forms; contact phone index has E.164 digits.
        val localA = "01021814406"
        val localB = "01098765432"
        val e164A = "821021814406"
        val e164B = "821098765432"
        val contactId = 42
        val calls = listOf(
            call(1, localA, 300L, contactId = contactId),
            call(2, localB, 200L, contactId = contactId),
        )
        val snapshot = snapshotWithContact(contactId, listOf(e164A, e164B))
        val planned = RecentGroupMutationPlanner.forPhoneEditMulti(
            contactId = contactId.toLong(),
            oldDigits = emptySet(),
            newDigits = setOf(e164A, e164B),
        )
        // Without expansion, planned keys miss local-form display keys (the bug).
        assertTrue(
            !planned.allGroupKeys.contains(CanonicalPhoneNumberResolver.numberGroupKey(localA)),
        )
        val expanded = RecentGroupMutationPlanner.expandWithExisting(
            base = planned,
            existingGroupKeys = setOf(
                CanonicalPhoneNumberResolver.numberGroupKey(localA),
                CanonicalPhoneNumberResolver.numberGroupKey(localB),
            ),
            additionalNumbers = listOf(localA, localB),
        )
        val result = engine.rebuildAffected(
            RecentGroupingMode.BY_CONTACT,
            expanded,
            calls,
            snapshot,
        )
        assertTrue(result.validation.valid)
        assertEquals(1, result.groups.size)
        assertEquals("contact:$contactId", result.groups.single().groupKey)
        assertEquals(2, result.groups.single().callCount)
        assertEquals(setOf(1, 2), result.memberships.map { it.callId.toInt() }.toSet())
        // Expanded delete set must cover both local orphans and E.164 planned keys.
        assertTrue(expanded.allGroupKeys.contains("number:$localA"))
        assertTrue(expanded.allGroupKeys.contains("number:$localB"))
        assertTrue(expanded.allGroupKeys.contains("number:$e164A"))
        assertTrue(expanded.allGroupKeys.contains("contact:$contactId"))
        // No leftover number: groups in the rebuild result.
        assertTrue(result.groups.none { it.groupKey.startsWith("number:") })
    }

    @Test
    fun sharedNumber_byNumber_singleGroup() = runBlocking {
        val calls = listOf(
            call(1, "5551000", 300L, contactId = 10),
            call(2, "5551000", 200L, contactId = 20),
        )
        val snapshot = RecentContactResolutionSnapshot(
            contactsById = mapOf(10L to summary(10), 20L to summary(20)),
            phoneIndexByCanonicalNumber = mapOf(
                "5551000" to listOf(phoneIndex(10, "5551000"), phoneIndex(20, "5551000")),
            ),
            displayByContactId = emptyMap(),
            visibleContactIds = setOf(10L, 20L),
            starredContactIds = emptySet(),
            contactsWithValidPhoto = emptySet(),
        )
        val full = engine.build(RecentGroupingMode.BY_NUMBER, calls, snapshot)
        assertTrue(full.validation.valid)
        assertEquals(1, full.groups.size)
        assertEquals("number:5551000", full.groups.single().groupKey)
        assertEquals(2, full.groups.single().callCount)
    }

    private fun call(id: Int, number: String, ts: Long, contactId: Int? = null) = CallLogEntity(
        callId = id,
        phoneNumber = number,
        cachedName = "",
        cachedPhotoUri = "",
        startTS = ts,
        duration = 0,
        type = 1,
        simID = -1,
        simTypeID = 1,
        simColor = 0,
        contactID = contactId,
        features = null,
        isUnknownNumber = contactId == null,
        isVoiceMail = false,
        blockReason = null,
        phoneAccountId = "",
        normalizedNumber = number,
    )

    private fun summary(id: Int) = ContactSummaryEntity(
        contactId = id,
        displayName = "C$id",
        lookupKey = "lk$id",
        photoThumbnailUri = "",
        hasPhoneNumber = true,
        lastUpdatedTimestamp = 0L,
    )

    private fun phoneIndex(contactId: Int, number: String) = ContactPhoneIndexEntity(
        id = contactId.toLong(),
        contactId = contactId,
        normalizedNumber = number,
        phoneDigits = number,
        digits = number,
    )

    private fun snapshotWithContact(contactId: Int, numbers: List<String>) = RecentContactResolutionSnapshot(
        contactsById = mapOf(contactId.toLong() to summary(contactId)),
        phoneIndexByCanonicalNumber = numbers.associateWith { listOf(phoneIndex(contactId, it)) },
        displayByContactId = emptyMap(),
        visibleContactIds = setOf(contactId.toLong()),
        starredContactIds = emptySet(),
        contactsWithValidPhoto = emptySet(),
    )

    private fun snapshotWithoutContact(deletedId: Int) = RecentContactResolutionSnapshot(
        contactsById = emptyMap(),
        phoneIndexByCanonicalNumber = emptyMap(),
        displayByContactId = emptyMap(),
        visibleContactIds = emptySet(),
        starredContactIds = emptySet(),
        contactsWithValidPhoto = emptySet(),
    )
}
