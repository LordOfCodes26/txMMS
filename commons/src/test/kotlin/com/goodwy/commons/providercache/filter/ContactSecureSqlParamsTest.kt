package com.goodwy.commons.providercache.filter

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactSecureSqlParamsTest {

    @Test
    fun filterByRawId_excludeRemovesProtectedIdsWhenLocked() {
        val params = ContactSecureSqlParams(excludeRawIds = listOf(10, 20))
        val ids = listOf(1, 10, 2, 20, 3)
        assertEquals(listOf(1, 2, 3), params.filterByRawId(ids) { it })
    }

    @Test
    fun filterByRawId_includeOnlyKeepsUnlockedBoxIds() {
        val params = ContactSecureSqlParams(includeOnlyRawIds = listOf(10, 20))
        val ids = listOf(1, 10, 2, 20, 3)
        assertEquals(listOf(10, 20), params.filterByRawId(ids) { it })
    }

    @Test
    fun filterByRawId_emptyIncludeOnlyYieldsEmpty() {
        val params = ContactSecureSqlParams(includeOnlyRawIds = emptyList())
        val ids = listOf(1, 2, 3)
        assertEquals(emptyList<Int>(), params.filterByRawId(ids) { it })
    }

    @Test
    fun filterByRawId_noParamsPassesThrough() {
        val params = ContactSecureSqlParams()
        val ids = listOf(1, 2, 3)
        assertEquals(ids, params.filterByRawId(ids) { it })
    }

    @Test
    fun dialpadNonLeak_lockedExcludesProtectedRawIds() {
        // Simulates dialpad search results before secure post-filter.
        val searchHits = listOf(5, 7, 9, 11)
        val protected = listOf(7, 11)
        val filtered = ContactSecureSqlParams(excludeRawIds = protected)
            .filterByRawId(searchHits) { it }
        assertEquals(listOf(5, 9), filtered)
    }

    @Test
    fun dialpadNonLeak_secureBoxOnlyIncludesUnlockedRawIds() {
        val searchHits = listOf(5, 7, 9, 11)
        val unlocked = listOf(7)
        val filtered = ContactSecureSqlParams(includeOnlyRawIds = unlocked)
            .filterByRawId(searchHits) { it }
        assertEquals(listOf(7), filtered)
    }
}
