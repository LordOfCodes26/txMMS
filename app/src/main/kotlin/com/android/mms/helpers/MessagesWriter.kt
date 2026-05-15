package com.android.mms.helpers

import android.annotation.SuppressLint
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.telephony.SubscriptionManager
import android.util.Base64
import com.google.android.mms.pdu_alt.PduHeaders
import com.klinker.android.send_message.Utils
import com.goodwy.commons.extensions.queryCursor
import com.goodwy.commons.helpers.isRPlus
import com.android.mms.extensions.messagesDB
import com.android.mms.extensions.updateLastConversationMessage
import com.android.mms.models.Message
import com.android.mms.models.MmsAddress
import com.android.mms.models.MmsBackup
import com.android.mms.models.MmsPart
import com.android.mms.models.SmsBackup
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MessagesWriter(private val context: Context) {
    private val INVALID_ID = -1L
    private val contentResolver = context.contentResolver
    private val modifiedThreadIds = mutableSetOf<Long>()

    /**
     * Thread-safe cache: address → threadId.
     * Populated from existing SMS rows (zero IPC) and by preResolveThreadIds (parallel IPC).
     * getOrCreateCachedThreadId only pays the IPC cost for truly unknown addresses.
     */
    private val threadIdCache = ConcurrentHashMap<String, Long>()

    private data class SmsKey(val date: Long, val address: String?, val type: Int)
    private data class MmsKey(val date: Long, val dateSent: Long, val threadId: Long, val messageBox: Int)

    /**
     * Pre-loaded dedup sets — one bulk DB query at first use, then O(1) per-message lookups.
     * loadExistingSmsKeys also fills threadIdCache from existing THREAD_ID values so that
     * addresses already in the DB never need a getOrCreateThreadId IPC call.
     */
    private val existingSmsKeys: HashSet<SmsKey> by lazy { loadExistingSmsKeys() }
    private val existingMmsKeys: HashSet<MmsKey> by lazy { loadExistingMmsKeys() }

    /** SMS ContentValues accumulated for a single applyBatch flush (one IPC call). */
    private val pendingSmsValues = ArrayList<ContentValues>()

    // ─── Thread ID helpers ──────────────────────────────────────────────────

    private fun getOrCreateCachedThreadId(address: String): Long {
        // Fast path: already in cache (populated from existing SMS or by preResolveThreadIds)
        threadIdCache[address]?.let { return it }
        // Slow path: IPC call — only happens for addresses not seen before
        val threadId = Utils.getOrCreateThreadId(context, address)
        threadIdCache.putIfAbsent(address, threadId)
        return threadIdCache[address]!!
    }

    /**
     * Pre-resolves thread IDs for [addresses] in parallel before the main import loop.
     * Call this once with all unique addresses from the backup so that
     * getOrCreateCachedThreadId never blocks during the sequential write loop.
     *
     * Also triggers the lazy load of existingSmsKeys, which populates the cache from
     * existing SMS rows at zero IPC cost.
     */
    fun preResolveThreadIds(addresses: Set<String>) {
        // Trigger the lazy load — fills threadIdCache from existing SMS data.
        @Suppress("UNUSED_EXPRESSION")
        existingSmsKeys

        val unresolved = addresses.filter { !threadIdCache.containsKey(it) }
        if (unresolved.isEmpty()) return

        val parallelism = minOf(unresolved.size, MAX_THREAD_RESOLVE_PARALLELISM)
        val executor = Executors.newFixedThreadPool(parallelism)
        try {
            val futures = unresolved.map { address ->
                executor.submit<Unit> {
                    try {
                        val id = Utils.getOrCreateThreadId(context, address)
                        threadIdCache.putIfAbsent(address, id)
                    } catch (_: Exception) {}
                }
            }
            futures.forEach { it.get() }
        } finally {
            executor.shutdown()
        }
    }

    // ─── Bulk pre-loaders ───────────────────────────────────────────────────

    private fun loadExistingSmsKeys(): HashSet<SmsKey> {
        // Load THREAD_ID alongside the dedup columns so we can prime the thread ID cache
        // for all addresses already in the database — zero extra IPC cost.
        val projection = arrayOf(Sms.DATE, Sms.ADDRESS, Sms.TYPE, Sms.THREAD_ID)
        return try {
            contentResolver.query(Sms.CONTENT_URI, projection, null, null, null)?.use { cursor ->
                val keys = HashSet<SmsKey>(cursor.count * 2)
                while (cursor.moveToNext()) {
                    val addr = cursor.getString(1)
                    val threadId = cursor.getLong(3)
                    keys.add(SmsKey(cursor.getLong(0), addr, cursor.getInt(2)))
                    if (addr != null && threadId > 0) {
                        threadIdCache.putIfAbsent(addr, threadId)
                    }
                }
                keys
            } ?: HashSet()
        } catch (_: Exception) { HashSet() }
    }

    private fun loadExistingMmsKeys(): HashSet<MmsKey> {
        val projection = arrayOf(Mms.DATE, Mms.DATE_SENT, Mms.THREAD_ID, Mms.MESSAGE_BOX)
        return try {
            contentResolver.query(Mms.CONTENT_URI, projection, null, null, null)?.use { cursor ->
                val keys = HashSet<MmsKey>(cursor.count * 2)
                while (cursor.moveToNext()) {
                    keys.add(MmsKey(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2), cursor.getInt(3)))
                }
                keys
            } ?: HashSet()
        } catch (_: Exception) { HashSet() }
    }

    // ─── Writers ────────────────────────────────────────────────────────────

    fun writeSmsMessage(smsBackup: SmsBackup) {
        val threadId = getOrCreateCachedThreadId(smsBackup.address)
        val key = SmsKey(smsBackup.date, smsBackup.address, smsBackup.type)
        if (key !in existingSmsKeys) {
            existingSmsKeys.add(key)
            modifiedThreadIds.add(threadId)
            val cv = smsBackup.toContentValues()
            cv.put(Sms.THREAD_ID, threadId)
            pendingSmsValues.add(cv)
            if (pendingSmsValues.size >= BATCH_SIZE) {
                flushSmsBatch()
            }
        }
    }

    fun writeMmsMessage(mmsBackup: MmsBackup) {
        val threadId = getMmsThreadId(mmsBackup)
        if (threadId == INVALID_ID) return

        val key = MmsKey(mmsBackup.date, mmsBackup.dateSent, threadId, mmsBackup.messageBox)
        if (key in existingMmsKeys) return

        existingMmsKeys.add(key)
        modifiedThreadIds.add(threadId)
        val cv = mmsBackup.toContentValues()
        cv.put(Mms.THREAD_ID, threadId)

        val insertedUri = contentResolver.insert(Mms.CONTENT_URI, cv)
        val messageId = insertedUri?.lastPathSegment?.toLongOrNull() ?: INVALID_ID
        if (messageId != INVALID_ID) {
            mmsBackup.parts.forEach { writeMmsPart(it, messageId) }
            mmsBackup.addresses.forEach { writeMmsAddress(it, messageId) }
        }
    }

    /**
     * Flushes all accumulated SMS inserts as one applyBatch() call.
     * Must be called after all writeSmsMessage() invocations.
     */
    fun flushPendingInserts() {
        flushSmsBatch()
    }

    private fun flushSmsBatch() {
        if (pendingSmsValues.isEmpty()) return
        val valuesToInsert = ArrayList(pendingSmsValues)
        pendingSmsValues.clear()

        val ops = valuesToInsert.mapTo(ArrayList(valuesToInsert.size)) { cv ->
            ContentProviderOperation.newInsert(Sms.CONTENT_URI).withValues(cv).build()
        }
        try {
            contentResolver.applyBatch(Sms.CONTENT_URI.authority!!, ops)
        } catch (_: Exception) {
            // Fallback: insert individually so a provider error doesn't lose the whole batch.
            for (cv in valuesToInsert) {
                try { contentResolver.insert(Sms.CONTENT_URI, cv) } catch (_: Exception) {}
            }
        }
    }

    private fun getMmsThreadId(mmsBackup: MmsBackup): Long {
        val address = when (mmsBackup.messageBox) {
            Mms.MESSAGE_BOX_INBOX -> mmsBackup.addresses.firstOrNull { it.type == PduHeaders.FROM }?.address
            else -> mmsBackup.addresses.firstOrNull { it.type == PduHeaders.TO }?.address
        }
        return if (!address.isNullOrEmpty()) {
            getOrCreateCachedThreadId(address)
        } else {
            INVALID_ID
        }
    }

    @SuppressLint("NewApi")
    private fun mmsAddressExist(mmsAddress: MmsAddress, messageId: Long): Boolean {
        val addressUri = if (isRPlus()) {
            Mms.Addr.getAddrUriForMessage(messageId.toString())
        } else {
            Uri.parse("content://mms/$messageId/addr")
        }
        val projection = arrayOf(Mms.Addr._ID)
        val selection = "${Mms.Addr.TYPE} = ? AND ${Mms.Addr.ADDRESS} = ? AND ${Mms.Addr.MSG_ID} = ?"
        val selectionArgs = arrayOf(mmsAddress.type.toString(), mmsAddress.address, messageId.toString())
        var exists = false
        context.queryCursor(addressUri, projection, selection, selectionArgs) { exists = it.count > 0 }
        return exists
    }

    @SuppressLint("NewApi")
    private fun writeMmsAddress(mmsAddress: MmsAddress, messageId: Long) {
        if (!mmsAddressExist(mmsAddress, messageId)) {
            val addressUri = if (isRPlus()) {
                Mms.Addr.getAddrUriForMessage(messageId.toString())
            } else {
                Uri.parse("content://mms/$messageId/addr")
            }
            val cv = mmsAddress.toContentValues()
            cv.put(Mms.Addr.MSG_ID, messageId)
            contentResolver.insert(addressUri, cv)
        }
    }

    @SuppressLint("NewApi")
    private fun writeMmsPart(mmsPart: MmsPart, messageId: Long) {
        if (!mmsPartExist(mmsPart, messageId)) {
            val uri = Uri.parse("content://mms/${messageId}/part")
            val cv = mmsPart.toContentValues()
            cv.put(Mms.Part.MSG_ID, messageId)
            val partUri = contentResolver.insert(uri, cv)
            try {
                if (partUri != null && mmsPart.isNonText()) {
                    contentResolver.openOutputStream(partUri).use {
                        it!!.write(Base64.decode(mmsPart.data, Base64.DEFAULT))
                    }
                }
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("NewApi")
    private fun mmsPartExist(mmsPart: MmsPart, messageId: Long): Boolean {
        val uri = Uri.parse("content://mms/${messageId}/part")
        val projection = arrayOf(Mms.Part._ID)
        val selection = "${Mms.Part.CONTENT_LOCATION} = ? AND ${Mms.Part.CONTENT_TYPE} = ? AND ${Mms.Part.MSG_ID} = ? AND ${Mms.Part.CONTENT_ID} = ?"
        val selectionArgs = arrayOf(
            mmsPart.contentLocation.toString(), mmsPart.contentType,
            messageId.toString(), mmsPart.contentId.toString()
        )
        var exists = false
        context.queryCursor(uri, projection, selection, selectionArgs) { exists = it.count > 0 }
        return exists
    }

    /**
     * For every thread modified by this import session, queries the Telephony provider for the
     * most recent SMS and writes a minimal [Message] row into the local Room messages table.
     *
     * The Room [ConversationsDao.getNonArchivedWithLatestSnippet] query reads `last_message_type`
     * exclusively from the Room messages table. Imported messages only land in the Telephony
     * provider, so without this sync the status icon (sent/received/failed) is always missing
     * from the conversation list after import.
     *
     * Uses insertOrIgnore so existing entries are never overwritten.
     */
    fun syncLastMessagesToRoom() {
        val db = context.messagesDB
        val projection = arrayOf(
            Sms._ID, Sms.BODY, Sms.TYPE, Sms.STATUS,
            Sms.ADDRESS, Sms.DATE, Sms.READ, Sms.SUBSCRIPTION_ID
        )
        for (threadId in modifiedThreadIds) {
            try {
                contentResolver.query(
                    Sms.CONTENT_URI, projection,
                    "${Sms.THREAD_ID} = ?", arrayOf(threadId.toString()),
                    "${Sms.DATE} DESC LIMIT 1"
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use
                    val subIdCol = cursor.getColumnIndex(Sms.SUBSCRIPTION_ID)
                    db.insertOrIgnore(
                        Message(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow(Sms._ID)),
                            body = cursor.getString(cursor.getColumnIndexOrThrow(Sms.BODY)) ?: "",
                            type = cursor.getInt(cursor.getColumnIndexOrThrow(Sms.TYPE)),
                            status = cursor.getInt(cursor.getColumnIndexOrThrow(Sms.STATUS)),
                            participants = ArrayList(),
                            date = (cursor.getLong(cursor.getColumnIndexOrThrow(Sms.DATE)) / 1000).toInt(),
                            read = cursor.getInt(cursor.getColumnIndexOrThrow(Sms.READ)) == 1,
                            threadId = threadId,
                            isMMS = false,
                            attachment = null,
                            senderPhoneNumber = cursor.getString(cursor.getColumnIndexOrThrow(Sms.ADDRESS)) ?: "",
                            senderName = "",
                            senderPhotoUri = "",
                            subscriptionId = if (subIdCol >= 0) cursor.getInt(subIdCol)
                                             else SubscriptionManager.INVALID_SUBSCRIPTION_ID
                        )
                    )
                }
            } catch (_: Exception) {}
        }
    }

    /** Fixes the timestamps of all conversations modified by previous writes. */
    fun fixConversationDates() {
        // Android's Telephony provider sets conversation date to *now* on insert, not the
        // message's own timestamp. This corrects that after all messages are written.
        // https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/android14-release/src/com/android/providers/telephony/MmsSmsDatabaseHelper.java#134
        context.updateLastConversationMessage(modifiedThreadIds)
    }

    companion object {
        private const val BATCH_SIZE = 100
        private const val MAX_THREAD_RESOLVE_PARALLELISM = 4
    }
}
