package com.goodwy.commons.providercache.display

/**
 * Outcome of patching [recent_display_cache] after contact deletion.
 * [deltas] are safe to apply incrementally unless [needsFullReload] is true.
 */
data class ContactDeletedRecentsResult(
    val updatedCallIds: List<Int> = emptyList(),
    val deltas: List<PendingRecentDelta> = emptyList(),
    val needsFullReload: Boolean = false,
) {
    companion object {
        val EMPTY = ContactDeletedRecentsResult()
    }
}
