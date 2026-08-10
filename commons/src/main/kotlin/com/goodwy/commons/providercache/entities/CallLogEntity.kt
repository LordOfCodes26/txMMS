package com.goodwy.commons.providercache.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "call_log_entries",
    indices = [
        Index(value = ["start_ts"]),
        Index(value = ["phone_number"]),
        Index(value = ["phone_account_id"]),
    ],
)
data class CallLogEntity(
    @PrimaryKey
    @ColumnInfo(name = "call_id")
    val callId: Int,
    @ColumnInfo(name = "phone_number")
    val phoneNumber: String,
    @ColumnInfo(name = "cached_name")
    val cachedName: String,
    @ColumnInfo(name = "cached_photo_uri")
    val cachedPhotoUri: String,
    @ColumnInfo(name = "start_ts")
    val startTS: Long,
    @ColumnInfo(name = "duration")
    val duration: Int,
    @ColumnInfo(name = "type")
    val type: Int,
    @ColumnInfo(name = "sim_id")
    val simID: Int,
    @ColumnInfo(name = "sim_type_id")
    val simTypeID: Int = 1,
    @ColumnInfo(name = "sim_color")
    val simColor: Int = 0,
    @ColumnInfo(name = "contact_id")
    val contactID: Int? = null,
    @ColumnInfo(name = "features")
    val features: Int? = null,
    @ColumnInfo(name = "is_unknown_number")
    val isUnknownNumber: Boolean = false,
    @ColumnInfo(name = "is_voice_mail")
    val isVoiceMail: Boolean = false,
    @ColumnInfo(name = "block_reason")
    val blockReason: Int? = 0,
    @ColumnInfo(name = "phone_account_id")
    val phoneAccountId: String = "",
    @ColumnInfo(name = "normalized_number")
    val normalizedNumber: String = "",
)
