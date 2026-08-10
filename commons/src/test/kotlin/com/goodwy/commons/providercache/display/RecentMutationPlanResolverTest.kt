package com.goodwy.commons.providercache.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentMutationPlanResolverTest {

    @Test
    fun displayOnlyPatch_forNameChange() {
        val plan = RecentMutationPlanResolver.forContactDisplayChange(
            ContactDisplayChanged(
                contactId = 10,
                lookupKey = "lk",
                oldName = "Alice",
                newName = "Alicia",
                oldPhotoThumbUri = "",
                newPhotoThumbUri = "",
                phoneDigits = listOf("5551234"),
                normalizedNumbers = listOf("+15551234"),
                oldPhoneDigits = listOf("5551234"),
                oldNormalizedNumbers = listOf("+15551234"),
            ),
        )
        assertTrue(plan is RecentMutationPlan.DisplayOnlyPatch)
        assertEquals("DISPLAY_ONLY", plan.planKind)
    }

    @Test
    fun multiGroupRebuild_forPhoneEdit() {
        val plan = RecentMutationPlanResolver.forContactDisplayChange(
            ContactDisplayChanged(
                contactId = 10,
                lookupKey = "lk",
                oldName = "Alice",
                newName = "Alice",
                oldPhotoThumbUri = "",
                newPhotoThumbUri = "",
                phoneDigits = listOf("5559999"),
                normalizedNumbers = listOf("+15559999"),
                oldPhoneDigits = listOf("5551234"),
                oldNormalizedNumbers = listOf("+15551234"),
            ),
        )
        assertTrue(plan is RecentMutationPlan.MultiGroupRebuild)
        val affected = (plan as RecentMutationPlan.MultiGroupRebuild).affected
        assertTrue(affected.affectedNumbers.contains("5551234"))
        assertTrue(affected.affectedNumbers.contains("5559999"))
        assertTrue(affected.oldGroupKeys.contains(CanonicalPhoneNumberResolver.numberGroupKey("5551234")))
        assertTrue(affected.newGroupKeys.contains(CanonicalPhoneNumberResolver.numberGroupKey("5559999")))
    }

    @Test
    fun fullRepair_forLookupKeyChange() {
        val plan = RecentMutationPlanResolver.forContactDisplayChange(
            ContactDisplayChanged(
                contactId = 10,
                lookupKey = "new-lk",
                oldName = "Alice",
                newName = "Alice",
                oldPhotoThumbUri = "",
                newPhotoThumbUri = "",
                phoneDigits = listOf("5551234"),
                normalizedNumbers = listOf("+15551234"),
                oldPhoneDigits = listOf("5551234"),
                oldNormalizedNumbers = listOf("+15551234"),
                lookupKeyChanged = true,
            ),
        )
        assertTrue(plan is RecentMutationPlan.FullModeRepair)
    }

    @Test
    fun deleteBatch_infersMergeOnSharedNumbers() {
        val plan = RecentMutationPlanResolver.forContactDeleteBatch(
            deleted = listOf(
                ContactDisplayDeleted(1, "lk1", phoneDigits = listOf("555"), normalizedNumbers = listOf("555")),
                ContactDisplayDeleted(2, "lk2", phoneDigits = listOf("555"), normalizedNumbers = listOf("555")),
            ),
            oldGroupKeysByContact = mapOf(1 to setOf("contact:1"), 2 to setOf("contact:2")),
        )
        assertTrue(plan is RecentMutationPlan.MultiGroupRebuild)
        assertEquals("CONTACT_MERGE_INFERRED", plan.reason)
    }

    @Test
    fun phoneEditMulti_unionsOldAndNewGroups() {
        val affected = RecentGroupMutationPlanner.forPhoneEditMulti(
            contactId = 42L,
            oldDigits = listOf("111", "222"),
            newDigits = listOf("222", "333"),
        )
        assertEquals(setOf("111", "222", "333"), affected.affectedNumbers)
        assertTrue(affected.allGroupKeys.contains(CanonicalPhoneNumberResolver.contactGroupKey(42L)))
        assertTrue(affected.allGroupKeys.contains(CanonicalPhoneNumberResolver.numberGroupKey("111")))
        assertTrue(affected.allGroupKeys.contains(CanonicalPhoneNumberResolver.numberGroupKey("333")))
    }

    @Test
    fun callLogInsert_includesRemappedNumberKeysForByContact() {
        val numberKey = CanonicalPhoneNumberResolver.numberGroupKey("5551000")
        val contactKey = CanonicalPhoneNumberResolver.contactGroupKey(42L)
        val affected = RecentGroupMutationPlanner.forCallLogInsert(
            newGroupKeys = setOf(contactKey),
            existingGroupKeys = setOf(numberKey),
            affectedNumbers = setOf("5551000"),
            affectedContactIds = setOf(42L),
            mode = RecentGroupingMode.BY_CONTACT,
        )
        assertTrue(affected.oldGroupKeys.contains(numberKey))
        assertTrue(affected.newGroupKeys.contains(contactKey))
        assertTrue(affected.allGroupKeys.contains(numberKey))
        assertTrue(affected.allGroupKeys.contains(contactKey))
    }

    @Test
    fun callLogInsert_byNumber_doesNotSynthesizeExtraRemapKeys() {
        val numberKey = CanonicalPhoneNumberResolver.numberGroupKey("5551000")
        val affected = RecentGroupMutationPlanner.forCallLogInsert(
            newGroupKeys = setOf(numberKey),
            existingGroupKeys = emptySet(),
            affectedNumbers = setOf("5551000"),
            affectedContactIds = emptySet(),
            mode = RecentGroupingMode.BY_NUMBER,
        )
        assertEquals(setOf(numberKey), affected.allGroupKeys)
        assertTrue(affected.oldGroupKeys.isEmpty())
    }

    @Test
    fun expandWithExisting_includesLocalFormNumberKeysForPhoneEdit() {
        // Contact phone-index uses E.164; existing display rows still use dialed local digits.
        val planned = RecentGroupMutationPlanner.forPhoneEditMulti(
            contactId = 42L,
            oldDigits = emptySet(),
            newDigits = setOf("821021814406", "821098765432"),
        )
        assertTrue(planned.allGroupKeys.contains(CanonicalPhoneNumberResolver.numberGroupKey("821021814406")))
        assertTrue(!planned.allGroupKeys.contains(CanonicalPhoneNumberResolver.numberGroupKey("01021814406")))

        val expanded = RecentGroupMutationPlanner.expandWithExisting(
            base = planned,
            existingGroupKeys = setOf(
                CanonicalPhoneNumberResolver.numberGroupKey("01021814406"),
                CanonicalPhoneNumberResolver.numberGroupKey("01098765432"),
            ),
            additionalNumbers = listOf("01021814406", "01098765432"),
        )
        assertTrue(expanded.allGroupKeys.contains(CanonicalPhoneNumberResolver.contactGroupKey(42L)))
        assertTrue(expanded.allGroupKeys.contains(CanonicalPhoneNumberResolver.numberGroupKey("01021814406")))
        assertTrue(expanded.allGroupKeys.contains(CanonicalPhoneNumberResolver.numberGroupKey("01098765432")))
        assertTrue(expanded.allGroupKeys.contains(CanonicalPhoneNumberResolver.numberGroupKey("821021814406")))
        assertTrue(expanded.affectedNumbers.contains("01021814406"))
        assertTrue(expanded.affectedNumbers.contains("821021814406"))
    }
}
