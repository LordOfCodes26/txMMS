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

class RecentGroupingAffectedEquivalenceTest {

    @Test
    fun newCall_affectedMatchesFullBuild() = runEquivalenceScenario(
        label = "newCall",
        calls = listOf(
            call(1, "5551000", 200L, contactId = 42),
            call(2, "5552000", 100L, contactId = 42),
        ),
        affected = AffectedRecentGroups(
            oldGroupKeys = emptySet(),
            newGroupKeys = setOf("contact:42"),
            affectedNumbers = setOf("5552000"),
            affectedContactIds = setOf(42L),
        ),
    )

    @Test
    fun callDelete_affectedMatchesFullBuild() = runEquivalenceScenario(
        label = "callDelete",
        calls = listOf(
            call(1, "5551000", 200L, contactId = 42),
        ),
        affected = AffectedRecentGroups(
            oldGroupKeys = setOf("contact:42"),
            newGroupKeys = setOf("contact:42"),
            affectedNumbers = setOf("5551000"),
            affectedContactIds = setOf(42L),
        ),
    )

    @Test
    fun sharedNumberOwnerDelete_affectedMatchesFullBuild() = runEquivalenceScenario(
        label = "sharedNumberOwnerDelete",
        calls = listOf(
            call(1, "5559999", 300L, contactId = null),
        ),
        snapshot = snapshotWithContacts(
            10 to listOf("5559999"),
            20 to listOf("5559999"),
        ),
        affected = AffectedRecentGroups(
            oldGroupKeys = setOf("contact:10", "contact:20"),
            newGroupKeys = setOf("number:5559999"),
            affectedNumbers = setOf("5559999"),
            affectedContactIds = setOf(10L),
        ),
    )

    @Test
    fun byNumber_newCall_affectedMatchesFullBuild() = runEquivalenceScenario(
        label = "byNumberNewCall",
        mode = RecentGroupingMode.BY_NUMBER,
        calls = listOf(
            call(1, "5551000", 200L),
            call(2, "5552000", 100L),
        ),
        affected = AffectedRecentGroups(
            oldGroupKeys = emptySet(),
            newGroupKeys = setOf("number:5552000"),
            affectedNumbers = setOf("5552000"),
            affectedContactIds = emptySet(),
        ),
        snapshot = RecentContactResolutionSnapshot(
            contactsById = emptyMap(),
            phoneIndexByCanonicalNumber = emptyMap(),
            displayByContactId = emptyMap(),
            visibleContactIds = emptySet(),
            starredContactIds = emptySet(),
            contactsWithValidPhoto = emptySet(),
        ),
    )

    @Test
    fun insertLocalVsE164_noPhoneIndex_thinMissesSibling_expandedMatchesFull() {
        // Without a phone index, local and E.164 stay distinct identity keys. Thin insert
        // affected (local only) rebuilds just number:local and misses contact: — the split
        // left on screen after replaceAffected. Expansion matches full build.
        val local = "01021814406"
        val e164 = "821021814406"
        val calls = listOf(
            call(10, e164, 200L, contactId = 42),
            call(11, local, 300L, contactId = null),
        )
        val snapshot = RecentContactResolutionSnapshot(
            contactsById = mapOf(
                42L to ContactSummaryEntity(
                    contactId = 42,
                    displayName = "Test",
                    lookupKey = "lk",
                    photoThumbnailUri = "",
                    hasPhoneNumber = true,
                    lastUpdatedTimestamp = 0L,
                ),
            ),
            phoneIndexByCanonicalNumber = emptyMap(),
            displayByContactId = emptyMap(),
            visibleContactIds = setOf(42L),
            starredContactIds = emptySet(),
            contactsWithValidPhoto = emptySet(),
        )
        val full = runBlocking {
            DefaultRecentGroupingEngine.build(RecentGroupingMode.BY_CONTACT, calls, snapshot)
        }
        assertTrue(full.validation.valid)
        assertEquals(2, full.groups.size)

        val thin = RecentGroupMutationPlanner.forCallLogInsert(
            newGroupKeys = setOf(CanonicalPhoneNumberResolver.numberGroupKey(local)),
            existingGroupKeys = emptySet(),
            affectedNumbers = setOf(local),
            affectedContactIds = emptySet(),
            mode = RecentGroupingMode.BY_CONTACT,
        )
        val thinResult = runBlocking {
            DefaultRecentGroupingEngine.rebuildAffected(
                RecentGroupingMode.BY_CONTACT,
                thin,
                calls,
                snapshot,
            )
        }
        assertTrue(thinResult.validation.valid)
        assertEquals(1, thinResult.groups.size)
        assertEquals(
            CanonicalPhoneNumberResolver.numberGroupKey(local),
            thinResult.groups.single().groupKey,
        )

        val expanded = RecentGroupMutationPlanner.expandWithExisting(
            base = thin,
            existingGroupKeys = setOf(
                CanonicalPhoneNumberResolver.contactGroupKey(42L),
                CanonicalPhoneNumberResolver.numberGroupKey(e164),
            ),
            additionalNumbers = listOf(e164),
        )
        assertTrue(expanded.allGroupKeys.contains(CanonicalPhoneNumberResolver.contactGroupKey(42L)))
        assertTrue(expanded.affectedNumbers.contains(e164))
        runEquivalenceScenario(
            label = "insertLocalVsE164NoIndexExpanded",
            calls = calls,
            affected = expanded,
            snapshot = snapshot,
        )
    }

