package com.goodwy.commons.providercache.display

/**
 * Version-based Contacts UI reconcile state.
 * [lastVisibleVersion] is owned by the app-layer controller; [cacheVersion] comes from
 * persisted [cache_metadata] via [com.goodwy.commons.providercache.repository.ContactsRepository].
 */
data class ContactsDisplayState(
    val cacheVersion: Long,
    val lastVisibleVersion: Long,
    val adapterLoaded: Boolean,
    val searchQuery: String = "",
    val searchSessionVersion: Long = 0L,
    val needsFullReload: Boolean = false,
) {
    fun isStale(): Boolean = cacheVersion != lastVisibleVersion

    fun needsReconcile(): Boolean =
        !adapterLoaded || needsFullReload || isStale()
}

/** Same target version while reconcile is in-flight coalesces duplicate callbacks. */
fun shouldCoalesceContactsReconcile(
    inFlightTargetVersion: Long,
    targetVersion: Long,
    reloadJobActive: Boolean,
): Boolean = reloadJobActive && inFlightTargetVersion == targetVersion

/** Hidden tab defers reconcile until tab enter unless cold-start catch-up or forced reload applies. */
fun shouldDeferContactsReconcile(
    tabVisible: Boolean,
    coldStartCatchUp: Boolean,
    isSearchQuery: Boolean,
    isForced: Boolean = false,
): Boolean = !tabVisible && !coldStartCatchUp && !isSearchQuery && !isForced

/** Active search invalidates when display version advances past search session version. */
fun shouldInvalidateContactsSearch(
    searchQuery: String,
    searchSessionVersion: Long,
    cacheVersion: Long,
): Boolean = searchQuery.isNotBlank() &&
    searchSessionVersion >= 0L &&
    searchSessionVersion != cacheVersion

/** First-page partial load must not claim full target until adapter rows match display rows. */
fun contactsPartialLoadClaimsVersion(
    adapterRows: Int,
    displayRows: Int,
    claimedVersion: Long,
    targetVersion: Long,
): Boolean = adapterRows > 0 && displayRows > 0 && adapterRows == displayRows && claimedVersion == targetVersion

/** Why the Contacts tab is reconciling display cache vs adapter. */
enum class ContactsUiReconcileReason {
    INITIAL,
    TAB_ENTER,
    CACHE_REBUILD,
    SEARCH_QUERY,
    SEARCH_VERSION_STALE,
    VISIBLE_EMPTY_ADAPTER,
    FORCED,
    PROVIDER_CHANGED_IMPORT,
    MANUAL_REFRESH,
}

fun ContactDisplayLoadReason.toUiReconcileReason(): ContactsUiReconcileReason = when (this) {
    ContactDisplayLoadReason.INITIAL -> ContactsUiReconcileReason.INITIAL
    ContactDisplayLoadReason.CACHE_REBUILD,
    ContactDisplayLoadReason.STARTUP_CACHE_REBUILT,
    ContactDisplayLoadReason.STARTUP_HIDDEN_ROW_WARM,
    -> ContactsUiReconcileReason.CACHE_REBUILD
    ContactDisplayLoadReason.SEARCH_QUERY -> ContactsUiReconcileReason.SEARCH_QUERY
    ContactDisplayLoadReason.VISIBLE_EMPTY_ADAPTER -> ContactsUiReconcileReason.VISIBLE_EMPTY_ADAPTER
    ContactDisplayLoadReason.FORCED -> ContactsUiReconcileReason.FORCED
    ContactDisplayLoadReason.PROVIDER_CHANGED_IMPORT -> ContactsUiReconcileReason.PROVIDER_CHANGED_IMPORT
    else -> ContactsUiReconcileReason.CACHE_REBUILD
}

fun ContactsUiReconcileReason.toContactDisplayLoadReason(): ContactDisplayLoadReason = when (this) {
    ContactsUiReconcileReason.INITIAL -> ContactDisplayLoadReason.INITIAL
    ContactsUiReconcileReason.TAB_ENTER -> ContactDisplayLoadReason.CACHE_REBUILD
    ContactsUiReconcileReason.CACHE_REBUILD -> ContactDisplayLoadReason.CACHE_REBUILD
    ContactsUiReconcileReason.SEARCH_QUERY -> ContactDisplayLoadReason.SEARCH_QUERY
    ContactsUiReconcileReason.SEARCH_VERSION_STALE -> ContactDisplayLoadReason.SEARCH_QUERY
    ContactsUiReconcileReason.VISIBLE_EMPTY_ADAPTER -> ContactDisplayLoadReason.VISIBLE_EMPTY_ADAPTER
    ContactsUiReconcileReason.FORCED -> ContactDisplayLoadReason.FORCED
    ContactsUiReconcileReason.PROVIDER_CHANGED_IMPORT -> ContactDisplayLoadReason.PROVIDER_CHANGED_IMPORT
    ContactsUiReconcileReason.MANUAL_REFRESH -> ContactDisplayLoadReason.CACHE_REBUILD
}
