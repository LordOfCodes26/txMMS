package com.goodwy.commons.providercache.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsDisplayStateTest {

    @Test
    fun needsReconcile_versionMatchLoaded_noOp() {
        val state = ContactsDisplayState(
            cacheVersion = 5L,
            lastVisibleVersion = 5L,
            adapterLoaded = true,
        )
        assertFalse(state.needsReconcile())
    }

    @Test
    fun needsReconcile_staleVersion() {
        val state = ContactsDisplayState(
            cacheVersion = 6L,
            lastVisibleVersion = 5L,
            adapterLoaded = true,
        )
        assertTrue(state.needsReconcile())
    }

    @Test
    fun shouldCoalesceContactsReconcile_sameVersionInFlight() {
        assertTrue(shouldCoalesceContactsReconcile(12L, 12L, true))
        assertFalse(shouldCoalesceContactsReconcile(12L, 13L, true))
    }

    @Test
    fun shouldDeferContactsReconcile_hiddenTab() {
        assertTrue(shouldDeferContactsReconcile(tabVisible = false, coldStartCatchUp = false, isSearchQuery = false))
        assertFalse(shouldDeferContactsReconcile(tabVisible = true, coldStartCatchUp = false, isSearchQuery = false))
        assertFalse(shouldDeferContactsReconcile(tabVisible = false, coldStartCatchUp = true, isSearchQuery = false))
        assertFalse(
            shouldDeferContactsReconcile(
                tabVisible = false,
                coldStartCatchUp = false,
                isSearchQuery = false,
                isForced = true,
            ),
        )
    }

    @Test
    fun shouldInvalidateContactsSearch_versionAdvanced() {
        assertTrue(shouldInvalidateContactsSearch("alice", searchSessionVersion = 3L, cacheVersion = 4L))
        assertFalse(shouldInvalidateContactsSearch("", searchSessionVersion = 3L, cacheVersion = 4L))
        assertFalse(shouldInvalidateContactsSearch("alice", searchSessionVersion = 4L, cacheVersion = 4L))
    }

    @Test
    fun contactsPartialLoadClaimsVersion_onlyWhenRowsMatch() {
        assertFalse(contactsPartialLoadClaimsVersion(50, 100, 5L, 5L))
        assertTrue(contactsPartialLoadClaimsVersion(100, 100, 5L, 5L))
    }
}
