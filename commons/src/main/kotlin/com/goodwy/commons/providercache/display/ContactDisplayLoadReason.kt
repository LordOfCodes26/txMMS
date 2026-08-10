package com.goodwy.commons.providercache.display

enum class ContactDisplayLoadReason {
    INITIAL,
    CACHE_REBUILD,
    SEARCH_QUERY,
    MANUAL_REFRESH,
    FORCED,
    /** Display cache is warm but the adapter has no contact rows yet. */
    VISIBLE_EMPTY_ADAPTER,
    /** Display cache rebuilt after startup validation detected stale/empty cache. */
    STARTUP_CACHE_REBUILT,
    /** External bulk provider write (e.g. VCF import) forced a display-cache reload. */
    PROVIDER_CHANGED_IMPORT,
    /**
     * Hidden startup warm: Room list rows → thin Contact + FastScroll sections into RAM only
     * (no adapter bind). Fired after Recents display-cache QUERY so tab open can reuse.
     */
    STARTUP_HIDDEN_ROW_WARM,
}
