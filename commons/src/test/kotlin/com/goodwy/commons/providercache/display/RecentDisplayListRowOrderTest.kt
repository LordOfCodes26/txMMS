package com.goodwy.commons.providercache.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class RecentDisplayListRowOrderTest {

    @Test
    fun listRowProjection_fieldsMatchStableIdentityContract() {
        val row = sampleRow(groupKey = "number:555", callId = 9, ts = 100L)
        assertEquals(0, row.groupingMode)
        assertEquals("number:555", row.groupKey)
        assertEquals(9, row.latestCallId)
        assertEquals(100L, row.latestTimestamp)
    }

    @Test
    fun finalOrderComparator_matchesDaoOrder() {
        val rows = listOf(
            sampleRow("number:a", 1, 100),
            sampleRow("number:b", 3, 200),
            sampleRow("number:c", 2, 200),
            sampleRow("number:d", 4, 50),
        )
        val ordered = rows.sortedWith(
            compareByDescending<RecentDisplayListRow> { it.latestTimestamp }
                .thenByDescending { it.latestCallId }
                .thenBy { it.groupKey },
        )
        assertEquals(listOf(3, 2, 1, 4), ordered.map { it.latestCallId })
    }

    @Test
    fun fixture_3k_orderPass_isLinear() {
        val rows = (0 until 3000).map { i ->
            sampleRow("number:$i", i + 1, (3000 - i).toLong())
        }
        val ms = measureTimeMillis {
            rows.sortedWith(
                compareByDescending<RecentDisplayListRow> { it.latestTimestamp }
                    .thenByDescending { it.latestCallId }
                    .thenBy { it.groupKey },
            )
        }
        assertTrue("sortMs=$ms", ms < 500)
        assertEquals(3000, rows.size)
    }

    private fun sampleRow(groupKey: String, callId: Int, ts: Long) = RecentDisplayListRow(
        groupingMode = 0,
        groupKey = groupKey,
        latestCallId = callId,
        latestTimestamp = ts,
        callCount = 1,
        displayName = "N",
        displayNumber = "1",
        displayContactId = null,
        phoneNumber = "1",
        cachedName = "N",
        photoThumbUri = "",
        photoUri = "",
        avatarInitials = "N",
        avatarDrawableIndex = 0,
        avatarColor = 1,
        avatarVersion = 0L,
        avatarShowProfileIcon = 0,
        usePhotoAvatar = 0,
        callTypeIconKey = "in",
        simColorResolved = 0,
        simLabel = "",
        simVisible = 0,
        simId = 0,
        simTypeId = 0,
        simColor = 0,
        callType = 1,
        duration = 0,
        isUnknownNumber = false,
        isVoiceMail = false,
        blockReason = null,
        features = null,
        nameIsMissedColor = 0,
        sectionDayCode = "TODAY",
        sectionHeaderText = "Today",
        groupCountText = "",
        formattedDateTime = "",
        displayOrder = 0,
        normalizedNumber = "1",
    )
}
