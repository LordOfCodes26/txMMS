package com.goodwy.commons.providercache.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatchRulesTest {

    @Test
    fun toolbar_numeric_doesNotMatchNameWithoutPhoneDigit() {
        assertFalse(
            SearchMatchRules.matches(
                displayName = "KT #1000",
                searchName = "kt #1000",
                t9Key = "58",
                phoneDigits = "1000000000",
                phoneDigitsJoined = "1000000000",
                displayNumberDigitsJoined = "1000000000",
                query = "5",
                mode = SearchMode.TOOLBAR,
                dialpadParams = null,
            ),
        )
    }

    @Test
    fun toolbar_numeric_matchesPhoneContainingDigit() {
        assertTrue(
            SearchMatchRules.matches(
                displayName = "Alice",
                searchName = "alice",
                t9Key = "",
                phoneDigits = "15551234567",
                phoneDigitsJoined = "15551234567",
                displayNumberDigitsJoined = "15551234567",
                query = "5",
                mode = SearchMode.TOOLBAR,
                dialpadParams = null,
            ),
        )
    }

    @Test
    fun dialpad_numericTwoDigits_canMatchT9Name() {
        val params = DialpadSearchContext(
            language = "en",
            inputMethod = null,
            queryLower = "56",
            queryLowerNoSpaces = "56",
        )
        assertTrue(
            SearchMatchRules.matches(
                displayName = "John",
                searchName = "john",
                t9Key = "5646",
                phoneDigits = "",
                phoneDigitsJoined = "",
                displayNumberDigitsJoined = "",
                query = "56",
                mode = SearchMode.DIALPAD,
                dialpadParams = params,
            ),
        )
    }

    @Test
    fun contactsTab_letters_matchNameAndT9() {
        assertTrue(
            SearchMatchRules.matches(
                displayName = "KT #1000",
                searchName = "kt #1000",
                t9Key = "58",
                phoneDigits = "1000000000",
                phoneDigitsJoined = "1000000000",
                displayNumberDigitsJoined = "1000000000",
                query = "KT",
                mode = SearchMode.CONTACTS_TAB,
                dialpadParams = null,
            ),
        )
    }

    @Test
    fun ranking_exactBeforeContains() {
        val exact = SearchRanking.rank(
            entity = sampleEntity(displayName = "John", searchName = "john", sortKey = "z"),
            query = "john",
            mode = SearchMode.CONTACTS_TAB,
            phoneDigitsJoined = "",
            displayNumberDigitsJoined = "",
            dialpadParams = null,
        )!!
        val contains = SearchRanking.rank(
            entity = sampleEntity(displayName = "Johnny", searchName = "johnny", sortKey = "a"),
            query = "john",
            mode = SearchMode.CONTACTS_TAB,
            phoneDigitsJoined = "",
            displayNumberDigitsJoined = "",
            dialpadParams = null,
        )!!
        assertTrue(SearchRanking.compare(exact, contains) < 0)
        assertEquals(SearchRankingReason.EXACT, exact.reason)
    }

    private fun sampleEntity(
        displayName: String,
        searchName: String,
        sortKey: String,
    ) = com.goodwy.commons.providercache.entities.ContactDisplayCacheEntity(
        rawId = 1,
        contactId = 1,
        displayName = displayName,
        thumbnailUri = "",
        photoUri = "",
        source = "",
        accountType = "",
        firstPhone = "",
        firstEmail = "",
        sectionLetter = "#",
        sortKey = sortKey,
        searchName = searchName,
        t9Key = "",
        phoneDigits = "",
    )
}
