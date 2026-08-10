package com.goodwy.commons.providercache.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.providercache.filter.ContactPageFilterEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactPageFilterPagingSourceTest {

    @Test
    fun postFilterShrinkDoesNotCrash() = runBlocking {
        val delegate = object : PagingSource<Int, Contact>() {
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Contact> =
                LoadResult.Page(
                    data = listOf(
                        contact(1, "Alice"),
                        contact(2, "Bob"),
                        contact(3, "Charlie"),
                    ),
                    prevKey = null,
                    nextKey = null,
                )

            override fun getRefreshKey(state: PagingState<Int, Contact>): Int? = null
        }
        val source = ContactPageFilterPagingSource(
            delegate = delegate,
            filterEngine = ContactPageFilterEngine { contacts -> contacts.take(1) },
        )
        val result = source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 3, placeholdersEnabled = false),
        )
        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertEquals(1, page.data.size)
    }

    private fun contact(id: Int, name: String): Contact = Contact(
        id = id,
        contactId = id,
        firstName = name,
    )
}
