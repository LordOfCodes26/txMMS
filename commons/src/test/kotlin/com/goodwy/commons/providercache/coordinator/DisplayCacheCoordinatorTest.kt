package com.goodwy.commons.providercache.coordinator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayCacheCoordinatorTest {

    @Test
    fun shouldIgnoreStaleCommit_olderMutationIgnored() {
        assertTrue(DisplayCacheCoordinator.shouldIgnoreStaleCommit(3L, 5L))
        assertFalse(DisplayCacheCoordinator.shouldIgnoreStaleCommit(5L, 5L))
        assertFalse(DisplayCacheCoordinator.shouldIgnoreStaleCommit(6L, 5L))
    }

    @Test
    fun shouldCoalesceInFlightReconcile_sameTargetWhileActive() {
        assertTrue(DisplayCacheCoordinator.shouldCoalesceInFlightReconcile(10L, 10L, true))
        assertFalse(DisplayCacheCoordinator.shouldCoalesceInFlightReconcile(10L, 11L, true))
        assertFalse(DisplayCacheCoordinator.shouldCoalesceInFlightReconcile(10L, 10L, false))
    }
}
