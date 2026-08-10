package com.goodwy.commons.providercache.grouping

import com.goodwy.commons.providercache.display.AffectedRecentGroups
import com.goodwy.commons.providercache.display.CallLogDigitGroupingGuard
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.entities.CallLogEntity
import com.goodwy.commons.providercache.entities.ContactPhoneIndexEntity
import com.goodwy.commons.providercache.entities.ContactSummaryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates incremental delete semantics for authoritative contact: keys — must never route through digit SQL.
 */
class RecentIncrementalGroupDeleteTest {

    @Test
    fun contactKey_guardRejectsDigitSqlQuery() {
        var failed = false
        try {
            CallLogDigitGroupingGuard.requireDigitGroupKeys(
                listOf("contact:2"),
                "getGroupedEntriesForGroupKeys",
            )
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun byContact_deleteOneCallFromTwoNumberContact_keepsSingleGroup() = runBlocking {
        val calls = listOf(
            call(1, "5551000", 300L, contactId = 42),
            call(2, "5552000", 200L, contactId = 42),
        )
        val remaining = calls.filter { it.callId != 2 }
        val affected = AffectedRecentGroups(
            oldGroupKeys = setOf("contact:42"),
            newGroupKeys = setOf("contact:42"),
            affectedNumbers = setOf("5552000"),
            affectedContactIds = setOf(42L),
        )
        val result = engine.rebuildAffected(
            RecentGroupingMode.BY_CONTACT,
            affected,
            remaining,
            snapshotWithContact(42, listOf("5551000", "5552000")),
        )
        assertTrue(result.validation.valid)
        assertEquals(1, result.groups.size)
        assertEquals("contact:42", result.groups.single().groupKey)
        assertEquals(1, result.groups.single().callCount)
        assertEquals(1, result.groups.single().latestCallId)
    }

    @Test
    fun byContact_deleteLastCallForContact_removesGroup() = runBlocking {
        val affected = AffectedRecentGroups(
            oldGroupKeys = setOf("contact:42"),
            newGroupKeys = setOf("contact:42"),
            affectedNumbers = setOf("5551000"),
            affectedContactIds = setOf(42L),
        )
        val result = engine.rebuildAffected(
            RecentGroupingMode.BY_CONTACT,
            affected,
            emptyList(),
            snapshotWithContact(42, listOf("5551000")),
        )
        assertTrue(result.groups.isEmpty())
    }

    @Test
    fun byNumber_deleteOneCallFromMultiCallGroup_updatesCount() = runBlocking {
        val calls = listOf(
            call(1, "5551000", 300L),
            call(2, "5551000", 200L),
        )
        val remaining = calls.filter { it.callId != 2 }
        val affected = AffectedRecentGroups(
            oldGroupKeys = setOf("number:5551000"),
            newGroupKeys = setOf("number:5551000"),
            affectedNumbers = setOf("5551000"),
            affectedContactIds = emptySet(),
        )
        val result = engine.rebuildAffected(
            RecentGroupingMode.BY_NUMBER,
            affected,
            remaining,
            emptySnapshot(),
        )
        assertTrue(result.validation.valid)
        assertEquals(1, result.groups.single().callCount)
        assertEquals(1, result.groups.single().latestCallId)
    }

    @Test
    fun byContact_affectedMatchesFullAfterDelete() = runBlocking {
        val remaining = listOf(call(1, "5551000", 300L, contactId = 42))
        val affected = AffectedRecentGroups(
            oldGroupKeys = setOf("contact:42"),
            newGroupKeys = setOf("contact:42"),
            affectedNumbers = setOf("5551000"),
            affectedContactIds = setOf(42L),
        )
        val snapshot = snapshotWithContact(42, listOf("5551000"))
        val affectedResult = engine.rebuildAffected(
            RecentGroupingMode.BY_CONTACT,
            affected,
            remaining,
            snapshot,
        )
        val fullResult = engine.build(RecentGroupingMode.BY_CONTACT, remaining, snapshot)
        val equivalence = RecentGroupingEngineWriteCoordinator.compareAffectedWithFullBuild(
            mode = RecentGroupingMode.BY_CONTACT,
            affectedKeys = affected.allGroupKeys,
            affectedResult = affectedResult,
            fullResult = fullResult,
        )
        assertTrue(equivalence.valid)
    }

    private val engine = DefaultRecentGroupingEngine

    private fun call(
        id: Int,
        number: String,
        ts: Long,
        contactId: Int? = null,
    ) = CallLogEntity(
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

    private fun snapshotWithContact(
        contactId: Long,
        numbers: List<String>,
    ): RecentContactResolutionSnapshot {
        val phoneIndex = numbers.mapIndexed { index, canonical ->
            ContactPhoneIndexEntity(
                id = (index + 1).toLong(),
                contactId = contactId.toInt(),
                normalizedNumber = canonical,
                phoneDigits = canonical,
                digits = canonical,
            )
        }.groupBy { it.normalizedNumber }
        return RecentContactResolutionSnapshot(
            contactsById = mapOf(
                contactId to ContactSummaryEntity(
                    contactId = contactId.toInt(),
                    lookupKey = "lk$contactId",
                    displayName = "Contact$contactId",
                    photoThumbnailUri = "",
                    hasPhoneNumber = true,
                    lastUpdatedTimestamp = 0L,
                ),
            ),
            phoneIndexByCanonicalNumber = phoneIndex,
            displayByContactId = emptyMap(),
            visibleContactIds = setOf(contactId),
            starredContactIds = emptySet(),
            contactsWithValidPhoto = emptySet(),
        )
    }

    private fun emptySnapshot() = RecentContactResolutionSnapshot(
        contactsById = emptyMap(),
        phoneIndexByCanonicalNumber = emptyMap(),
        displayByContactId = emptyMap(),
        visibleContactIds = emptySet(),
        starredContactIds = emptySet(),
        contactsWithValidPhoto = emptySet(),
    )
}
