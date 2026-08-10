package com.goodwy.commons.providercache.datasource

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.goodwy.commons.extensions.getStringValue
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.helpers.CallLogProtectionHelper
import com.goodwy.commons.helpers.ContactProtectionHelper
import com.goodwy.commons.helpers.PERMISSION_READ_CALL_LOG
import com.goodwy.commons.helpers.isQPlus
import com.goodwy.commons.providercache.model.CallLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CallLogProviderDataSource(
    private val context: Context,
) {

    companion object {
        private val PROJECTION_Q = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.CACHED_PHOTO_URI,
            CallLog.Calls.CACHED_NORMALIZED_NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
            CallLog.Calls.PHONE_ACCOUNT_ID,
            CallLog.Calls.NUMBER_PRESENTATION,
            CallLog.Calls.FEATURES,
            CallLog.Calls.BLOCK_REASON,
        )

        private val PROJECTION_LEGACY = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.CACHED_PHOTO_URI,
            CallLog.Calls.CACHED_NORMALIZED_NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
            CallLog.Calls.PHONE_ACCOUNT_ID,
            CallLog.Calls.NUMBER_PRESENTATION,
            CallLog.Calls.FEATURES,
        )

        private const val SORT_ORDER = "${CallLog.Calls.DATE} DESC"

        private const val PAGE_SIZE = 50
    }

    fun pagingSource(): PagingSource<Int, CallLogEntry> = ProviderCallLogPagingSource()

    suspend fun loadPage(offset: Int, limit: Int): List<CallLogEntry> = withContext(Dispatchers.IO) {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) return@withContext emptyList()
        ContactProtectionHelper.ensureUnlockedForThread(context)
        CallLogProtectionHelper.ensureUnlockedForThread(context)
        queryEntries(offset, limit)
    }

    /** Newest call-log row from the provider — used for provisional recents UI. */
    suspend fun loadLatestEntry(): CallLogEntry? = withContext(Dispatchers.IO) {
        loadPage(offset = 0, limit = 1).firstOrNull()
    }

    suspend fun loadNewerThan(timestamp: Long, limit: Int, offset: Int = 0): List<CallLogEntry> = withContext(Dispatchers.IO) {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) return@withContext emptyList()
        ContactProtectionHelper.ensureUnlockedForThread(context)
        CallLogProtectionHelper.ensureUnlockedForThread(context)
        // >= instead of > so that two calls within the same millisecond are both fetched.
        // REPLACE-on-conflict in insertAll makes re-inserting an existing entry a no-op.
        queryEntries(
            offset = offset,
            limit = limit,
            selection = "${CallLog.Calls.DATE} >= ?",
            selectionArgs = arrayOf(timestamp.toString()),
        )
    }

    /** Returns the _ID of the [limit] most-recent calls, newest first. Used to detect deleted entries. */
    /** Fast COUNT of the most-recent [limit] entries in the provider (one IPC round-trip, no rows). */
    suspend fun countRecent(limit: Int): Int = withContext(Dispatchers.IO) {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) return@withContext 0
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI.buildUpon()
                .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, limit.toString())
                .build(),
            arrayOf(CallLog.Calls._ID),
            null, null,
            "${CallLog.Calls.DATE} DESC",
        )
        cursor?.use { it.count } ?: 0
    }

    suspend fun loadRecentIds(limit: Int): List<Int> = withContext(Dispatchers.IO) {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) return@withContext emptyList()
        val ids = ArrayList<Int>(limit)
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID),
            null,
            null,
            "${CallLog.Calls.DATE} DESC LIMIT $limit",
        )
        cursor?.use {
            val col = it.getColumnIndex(CallLog.Calls._ID)
            if (col >= 0) while (it.moveToNext()) ids.add(it.getInt(col))
        }
        ids
    }

    /** Loads specific call-log rows by _ID (for ID-set drift insert path). */
    suspend fun loadByIds(callIds: Collection<Int>): List<CallLogEntry> = withContext(Dispatchers.IO) {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG) || callIds.isEmpty()) {
            return@withContext emptyList()
        }
        ContactProtectionHelper.ensureUnlockedForThread(context)
        CallLogProtectionHelper.ensureUnlockedForThread(context)
        val idList = callIds.distinct()
        val placeholders = idList.joinToString(",") { "?" }
        val selection = "${CallLog.Calls._ID} IN ($placeholders)"
        val selectionArgs = idList.map { it.toString() }.toTypedArray()
        queryEntries(
            offset = 0,
            limit = idList.size,
            selection = selection,
            selectionArgs = selectionArgs,
        )
    }

    private fun queryEntries(
        offset: Int,
        limit: Int,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
    ): List<CallLogEntry> {
        val results = ArrayList<CallLogEntry>(limit)
        val projection = if (isQPlus()) PROJECTION_Q else PROJECTION_LEGACY
        // Note for callers: the call-log provider honours this URI LIMIT param but ignores
        // QUERY_ARG_OFFSET, so a non-zero [offset] does not skip rows — every page comes back as
        // the newest [limit] rows. Read a whole window in one call rather than paging by offset.
        val uri = CallLog.Calls.CONTENT_URI.buildUpon()
            .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, limit.toString())
            .build()

        val cursor = if (isQPlus()) {
            val queryArgs = Bundle().apply {
                if (selection != null) {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                }
                if (selectionArgs != null) {
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                }
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, SORT_ORDER)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            }
            context.contentResolver.query(uri, projection, queryArgs, null)
        } else {
            val sortWithLimit = if (selection == null) {
                "$SORT_ORDER LIMIT $limit OFFSET $offset"
            } else {
                SORT_ORDER
            }
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortWithLimit)
        }

        cursor?.use {
            while (it.moveToNext()) {
                it.toCallLogEntrySafe(isQPlus())?.let { entry -> results.add(entry) }
            }
        }
        return results
    }

    private fun Cursor.toEntry(): CallLogEntry =
        toCallLogEntrySafe(isQPlus()) ?: CallLogEntry(
            callId = 0,
            phoneNumber = "",
            cachedName = "",
            cachedPhotoUri = "",
            startTS = 0L,
            duration = 0,
            type = CallLog.Calls.MISSED_TYPE,
            simID = 0,
        )

    private inner class ProviderCallLogPagingSource : PagingSource<Int, CallLogEntry>() {
        override fun getRefreshKey(state: PagingState<Int, CallLogEntry>): Int? {
            return state.anchorPosition?.let { anchor ->
                state.closestPageToPosition(anchor)?.prevKey?.plus(PAGE_SIZE)
                    ?: state.closestPageToPosition(anchor)?.nextKey?.minus(PAGE_SIZE)
            }
        }

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CallLogEntry> {
            return try {
                val offset = params.key ?: 0
                val data = queryEntries(offset, params.loadSize)
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
}
