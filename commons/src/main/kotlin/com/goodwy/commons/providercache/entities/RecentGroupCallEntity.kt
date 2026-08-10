package com.goodwy.commons.providercache.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "recent_group_calls",
    primaryKeys = ["grouping_mode", "group_key", "call_id"],
    foreignKeys = [
        ForeignKey(
            entity = CallLogEntity::class,
            parentColumns = ["call_id"],
            childColumns = ["call_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("call_id"),
        Index(value = ["grouping_mode", "group_key"]),
    ],
)
data class RecentGroupCallEntity(
    @ColumnInfo(name = "grouping_mode")
    val groupingMode: Int,
    @ColumnInfo(name = "group_key")
    val groupKey: String,
    @ColumnInfo(name = "call_id")
    val callId: Long,
)
