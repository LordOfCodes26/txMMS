package com.android.mms.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.goodwy.commons.extensions.notificationManager
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.android.mms.extensions.conversationsDB
import com.android.mms.extensions.deleteMessage
import com.android.mms.extensions.markThreadMessagesRead
import com.android.mms.extensions.updateLastConversationMessage
import com.android.mms.helpers.IS_MMS
import com.android.mms.helpers.MESSAGE_ID
import com.android.mms.helpers.THREAD_ID
import com.android.mms.helpers.refreshConversations
import com.android.mms.helpers.refreshMessages

class DeleteSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(THREAD_ID, 0L)
        val messageId = intent.getLongExtra(MESSAGE_ID, 0L)
        val isMms = intent.getBooleanExtra(IS_MMS, false)
        context.notificationManager.cancel(threadId.hashCode())
        ensureBackgroundThread {
            context.markThreadMessagesRead(threadId)
            context.conversationsDB.markRead(threadId)
            context.deleteMessage(messageId, isMms)
            context.updateLastConversationMessage(threadId)
            refreshMessages()
            refreshConversations()
        }
    }
}
