package com.goodwy.commons.providercache.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contact_phones",
    foreignKeys = [
        ForeignKey(
            entity = ContactDetailEntity::class,
            parentColumns = ["contact_id"],
            childColumns = ["contact_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("contact_id")],
)
data class ContactPhoneEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "contact_id")
    val contactId: Int,
    @ColumnInfo(name = "number")
    val number: String,
    @ColumnInfo(name = "normalized_number")
    val normalizedNumber: String,
    @ColumnInfo(name = "type")
    val type: Int,
    @ColumnInfo(name = "label")
    val label: String = "",
    @ColumnInfo(name = "is_primary")
    val isPrimary: Boolean = false,
)
