package com.goodwy.commons.providercache.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.goodwy.commons.providercache.entities.RecentGroupEntity

@Dao
interface RecentGroupDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(groups: List<RecentGroupEntity>)

    @Transaction
    suspend fun replaceMode(mode: Int, groups: List<RecentGroupEntity>) {
        deleteMode(mode)
        if (groups.isNotEmpty()) {
            upsertAll(groups)
        }
    }

    @Query(
        """
        SELECT * FROM recent_groups
        WHERE grouping_mode = :mode
        ORDER BY display_order ASC
        """,
    )
    suspend fun getGroups(mode: Int): List<RecentGroupEntity>

    @Query(
        """
        SELECT * FROM recent_groups
        WHERE grouping_mode = :mode AND group_key = :groupKey
        LIMIT 1
        """,
    )
    suspend fun getGroup(mode: Int, groupKey: String): RecentGroupEntity?

    @Query(
        """
        SELECT group_key, call_count FROM recent_groups
        WHERE grouping_mode = :mode AND group_key IN (:groupKeys)
        """,
    )
    suspend fun getCallCountsForKeys(mode: Int, groupKeys: List<String>): List<RecentGroupCountRef>

    @Query("DELETE FROM recent_groups WHERE grouping_mode = :mode AND group_key = :groupKey")
    suspend fun deleteGroup(mode: Int, groupKey: String)

    @Query("DELETE FROM recent_groups WHERE grouping_mode = :mode")
    suspend fun deleteMode(mode: Int)

    @Query("SELECT COUNT(*) FROM recent_groups WHERE grouping_mode = :mode")
    suspend fun countGroups(mode: Int): Int

    @Query(
        """
        UPDATE recent_groups
        SET display_contact_id = :displayContactId
        WHERE grouping_mode = :mode AND group_key = :groupKey
        """,
    )
    suspend fun updateDisplayContact(mode: Int, groupKey: String, displayContactId: Long?)
}

data class RecentGroupCountRef(
    @ColumnInfo(name = "group_key")
    val groupKey: String,
    @ColumnInfo(name = "call_count")
    val callCount: Int,
)
