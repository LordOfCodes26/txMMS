package com.android.mms.models

import androidx.room.ColumnInfo
import androidx.room.Embedded

data class ConversationWithSnippetOverride(
    @ColumnInfo(name = "new_snippet") val snippet: String?,
    @ColumnInfo(name = "last_message_date") val lastMessageDate: Int?,
    @ColumnInfo(name = "last_message_type") val lastMessageType: Int?,
    /** Local draft save time in seconds, when a draft exists; else null. */
    @ColumnInfo(name = "draft_date_sec") val draftDateSec: Int?,
    @Embedded val conversation: Conversation
) {
    fun toConversation(): Conversation {
        var c = if (snippet == null) conversation else conversation.copy(snippet = snippet)
        // last_message_date is the latest *stored* message; a draft is newer until sent, so the list
        // time (e.g. "Now") must use the later of last message and local draft.
        val fromMessages = lastMessageDate ?: conversation.date
        val fromDraft = draftDateSec
        val effectiveDate =
            if (fromDraft != null && fromDraft > fromMessages) fromDraft else fromMessages
        c = c.copy(date = effectiveDate)
        c.lastMessageType = lastMessageType
        return c
    }
}
