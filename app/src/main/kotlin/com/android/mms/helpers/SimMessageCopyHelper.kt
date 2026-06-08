package com.android.mms.helpers

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.annotation.RequiresApi
import com.android.mms.messaging.getSmsManager
import com.android.mms.models.Message

object SimMessageCopyHelper {
    private val ICC_URI = Uri.parse("content://sms/icc")
    private const val STATUS_ON_ICC_READ = 1
    private const val STATUS_ON_ICC_SENT = 5
    data class SimStorageInfo(
        val usedCount: Int,
        val totalCapacity: Int
    ) {
        val isFull: Boolean
            get() = totalCapacity > 0 && usedCount >= totalCapacity
    }
    fun resolveSubscriptionId(message: Message): Int {
        if (message.subscriptionId >= 0) return message.subscriptionId
        val defaultSubId = SmsManager.getDefaultSmsSubscriptionId()
        return defaultSubId.takeIf { it >= 0 } ?: -1
    }

    fun resolveCopyAddress(message: Message): String? {
        if (message.isReceivedMessage()) {
            return message.senderPhoneNumber.trim().takeIf { it.isNotEmpty() }
        }
        return message.participants
            .flatMap { contact ->  contact.phoneNumbers.map { it.normalizedNumber.ifBlank { it.value } } }
            .firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    fun getStorageInfo(context: Context, subscriptionId: Int): SimStorageInfo? {
        val totalCapacity = try {
            getSmsManager(subscriptionId).smsCapacityOnIcc
        } catch (_: Exception) {
            -1
        }
        if (totalCapacity <= 0 ) return null
        val usedCount = querySimMessages(context, subscriptionId).size
        return SimStorageInfo(usedCount = usedCount, totalCapacity = totalCapacity)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun copyMessageToSim(
        context: Context,
        message: Message,
        address: String,
        subscriptionId: Int,
        storageInfo: SimStorageInfo?,
        overrideIfFull: Boolean
    ): Boolean {

        if (storageInfo?.isFull == true) {
            if (!overrideIfFull) return false
            if (!deleteOldestSimMessage(context, subscriptionId)) return false
        }
        return insertMessageOnSim(context, subscriptionId, message, address)
    }

    private fun buildSimUri(subscriptionId: Int): Uri = ICC_URI.buildUpon()
        .appendQueryParameter("subscription", subscriptionId.toString())
        .build()
    private fun querySimMessages(context: Context, subscriptionId: Int): List<SimIccRow> {
        val result = mutableListOf<SimIccRow>()
        return try {
            context.contentResolver.query(buildSimUri(subscriptionId), null, null, null, null)?.use {
                cursor ->
                val dateCol = cursor.getColumnIndex("date").takeIf { it >= 0 } ?: return@use
                val indexCol = cursor.getColumnIndex("index_on_icc").takeIf { it >= 0 }
                while (cursor.moveToNext()) {
                    val date = cursor.getLong(dateCol)
                    val indexOnIcc = if (indexCol != null) {
                        cursor.getString(indexCol).orEmpty()
                    } else {
                        cursor.getLong(cursor.getColumnIndexOrThrow("_id")).toString()
                    }
                    result.add(SimIccRow(date = date, indexOnIcc = indexOnIcc))
                }
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun deleteOldestSimMessage(context: Context, subscriptionId: Int): Boolean {
        val oldest = querySimMessages(context, subscriptionId).minByOrNull { it.date } ?: return false
        val indexParts = oldest.indexOnIcc.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        val selectionArs = indexParts.ifEmpty { listOf(oldest.indexOnIcc) }.toTypedArray()
        return try {
            context.contentResolver.delete(buildSimUri(subscriptionId), "ForMultiDelete", selectionArs) > 0
        } catch (_: Exception) {
            false
        }
    }
    private fun insertMessageOnSim(
        context: Context,
        subscriptionId: Int,
        message: Message,
        address: String
    ): Boolean {
        return try {
            val iccUri = Uri.parse("content://sms/icc_subId/$subscriptionId")
            val values = ContentValues().apply {
                put("address", address)
                put("body", message.body)
                put(
                    "type", if (message.isReceivedMessage()) Telephony.Sms.MESSAGE_TYPE_INBOX
                    else Telephony.Sms.MESSAGE_TYPE_SENT
                )
                put("service_center", "")
                put("status",
                    if (message.isReceivedMessage()) STATUS_ON_ICC_READ else STATUS_ON_ICC_SENT)
                put("date", message.date)
            }
            context.contentResolver.insert(iccUri, values) != null
        } catch (_: Exception) {
            false
        }
    }

    private data class SimIccRow(
        val date: Long,
        val indexOnIcc: String
    )
}
