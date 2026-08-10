package com.goodwy.commons.providercache.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contact_phone_index",
    indices = [
        Index("contact_id"),
        Index("normalized_number"),
        Index("digits"),
        Index("phone_digits"),
    ],
)
data class ContactPhoneIndexEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "contact_id")
    val contactId: Int,
    @ColumnInfo(name = "normalized_number")
    val normalizedNumber: String,
    @ColumnInfo(name = "digits")
    val digits: String,
    @ColumnInfo(name = "phone_digits", defaultValue = "")
    val phoneDigits: String = "",
)
