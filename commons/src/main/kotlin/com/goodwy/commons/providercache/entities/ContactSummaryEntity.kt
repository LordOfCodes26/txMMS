package com.goodwy.commons.providercache.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contact_summaries",
    indices = [
        Index(value = ["display_name"]),
        Index(value = ["primary_raw_id"]),
        Index(value = ["account_name"]),
        Index(value = ["account_type"]),
        Index(value = ["last_updated_timestamp"]),
    ],
)
data class ContactSummaryEntity(
    @PrimaryKey
    @ColumnInfo(name = "contact_id")
    val contactId: Int,
    @ColumnInfo(name = "lookup_key")
    val lookupKey: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "photo_thumbnail_uri")
    val photoThumbnailUri: String,
    @ColumnInfo(name = "has_phone_number")
    val hasPhoneNumber: Boolean,
    @ColumnInfo(name = "last_updated_timestamp")
    val lastUpdatedTimestamp: Long,
    @ColumnInfo(name = "primary_raw_id", defaultValue = "0")
    val primaryRawId: Int = 0,
    @ColumnInfo(name = "account_name", defaultValue = "")
    val accountName: String = "",
    @ColumnInfo(name = "account_type", defaultValue = "")
    val accountType: String = "",
    @ColumnInfo(name = "first_phone", defaultValue = "")
    val firstPhoneNormalized: String = "",
    @ColumnInfo(name = "first_email", defaultValue = "")
    val firstEmail: String = "",
)
