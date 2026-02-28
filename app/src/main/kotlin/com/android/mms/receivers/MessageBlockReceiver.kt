package com.android.mms.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.android.mms.extensions.config

private const val ACTION_MESSAGE_BLOCK = "com.chonha.total.action.ACTION_MESSAGE_BLOCK"

class MessageBlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_MESSAGE_BLOCK) {
            context.config.blockNextFeeServiceMessage = true
        }
    }
}
