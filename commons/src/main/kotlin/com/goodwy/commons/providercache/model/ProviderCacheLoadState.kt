package com.goodwy.commons.providercache.model

enum class ProviderCacheLoadState {
    /** Initial / no data yet. */
    LoadingFirstPage,
    /** Showing provider-backed paging while Room is empty. */
    ShowingProviderFallback,
    /** Background sync is writing into Room. */
    RebuildingCache,
    /** Room cache is populated; paging reads from Room. */
    ShowingRoomCache,
    /** Recoverable error; user can retry. */
    Error,
}
