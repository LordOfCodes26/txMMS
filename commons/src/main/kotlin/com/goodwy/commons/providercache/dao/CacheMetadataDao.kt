package com.goodwy.commons.providercache.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.goodwy.commons.providercache.entities.CacheMetadataEntity

@Dao
interface CacheMetadataDao {

    @Query("SELECT * FROM cache_metadata WHERE domain = :domain LIMIT 1")
    suspend fun getByDomain(domain: String): CacheMetadataEntity?

    @Query("SELECT * FROM cache_metadata")
    suspend fun getAll(): List<CacheMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CacheMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: CacheMetadataEntity): Long

    @Update
    suspend fun update(entity: CacheMetadataEntity)

    @Query("UPDATE cache_metadata SET dirty = :dirty, updated_at = :updatedAt WHERE domain = :domain")
    suspend fun setDirty(domain: String, dirty: Boolean, updatedAt: Long)

    @Query(
        """
        UPDATE cache_metadata SET
            display_version = :displayVersion,
            raw_version = :rawVersion,
            last_successful_mutation_id = :mutationId,
            last_mutation_reason = :reason,
            row_count = :rowCount,
            content_checksum = :checksum,
            dirty = 0,
            repair_required = :repairRequired,
            updated_at = :updatedAt
        WHERE domain = :domain
        """,
    )
    suspend fun commitMutation(
        domain: String,
        rawVersion: Long,
        displayVersion: Long,
        mutationId: Long,
        reason: String,
        rowCount: Int,
        checksum: Long,
        repairRequired: Boolean,
        updatedAt: Long,
    )
}
