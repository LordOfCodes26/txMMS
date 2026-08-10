package com.goodwy.commons.providercache.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger

/**
 * Maps each page from delegate type [S] to [T] without per-item side effects in [PagingData.map].
 */
class EntityMappingPagingSource<S : Any, T : Any>(
    private val delegate: PagingSource<Int, S>,
    private val mapper: (S) -> T,
    private val roomQueryLabel: String = "contacts_page",
) : PagingSource<Int, T>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        val queryStart = System.currentTimeMillis()
        val result = delegate.load(params)
        val queryMs = System.currentTimeMillis() - queryStart
        return when (result) {
            is LoadResult.Page<Int, S> -> {
                ProviderCacheDebugLogger.logPagingStep(
                    stage = "roomQueryLoad",
                    durationMs = queryMs,
                    detail = "label=$roomQueryLabel rows=${result.data.size}",
                )
                val mapStart = System.currentTimeMillis()
                val mapped = result.data.map(mapper)
                ProviderCacheDebugLogger.logPagingStep(
                    stage = "entityMap",
                    durationMs = System.currentTimeMillis() - mapStart,
                    detail = "rows=${mapped.size}",
                )
                LoadResult.Page(
                    data = mapped,
                    prevKey = result.prevKey,
                    nextKey = result.nextKey,
                    itemsBefore = result.itemsBefore,
                    itemsAfter = result.itemsAfter,
                )
            }
            is LoadResult.Error<Int, S> -> LoadResult.Error(result.throwable)
            is LoadResult.Invalid -> LoadResult.Invalid()
        }
    }

    override fun getRefreshKey(state: PagingState<Int, T>): Int? = null
}