    @Test
    fun insertBackfilledLocal_expandedAffectedMatchesFullOneContactGroup() {
        val local = "01021814406"
        val e164 = "821021814406"
        val calls = listOf(
            call(1, e164, 200L, contactId = 42),
            call(2, local, 300L, contactId = 42),
        )
        val snapshot = snapshotWithContact(42, listOf(e164, local))
        val planned = RecentGroupMutationPlanner.forCallLogInsert(
            newGroupKeys = setOf(CanonicalPhoneNumberResolver.contactGroupKey(42L)),
            existingGroupKeys = emptySet(),
            affectedNumbers = setOf(local),
            affectedContactIds = setOf(42L),
            mode = RecentGroupingMode.BY_CONTACT,
        )
        val expanded = RecentGroupMutationPlanner.expandWithExisting(
            base = planned,
            existingGroupKeys = setOf(
                CanonicalPhoneNumberResolver.contactGroupKey(42L),
                CanonicalPhoneNumberResolver.numberGroupKey(e164),
                CanonicalPhoneNumberResolver.numberGroupKey(local),
            ),
            additionalNumbers = listOf(e164, local),
        )
        runEquivalenceScenario(
            label = "insertBackfilledLocalExpanded",
            calls = calls,
            affected = expanded,
            snapshot = snapshot,
        )
        val full = runBlocking {
            DefaultRecentGroupingEngine.build(RecentGroupingMode.BY_CONTACT, calls, snapshot)
        }
        assertEquals(1, full.groups.size)
        assertEquals(2, full.groups.single().callCount)
        assertEquals("contact:42", full.groups.single().groupKey)
    }

    private fun runEquivalenceScenario(
        label: String,
        mode: RecentGroupingMode = RecentGroupingMode.BY_CONTACT,
        calls: List<CallLogEntity>,
        affected: AffectedRecentGroups,
        snapshot: RecentContactResolutionSnapshot = snapshotWithContact(42, listOf("5551000", "5552000")),
    ) {
        val full = runBlocking {
            DefaultRecentGroupingEngine.build(mode, calls, snapshot)
        }
        val affectedResult = runBlocking {
            DefaultRecentGroupingEngine.rebuildAffected(mode, affected, calls, snapshot)
        }
        assertTrue("$label full invalid: ${full.validation.failures}", full.validation.valid)
        assertTrue("$label affected invalid: ${affectedResult.validation.failures}", affectedResult.validation.valid)
        val equivalence = RecentGroupingEngineWriteCoordinator.compareAffectedWithFullBuild(
            mode = mode,
            affectedKeys = affected.allGroupKeys,
            affectedResult = affectedResult,
            fullResult = full,
        )
        assertTrue("$label mismatches=${equivalence.mismatches}", equivalence.valid)
        assertEquals(affected.allGroupKeys.size, equivalence.affectedKeyCount)
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
        return RecentContactResolutionSnapshot(
            contactsById = mapOf(contactId.toLong() to summary),
            phoneIndexByCanonicalNumber = phoneIndex.groupBy { it.normalizedNumber },
            displayByContactId = emptyMap(),
            visibleContactIds = setOf(contactId.toLong()),
            starredContactIds = emptySet(),
            contactsWithValidPhoto = emptySet(),
        )
    }

    private fun snapshotWithContacts(
        vararg contacts: Pair<Int, List<String>>,
    ): RecentContactResolutionSnapshot {
        val summaries = contacts.associate { (id, _) ->
            id.toLong() to ContactSummaryEntity(
                contactId = id,
                displayName = "C$id",
                lookupKey = "lk$id",
                photoThumbnailUri = "",
                hasPhoneNumber = true,
                lastUpdatedTimestamp = 0L,
            )
        }
        val phoneIndex = contacts.flatMap { (id, numbers) ->
            numbers.mapIndexed { index, number ->
                ContactPhoneIndexEntity(
                    id = (id * 10L + index),
                    contactId = id,
                    normalizedNumber = number,
                    phoneDigits = number,
                    digits = number,
                )
            }
        }
        return RecentContactResolutionSnapshot(
            contactsById = summaries,
            phoneIndexByCanonicalNumber = phoneIndex.groupBy { it.normalizedNumber },
            displayByContactId = emptyMap(),
            visibleContactIds = contacts.map { it.first.toLong() }.toSet(),
            starredContactIds = emptySet(),
            contactsWithValidPhoto = emptySet(),
        )
    }

    private fun call(id: Int, number: String, ts: Long, contactId: Int? = 42) = CallLogEntity(
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
