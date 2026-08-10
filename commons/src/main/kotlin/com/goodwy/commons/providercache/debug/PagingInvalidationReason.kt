package com.goodwy.commons.providercache.debug

enum class PagingInvalidationReason {
    UNSPECIFIED,
    SEARCH_QUERY,
    SECURE_FILTER,
    SYNC_COMPLETE,
    ROOM_CACHE_READY,
    PHONE_INDEX_READY,
    MANUAL_REFRESH,
    PROVIDER_FALLBACK,
    ERROR_RETRY,
    DEBUG_ACTION,
}
