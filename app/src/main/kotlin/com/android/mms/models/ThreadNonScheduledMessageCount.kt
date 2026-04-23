package com.android.mms.models

import androidx.room.ColumnInfo

/**
 * One row per thread: count of non-scheduled messages not in the recycle bin.
 * Matches [com.android.mms.interfaces.MessagesDao.getThreadMessageCount] semantics.
 */
data class ThreadNonScheduledMessageCount(
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "message_count") val count: Int,
)
