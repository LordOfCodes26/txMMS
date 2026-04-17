package com.android.mms.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "drafts")
data class Draft(
    @ColumnInfo(name = "thread_id") @PrimaryKey val threadId: Long,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "date") val date: Long,
    @ColumnInfo(name = "attachments_json") val attachmentsJson: String? = null,
    @ColumnInfo(name = "is_scheduled") val isScheduled: Boolean = false,
    @ColumnInfo(name = "scheduled_millis") val scheduledMillis: Long = 0L,
)
