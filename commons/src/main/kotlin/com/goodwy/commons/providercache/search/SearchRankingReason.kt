package com.goodwy.commons.providercache.search

/** Primary match reason for the first result on a page — used for diagnostics. */
enum class SearchRankingReason {
    EXACT,
    STARTS_WITH,
    WORD_START,
    PHONE_PREFIX,
    PHONE_CONTAINS,
    CONTAINS,
    T9,
}
