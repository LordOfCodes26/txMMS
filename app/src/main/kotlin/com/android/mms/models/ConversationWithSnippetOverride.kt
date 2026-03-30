package com.android.mms.models

import androidx.room.ColumnInfo
import androidx.room.Embedded

data class ConversationWithSnippetOverride(
    @ColumnInfo(name = "new_snippet") val snippet: String?,
    @ColumnInfo(name = "last_message_date") val lastMessageDate: Int?,
    @ColumnInfo(name = "last_message_type") val lastMessageType: Int?,
    @Embedded val conversation: Conversation
) {
    fun toConversation(): Conversation {
        var c = if (snippet == null) conversation else conversation.copy(snippet = snippet)
        if (lastMessageDate != null) {
            c = c.copy(date = lastMessageDate)
        }
        c.lastMessageType = lastMessageType
        return c
    }
}
