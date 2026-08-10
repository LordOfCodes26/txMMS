package com.goodwy.commons.providercache.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "recent_groups",
    primaryKeys = ["grouping_mode", "group_key"],
    indices = [
        Index("latest_timestamp"),
        Index("display_contact_id"),
        Index(value = ["grouping_mode", "display_order"]),
    ],
)
data class RecentGroupEntity(
    @ColumnInfo(name = "grouping_mode")
    val groupingMode: Int,
    @ColumnInfo(name = "group_key")
    val groupKey: String,
    @ColumnInfo(name = "display_contact_id")
    val displayContactId: Long?,
    @ColumnInfo(name = "latest_call_id")
    val latestCallId: Long,
    @ColumnInfo(name = "latest_timestamp")
    val latestTimestamp: Long,
    @ColumnInfo(name = "call_count")
    val callCount: Int,
    @ColumnInfo(name = "primary_number")
    val primaryNumber: String,
    @ColumnInfo(name = "display_order")
    val displayOrder: Int,
    @ColumnInfo(name = "membership_checksum")
    val membershipChecksum: Long = 0L,
)
