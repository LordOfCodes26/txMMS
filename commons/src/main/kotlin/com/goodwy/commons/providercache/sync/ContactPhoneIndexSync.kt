package com.goodwy.commons.providercache.sync

import android.content.Context
import android.provider.ContactsContract
import com.goodwy.commons.extensions.getStringValue
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.extensions.normalizePhoneNumber
import com.goodwy.commons.helpers.PERMISSION_READ_CONTACTS
import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.entities.ContactPhoneIndexEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds a lightweight phone index for name + number / T9 search without loading full contacts.
 */
class ContactPhoneIndexSync(
    private val context: Context,
    private val database: ProviderCacheDatabase,
) {
    private val indexDao get() = database.contactPhoneIndexDao()

    suspend fun rebuildIfNeeded() = withContext(Dispatchers.IO) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) return@withContext
        if (indexDao.getCount() > 0) return@withContext
        rebuildAll()
    }

    suspend fun rebuildAll() = withContext(Dispatchers.IO) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) return@withContext
        indexDao.clearAll()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
        )
        val batch = ArrayList<ContactPhoneIndexEntity>(200)
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            if (idCol < 0) return@use
            while (cursor.moveToNext()) {
                val contactId = cursor.getInt(idCol)
                val number = cursor.getStringValue(ContactsContract.CommonDataKinds.Phone.NUMBER) ?: ""
                if (number.isEmpty()) continue
                val normalized = cursor.getStringValue(
                    ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                )?.takeIf { it.isNotEmpty() } ?: number.normalizePhoneNumber()
                val digits = normalized.filter { it.isDigit() }
                batch.add(
                    ContactPhoneIndexEntity(
                        contactId = contactId,
                        normalizedNumber = normalized,
                        digits = digits,
                        phoneDigits = digits,
                    ),
                )
                if (batch.size >= 200) {
                    indexDao.insertAll(batch)
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) {
            indexDao.insertAll(batch)
        }
    }

    suspend fun rebuildForContactIds(contactIds: List<Int>) = withContext(Dispatchers.IO) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS) || contactIds.isEmpty()) return@withContext
        invalidateForContacts(contactIds)
        val placeholders = contactIds.joinToString(",") { "?" }
        val selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} IN ($placeholders)"
        val args = contactIds.map { it.toString() }.toTypedArray()
        val batch = ArrayList<ContactPhoneIndexEntity>(200)
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                ),
                selection,
                args,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                if (idCol < 0) return@use
                while (cursor.moveToNext()) {
                    val contactId = cursor.getInt(idCol)
                    val number = cursor.getStringValue(ContactsContract.CommonDataKinds.Phone.NUMBER) ?: ""
                    if (number.isEmpty()) continue
                    val normalized = cursor.getStringValue(
                        ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                    )?.takeIf { it.isNotEmpty() } ?: number.normalizePhoneNumber()
                    val digits = normalized.filter { it.isDigit() }
                    batch.add(
                        ContactPhoneIndexEntity(
                            contactId = contactId,
                            normalizedNumber = normalized,
                            digits = digits,
                            phoneDigits = digits,
                        ),
                    )
                    if (batch.size >= 200) {
                        indexDao.insertAll(batch)
                        batch.clear()
                    }
                }
            }
        } catch (_: Exception) {
            return@withContext
        }
        if (batch.isNotEmpty()) indexDao.insertAll(batch)
    }

    suspend fun invalidateForContacts(contactIds: List<Int>) {
        if (contactIds.isEmpty()) return
        contactIds.chunked(200).forEach { chunk ->
            indexDao.deleteForContacts(chunk)
        }
    }
}
