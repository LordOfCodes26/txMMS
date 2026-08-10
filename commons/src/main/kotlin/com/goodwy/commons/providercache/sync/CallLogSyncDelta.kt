package com.goodwy.commons.providercache.sync

/**
 * Pure ID-set drift detection for call-log mirror sync.
 */
object CallLogSyncDelta {

    data class IdDrift(
        val insertedIds: Set<Int>,
        val deletedIds: Set<Int>,
        val equalCountDifferentIds: Boolean,
    )

    fun computeIdDrift(roomIds: Set<Int>, providerIds: Set<Int>): IdDrift {
        val inserted = providerIds - roomIds
        val deleted = roomIds - providerIds
        val equalCountDifferentIds =
            roomIds.size == providerIds.size && (inserted.isNotEmpty() || deleted.isNotEmpty())
        return IdDrift(
            insertedIds = inserted,
            deletedIds = deleted,
            equalCountDifferentIds = equalCountDifferentIds,
        )
    }
}
