package com.android.mms.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.goodwy.commons.extensions.showErrorToast
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.android.mms.extensions.conversationsDB
import com.android.mms.extensions.deleteScheduledMessage
import com.android.mms.extensions.getAddresses
import com.android.mms.extensions.messagesDB
import com.android.mms.helpers.SCHEDULED_MESSAGE_ID
import com.android.mms.helpers.THREAD_ID
import com.android.mms.helpers.refreshConversations
import com.android.mms.helpers.refreshMessages
import com.android.mms.messaging.sendMessageCompat

class ScheduledMessageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakelock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "goodwy.messages:scheduled.message.receiver")
        wakelock.acquire(3000)


        ensureBackgroundThread {
            handleIntent(context, intent)
        }
    }

    private fun handleIntent(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(THREAD_ID, 0L)
        val messageId = intent.getLongExtra(SCHEDULED_MESSAGE_ID, 0L)
        val message = try {
            context.messagesDB.getScheduledMessageWithId(threadId, messageId)
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        val addresses = message.participants.getAddresses()
        val attachments = message.attachment?.attachments ?: emptyList()

        try {
            Handler(Looper.getMainLooper()).post {
                context.sendMessageCompat(message.body, addresses, message.subscriptionId, attachments)
            }

            // delete temporary conversation and message as it's already persisted to the telephony db now
            context.deleteScheduledMessage(messageId)
            context.conversationsDB.deleteThreadId(messageId)
            refreshMessages()
            refreshConversations()
        } catch (e: Exception) {
            context.showErrorToast(e)
        } catch (e: Error) {
            context.showErrorToast(e.localizedMessage ?: context.getString(com.goodwy.commons.R.string.unknown_error_occurred))
        }
    }
}
