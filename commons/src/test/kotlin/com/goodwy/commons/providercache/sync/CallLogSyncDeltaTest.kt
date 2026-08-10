package com.goodwy.commons.providercache.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLogSyncDeltaTest {

    @Test
    fun equalCountDifferentIds_detectsReplacement() {
        val room = setOf(1, 2, 3)
        val provider = setOf(1, 2, 4)
        val drift = CallLogSyncDelta.computeIdDrift(room, provider)
        assertEquals(setOf(4), drift.insertedIds)
        assertEquals(setOf(3), drift.deletedIds)
        assertTrue(drift.equalCountDifferentIds)
    }

    @Test
    fun identicalSets_noDrift() {
        val ids = setOf(10, 20, 30)
        val drift = CallLogSyncDelta.computeIdDrift(ids, ids)
        assertTrue(drift.insertedIds.isEmpty())
        assertTrue(drift.deletedIds.isEmpty())
        assertEquals(false, drift.equalCountDifferentIds)
    }

    @Test
    fun providerCleared_allRoomDeleted() {
        val room = setOf(1, 2, 3)
        val provider = emptySet<Int>()
        val drift = CallLogSyncDelta.computeIdDrift(room, provider)
        assertEquals(room, drift.deletedIds)
        assertTrue(drift.insertedIds.isEmpty())
    }

    @Test
    fun oneRemovedOneAdded_replacement() {
        val room = setOf(10, 20, 30)
        val provider = setOf(10, 20, 40)
        val drift = CallLogSyncDelta.computeIdDrift(room, provider)
        assertEquals(setOf(40), drift.insertedIds)
        assertEquals(setOf(30), drift.deletedIds)
        assertTrue(drift.equalCountDifferentIds)
    }

    @Test
    fun largeMirrorRollover_detectsExactSets() {
        val room = (1..1000).toSet()
        val provider = (2..1001).toSet()
        val drift = CallLogSyncDelta.computeIdDrift(room, provider)
        assertEquals(setOf(1001), drift.insertedIds)
        assertEquals(setOf(1), drift.deletedIds)
        assertTrue(drift.equalCountDifferentIds)
    }

    @Test
    fun duplicateObserverEvents_idempotentSets() {
        val ids = setOf(5, 6, 7)
        val first = CallLogSyncDelta.computeIdDrift(ids, ids)
        val second = CallLogSyncDelta.computeIdDrift(ids, ids)
        assertEquals(first, second)
        assertTrue(first.insertedIds.isEmpty())
        assertTrue(first.deletedIds.isEmpty())
    }

    @Test
    fun staleIdDeletion_independentOfCountOnlyComparison() {
        val room = setOf(1, 2, 3)
        val provider = setOf(1, 2, 4)
        val drift = CallLogSyncDelta.computeIdDrift(room, provider)
        assertEquals(setOf(3), drift.deletedIds)
        assertEquals(setOf(4), drift.insertedIds)
    }
}
