package com.goodwy.commons.providercache.grouping

import com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity
import com.goodwy.commons.providercache.entities.RecentGroupEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentAffectedMutationPlannerTest {

    @Test
    fun identicalChecksum_isNoOp() {
        val base = RecentGroupEntity(
            groupingMode = 1,
            groupKey = "contact:42",
            displayContactId = 42L,
            latestCallId = 3L,
            latestTimestamp = 300L,
            callCount = 2,
            primaryNumber = "5551000",
            displayOrder = 0,
        )
        val checksum = RecentGroupMembershipChecksum.computeForGroup(
            mode = com.goodwy.commons.providercache.display.RecentGroupingMode.BY_CONTACT,
            group = base,
            callIds = listOf(1L, 3L),
            canonicalNumbers = listOf("5551000"),
        )
        val group = base.copy(membershipChecksum = checksum)
        val plan = RecentAffectedMutationPlanner.planGroupUpdate(
            mode = com.goodwy.commons.providercache.display.RecentGroupingMode.BY_CONTACT,
            existingGroup = group,
            newGroup = group,
            newCallIds = listOf(1L, 3L),
            newNumbers = listOf("5551000"),
            existingDisplay = null,
            newDisplay = null,
        )
        assertEquals(RecentAffectedMutationPlanner.MutationResult.NO_OP, plan.result)
    }

    @Test
    fun sameMembership_headPhoneChanged_isDisplayOnly() {
        val base = RecentGroupEntity(
            groupingMode = 1,
            groupKey = "contact:42",
            displayContactId = 42L,
            latestCallId = 10L,
            latestTimestamp = 5_000L,
            callCount = 7,
            primaryNumber = "1957007653",
            displayOrder = 0,
        )
        val checksum = RecentGroupMembershipChecksum.computeForGroup(
            mode = com.goodwy.commons.providercache.display.RecentGroupingMode.BY_CONTACT,
            group = base,
            callIds = listOf(1L, 2L, 3L, 4L, 5L, 9L, 10L),
            canonicalNumbers = listOf("1915882855", "1957007653"),
        )
        val group = base.copy(membershipChecksum = checksum)
        val oldDisplay = displayRow(
            callId = 9,
            phone = "1915882855",
            displayNumber = "191 588 2855",
            startTs = 4_000L,
            count = 6,
        )
        val newDisplay = displayRow(
            callId = 10,
            phone = "1957007653",
            displayNumber = "195 700 7653",
            startTs = 5_000L,
            count = 7,
        )
        val plan = RecentAffectedMutationPlanner.planGroupUpdate(
            mode = com.goodwy.commons.providercache.display.RecentGroupingMode.BY_CONTACT,
            existingGroup = group,
            newGroup = group,
            newCallIds = listOf(1L, 2L, 3L, 4L, 5L, 9L, 10L),
            newNumbers = listOf("1915882855", "1957007653"),
            existingDisplay = oldDisplay,
            newDisplay = newDisplay,
        )
        assertEquals(RecentAffectedMutationPlanner.MutationResult.DISPLAY_ONLY, plan.result)
    }

    private fun displayRow(
        callId: Int,
        phone: String,
        displayNumber: String,
        startTs: Long,
        count: Int,
    ) = RecentDisplayCacheEntity(
        callId = callId,
        phoneNumber = phone,
        cachedName = "Test123",
        photoUri = "",
        startTS = startTs,
        duration = 0,
        type = 2,
        simID = 1,
        simTypeID = 1,
        simColor = 0,
        contactID = 42,
        callCount = count,
        groupedCallIds = callId.toString(),
        normalizedNumber = phone,
        groupKey = "contact:42",
        isUnknownNumber = false,
        isVoiceMail = false,
        blockReason = 0,
        features = 0,
        groupByContact = 1,
        displayOrder = 0,
        displayName = "Test123",
        displayNumber = displayNumber,
    )
}
