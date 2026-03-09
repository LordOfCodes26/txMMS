package com.android.mms.models

/**
 * Sealed type for conversation list items: either a date section header (Today, Yesterday, Previous)
 * or a conversation row. Used to group the conversation list by last message date, following
 * the same pattern as RecentsFragment in txDial.
 */
sealed class ConversationListItem {

    companion object {
        const val SECTION_TODAY = "__section_today__"
        const val SECTION_YESTERDAY = "__section_yesterday__"
        const val SECTION_BEFORE = "__section_before__"
    }

    data class DateHeader(
        val timestamp: Long,
        val dayCode: String,
    ) : ConversationListItem()

    data class ConversationItem(
        val conversation: Conversation,
    ) : ConversationListItem()

    fun getItemId(): Long = when (this) {
        is DateHeader -> -timestamp
        is ConversationItem -> conversation.threadId
    }
}
