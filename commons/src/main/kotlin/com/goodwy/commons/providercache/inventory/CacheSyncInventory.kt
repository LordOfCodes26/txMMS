package com.goodwy.commons.providercache.inventory

/**
 * Phase A inventory — write-path map for provider-cache tables.
 *
 * WHO WRITES EACH TABLE:
 * - contact_summaries: ContactsSyncManager (full/incremental), ContactsRepository.refreshSingleContactDisplay,
 *   ContactsRepository.removeContactsFromCachesImmediately, ContactsBulkDeleteManager
 * - contact_phone_index: ContactPhoneIndexSync (full/per-id), ContactsRepository purge paths
 * - contact_search_index: ContactSearchIndexSync (full/per-id), ContactsRepository purge paths
 * - call_log_entries: CallLogSyncManager, CallLogRepository.applyExternalCallLogDeletes,
 *   CallLogRepository contact-change patches (backfill/clear/reassign)
 * - contact_display_cache: ContactDisplayCacheBuilder via ContactsRepository scheduler +
 *   applySyncChangesToDisplayCacheBlocking + refreshSingleContactDisplay
 * - recent_display_cache: RecentDisplayCacheBuilder via CallLogRepository scheduler +
 *   contact-change handlers (app layer)
 *
 * WHO INCREMENTS VERSIONS (pre-coordinator):
 * - contact_display_cache: ContactsRepository.bumpDisplayCacheVersion (in-memory)
 * - recent_display_cache: CallLogRepository.recordRecentsCacheChanged / recordRecentsDisplayPatched
 *
 * WHO NOTIFIES UI:
 * - Contacts: ContactsRepository.onContactDisplayCacheUpdated, onDisplayCacheBecameReady
 * - Recents: CallLogRepository.onRecentsDisplayStateChanged → RecentsDisplayBridge.reconcileRecentsUi
 *
 * TRANSACTIONAL PATHS:
 * - Room DAO insert/replace batches (per-table, not cross-table atomic)
 * - Display cache upsertAndPrune (single-table transaction)
 *
 * NON-TRANSACTIONAL / RISKY (Phase 2 partially addressed):
 * - Contact delete → call_log patch → recent_display patch still multi-stage but Room purge is transactional
 * - Progressive contacts sync (streaming pages, display rebuild deferred)
 *
 * CONCURRENT WRITERS:
 * - ContactsChangeObserver + ContactChangeCoordinator (debounced, mutex in ContactsSyncManager)
 * - CallLogChangeObserver + user deletes (debounced, mutex in CallLogSyncManager)
 * - ContactDisplayCacheRebuildScheduler + RecentDisplayCacheRebuildScheduler (debounced)
 * - Multiple version bumps from rebuild complete + contact-change patches
 *
 * SILENT CATCH SITES (Phase I targets):
 * - ContactsSyncManager.runFullSyncLocked
 * - CallLogRepository.loadFirstPage
 * - ContactsRepository (startup validation)
 * - ContactPhoneIndexSync
 *
 * LEGACY DISK CACHES (Phase 5 — gated by [com.goodwy.commons.providercache.validation.legacy.LegacyCacheGate]):
 * - ContactsListDiskCache (DialerContactsTabSupport) — writes skipped when Room authoritative
 * - RecentsListDiskCache (DialerCore) — writes skipped when Room authoritative
 * - RecentCallsMemoryCache (DialerCore instant preview) — writes skipped when Room authoritative
 * Eviction: [com.android.dialer.providercache.LegacyCacheEvictor]
 *
 * DEBUG (Phase R): [com.goodwy.commons.providercache.validation.CacheDebugCommands] +
 * [com.android.dialer.providercache.ProviderCacheDebugSupport] QA menu
 */
internal object CacheSyncInventory
