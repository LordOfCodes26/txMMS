package com.goodwy.commons.providercache.search

data class SearchSession(
    val query: String,
    val mode: SearchMode,
    val generation: Long,
    val displayCacheVersion: Long,
)
