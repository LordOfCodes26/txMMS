package com.goodwy.commons.providercache.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contact_search_index",
    indices = [
        Index("contact_id"),
        Index("display_name_lower"),
        Index("name_t9_key"),
    ],
)
data class ContactSearchIndexEntity(
    @PrimaryKey
    @ColumnInfo(name = "contact_id")
    val contactId: Int,
    @ColumnInfo(name = "display_name_lower")
    val displayNameLower: String,
    @ColumnInfo(name = "name_t9_key")
    val nameT9Key: String,
)
