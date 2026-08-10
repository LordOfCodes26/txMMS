package com.goodwy.commons.providercache.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsColdStartPaintPolicyTest {

    @Test
    fun allowDisplayRebuild_blockedWhileProgressiveSyncInProgress() {
        assertFalse(ContactsColdStartPaintPolicy.allowDisplayRebuild(progressiveSyncInProgress = true))
        assertTrue(ContactsColdStartPaintPolicy.allowDisplayRebuild(progressiveSyncInProgress = false))
    }

    @Test
    fun isCompleteSnapshotForFirstPaint_requiresMatchingCounts() {
        assertTrue(ContactsColdStartPaintPolicy.isCompleteSnapshotForFirstPaint(0, 0))
        assertTrue(ContactsColdStartPaintPolicy.isCompleteSnapshotForFirstPaint(1200, 1200))
        assertFalse(ContactsColdStartPaintPolicy.isCompleteSnapshotForFirstPaint(1200, 500))
        assertFalse(ContactsColdStartPaintPolicy.isCompleteSnapshotForFirstPaint(500, 0))
    }

    @Test
    fun secondaryIndexesAreDeferredAfterDisplayCache() {
        val critical = ContactsColdStartPaintPolicy.criticalPathBeforeFirstPaint
        val deferred = ContactsColdStartPaintPolicy.deferredAfterFirstPaint
        assertEquals(listOf("raw_summaries", "display_cache"), critical)
        assertTrue(deferred.contains("phone_index"))
        assertTrue(deferred.contains("search_index"))
        assertTrue(deferred.contains("call_log_backfill"))
        assertTrue(critical.none { it in deferred })
    }
}
