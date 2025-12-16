package com.android.mms.interfaces

import androidx.room.Dao
import androidx.room.Query
import com.android.mms.models.MessageAttachment

@Dao
interface MessageAttachmentsDao {
    @Query("SELECT * FROM message_attachments")
    fun getAll(): List<MessageAttachment>
}
