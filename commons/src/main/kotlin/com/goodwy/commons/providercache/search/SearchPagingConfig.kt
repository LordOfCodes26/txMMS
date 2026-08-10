package com.goodwy.commons.providercache.search

data class SearchPagingConfig(
    val initialLimit: Int = 10,
    val pageSize: Int = 10,
    val preloadThreshold: Int = 3,
    val maxResults: Int = 60,
)
