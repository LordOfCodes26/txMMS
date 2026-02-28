package com.android.mms.receivers

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony.Sms
import androidx.annotation.RequiresPermission
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.goodwy.commons.extensions.getMyContactsCursor
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.android.mms.extensions.subscriptionManagerCompat
import com.android.mms.extensions.getMessageRecipientAddress
import com.android.mms.extensions.getNameFromAddress
import com.android.mms.extensions.getThreadId
import com.android.mms.extensions.messagesDB
import com.android.mms.extensions.messagingUtils
import com.android.mms.extensions.notificationHelper
import com.android.mms.helpers.refreshConversations
import com.android.mms.helpers.refreshMessages

/** Handles updating databases and states when a SMS message is sent. */
class SmsStatusSentReceiver : SendStatusReceiver() {
    private companion object {
        const val ACTION_FEE_INFO_SET = "com.chonha.total.action.ACTION_FEE_INFO_SET"
    }

    override fun updateAndroidDatabase(context: Context, intent: Intent, receiverResultCode: Int) {
        val messageUri: Uri? = intent.data
        val resultCode = resultCode
        val messagingUtils = context.messagingUtils

        val type = if (resultCode == Activity.RESULT_OK) {
            Sms.MESSAGE_TYPE_SENT
        } else {
            Sms.MESSAGE_TYPE_FAILED
        }
        messagingUtils.updateSmsMessageSendingStatus(messageUri, type)
        messagingUtils.maybeShowErrorToast(
            resultCode = resultCode,
            errorCode = intent.getIntExtra(EXTRA_ERROR_CODE, NO_ERROR_CODE)
        )

        maybeNotifyFeeUsage(context, intent, receiverResultCode)
    }

    override fun updateAppDatabase(context: Context, intent: Intent, receiverResultCode: Int) {
        val messageUri = intent.data
        if (messageUri != null) {
            val messageId = messageUri.lastPathSegment?.toLong() ?: 0L
            ensureBackgroundThread {
                val type = if (receiverResultCode == Activity.RESULT_OK) {
                    Sms.MESSAGE_TYPE_SENT
                } else {
                    showSendingFailedNotification(context, messageId)
                    Sms.MESSAGE_TYPE_FAILED
                }

                context.messagesDB.updateType(messageId, type)
                refreshMessages()
                refreshConversations()
            }
        }
    }

    private fun showSendingFailedNotification(context: Context, messageId: Long) {
        Handler(Looper.getMainLooper()).post {
            if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                return@post
            }
            val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
            ensureBackgroundThread {
                val address = context.getMessageRecipientAddress(messageId)
                val threadId = context.getThreadId(address)
                val recipientName = context.getNameFromAddress(address, privateCursor)
                context.notificationHelper.showSendingFailedNotification(recipientName, threadId)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun maybeNotifyFeeUsage(context: Context, intent: Intent, receiverResultCode: Int) {
        if (receiverResultCode != Activity.RESULT_OK) return

        val partsCount = intent.getIntExtra(EXTRA_PARTS_COUNT, 1).coerceAtLeast(1)
        val partId = intent.getIntExtra(EXTRA_PART_ID, if (partsCount == 1) 0 else 1)
        val isLastPart = if (partsCount == 1) partId == 0 else partId == partsCount
        if (!isLastPart) return

        val subId = intent.getIntExtra(EXTRA_SUB_ID, -1)
        val slotId = resolveSlotId(context, subId)
        val usageDelta = -partsCount

        context.sendBroadcast(
            Intent(ACTION_FEE_INFO_SET).apply {
                putExtra("slotId", slotId)
                putExtra("cnt", usageDelta)
            }
        )
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    private fun resolveSlotId(context: Context, subId: Int): Int {
        return try {
            val slotIndex = context.subscriptionManagerCompat()
                .activeSubscriptionInfoList
                ?.firstOrNull { it.subscriptionId == subId }
                ?.simSlotIndex
            if (slotIndex == 0 || slotIndex == 1) slotIndex else 0
        } catch (_: Exception) {
            0
        }
    }
}
