package com.android.mms.interfaces

import androidx.room.Dao
import androidx.room.Query
import com.android.mms.models.Attachment

@Dao
interface AttachmentsDao {
    @Query("SELECT * FROM attachments")
    fun getAll(): List<Attachment>
}
