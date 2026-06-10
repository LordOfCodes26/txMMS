package com.android.mms.helpers

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.Context
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import androidx.annotation.RequiresPermission
import com.android.mms.extensions.subscriptionManagerCompat
import com.android.mms.models.SIMCard

object FeeInfoUtils {
    private const val TAG = "FeeInfoUtils"

    @SuppressLint("MissingPermission")
    fun getCurrentSimSlotId(
        context: Context,
        availableSIMCards: List<SIMCard>,
        currentSIMCardIndex: Int,
    ): Int? {
        val activeSIMs = context.subscriptionManagerCompat().activeSubscriptionInfoList ?: return null
        if (activeSIMs.isEmpty()) return null

        val defaultSubId = SmsManager.getDefaultSmsSubscriptionId()
        val selectedSubscriptionId = availableSIMCards.getOrNull(currentSIMCardIndex)?.subscriptionId
            ?: defaultSubId
        val resolvedSlotId = activeSIMs.firstOrNull { it.subscriptionId == selectedSubscriptionId }?.simSlotIndex
            ?: activeSIMs.firstOrNull()?.simSlotIndex
            ?: currentSIMCardIndex
        return resolvedSlotId
    }

    fun getAvailableSmsCountForSlot(context: Context, slotId: Int): Int? {
        return try {
            val allUri = Uri.parse("content://com.android.dialer.feeinfo/fee_info")
            val cursor = context.contentResolver.query(allUri, null, null, null, null)
            if (cursor == null) {
                return null
            }
            cursor.use {
                val slotIdColumn = cursor.getColumnIndex("slot_id")
                val smsColumn = cursor.getColumnIndex("sms")
                if (slotIdColumn == -1 || smsColumn == -1) {
                    return null
                }

                var rowCount = 0
                while (cursor.moveToNext()) {
                    rowCount++
                    val providerSlotId = cursor.getInt(slotIdColumn)
                    val providerSmsCount = cursor.getInt(smsColumn)

                    if (providerSlotId == slotId) {
                        return providerSmsCount
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAvailableSmsCountForSlot: query failed for slotId=$slotId", e)
            null
        }
    }
    // added by sun

    fun getAvailableFeeCashForSlot(context: Context, slotId: Int): Float? {
        return try {
            val allUri = Uri.parse("content://com.android.dialer.feeinfo/fee_info")
            val cursor = context.contentResolver.query(allUri, null, null, null, null)
            if (cursor == null) {
                Log.d(TAG, "getAvailableSmsCountForSlot: query returned null cursor")
                return null
            }
            cursor.use {
                val slotIdColumn = cursor.getColumnIndex("slot_id")
                val cashColumn = cursor.getColumnIndex("cash")
                if (slotIdColumn == -1 || cashColumn == -1) {
                    Log.d(TAG, "getAvailableSmsCountForSlot: missing columns slot_id/sms")
                    return null
                }

                var rowCount = 0
                while (cursor.moveToNext()) {
                    rowCount++
                    val providerSlotId = cursor.getInt(slotIdColumn)
                    val providerFeeCash = cursor.getFloat(cashColumn)

                    if (providerSlotId == slotId) {
                        return providerFeeCash
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAvailableSmsCountForSlot: query failed for slotId=$slotId", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun getSimSlotIndexForSubscriptionId(context: Context, subscriptionId: Int): Int? {
        val activeSIMS = context.subscriptionManagerCompat().activeSubscriptionInfoList ?: return null
        return activeSIMS.firstOrNull { it.subscriptionId == subscriptionId }?.simSlotIndex
    }
}
