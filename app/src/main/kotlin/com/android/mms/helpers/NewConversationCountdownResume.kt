package com.android.mms.helpers

import android.content.Context
import android.content.Intent
import com.android.mms.activities.NewConversationActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object NewConversationCountdownResume {
    fun buildResumeIntent(context: Context, pending: PendingSendCountdown): Intent? {
        val numbers = parseRecipientNumbers(pending)
        if (numbers.isEmpty()) {
            return null
        }
        val numberExtra = when (numbers.size) {
            1 -> numbers[0]
            else -> Gson().toJson(numbers.toSet())
        }
        return Intent(context, NewConversationActivity::class.java).apply {
            putExtra(NEW_CONVERSATION_RESUME_DRAFT, true)
            putExtra(THREAD_ID, pending.threadId)
            putExtra(THREAD_NUMBER, numberExtra)
        }
    }

    fun parseRecipientNumbers(pending: PendingSendCountdown): List<String> {
        val json = pending.recipientNumbersJson ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            Gson().fromJson<List<String>>(json, type)?.filter { it.isNotBlank() }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
