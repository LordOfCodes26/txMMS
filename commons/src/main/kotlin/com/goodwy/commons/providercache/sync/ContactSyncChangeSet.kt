package com.goodwy.commons.providercache.sync

/**
 * Summary of contact rows touched during a sync pass, for incremental display-cache rebuilds.
 */
data class ContactSyncChangeSet(
    val updatedContactIds: List<Int> = emptyList(),
    val deletedContactIds: List<Int> = emptyList(),
    val wasFullRebuild: Boolean = false,
    /** False when a sync pass found no summary rows that differ from Room (e.g. timestamp-only query hits). */
    val hasProvenProviderDelta: Boolean = true,
    /** Subset of [updatedContactIds] that did not previously exist in Room (e.g. a contact import). */
    val insertedContactIds: List<Int> = emptyList(),
)
