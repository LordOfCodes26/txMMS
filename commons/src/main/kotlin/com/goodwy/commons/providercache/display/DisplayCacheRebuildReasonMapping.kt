package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.coordinator.CacheMutationReason

fun DisplayCacheRebuildReason.toCacheMutationReason(): CacheMutationReason = when (this) {
    DisplayCacheRebuildReason.COLD_EMPTY_CACHE,
    DisplayCacheRebuildReason.STARTUP_INVALID_CACHE,
    -> CacheMutationReason.STARTUP_REPAIR
    DisplayCacheRebuildReason.CONTACT_SYNC_COMPLETED,
    DisplayCacheRebuildReason.PROVIDER_CHANGED_IMPORT,
    -> CacheMutationReason.CONTACT_IMPORT
    DisplayCacheRebuildReason.CHANGED_CONTACT_IDS,
    DisplayCacheRebuildReason.CONTACT_DISPLAY_CHANGED,
    -> CacheMutationReason.CONTACT_UPDATED
    DisplayCacheRebuildReason.DELETED_CONTACT_IDS,
    -> CacheMutationReason.CONTACT_DELETED
    DisplayCacheRebuildReason.CONTACTS_CHANGED,
    -> CacheMutationReason.CONTACT_PROVIDER_CHANGED
    DisplayCacheRebuildReason.CALL_LOG_INSERTED,
    DisplayCacheRebuildReason.CALL_LOG_SYNC_COMPLETED,
    -> CacheMutationReason.CALL_INSERTED
    DisplayCacheRebuildReason.CALL_LOG_DELETED,
    -> CacheMutationReason.CALL_DELETED
    DisplayCacheRebuildReason.RECENTS_GROUPING_CHANGED,
    -> CacheMutationReason.GROUPING_CHANGED
    DisplayCacheRebuildReason.SECURE_MODE_CHANGED,
    DisplayCacheRebuildReason.SOURCE_FILTER_CHANGED,
    -> CacheMutationReason.FILTER_CHANGED
    DisplayCacheRebuildReason.MIGRATION,
    -> CacheMutationReason.MIGRATION_REPAIR
    DisplayCacheRebuildReason.MANUAL_DEBUG,
    -> CacheMutationReason.MANUAL_REFRESH
    else -> CacheMutationReason.MANUAL_REFRESH
}
