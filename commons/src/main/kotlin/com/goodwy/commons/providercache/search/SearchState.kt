package com.goodwy.commons.providercache.search

import com.goodwy.commons.models.contacts.Contact

sealed class SearchState {
    data object LoadingCache : SearchState()

    data class Page(
        val contacts: List<Contact>,
        val topRankingReason: SearchRankingReason?,
    ) : SearchState()
}
