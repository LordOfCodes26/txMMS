package com.goodwy.commons.providercache.sync

/**
 * Summary of call-log rows touched during a sync pass, for incremental display-cache rebuilds.
 */
data class CallLogSyncChangeSet(
    val insertedCallIds: List<Int> = emptyList(),
    val deletedCallIds: List<Int> = emptyList(),
    val wasColdRebuild: Boolean = false,
) {
    val hasChanges: Boolean
        get() = wasColdRebuild || insertedCallIds.isNotEmpty() || deletedCallIds.isNotEmpty()
}
