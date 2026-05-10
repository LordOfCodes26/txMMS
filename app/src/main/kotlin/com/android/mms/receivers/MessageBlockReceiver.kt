package com.android.mms.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.mms.extensions.config

private const val ACTION_MESSAGE_BLOCK = "com.chonha.total.action.ACTION_MESSAGE_BLOCK"
private const val TAG = "MessageBlockReceiver"
private const val BLOCK_DURATION_MS = 5_000L

class MessageBlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: action=${intent.action}")
        if (intent.action == ACTION_MESSAGE_BLOCK) {
            context.config.blockNextFeeServiceMessage = true
            Log.d(TAG, "onReceive: blockNextFeeServiceMessage=true")

            // Cancel any previously scheduled reset so the 5-second window restarts.
            handler.removeCallbacks(resetRunnable)

            val appContext = context.applicationContext
            resetRunnable = Runnable {
                appContext.config.blockNextFeeServiceMessage = false
                Log.d(TAG, "blockNextFeeServiceMessage reset to false after ${BLOCK_DURATION_MS}ms")
            }
            handler.postDelayed(resetRunnable, BLOCK_DURATION_MS)
        }
    }

    companion object {
        private val handler = Handler(Looper.getMainLooper())
        private var resetRunnable: Runnable = Runnable {}
    }
}
