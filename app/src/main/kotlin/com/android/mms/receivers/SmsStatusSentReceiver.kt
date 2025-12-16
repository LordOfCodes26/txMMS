package com.android.mms.receivers

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony.Sms
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.goodwy.commons.extensions.getMyContactsCursor
import com.goodwy.commons.helpers.ensureBackgroundThread
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
}
