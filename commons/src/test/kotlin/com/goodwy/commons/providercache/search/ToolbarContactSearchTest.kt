package com.goodwy.commons.providercache.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarContactSearchTest {

    @Test
    fun classify_numericQuery() {
        assertEquals(ToolbarContactSearch.Mode.NUMERIC_DIGITS_ONLY, ToolbarContactSearch.classifyMode("5"))
        assertEquals(ToolbarContactSearch.Mode.NUMERIC_DIGITS_ONLY, ToolbarContactSearch.classifyMode("123"))
    }

    @Test
    fun classify_letterQuery() {
        assertEquals(ToolbarContactSearch.Mode.LETTERS, ToolbarContactSearch.classifyMode("KT"))
        assertEquals(ToolbarContactSearch.Mode.LETTERS, ToolbarContactSearch.classifyMode("john"))
    }

    @Test
    fun classify_mixedQuery() {
        assertEquals(ToolbarContactSearch.Mode.MIXED, ToolbarContactSearch.classifyMode("KT5"))
        assertEquals(ToolbarContactSearch.Mode.MIXED, ToolbarContactSearch.classifyMode("+1555"))
    }

    @Test
    fun sqlWhereLabel_numeric() {
        assertEquals(
            "name_or_phone",
            ToolbarContactSearch.sqlWhereLabel(ToolbarContactSearch.Mode.NUMERIC_DIGITS_ONLY),
        )
    }

    @Test
    fun preferIndexedPrefix_multiDigit() {
        assertTrue(ToolbarContactSearch.preferIndexedPrefixForDigits("55"))
        assertFalse(ToolbarContactSearch.preferIndexedPrefixForDigits("5"))
    }

    @Test
    fun query5_matchesPhoneContaining5() {
        assertTrue(
            ToolbarContactSearch.matchesToolbarContact(
                displayName = "Alice",
                phoneDigits = "15551234567",
                normalizedNumbers = "+15551234567",
                displayNumberDigits = "15551234567",
                query = "5",
            ),
        )
    }

    @Test
    fun query5_doesNotMatchKtNameWithoutFiveInPhone() {
        assertFalse(
            ToolbarContactSearch.matchesToolbarContact(
                displayName = "KT #1000",
                phoneDigits = "1000000000",
                normalizedNumbers = "+821000000000",
                displayNumberDigits = "1000000000",
                query = "5",
            ),
        )
    }

    @Test
    fun query5_matchesKtNameWhenPhoneContainsFive() {
        assertTrue(
            ToolbarContactSearch.matchesToolbarContact(
                displayName = "KT #1000",
                phoneDigits = "15551234000",
                normalizedNumbers = "+15551234000",
                displayNumberDigits = "15551234000",
                query = "5",
            ),
        )
    }

    @Test
    fun queryKt_matchesKtName() {
        assertTrue(
            ToolbarContactSearch.matchesToolbarContact(
                displayName = "KT #1000",
                phoneDigits = "1000000000",
                normalizedNumbers = "+821000000000",
                displayNumberDigits = "1000000000",
                query = "KT",
            ),
        )
    }

    @Test
    fun queryKt_doesNotMatchUnrelatedName() {
        assertFalse(
            ToolbarContactSearch.matchesToolbarContact(
                displayName = "Alice",
                phoneDigits = "1000000000",
                normalizedNumbers = "+821000000000",
                displayNumberDigits = "1000000000",
                query = "KT",
            ),
        )
    }

    @Test
    fun mixedQuery_matchesFullNameNotDigitStrippedPhone() {
        // "123a" must hit a name that contains "123a", not every contact whose phone has "123".
        assertTrue(
            ToolbarContactSearch.matchesToolbarContact(
                displayName = "123a Torwell",
                phoneDigits = "01099998888",
                normalizedNumbers = "+821099998888",
                displayNumberDigits = "01099998888",
                query = "123a",
            ),
        )
        assertFalse(
            ToolbarContactSearch.matchesToolbarContact(
                displayName = "Albeles Torwell",
                phoneDigits = "821012345678",
                normalizedNumbers = "+821012345678",
                displayNumberDigits = "01012345678",
                query = "123a",
            ),
        )
    }

    @Test
    fun mixedQuery_kt5_requiresFullNameSubstring() {
        assertTrue(
            ToolbarContactSearch.matchesToolbarContact(
                displayName = "KT5 Lab",
                phoneDigits = "1000000000",
                normalizedNumbers = "+821000000000",
                displayNumberDigits = "1000000000",
                query = "KT5",
            ),
        )
        // Digit-stripped phone OR ("5") must not match unrelated names.
        assertFalse(
            ToolbarContactSearch.matchesToolbarContact(
                displayName = "Alice",
                phoneDigits = "15551234000",
                normalizedNumbers = "+15551234000",
                displayNumberDigits = "15551234000",
                query = "KT5",
            ),
        )
        assertFalse(
            ToolbarContactSearch.matchesToolbarContact(
                displayName = "KT #1000",
                phoneDigits = "15551234000",
                normalizedNumbers = "+15551234000",
                displayNumberDigits = "15551234000",
                query = "KT5",
            ),
        )
    }

    @Test
    fun shouldMatchPhoneDigits_letterDigitMixed_false() {
        assertFalse(ToolbarContactSearch.shouldMatchPhoneDigits("123a"))
        assertFalse(ToolbarContactSearch.shouldMatchPhoneDigits("KT5"))
        assertTrue(ToolbarContactSearch.shouldMatchPhoneDigits("123"))
        assertTrue(ToolbarContactSearch.shouldMatchPhoneDigits("+1555"))
    }

    @Test
    fun query15_doesNotMatchPhoneWithoutFifteen() {
        assertFalse(
            ToolbarContactSearch.matchesToolbarContact(
                displayName = "Albelan Harwell",
                phoneDigits = "821019593860",
                normalizedNumbers = "+821019593860",
                displayNumberDigits = "01019593860",
                query = "15",
            ),
        )
    }

    @Test
    fun query15_matchesPhoneContainingFifteen() {
        assertTrue(
            ToolbarContactSearch.matchesToolbarContact(
                displayName = "Albeler Morwell",
                phoneDigits = "821021444315",
                normalizedNumbers = "+821021444315",
                displayNumberDigits = "01021444315",
                query = "15",
            ),
        )
    }

    @Test
    fun query5_doesNotMatchViaNormalizedOnly() {
        assertFalse(
            ToolbarContactSearch.matchesPhoneFields(
                phoneDigits = "821012345678",
                normalizedNumbers = "+8215012345678",
                displayNumberDigits = "01012345678",
                query = "15",
                mode = ToolbarContactSearch.Mode.NUMERIC_DIGITS_ONLY,
            ),
        )
    }
}
