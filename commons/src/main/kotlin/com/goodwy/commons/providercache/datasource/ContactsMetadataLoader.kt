package com.goodwy.commons.providercache.datasource

import android.content.Context
import android.provider.ContactsContract
import com.goodwy.commons.extensions.getIntValueOr
import com.goodwy.commons.extensions.getStringValue
import com.goodwy.commons.extensions.getStringValueOr
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.extensions.normalizePhoneNumber
import com.goodwy.commons.helpers.PERMISSION_READ_CONTACTS
import com.goodwy.commons.providercache.model.ContactSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads per-contact metadata needed for secure-mode, source, and duplicate-merge filters.
 */
class ContactsMetadataLoader(private val context: Context) {

    data class ContactMetadata(
        val contactId: Int,
        val primaryRawId: Int,
        val accountName: String,
        val accountType: String,
        val firstPhoneNormalized: String,
        val firstEmail: String,
    )

    suspend fun loadForSummaries(summaries: List<ContactSummary>): Map<Int, ContactMetadata> =
        withContext(Dispatchers.IO) {
            if (!context.hasPermission(PERMISSION_READ_CONTACTS) || summaries.isEmpty()) {
                return@withContext emptyMap()
            }
            val ids = summaries.map { it.contactId }
            val rawByContact = loadPrimaryRawContacts(ids)
            val phones = loadFirstPhones(ids)
            val emails = loadFirstEmails(ids)
            ids.associateWith { contactId ->
                val raw = rawByContact[contactId]
                ContactMetadata(
                    contactId = contactId,
                    primaryRawId = raw?.rawId ?: contactId,
                    accountName = raw?.accountName.orEmpty(),
                    accountType = raw?.accountType.orEmpty(),
                    firstPhoneNormalized = phones[contactId].orEmpty(),
                    firstEmail = emails[contactId].orEmpty(),
                )
            }
        }

    private data class RawContactInfo(
        val rawId: Int,
        val accountName: String,
        val accountType: String,
    )

    private fun loadPrimaryRawContacts(contactIds: List<Int>): Map<Int, RawContactInfo> {
        val result = HashMap<Int, RawContactInfo>()
        contactIds.chunked(200).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val selection = "${ContactsContract.RawContacts.CONTACT_ID} IN ($placeholders)"
            val args = chunk.map { it.toString() }.toTypedArray()
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.RawContacts._ID,
                    ContactsContract.RawContacts.CONTACT_ID,
                    ContactsContract.RawContacts.ACCOUNT_NAME,
                    ContactsContract.RawContacts.ACCOUNT_TYPE,
                ),
                selection,
                args,
                null,
            )?.use { cursor ->
                val contactIdCol = cursor.getColumnIndex(ContactsContract.RawContacts.CONTACT_ID)
                val rawIdCol = cursor.getColumnIndex(ContactsContract.RawContacts._ID)
                if (contactIdCol < 0 || rawIdCol < 0) return@use
                while (cursor.moveToNext()) {
                    val contactId = cursor.getInt(contactIdCol)
                    if (result.containsKey(contactId)) continue
                    result[contactId] = RawContactInfo(
                        rawId = cursor.getInt(rawIdCol),
                        accountName = cursor.getStringValueOr(ContactsContract.RawContacts.ACCOUNT_NAME, ""),
                        accountType = cursor.getStringValueOr(ContactsContract.RawContacts.ACCOUNT_TYPE, ""),
                    )
                }
            }
        }
        return result
    }

    private fun loadFirstPhones(contactIds: List<Int>): Map<Int, String> {
        val result = HashMap<Int, String>()
        contactIds.chunked(200).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} IN ($placeholders)"
            val args = chunk.map { it.toString() }.toTypedArray()
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                selection,
                args,
                null,
            )?.use { cursor ->
                val contactIdCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                if (contactIdCol < 0) return@use
                while (cursor.moveToNext()) {
                    val contactId = cursor.getInt(contactIdCol)
                    if (result.containsKey(contactId)) continue
                    val normalized = cursor.getStringValue(
                        ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                    )?.takeIf { it.isNotEmpty() }
                        ?: cursor.getStringValue(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            ?.normalizePhoneNumber()
                            .orEmpty()
                    if (normalized.isNotEmpty()) result[contactId] = normalized
                }
            }
        }
        return result
    }

    private fun loadFirstEmails(contactIds: List<Int>): Map<Int, String> {
        val result = HashMap<Int, String>()
        contactIds.chunked(200).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val selection = "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} IN ($placeholders)"
            val args = chunk.map { it.toString() }.toTypedArray()
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                ),
                selection,
                args,
                null,
            )?.use { cursor ->
                val contactIdCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                if (contactIdCol < 0) return@use
                while (cursor.moveToNext()) {
                    val contactId = cursor.getInt(contactIdCol)
                    if (result.containsKey(contactId)) continue
                    val email = cursor.getStringValue(ContactsContract.CommonDataKinds.Email.ADDRESS).orEmpty()
                    if (email.isNotEmpty()) result[contactId] = email
                }
            }
        }
        return result
    }
}
