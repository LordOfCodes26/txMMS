package com.goodwy.commons.providercache.sync

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.provider.ContactsContract
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.extensions.showErrorToast
import com.goodwy.commons.helpers.ContactsHelper
import com.goodwy.commons.helpers.PERMISSION_READ_CONTACTS
import com.goodwy.commons.helpers.PERMISSION_WRITE_CONTACTS
import com.goodwy.commons.helpers.isSimAccountTypeForPersistence
import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.datasource.ContactsProviderDataSource
import com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deletes every raw contact row from [ContactsContract] in provider batches, then clears Room caches.
 * Used for Select-all → Delete on the main Contacts tab (does not rely on adapter/paged list data).
 */
class ContactsBulkDeleteManager(
    private val context: Context,
    private val database: ProviderCacheDatabase,
    private val providerDataSource: ContactsProviderDataSource,
    private val contactsHelper: ContactsHelper = ContactsHelper(context),
) {
    private val contactDao get() = database.contactDao()
    private val displayCacheDao get() = database.contactDisplayCacheDao()

    /**
     * @param onRoomCleared invoked on IO after provider deletes succeed and Room tables are cleared.
     * @param onUiUpdate invoked on the main thread after [onRoomCleared] to paint an empty list.
     */
    suspend fun deleteAllContacts(
        onRoomCleared: suspend () -> Unit,
        onUiUpdate: suspend () -> Unit,
    ): BulkDeleteOutcome = deleteAllRawContacts(onRoomCleared, onUiUpdate)

    suspend fun deleteAllRawContacts(
        onRoomCleared: suspend () -> Unit,
        onUiUpdate: suspend () -> Unit,
    ): BulkDeleteOutcome = withContext(Dispatchers.IO) {
        if (!context.hasPermission(PERMISSION_WRITE_CONTACTS)) {
            ProviderCacheDebugLogger.logBulkDeletePermissionDenied()
            return@withContext logBulkDeleteVerification(success = false)
        }

        val rawIds = queryAllRawContactIds()
        ProviderCacheDebugLogger.logBulkDeleteStart(rawIds.size)

        var success = false
        try {
            if (rawIds.isEmpty()) {
                onRoomCleared()
                withContext(Dispatchers.Main) { onUiUpdate() }
                success = true
            } else {
                success = deleteProviderRawContacts(rawIds)
                if (success) {
                    success = verifyProviderEmpty()
                }
                if (success) {
                    onRoomCleared()
                    withContext(Dispatchers.Main) { onUiUpdate() }
                }
            }
        } catch (_: Exception) {
            success = false
        }
        logBulkDeleteVerification(success = success)
    }

    /**
     * Provider-only delete after Room/UI were cleared optimistically on the main thread.
     */
    suspend fun deleteAllRawContactsProviderOnly(): BulkDeleteOutcome = withContext(Dispatchers.IO) {
        if (!context.hasPermission(PERMISSION_WRITE_CONTACTS)) {
            ProviderCacheDebugLogger.logBulkDeletePermissionDenied()
            return@withContext logBulkDeleteVerification(success = false)
        }

        val rawIds = queryAllRawContactIds()
        ProviderCacheDebugLogger.logBulkDeleteStart(rawIds.size)

        var success = false
        try {
            if (rawIds.isEmpty()) {
                success = true
            } else {
                val simCount = countSimRawContacts(rawIds)
                if (simCount > 0) {
                    ProviderCacheDebugLogger.log(
                        "bulkDelete simRawContacts=$simCount (SIM ADN purge + raw delete)",
                    )
                }
                success = deleteProviderRawContacts(rawIds)
            }
            if (success) {
                success = verifyProviderEmpty()
            }
        } catch (_: Exception) {
            success = false
        }
        logBulkDeleteVerification(success = success)
    }

    private suspend fun deleteProviderRawContacts(rawIds: List<Int>): Boolean {
        contactsHelper.purgeSimAdnBeforeRawContactDelete(rawIds.map { it.toLong() })

        return try {
            rawIds.chunked(BATCH_OPS).forEach { chunk ->
                val operations = ArrayList<ContentProviderOperation>(chunk.size)
                for (rawId in chunk) {
                    operations.add(
                        ContentProviderOperation.newDelete(
                            ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId.toLong()),
                        ).build(),
                    )
                }
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
                ProviderCacheDebugLogger.logBulkDeleteBatch(chunk.size)
            }
            true
        } catch (e: Exception) {
            ProviderCacheDebugLogger.logBulkDeleteError(e.message ?: e.javaClass.simpleName)
            context.showErrorToast(e)
            false
        }
    }

    private suspend fun verifyProviderEmpty(): Boolean {
        return queryAllRawContactIds().isEmpty() && providerDataSource.loadAllSummaryIds().isEmpty()
    }

    private fun queryAllRawContactIds(): List<Int> {
        if (!context.hasPermission(PERMISSION_WRITE_CONTACTS)) return emptyList()
        val ids = ArrayList<Int>()
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.DELETED} = 0",
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(ContactsContract.RawContacts._ID)
            if (idCol < 0) return@use
            while (cursor.moveToNext()) {
                ids.add(cursor.getInt(idCol))
            }
        }
        return ids
    }

    private fun countSimRawContacts(rawIds: List<Int>): Int {
        if (rawIds.isEmpty()) return 0
        val idSet = rawIds.toHashSet()
        var count = 0
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
            ),
            "${ContactsContract.RawContacts.DELETED} = 0",
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(ContactsContract.RawContacts._ID)
            val typeCol = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)
            if (idCol < 0 || typeCol < 0) return@use
            while (cursor.moveToNext()) {
                val rawId = cursor.getInt(idCol)
                if (rawId !in idSet) continue
                val accountType = cursor.getString(typeCol).orEmpty()
                if (isSimAccountTypeForPersistence(accountType)) count++
            }
        }
        return count
    }

    private fun queryRemainingRawContactSamples(limit: Int): List<BulkDeleteRemainingRow> {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) return emptyList()
        val rows = ArrayList<BulkDeleteRemainingRow>(limit)
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.CONTACT_ID,
                ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.SOURCE_ID,
                ContactsContract.RawContacts.DELETED,
            ),
            null,
            null,
            "${ContactsContract.RawContacts._ID} ASC",
        )?.use { cursor ->
            val rawIdCol = cursor.getColumnIndex(ContactsContract.RawContacts._ID)
            val contactIdCol = cursor.getColumnIndex(ContactsContract.RawContacts.CONTACT_ID)
            val accountNameCol = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME)
            val accountTypeCol = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)
            val sourceIdCol = cursor.getColumnIndex(ContactsContract.RawContacts.SOURCE_ID)
            val deletedCol = cursor.getColumnIndex(ContactsContract.RawContacts.DELETED)
            if (rawIdCol < 0) return@use
            while (cursor.moveToNext() && rows.size < limit) {
                rows += BulkDeleteRemainingRow(
                    contactId = if (contactIdCol >= 0) cursor.getInt(contactIdCol) else 0,
                    rawContactId = cursor.getInt(rawIdCol),
                    accountName = if (accountNameCol >= 0) cursor.getString(accountNameCol).orEmpty() else "",
                    accountType = if (accountTypeCol >= 0) cursor.getString(accountTypeCol).orEmpty() else "",
                    sourceId = if (sourceIdCol >= 0) cursor.getString(sourceIdCol).orEmpty() else "",
                    deleted = if (deletedCol >= 0) cursor.getInt(deletedCol) else 0,
                )
            }
        }
        return rows
    }

    suspend fun logVerificationAfterFailure(): BulkDeleteOutcome =
        logBulkDeleteVerification(success = false)

    private suspend fun logBulkDeleteVerification(success: Boolean): BulkDeleteOutcome {
        val providerRawContacts = queryAllRawContactIds().size
        val providerContacts = providerDataSource.loadAllSummaryIds().size
        val roomContacts = contactDao.getSummaryCount()
        val displayRows = displayCacheDao.getCount()
        ProviderCacheDebugLogger.logBulkDeleteEnd(
            providerRawContacts = providerRawContacts,
            providerContacts = providerContacts,
            roomContacts = roomContacts,
            displayRows = displayRows,
        )
        val remainingSamples = if (providerRawContacts > 0 || providerContacts > 0) {
            queryRemainingRawContactSamples(REMAINING_SAMPLE_LIMIT).also { samples ->
                ProviderCacheDebugLogger.logBulkDeleteRemainingRows(samples)
            }
        } else {
            emptyList()
        }
        return BulkDeleteOutcome(
            success = success,
            providerRawContacts = providerRawContacts,
            providerContacts = providerContacts,
            roomContacts = roomContacts,
            displayRows = displayRows,
            remainingSamples = remainingSamples,
        )
    }

    companion object {
        private const val BATCH_OPS = 400
        private const val REMAINING_SAMPLE_LIMIT = 10
    }
}
