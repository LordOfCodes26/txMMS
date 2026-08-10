package com.goodwy.commons.providercache.datasource

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.os.Bundle
import android.provider.ContactsContract
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.getIntValue
import com.goodwy.commons.extensions.getLongValueOr
import com.goodwy.commons.extensions.getStringValue
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.helpers.PERMISSION_READ_CONTACTS
import com.goodwy.commons.helpers.isQPlus
import com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger
import com.goodwy.commons.providercache.model.ContactSummary
import com.goodwy.commons.providercache.validation.ContactsCacheHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactsProviderDataSource(
    private val context: Context,
    private val showOnlyContactsWithNumbers: Boolean = context.baseConfig.showOnlyContactsWithNumbers,
) {

    companion object {
        private val SUMMARY_PROJECTION = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            ContactsContract.Contacts.PHOTO_URI,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
            ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
        )

        private const val SORT_ORDER =
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC"

        private const val PAGE_SIZE = 50
    }

    fun pagingSource(): PagingSource<Int, ContactSummary> = ProviderContactSummaryPagingSource()

    fun searchPagingSource(
        query: String,
        normalizedQuery: String,
        digitsQuery: String,
        t9Digits: String = "",
    ): PagingSource<Int, ContactSummary> =
        ProviderContactSearchPagingSource(query, normalizedQuery, digitsQuery, t9Digits)

    suspend fun getCount(): Int = withContext(Dispatchers.IO) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) return@withContext 0
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            buildSelection(),
            null,
            null,
        )?.use { it.count } ?: 0
    }

    suspend fun loadPage(offset: Int, limit: Int): List<ContactSummary> = withContext(Dispatchers.IO) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) return@withContext emptyList()
        querySummaries(offset, limit)
    }

    suspend fun loadSummariesUpdatedSince(sinceTimestamp: Long, limit: Int = 500): List<ContactSummary> =
        withContext(Dispatchers.IO) {
            if (!context.hasPermission(PERMISSION_READ_CONTACTS)) return@withContext emptyList()
            val selection = buildString {
                append("${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} > ?")
                if (showOnlyContactsWithNumbers) {
                    append(" AND ${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1")
                }
            }
            querySummaries(
                offset = 0,
                limit = limit,
                selection = selection,
                selectionArgs = arrayOf(sinceTimestamp.toString()),
                sortOrder = "${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} ASC",
            )
        }

    suspend fun loadSummariesForContactIds(ids: Collection<Int>): List<ContactSummary> =
        withContext(Dispatchers.IO) {
            if (!context.hasPermission(PERMISSION_READ_CONTACTS) || ids.isEmpty()) {
                return@withContext emptyList()
            }
            querySummariesForContactIds(ids, offset = 0, limit = ids.size.coerceAtLeast(500))
        }

    suspend fun loadAllSummaryIds(): List<Int> = withContext(Dispatchers.IO) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) return@withContext emptyList()
        val ids = ArrayList<Int>()
        val projection = arrayOf(ContactsContract.Contacts._ID)
        val selection = if (showOnlyContactsWithNumbers) {
            "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1"
        } else {
            null
        }
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            selection,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(ContactsContract.Contacts._ID)
            if (idCol < 0) return@use
            while (cursor.moveToNext()) {
                ids.add(cursor.getInt(idCol))
            }
        }
        ids
    }

    data class ValidationSnapshot(
        val contactsCount: Int,
        val maxLastUpdatedTimestamp: Long,
        val idsHash: Long,
    )

    /** Lightweight provider snapshot for startup cache validation. */
    suspend fun loadValidationSnapshot(): ValidationSnapshot = withContext(Dispatchers.IO) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return@withContext ValidationSnapshot(contactsCount = 0, maxLastUpdatedTimestamp = 0L, idsHash = 0L)
        }
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
        )
        var count = 0
        var maxTs = 0L
        val ids = ArrayList<Int>()
        val selection = if (showOnlyContactsWithNumbers) {
            "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1"
        } else {
            null
        }
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            selection,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(ContactsContract.Contacts._ID)
            val tsCol = cursor.getColumnIndex(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP)
            if (idCol < 0) return@use
            while (cursor.moveToNext()) {
                val id = cursor.getInt(idCol)
                ids.add(id)
                count++
                if (tsCol >= 0) {
                    val ts = cursor.getLong(tsCol)
                    if (ts > maxTs) maxTs = ts
                }
            }
        }
        ValidationSnapshot(
            contactsCount = count,
            maxLastUpdatedTimestamp = maxTs,
            idsHash = ContactsCacheHash.computeIdsHash(ids),
        )
    }

    suspend fun resolveContactIdFromRawId(rawId: Int): Int? = withContext(Dispatchers.IO) {
        if (rawId <= 0 || !context.hasPermission(PERMISSION_READ_CONTACTS)) return@withContext null
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            "${ContactsContract.RawContacts._ID} = ?",
            arrayOf(rawId.toString()),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getIntValue(ContactsContract.RawContacts.CONTACT_ID).takeIf { it > 0 }
            } else {
                null
            }
        }
    }

    /**
     * Lightweight read for post-edit list refresh — one aggregate Contacts row plus first phone/email.
     * Avoids full [loadContactDetail] / [ContactsHelper.getContactWithId] cost on the hot path.
     */
    suspend fun loadEditRefreshFields(contactId: Int): ContactEditRefreshFields? = withContext(Dispatchers.IO) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS) || contactId <= 0) return@withContext null
        val uri = ContactsContract.Contacts.CONTENT_URI.buildUpon()
            .appendPath(contactId.toString())
            .build()
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.PHOTO_URI,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            ContactsContract.Contacts.PHOTO_ID,
            ContactsContract.Contacts.STARRED,
            ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
        )
        val aggregate = context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            ContactEditRefreshFields(
                contactId = cursor.getIntValue(ContactsContract.Contacts._ID),
                lookupKey = cursor.getStringValue(ContactsContract.Contacts.LOOKUP_KEY) ?: "",
                displayName = cursor.getStringValue(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY) ?: "",
                photoUri = cursor.getStringValue(ContactsContract.Contacts.PHOTO_URI) ?: "",
                thumbnailUri = cursor.getStringValue(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI) ?: "",
                photoId = cursor.getLongValueOr(ContactsContract.Contacts.PHOTO_ID, 0L),
                starred = cursor.getIntValue(ContactsContract.Contacts.STARRED),
                lastUpdatedTimestamp = cursor.getLongValueOr(
                    ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
                    0L,
                ),
                firstPhoneNormalized = "",
                firstPhoneRaw = "",
                firstEmail = "",
            )
        } ?: return@withContext null
        val (phoneRaw, phoneNorm) = loadFirstPhone(contactId)
        val firstEmail = loadFirstEmail(contactId)
        aggregate.copy(
            firstPhoneNormalized = phoneNorm,
            firstPhoneRaw = phoneRaw,
            firstEmail = firstEmail,
        )
    }

    suspend fun loadContactDetail(contactId: Int): ContactDetailLoadResult? = withContext(Dispatchers.IO) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) return@withContext null
        val uri = ContactsContract.Contacts.CONTENT_URI.buildUpon()
            .appendPath(contactId.toString())
            .build()
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.PHOTO_URI,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            ContactsContract.Contacts.STARRED,
            ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
        )
        val summary = context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            cursor.toContactSummarySafe()
        } ?: return@withContext null

        val phones = loadPhones(contactId)
        val emails = loadEmails(contactId)
        val structuredName = loadStructuredName(contactId)
        ContactDetailLoadResult(
            summary = summary,
            prefix = structuredName.prefix,
            firstName = structuredName.firstName,
            middleName = structuredName.middleName,
            surname = structuredName.surname,
            suffix = structuredName.suffix,
            nickname = structuredName.nickname,
            notes = structuredName.notes,
            company = structuredName.company,
            jobPosition = structuredName.jobPosition,
            phones = phones,
            emails = emails,
        )
    }

    private fun querySummaries(
        offset: Int,
        limit: Int,
        selection: String? = buildSelection(),
        selectionArgs: Array<String>? = null,
        sortOrder: String = SORT_ORDER,
    ): List<ContactSummary> {
        val results = ArrayList<ContactSummary>(limit)
        val started = System.currentTimeMillis()
        val cursor = if (isQPlus()) {
            val queryArgs = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                if (selectionArgs != null) {
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                }
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            }
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                SUMMARY_PROJECTION,
                queryArgs,
                null,
            )
        } else {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                SUMMARY_PROJECTION,
                selection,
                selectionArgs,
                "$sortOrder LIMIT $limit OFFSET $offset",
            )
        }
        cursor?.use {
            it.readContactPage(offset, limit) { row ->
                row.toContactSummarySafe()?.let { summary -> results.add(summary) }
            }
        }
        ProviderCacheDebugLogger.logProviderQuery(
            label = "contacts_summaries",
            durationMs = System.currentTimeMillis() - started,
            rowCount = results.size,
        )
        return results
    }

    private fun buildSelection(): String? =
        if (showOnlyContactsWithNumbers) "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1" else null

    private fun searchContactIdsByPhone(needle: String): Set<Int> {
        if (needle.isEmpty()) return emptySet()
        val ids = HashSet<Int>()
        try {
            val selection = "(${ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER} LIKE ?" +
                " OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?)"
            val args = arrayOf("%$needle%", "%$needle%")
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID),
                selection,
                args,
                null,
            )?.use { cursor ->
                val col = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                if (col < 0) return@use
                while (cursor.moveToNext()) {
                    ids.add(cursor.getInt(col))
                }
            }
        } catch (_: Exception) {
            // OEM may reject LIKE on phone columns — caller falls back to name-only search.
        }
        return ids
    }

    private fun querySummariesForContactIds(ids: Collection<Int>, offset: Int, limit: Int): List<ContactSummary> {
        if (ids.isEmpty()) return emptyList()
        val results = ArrayList<ContactSummary>(limit)
        var globalIndex = 0
        ids.toList().chunked(100).forEach { chunk ->
            if (results.size >= limit) return@forEach
            val placeholders = chunk.joinToString(",") { "?" }
            var selection = "${ContactsContract.Contacts._ID} IN ($placeholders)"
            buildSelection()?.let { phoneFilter ->
                selection = "($selection) AND $phoneFilter"
            }
            val args = chunk.map { it.toString() }.toTypedArray()
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                SUMMARY_PROJECTION,
                selection,
                args,
                SORT_ORDER,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (globalIndex < offset) {
                        globalIndex++
                        continue
                    }
                    if (results.size >= limit) break
                    cursor.toContactSummarySafe()?.let { results.add(it) }
                    globalIndex++
                }
            }
        }
        return results
    }

    private fun searchByName(query: String, offset: Int, limit: Int): List<ContactSummary> {
        val selection = buildString {
            append("${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ? ESCAPE '\\'")
            buildSelection()?.let { append(" AND $it") }
        }
        return querySummaries(
            offset = offset,
            limit = limit,
            selection = selection,
            selectionArgs = arrayOf("%${escapeLikeArg(query)}%"),
        )
    }

    private fun escapeLikeArg(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private inner class ProviderContactSearchPagingSource(
        private val query: String,
        private val normalizedQuery: String,
        private val digitsQuery: String,
        private val t9Digits: String,
    ) : PagingSource<Int, ContactSummary>() {
        private var phoneMatchIds: Set<Int>? = null

        override fun getRefreshKey(state: PagingState<Int, ContactSummary>): Int? {
            return state.anchorPosition?.let { anchor ->
                state.closestPageToPosition(anchor)?.prevKey?.plus(PAGE_SIZE)
                    ?: state.closestPageToPosition(anchor)?.nextKey?.minus(PAGE_SIZE)
            }
        }

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ContactSummary> {
            return try {
                val offset = params.key ?: 0
                val phoneNeedle = digitsQuery.ifEmpty { normalizedQuery }
                val data = try {
                    loadSearchPage(offset, params.loadSize, phoneNeedle)
                } catch (_: Exception) {
                    searchByName(query, offset, params.loadSize)
                }
                LoadResult.Page(
                    data = data,
                    prevKey = if (offset == 0) null else (offset - params.loadSize).coerceAtLeast(0),
                    nextKey = if (data.size < params.loadSize) null else offset + data.size,
                )
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }

        private fun loadSearchPage(offset: Int, limit: Int, phoneNeedle: String): List<ContactSummary> {
            val nameResults = if (query.isNotEmpty()) {
                searchByName(query, offset, limit)
            } else {
                emptyList()
            }
            if (phoneNeedle.isEmpty() && t9Digits.isEmpty()) {
                return nameResults
            }
            if (phoneMatchIds == null) {
                phoneMatchIds = searchContactIdsByPhone(phoneNeedle.ifEmpty { t9Digits })
            }
            val phoneResults = querySummariesForContactIds(phoneMatchIds.orEmpty(), offset, limit)
            if (nameResults.isEmpty()) return phoneResults
            if (phoneResults.isEmpty()) return nameResults
            val merged = LinkedHashMap<Int, ContactSummary>()
            for (item in nameResults) merged[item.contactId] = item
            for (item in phoneResults) merged[item.contactId] = item
            return merged.values.drop(offset).take(limit)
        }
    }

    private fun Cursor.toSummary(): ContactSummary =
        toContactSummarySafe() ?: ContactSummary(
            contactId = 0,
            lookupKey = "",
            displayName = "",
            photoThumbnailUri = "",
            hasPhoneNumber = false,
            lastUpdatedTimestamp = 0L,
        )

    private fun loadFirstPhone(contactId: Int): Pair<String, String> {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC"
        context.contentResolver.query(
            uri,
            projection,
            selection,
            arrayOf(contactId.toString()),
            sortOrder,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val raw = cursor.getStringValue(ContactsContract.CommonDataKinds.Phone.NUMBER) ?: ""
                val norm = cursor.getStringValue(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER) ?: raw
                return raw to norm
            }
        }
        return "" to ""
    }

    private fun loadFirstEmail(contactId: Int): String {
        val uri = ContactsContract.CommonDataKinds.Email.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS)
        val selection = "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?"
        context.contentResolver.query(
            uri,
            projection,
            selection,
            arrayOf(contactId.toString()),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getStringValue(ContactsContract.CommonDataKinds.Email.ADDRESS) ?: ""
            }
        }
        return ""
    }

    private fun loadPhones(contactId: Int): List<ContactPhoneLoad> {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
        val phones = ArrayList<ContactPhoneLoad>()
        context.contentResolver.query(uri, projection, selection, arrayOf(contactId.toString()), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                phones.add(
                    ContactPhoneLoad(
                        number = cursor.getStringValue(ContactsContract.CommonDataKinds.Phone.NUMBER) ?: "",
                        type = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)),
                        label = cursor.getStringValue(ContactsContract.CommonDataKinds.Phone.LABEL) ?: "",
                        normalizedNumber = cursor.getStringValue(
                            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                        ) ?: "",
                        isPrimary = cursor.getInt(
                            cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.IS_PRIMARY),
                        ) == 1,
                    ),
                )
            }
        }
        return phones
    }

    private fun loadEmails(contactId: Int): List<ContactEmailLoad> {
        val uri = ContactsContract.CommonDataKinds.Email.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Email.ADDRESS,
            ContactsContract.CommonDataKinds.Email.TYPE,
            ContactsContract.CommonDataKinds.Email.LABEL,
        )
        val selection = "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?"
        val emails = ArrayList<ContactEmailLoad>()
        context.contentResolver.query(uri, projection, selection, arrayOf(contactId.toString()), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                emails.add(
                    ContactEmailLoad(
                        value = cursor.getStringValue(ContactsContract.CommonDataKinds.Email.ADDRESS) ?: "",
                        type = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.TYPE)),
                        label = cursor.getStringValue(ContactsContract.CommonDataKinds.Email.LABEL) ?: "",
                    ),
                )
            }
        }
        return emails
    }

    private fun loadStructuredName(contactId: Int): StructuredNameLoad {
        val uri = ContactsContract.Data.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.StructuredName.PREFIX,
            ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
            ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME,
            ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
            ContactsContract.CommonDataKinds.StructuredName.SUFFIX,
            ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Note.NOTE,
            ContactsContract.CommonDataKinds.Organization.COMPANY,
            ContactsContract.CommonDataKinds.Organization.TITLE,
            ContactsContract.Data.MIMETYPE,
        )
        val selection = "${ContactsContract.Data.CONTACT_ID} = ?"
        var result = StructuredNameLoad()
        context.contentResolver.query(uri, projection, selection, arrayOf(contactId.toString()), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                when (cursor.getStringValue(ContactsContract.Data.MIMETYPE)) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        result = result.copy(
                            prefix = cursor.getStringValue(ContactsContract.CommonDataKinds.StructuredName.PREFIX) ?: "",
                            firstName = cursor.getStringValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME)
                                ?: "",
                            middleName = cursor.getStringValue(
                                ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME,
                            ) ?: "",
                            surname = cursor.getStringValue(
                                ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
                            ) ?: "",
                            suffix = cursor.getStringValue(ContactsContract.CommonDataKinds.StructuredName.SUFFIX) ?: "",
                        )
                    }
                    ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE -> {
                        result = result.copy(
                            nickname = cursor.getStringValue(ContactsContract.CommonDataKinds.Nickname.NAME) ?: "",
                        )
                    }
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                        result = result.copy(
                            notes = cursor.getStringValue(ContactsContract.CommonDataKinds.Note.NOTE) ?: "",
                        )
                    }
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        result = result.copy(
                            company = cursor.getStringValue(ContactsContract.CommonDataKinds.Organization.COMPANY)
                                ?: "",
                            jobPosition = cursor.getStringValue(ContactsContract.CommonDataKinds.Organization.TITLE)
                                ?: "",
                        )
                    }
                }
            }
        }
        return result
    }

    private inner class ProviderContactSummaryPagingSource : PagingSource<Int, ContactSummary>() {
        override fun getRefreshKey(state: PagingState<Int, ContactSummary>): Int? {
            return state.anchorPosition?.let { anchor ->
                state.closestPageToPosition(anchor)?.prevKey?.plus(PAGE_SIZE)
                    ?: state.closestPageToPosition(anchor)?.nextKey?.minus(PAGE_SIZE)
            }
        }

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ContactSummary> {
            return try {
                val offset = params.key ?: 0
                val data = querySummaries(offset, params.loadSize)
                LoadResult.Page(
                    data = data,
                    prevKey = if (offset == 0) null else (offset - params.loadSize).coerceAtLeast(0),
                    nextKey = if (data.size < params.loadSize) null else offset + data.size,
                )
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }
    }

    data class ContactEditRefreshFields(
        val contactId: Int,
        val lookupKey: String,
        val displayName: String,
        val photoUri: String,
        val thumbnailUri: String,
        val photoId: Long,
        val starred: Int,
        val lastUpdatedTimestamp: Long,
        val firstPhoneNormalized: String,
        val firstPhoneRaw: String,
        val firstEmail: String,
    )

    data class ContactDetailLoadResult(
        val summary: ContactSummary,
        val prefix: String,
        val firstName: String,
        val middleName: String,
        val surname: String,
        val suffix: String,
        val nickname: String,
        val notes: String,
        val company: String,
        val jobPosition: String,
        val phones: List<ContactPhoneLoad>,
        val emails: List<ContactEmailLoad>,
    )

    data class ContactPhoneLoad(
        val number: String,
        val type: Int,
        val label: String,
        val normalizedNumber: String,
        val isPrimary: Boolean,
    )

    data class ContactEmailLoad(
        val value: String,
        val type: Int,
        val label: String,
    )

    private data class StructuredNameLoad(
        val prefix: String = "",
        val firstName: String = "",
        val middleName: String = "",
        val surname: String = "",
        val suffix: String = "",
        val nickname: String = "",
        val notes: String = "",
        val company: String = "",
        val jobPosition: String = "",
    )
}
