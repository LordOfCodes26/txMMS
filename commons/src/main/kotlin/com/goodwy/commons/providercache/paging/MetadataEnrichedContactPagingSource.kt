package com.goodwy.commons.providercache.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger
import com.goodwy.commons.providercache.datasource.ContactsMetadataLoader
import com.goodwy.commons.providercache.filter.ContactPagingMapper
import com.goodwy.commons.providercache.model.ContactSummary

/**
 * Loads pages from a [ContactSummary] delegate and enriches each page with metadata in one batch.
 */
class MetadataEnrichedContactPagingSource(
    private val delegate: PagingSource<Int, ContactSummary>,
    private val metadataLoader: ContactsMetadataLoader,
) : PagingSource<Int, Contact>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Contact> {
        val delegateStart = System.currentTimeMillis()
        return when (val result = delegate.load(params)) {
            is LoadResult.Page<Int, ContactSummary> -> {
                ProviderCacheDebugLogger.logPagingStep(
                    stage = "providerQueryLoad",
                    durationMs = System.currentTimeMillis() - delegateStart,
                    detail = "rows=${result.data.size}",
                )
                val metaStart = System.currentTimeMillis()
                val meta = metadataLoader.loadForSummaries(result.data)
                ProviderCacheDebugLogger.logPagingStep(
                    stage = "metadataEnrich",
                    durationMs = System.currentTimeMillis() - metaStart,
                    detail = "rows=${result.data.size} meta=${meta.size}",
                )
                LoadResult.Page(
                    data = result.data.map { summary ->
                        val m = meta[summary.contactId]
                        if (m != null) {
                            ContactPagingMapper.summaryToContact(summary, m)
                        } else {
                            ContactPagingMapper.summaryToContactFallback(summary)
                        }
                    },
                    prevKey = result.prevKey,
                    nextKey = result.nextKey,
                    itemsBefore = result.itemsBefore,
                    itemsAfter = result.itemsAfter,
                )
            }
            is LoadResult.Error<Int, ContactSummary> -> LoadResult.Error(result.throwable)
            is LoadResult.Invalid -> LoadResult.Invalid()
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Contact>): Int? = null
}
