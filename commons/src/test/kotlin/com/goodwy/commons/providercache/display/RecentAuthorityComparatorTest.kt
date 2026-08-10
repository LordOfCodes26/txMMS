package com.goodwy.commons.providercache.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentAuthorityComparatorTest {

    private fun group(
        key: String = "contact:42",
        callIds: Set<Long> = setOf(1L, 2L),
        contactId: Long? = 42L,
        numbers: Set<String> = setOf("5551234"),
        count: Int = callIds.size,
        latestCall: Long = callIds.maxOrNull() ?: 0L,
        latestTs: Long = 2000L,
        primary: String = "5551234",
        displayOrder: Int = 0,
    ) = ComparableRecentGroup(
        semanticKey = key,
        callIds = callIds,
        displayContactId = contactId,
        normalizedNumbers = numbers,
        callCount = count,
        latestCallId = latestCall,
        latestTimestamp = latestTs,
        primaryNumber = primary,
        displayOrder = displayOrder,
    )

    @Test
    fun identicalGroups_valid() {
        val g = group()
        val result = RecentAuthorityComparator.compareSemanticGroups(
            RecentGroupingMode.BY_CONTACT,
            mapOf(g.semanticKey to g),
            mapOf(g.semanticKey to g),
        )
        assertTrue(result.valid)
    }

    @Test
    fun callIdMismatch_failsAuthority() {
        val legacy = group(callIds = setOf(1L), count = 1, latestCall = 1L, latestTs = 1000L)
        val relational = legacy.copy(
            callIds = setOf(1L, 2L),
            callCount = 2,
            latestCallId = 2L,
            latestTimestamp = 2000L,
        )
        val result = RecentAuthorityComparator.compareSemanticGroups(
            RecentGroupingMode.BY_CONTACT,
            mapOf(legacy.semanticKey to legacy),
            mapOf(relational.semanticKey to relational),
        )
        assertFalse(result.valid)
        assertTrue(result.mismatches.any { it.field == ComparableRecentGroupField.CALL_IDS })
    }

    @Test
    fun displayOrderMismatch_doesNotFailAuthorityOrDualWrite() {
        val legacy = group(displayOrder = 0)
        val relational = group(displayOrder = 7)
        val result = RecentAuthorityComparator.compareSemanticGroups(
            RecentGroupingMode.BY_CONTACT,
            mapOf(legacy.semanticKey to legacy),
            mapOf(relational.semanticKey to relational),
        )
        assertTrue(result.valid)
        assertEquals(0, result.authorityMismatchCount)
        assertTrue(result.mismatches.none { it.field == ComparableRecentGroupField.DISPLAY_ORDER })
    }

    /**
     * The relational projection never runs enrichment, so its enrichment-derived fields sit at
     * type defaults. Compared against enriched legacy rows that produced a constant 2227
     * mismatches per pass — noise that hid real drift. Narrowing to fields both sides populate
     * must keep the drift signal (DISPLAY_ORDER here) and drop the structural noise.
     */
    @Test
    fun projectionFieldSet_ignoresUnenrichedFields_butKeepsRealDrift() {
        val legacy = enrichedGroup()
        val unenrichedProjection = legacy.copy(
            displayOrder = 3,
            displayName = "",
            avatarInitials = "",
            avatarDrawableIndex = -1,
            avatarShowProfileIcon = false,
            avatarColor = 0,
            callTypeIconKey = "",
            groupCountText = "",
            nameIsMissedColor = false,
            sectionDayCode = "",
            sectionHeaderText = "",
        )

        val narrowed = RecentAuthorityComparator.compareDisplayGroups(
            RecentGroupingMode.BY_NUMBER,
            mapOf(legacy.semanticKey to legacy),
            mapOf(unenrichedProjection.semanticKey to unenrichedProjection),
            fields = RecentAuthorityComparator.PROJECTION_COMPARABLE_COSMETIC_FIELDS,
        )
        assertEquals(listOf(ComparableDisplayField.DISPLAY_ORDER), narrowed.map { it.field })

        // Unnarrowed, the same pair reports every unenriched field — the 2227-per-pass noise.
        val unnarrowed = RecentAuthorityComparator.compareDisplayGroups(
            RecentGroupingMode.BY_NUMBER,
            mapOf(legacy.semanticKey to legacy),
            mapOf(unenrichedProjection.semanticKey to unenrichedProjection),
        )
        assertTrue(unnarrowed.size > narrowed.size)
        assertTrue(unnarrowed.any { it.field == ComparableDisplayField.SECTION_DAY_CODE })
    }

    private fun enrichedGroup() = ComparableDisplayGroup(
        semanticKey = "number:555",
        displayOrder = 0,
        displayContactId = null,
        displayName = "555",
        displayNumber = "555",
        photoThumbUri = "",
        avatarInitials = "T",
        avatarDrawableIndex = 8,
        usePhotoAvatar = false,
        avatarShowProfileIcon = true,
        avatarColor = -2709880,
        simLabel = "",
        simColorResolved = 0,
        simVisible = false,
        callTypeIconKey = "in",
        groupCountText = "(3)",
        nameIsMissedColor = true,
        sectionDayCode = "TODAY",
        sectionHeaderText = "Today",
    )

    @Test
    fun sectionHeaderCosmetic_doesNotFailAuthority() {
        val legacy = ComparableDisplayGroup(
            semanticKey = "number:555",
            displayOrder = 0,
            displayContactId = null,
            displayName = "555",
            displayNumber = "555",
            photoThumbUri = "",
            avatarInitials = "",
            avatarDrawableIndex = 0,
            usePhotoAvatar = false,
            avatarShowProfileIcon = false,
            avatarColor = 0,
            simLabel = "",
            simColorResolved = 0,
            simVisible = false,
            callTypeIconKey = "in",
            groupCountText = "(1)",
            nameIsMissedColor = false,
            sectionDayCode = "TODAY",
            sectionHeaderText = "Today",
        )
        val relational = legacy.copy(
            displayOrder = 3,
            sectionDayCode = "YESTERDAY",
            sectionHeaderText = "Yesterday",
        )
        val cosmetics = RecentAuthorityComparator.compareDisplayGroups(
            RecentGroupingMode.BY_NUMBER,
            mapOf(legacy.semanticKey to legacy),
            mapOf(relational.semanticKey to relational),
        )
        assertTrue(cosmetics.any { it.field == ComparableDisplayField.DISPLAY_ORDER })
        assertTrue(cosmetics.any { it.field == ComparableDisplayField.SECTION_HEADER_TEXT })
        assertTrue(cosmetics.any { it.field == ComparableDisplayField.SECTION_DAY_CODE })

        val semantic = RecentAuthorityComparator.compareSemanticGroups(
            RecentGroupingMode.BY_NUMBER,
            mapOf(
                "number:555" to group(
                    key = "number:555",
                    callIds = setOf(1L),
                    contactId = null,
                    numbers = setOf("555"),
                    primary = "555",
                ),
            ),
            mapOf(
                "number:555" to group(
                    key = "number:555",
                    callIds = setOf(1L),
                    contactId = null,
                    numbers = setOf("555"),
                    primary = "555",
                ),
            ),
        )
        val combined = semantic.copy(displayMismatches = cosmetics)
        assertTrue(combined.valid)
        assertEquals(0, combined.authorityMismatchCount)
        assertTrue(combined.cosmeticMismatchCount > 0)
    }

    @Test
    fun sameTotalCountWrongAssignment_fails() {
        val legacy = group(
            key = "contact:1",
            callIds = setOf(10L),
            contactId = 1L,
            numbers = setOf("111"),
            count = 1,
            latestCall = 10L,
            latestTs = 1000L,
            primary = "111",
        )
        val relational = group(
            key = "contact:2",
            callIds = setOf(10L),
            contactId = 2L,
            numbers = setOf("111"),
            count = 1,
            latestCall = 10L,
            latestTs = 1000L,
            primary = "111",
        )
        val result = RecentAuthorityComparator.compareSemanticGroups(
            RecentGroupingMode.BY_CONTACT,
            mapOf(legacy.semanticKey to legacy),
            mapOf(relational.semanticKey to relational),
        )
        assertFalse(result.valid)
    }
}
