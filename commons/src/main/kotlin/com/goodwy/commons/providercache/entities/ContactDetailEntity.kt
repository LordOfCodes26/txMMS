package com.goodwy.commons.providercache.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contact_details")
data class ContactDetailEntity(
    @PrimaryKey
    @ColumnInfo(name = "contact_id")
    val contactId: Int,
    @ColumnInfo(name = "lookup_key")
    val lookupKey: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "prefix")
    val prefix: String = "",
    @ColumnInfo(name = "first_name")
    val firstName: String = "",
    @ColumnInfo(name = "middle_name")
    val middleName: String = "",
    @ColumnInfo(name = "surname")
    val surname: String = "",
    @ColumnInfo(name = "suffix")
    val suffix: String = "",
    @ColumnInfo(name = "nickname")
    val nickname: String = "",
    @ColumnInfo(name = "photo_uri")
    val photoUri: String = "",
    @ColumnInfo(name = "photo_thumbnail_uri")
    val photoThumbnailUri: String = "",
    @ColumnInfo(name = "notes")
    val notes: String = "",
    @ColumnInfo(name = "company")
    val company: String = "",
    @ColumnInfo(name = "job_position")
    val jobPosition: String = "",
    @ColumnInfo(name = "starred")
    val starred: Int = 0,
    @ColumnInfo(name = "source")
    val source: String = "",
    @ColumnInfo(name = "ringtone")
    val ringtone: String? = null,
    @ColumnInfo(name = "last_updated_timestamp")
    val lastUpdatedTimestamp: Long,
)
