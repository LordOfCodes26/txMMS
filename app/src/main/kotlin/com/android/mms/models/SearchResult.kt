package com.android.mms.models

data class SearchResult(
    val messageId: Long,
    val title: String,
    val phoneNumber: String?,
    val snippet: String,
    val date: String,
    /** Epoch millis for grouping (Today / Yesterday / Previous), same basis as conversation list. */
    val dateMillis: Long,
    val threadId: Long,
    var photoUri: String,
    val isCompany: Boolean = false,
    val isBlocked: Boolean = false
)
