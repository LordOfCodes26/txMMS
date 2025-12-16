package com.android.mms.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.goodwy.commons.extensions.copyToClipboard
import com.goodwy.commons.extensions.notificationManager
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.android.mms.extensions.conversationsDB
import com.android.mms.extensions.deleteMessage
import com.android.mms.extensions.markThreadMessagesRead
import com.android.mms.extensions.updateLastConversationMessage
import com.android.mms.helpers.COPY_NUMBER
import com.android.mms.helpers.COPY_NUMBER_AND_DELETE
import com.android.mms.helpers.MESSAGE_ID
import com.android.mms.helpers.THREAD_ID
import com.android.mms.helpers.THREAD_TEXT
import com.android.mms.helpers.refreshConversations
import com.android.mms.helpers.refreshMessages

class CopyNumberReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            COPY_NUMBER -> {
                val body = intent.getStringExtra(THREAD_TEXT)
                val threadId = intent.getLongExtra(THREAD_ID, 0L)
                context.notificationManager.cancel(threadId.hashCode())
                ensureBackgroundThread {
                    context.copyToClipboard(body!!)
                    context.markThreadMessagesRead(threadId)
                    context.conversationsDB.markRead(threadId)
                    refreshMessages()
                    refreshConversations()
                }
            }
            COPY_NUMBER_AND_DELETE -> {
                val body = intent.getStringExtra(THREAD_TEXT)
                val threadId = intent.getLongExtra(THREAD_ID, 0L)
                val messageId = intent.getLongExtra(MESSAGE_ID, 0L)
                context.notificationManager.cancel(threadId.hashCode())
                ensureBackgroundThread {
                    context.copyToClipboard(body!!)
                    context.markThreadMessagesRead(threadId)
                    context.conversationsDB.markRead(threadId)
                    context.deleteMessage(messageId, false)
                    context.updateLastConversationMessage(threadId)
                    refreshMessages()
                    refreshConversations()
                }
            }
        }
    }
}
