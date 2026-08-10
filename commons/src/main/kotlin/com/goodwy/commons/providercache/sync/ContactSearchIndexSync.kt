package com.goodwy.commons.providercache.sync

import android.content.Context
import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.dao.ContactDao
import com.goodwy.commons.providercache.entities.ContactSearchIndexEntity
import com.goodwy.commons.providercache.filter.T9Mapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Builds display-name and T9 search keys from cached contact summaries.
 */
class ContactSearchIndexSync(
    private val context: Context,
    private val database: ProviderCacheDatabase,
) {
    private val searchDao get() = database.contactSearchIndexDao()
    private val contactDao: ContactDao get() = database.contactDao()

    suspend fun rebuildIfNeeded() = withContext(Dispatchers.IO) {
        if (searchDao.getCount() > 0) return@withContext
        rebuildAll()
    }

    suspend fun rebuildAll() = withContext(Dispatchers.IO) {
        searchDao.clearAll()
        indexSummaries(contactDao.getAllSummaries())
    }

    suspend fun updateForContactIds(contactIds: List<Int>) = withContext(Dispatchers.IO) {
        if (contactIds.isEmpty()) return@withContext
        contactIds.chunked(200).forEach { chunk ->
            searchDao.deleteForContacts(chunk)
        }
        indexSummaries(contactDao.getSummariesByIds(contactIds))
    }

    suspend fun deleteForContactIds(contactIds: List<Int>) = withContext(Dispatchers.IO) {
        if (contactIds.isEmpty()) return@withContext
        contactIds.chunked(200).forEach { chunk ->
            searchDao.deleteForContacts(chunk)
        }
    }

    private suspend fun indexSummaries(summaries: List<com.goodwy.commons.providercache.entities.ContactSummaryEntity>) {
        if (summaries.isEmpty()) return
        val batch = ArrayList<ContactSearchIndexEntity>(200)
        for (summary in summaries) {
            val name = summary.displayName
            if (name.isEmpty()) continue
            val lower = name.lowercase(Locale.getDefault())
            batch.add(
                ContactSearchIndexEntity(
                    contactId = summary.contactId,
                    displayNameLower = lower,
                    nameT9Key = T9Mapper.toT9Digits(lower),
                ),
            )
            if (batch.size >= 200) {
                searchDao.insertAll(batch)
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) searchDao.insertAll(batch)
    }
}
