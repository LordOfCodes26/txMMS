package com.goodwy.commons.providercache.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contact_emails",
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
data class ContactEmailEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "contact_id")
    val contactId: Int,
    @ColumnInfo(name = "value")
    val value: String,
    @ColumnInfo(name = "type")
    val type: Int,
    @ColumnInfo(name = "label")
    val label: String = "",
)
