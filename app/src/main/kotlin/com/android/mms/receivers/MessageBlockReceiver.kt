package com.android.mms.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.android.mms.extensions.config

private const val ACTION_MESSAGE_BLOCK = "com.chonha.total.action.ACTION_MESSAGE_BLOCK"
private const val TAG = "MessageBlockReceiver"

class MessageBlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: action=${intent.action}")
        if (intent.action == ACTION_MESSAGE_BLOCK) {
            context.config.blockNextFeeServiceMessage = true
            Log.d(TAG, "onReceive: blockNextFeeServiceMessage=true")
        }
    }
}
