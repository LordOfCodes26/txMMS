package com.android.mms.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.telephony.SmsManager
import com.android.mms.extensions.subscriptionManagerCompat
import com.android.mms.models.SIMCard

object FeeInfoUtils {
    @SuppressLint("MissingPermission")
    fun getCurrentSimSlotId(
        context: Context,
        availableSIMCards: List<SIMCard>,
        currentSIMCardIndex: Int,
    ): Int? {
        val activeSIMs = context.subscriptionManagerCompat().activeSubscriptionInfoList ?: return null
        if (activeSIMs.isEmpty()) return null

        val selectedSubscriptionId = availableSIMCards.getOrNull(currentSIMCardIndex)?.subscriptionId
            ?: SmsManager.getDefaultSmsSubscriptionId()

        return activeSIMs.firstOrNull { it.subscriptionId == selectedSubscriptionId }?.simSlotIndex
            ?: activeSIMs.firstOrNull()?.simSlotIndex
            ?: currentSIMCardIndex
    }

    fun getAvailableSmsCountForSlot(context: Context, slotId: Int): Int? {
        return try {
            val allUri = Uri.parse("content://com.android.dialer.feeinfo/fee_info")
            context.contentResolver.query(allUri, null, null, null, null)?.use { cursor ->
                val slotIdColumn = cursor.getColumnIndex("slot_id")
                val smsColumn = cursor.getColumnIndex("sms")
                if (slotIdColumn == -1 || smsColumn == -1) {
                    return null
                }

                while (cursor.moveToNext()) {
                    if (cursor.getInt(slotIdColumn) == slotId) {
                        return cursor.getInt(smsColumn)
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
