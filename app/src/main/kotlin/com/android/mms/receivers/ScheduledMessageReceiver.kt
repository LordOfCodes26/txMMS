package com.android.mms.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.android.mms.extensions.deliverScheduledMessage
import com.android.mms.extensions.messagesDB
import com.android.mms.helpers.SCHEDULED_MESSAGE_ID
import com.android.mms.helpers.THREAD_ID

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

        context.deliverScheduledMessage(message)
    }
}
