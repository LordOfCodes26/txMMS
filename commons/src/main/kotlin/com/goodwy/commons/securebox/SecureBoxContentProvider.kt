package com.goodwy.commons.securebox

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class SecureBoxContentProvider : ContentProvider() {

    companion object {
        private const val AUTHORITY = "com.android.dialer.securebox"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/secure_box")
        
        private const val SECURE_BOX_CALLS = 1
        private const val SECURE_BOX_CALL_ID = 2
        private const val SECURE_BOX_CONTACTS = 3
        private const val SECURE_BOX_CONTACT_ID = 4
        
        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "secure_box_calls", SECURE_BOX_CALLS)
            addURI(AUTHORITY, "secure_box_calls/#", SECURE_BOX_CALL_ID)
            addURI(AUTHORITY, "secure_box_contacts", SECURE_BOX_CONTACTS)
            addURI(AUTHORITY, "secure_box_contacts/#", SECURE_BOX_CONTACT_ID)
        }
        
        const val COL_CALL_ID = "callId"
        const val COL_ADDED_AT = "addedAt"
        const val COL_CIPHER_NUMBER = "cipherNumber"
        const val COL_CONTACT_ID = "contactId"
    }

    private val database: SecureBoxDatabase?
        get() = context?.let { SecureBoxDatabase.getInstance(it) }

    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        val db = database ?: return null
        
        return when (uriMatcher.match(uri)) {
            SECURE_BOX_CALLS -> {
                val dao = db.SecureBoxCallDao()
                val defaultProjection = arrayOf(COL_CALL_ID, COL_ADDED_AT, COL_CIPHER_NUMBER)
                val columns = projection ?: defaultProjection
                val cursor = MatrixCursor(columns)
                
                val calls = dao.getAllSecureBoxCalls()
                calls.forEach { call ->
                    val row = arrayOfNulls<Any?>(columns.size)
                    columns.forEachIndexed { index, column ->
                        row[index] = when (column) {
                            COL_CALL_ID -> call.callId
                            COL_ADDED_AT -> call.addedAt
                            COL_CIPHER_NUMBER -> call.cipherNumber
                            else -> null
                        }
                    }
                    cursor.addRow(row)
                }
                cursor.setNotificationUri(context?.contentResolver, uri)
                cursor
            }
            SECURE_BOX_CALL_ID -> {
                val dao = db.SecureBoxCallDao()
                val callId = uri.lastPathSegment?.toIntOrNull() ?: return null
                val call = dao.getSecureBoxCall(callId) ?: return null
                
                val defaultProjection = arrayOf(COL_CALL_ID, COL_ADDED_AT, COL_CIPHER_NUMBER)
                val columns = projection ?: defaultProjection
                val cursor = MatrixCursor(columns)
                
                val row = arrayOfNulls<Any?>(columns.size)
                columns.forEachIndexed { index, column ->
                    row[index] = when (column) {
                        COL_CALL_ID -> call.callId
                        COL_ADDED_AT -> call.addedAt
                        COL_CIPHER_NUMBER -> call.cipherNumber
                        else -> null
                    }
                }
                cursor.addRow(row)
                cursor.setNotificationUri(context?.contentResolver, uri)
                cursor
            }
            SECURE_BOX_CONTACTS -> {
                val dao = db.SecureBoxContactDao()
                val defaultProjection = arrayOf(COL_CONTACT_ID, COL_ADDED_AT, COL_CIPHER_NUMBER)
                val columns = projection ?: defaultProjection
                val cursor = MatrixCursor(columns)
                
                val contacts = dao.getAllSecureBoxContacts()
                contacts.forEach { contact ->
                    val row = arrayOfNulls<Any?>(columns.size)
                    columns.forEachIndexed { index, column ->
                        row[index] = when (column) {
                            COL_CONTACT_ID -> contact.contactId
                            COL_ADDED_AT -> contact.addedAt
                            COL_CIPHER_NUMBER -> contact.cipherNumber
                            else -> null
                        }
                    }
                    cursor.addRow(row)
                }
                cursor.setNotificationUri(context?.contentResolver, uri)
                cursor
            }
            SECURE_BOX_CONTACT_ID -> {
                val dao = db.SecureBoxContactDao()
                val contactId = uri.lastPathSegment?.toIntOrNull() ?: return null
                val contact = dao.getSecureBoxContact(contactId) ?: return null
                
                val defaultProjection = arrayOf(COL_CONTACT_ID, COL_ADDED_AT, COL_CIPHER_NUMBER)
                val columns = projection ?: defaultProjection
                val cursor = MatrixCursor(columns)
                
                val row = arrayOfNulls<Any?>(columns.size)
                columns.forEachIndexed { index, column ->
                    row[index] = when (column) {
                        COL_CONTACT_ID -> contact.contactId
                        COL_ADDED_AT -> contact.addedAt
                        COL_CIPHER_NUMBER -> contact.cipherNumber
                        else -> null
                    }
                }
                cursor.addRow(row)
                cursor.setNotificationUri(context?.contentResolver, uri)
                cursor
            }
            else -> null
        }
    }

    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            SECURE_BOX_CALLS -> "vnd.android.cursor.dir/vnd.com.android.dialer.securebox.call"
            SECURE_BOX_CALL_ID -> "vnd.android.cursor.item/vnd.com.android.dialer.securebox.call"
            SECURE_BOX_CONTACTS -> "vnd.android.cursor.dir/vnd.com.android.dialer.securebox.contact"
            SECURE_BOX_CONTACT_ID -> "vnd.android.cursor.item/vnd.com.android.dialer.securebox.contact"
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val db = database ?: return null
        
        return when (uriMatcher.match(uri)) {
            SECURE_BOX_CALLS -> {
                val dao = db.SecureBoxCallDao()
                val callId = values?.getAsInteger(COL_CALL_ID) ?: return null
                val addedAt = values.getAsLong(COL_ADDED_AT) ?: System.currentTimeMillis()
                val cipherNumber = values.getAsInteger(COL_CIPHER_NUMBER) ?: 0

                val secureBoxCall = SecureBoxCall(
                    callId = callId,
                    addedAt = addedAt,
                    cipherNumber = cipherNumber
                )
                dao.insertSecureBoxCall(secureBoxCall)
                context?.contentResolver?.notifyChange(uri, null)
                Uri.withAppendedPath(uri, callId.toString())
            }
            SECURE_BOX_CONTACTS -> {
                val dao = db.SecureBoxContactDao()
                val contactId = values?.getAsInteger(COL_CONTACT_ID) ?: return null
                val addedAt = values.getAsLong(COL_ADDED_AT) ?: System.currentTimeMillis()
                val cipherNumber = values.getAsInteger(COL_CIPHER_NUMBER) ?: 0

                val secureBoxContact = SecureBoxContact(
                    contactId = contactId,
                    addedAt = addedAt,
                    cipherNumber = cipherNumber
                )
                dao.insertSecureBoxContact(secureBoxContact)
                context?.contentResolver?.notifyChange(uri, null)
                Uri.withAppendedPath(uri, contactId.toString())
            }
            else -> null
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        val db = database ?: return 0

        return when (uriMatcher.match(uri)) {
            SECURE_BOX_CALL_ID -> {
                val dao = db.SecureBoxCallDao()
                val callId = uri.lastPathSegment?.toIntOrNull()
                if (callId != null) {
                    dao.deleteSecureBoxCall(callId)
                    context?.contentResolver?.notifyChange(uri, null)
                    1
                } else {
                    0
                }
            }
            SECURE_BOX_CALLS -> {
                val dao = db.SecureBoxCallDao()
                dao.deleteAllSecureBoxCalls()
                context?.contentResolver?.notifyChange(uri, null)
                1
            }
            SECURE_BOX_CONTACT_ID -> {
                val dao = db.SecureBoxContactDao()
                val contactId = uri.lastPathSegment?.toIntOrNull()
                if (contactId != null) {
                    dao.deleteSecureBoxContact(contactId)
                    context?.contentResolver?.notifyChange(uri, null)
                    1
                } else {
                    0
                }
            }
            SECURE_BOX_CONTACTS -> {
                val dao = db.SecureBoxContactDao()
                dao.deleteAllSecureBoxContacts()
                context?.contentResolver?.notifyChange(uri, null)
                1
            }
            else -> 0
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        // Updates are handled via insert (REPLACE strategy)
        return 0
    }
}


