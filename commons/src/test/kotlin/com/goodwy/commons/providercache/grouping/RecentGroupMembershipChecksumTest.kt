package com.goodwy.commons.providercache.grouping

import com.goodwy.commons.providercache.display.RecentGroupingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RecentGroupMembershipChecksumTest {

    @Test
    fun sameMembership_producesSameChecksum() {
        val a = RecentGroupMembershipChecksum.compute(
            mode = RecentGroupingMode.BY_CONTACT,
            groupKey = "contact:42",
            callIds = listOf(3L, 1L, 2L),
            canonicalNumbers = listOf("5551000", "5552000"),
            displayContactId = 42L,
            latestCallId = 3L,
            latestTimestamp = 300L,
            callCount = 3,
        )
        val b = RecentGroupMembershipChecksum.compute(
            mode = RecentGroupingMode.BY_CONTACT,
            groupKey = "contact:42",
            callIds = listOf(2L, 3L, 1L),
            canonicalNumbers = listOf("5552000", "5551000"),
            displayContactId = 42L,
            latestCallId = 3L,
            latestTimestamp = 300L,
            callCount = 3,
        )
        assertEquals(a, b)
    }

    @Test
    fun membershipChange_producesDifferentChecksum() {
        val before = RecentGroupMembershipChecksum.compute(
            mode = RecentGroupingMode.BY_NUMBER,
            groupKey = "number:5551000",
            callIds = listOf(1L, 2L),
            canonicalNumbers = listOf("5551000"),
            displayContactId = null,
            latestCallId = 2L,
            latestTimestamp = 200L,
            callCount = 2,
        )
        val after = RecentGroupMembershipChecksum.compute(
            mode = RecentGroupingMode.BY_NUMBER,
            groupKey = "number:5551000",
            callIds = listOf(1L),
            canonicalNumbers = listOf("5551000"),
            displayContactId = null,
            latestCallId = 1L,
            latestTimestamp = 100L,
            callCount = 1,
        )
        assertNotEquals(before, after)
    }
}
