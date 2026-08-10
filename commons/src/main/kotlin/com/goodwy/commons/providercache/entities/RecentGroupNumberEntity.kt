package com.goodwy.commons.providercache.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "recent_group_numbers",
    primaryKeys = ["grouping_mode", "group_key", "normalized_number"],
    indices = [
        Index("normalized_number"),
        Index(value = ["grouping_mode", "group_key"]),
    ],
)
data class RecentGroupNumberEntity(
    @ColumnInfo(name = "grouping_mode")
    val groupingMode: Int,
    @ColumnInfo(name = "group_key")
    val groupKey: String,
    @ColumnInfo(name = "normalized_number")
    val normalizedNumber: String,
)
