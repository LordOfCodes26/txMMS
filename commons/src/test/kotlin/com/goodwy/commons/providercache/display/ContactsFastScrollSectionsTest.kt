package com.goodwy.commons.providercache.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsFastScrollSectionsTest {

    @Test
    fun buildFromRows_collapsesAdjacentDuplicateLetters() {
        val rows = listOf(
            listRow(1, "ㄱ"),
            listRow(2, "ㄱ"),
            listRow(3, "ㄴ"),
            listRow(4, "ㄴ"),
            listRow(5, "A"),
        )
        val sections = ContactsFastScrollSections.buildFromRows(rows)
        assertEquals(
            listOf(
                FastScrollSection("ㄱ", 0),
                FastScrollSection("ㄴ", 2),
                FastScrollSection("A", 4),
            ),
            sections,
        )
        assertTrue(ContactsFastScrollSections.validate(sections, rows.size))
    }

    @Test
    fun buildFromRows_blankAndUnsupportedMapToHash() {
        val rows = listOf(
            listRow(1, ""),
            listRow(2, " "),
            listRow(3, "1"),
            listRow(4, "A"),
        )
        val sections = ContactsFastScrollSections.buildFromRows(rows)
        assertEquals(FastScrollSection("#", 0), sections[0])
        assertEquals(FastScrollSection("0", 2), sections[1])
        assertEquals(FastScrollSection("A", 3), sections[2])
        assertEquals(3, sections.size)
        assertTrue(ContactsFastScrollSections.validate(sections, rows.size))
    }

    @Test
    fun buildFromRows_preservesKoreanChoseongAndMergesDoubles() {
        val rows = listOf(
            listRow(1, "ㄱ"),
            listRow(2, "ㄲ"),
            listRow(3, "나"),
            listRow(4, "다"),
            listRow(5, "A"),
        )
        val sections = ContactsFastScrollSections.buildFromRows(rows)
        assertEquals(
            listOf(
                FastScrollSection("ㄱ", 0),
                FastScrollSection("ㄴ", 2),
                FastScrollSection("ㄷ", 3),
                FastScrollSection("A", 4),
            ),
            sections,
        )
        assertTrue(ContactsFastScrollSections.validate(sections, rows.size))
    }

    @Test
    fun normalizeLabel_keepsHangulIndexBuckets() {
        assertEquals("ㄱ", ContactsFastScrollSections.normalizeLabel("김"))
        assertEquals("ㄱ", ContactsFastScrollSections.normalizeLabel("ㄲ"))
        assertEquals("ㅇ", ContactsFastScrollSections.normalizeLabel("이"))
        assertEquals("A", ContactsFastScrollSections.normalizeLabel("alice"))
        assertEquals("#", ContactsFastScrollSections.normalizeLabel(""))
    }

    @Test
    fun validate_rejectsNonMonotonicOrDuplicateAdjacent() {
        assertFalse(
            ContactsFastScrollSections.validate(
                listOf(FastScrollSection("A", 0), FastScrollSection("A", 1)),
                rowCount = 2,
            ),
        )
        assertFalse(
            ContactsFastScrollSections.validate(
                listOf(FastScrollSection("A", 0), FastScrollSection("B", 0)),
                rowCount = 2,
            ),
        )
        assertFalse(
            ContactsFastScrollSections.validate(
                listOf(FastScrollSection("B", 1)),
                rowCount = 2,
            ),
        )
    }

    @Test
    fun fixture_3k_coversFullRange() {
        val choseong = charArrayOf('ㄱ', 'ㄴ', 'ㄷ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅅ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ')
        val rows = (0 until 3000).map { i ->
            val letter = choseong[(i / 215).coerceAtMost(choseong.lastIndex)].toString()
            listRow(rawId = i + 1, sectionLetter = letter, displayOrder = i)
        }
        val sections = ContactsFastScrollSections.buildFromRows(rows)
        assertTrue(ContactsFastScrollSections.validate(sections, rows.size))
        assertEquals(0, sections.first().firstPosition)
        assertTrue(sections.last().firstPosition < rows.size)
        // Last section must reach the final row (next section start or end).
        val lastRangeEnd = rows.size - 1
        assertTrue(sections.last().firstPosition <= lastRangeEnd)
        assertEquals(choseong.size, sections.size)
    }

    private fun listRow(
        rawId: Int,
        sectionLetter: String,
        displayOrder: Int = rawId,
    ): ContactDisplayListRow = ContactDisplayListRow(
        rawId = rawId,
        contactId = rawId,
        displayName = "Contact $rawId",
        starred = 0,
        sectionLetter = sectionLetter,
        firstPhoneFormatted = "",
        showPhoneNumber = 0,
        avatarInitials = "C",
        avatarDrawableIndex = 0,
        avatarColor = 0,
        photoThumbUri = "",
        usePhotoAvatar = 0,
        hasValidPhotoUri = 0,
        displayOrder = displayOrder,
    )
}
