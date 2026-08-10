package com.goodwy.commons.providercache.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cache_metadata")
data class CacheMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "domain")
    val domain: String,
    @ColumnInfo(name = "raw_version", defaultValue = "0")
    val rawVersion: Long = 0L,
    @ColumnInfo(name = "display_version", defaultValue = "0")
    val displayVersion: Long = 0L,
    @ColumnInfo(name = "last_successful_mutation_id", defaultValue = "0")
    val lastSuccessfulMutationId: Long = 0L,
    @ColumnInfo(name = "last_mutation_reason", defaultValue = "")
    val lastMutationReason: String = "",
    @ColumnInfo(name = "row_count", defaultValue = "0")
    val rowCount: Int = 0,
    @ColumnInfo(name = "content_checksum", defaultValue = "0")
    val contentChecksum: Long = 0L,
    @ColumnInfo(name = "dirty", defaultValue = "0")
    val dirty: Boolean = false,
    @ColumnInfo(name = "repair_required", defaultValue = "0")
    val repairRequired: Boolean = false,
    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAt: Long = 0L,
)

object CacheMetadataDomain {
    const val CONTACTS_RAW = "CONTACTS_RAW"
    const val CONTACTS_DISPLAY = "CONTACTS_DISPLAY"
    const val RECENTS_RAW = "RECENTS_RAW"
    const val RECENTS_DISPLAY = "RECENTS_DISPLAY"

    val ALL = listOf(CONTACTS_RAW, CONTACTS_DISPLAY, RECENTS_RAW, RECENTS_DISPLAY)
}
