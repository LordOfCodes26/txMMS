package com.goodwy.commons.providercache.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger
import com.goodwy.commons.providercache.filter.ContactListPagingFilters
import com.goodwy.commons.providercache.filter.ContactPageFilterEngine
import com.goodwy.commons.providercache.filter.ContactRoomQueryFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Applies [ContactListPagingFilters] once per loaded page (secure, source, duplicate merge).
 */
class ContactPageFilterPagingSource(
    private val delegate: PagingSource<Int, Contact>,
    private val filterEngine: ContactPageFilterEngine,
    private val sqlFilters: ContactRoomQueryFilters? = null,
) : PagingSource<Int, Contact>() {

    constructor(
        delegate: PagingSource<Int, Contact>,
        filters: ContactListPagingFilters,
        sqlFilters: ContactRoomQueryFilters? = null,
    ) : this(
        delegate = delegate,
        filterEngine = ContactPageFilterEngine { page -> filters.filterPage(page, sqlFilters) },
        sqlFilters = sqlFilters,
    )

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Contact> = withContext(Dispatchers.IO) {
        val delegateStart = System.currentTimeMillis()
        when (val result = delegate.load(params)) {
            is LoadResult.Page<Int, Contact> -> {
                ProviderCacheDebugLogger.logPagingStep(
                    stage = "pagingSourceDelegate",
                    durationMs = System.currentTimeMillis() - delegateStart,
                    detail = "rows=${result.data.size}",
                )
                val filterStart = System.currentTimeMillis()
                val filtered = filterEngine.filterPage(result.data)
                ProviderCacheDebugLogger.logPagingStep(
                    stage = "pageFilterTotal",
                    durationMs = System.currentTimeMillis() - filterStart,
                    detail = "in=${result.data.size} out=${filtered.size} sqlAccount=${sqlFilters?.sqlAccountFilterApplied == true} sqlSecure=${sqlFilters?.sqlSecureFilterApplied == true}",
                )
                LoadResult.Page(
                    data = filtered,
                    prevKey = result.prevKey,
                    nextKey = result.nextKey,
                    itemsBefore = result.itemsBefore,
                    itemsAfter = result.itemsAfter,
                )
            }
            is LoadResult.Error<Int, Contact> -> LoadResult.Error(result.throwable)
            is LoadResult.Invalid -> LoadResult.Invalid()
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Contact>): Int? = null
}
