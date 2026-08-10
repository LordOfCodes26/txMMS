package com.goodwy.commons.providercache.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.goodwy.commons.providercache.entities.RecentGroupCallEntity

@Dao
interface RecentGroupCallDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<RecentGroupCallEntity>)

    @Query(
        """
        DELETE FROM recent_group_calls
        WHERE grouping_mode = :mode AND group_key = :groupKey
        """,
    )
    suspend fun deleteForGroup(mode: Int, groupKey: String)

    @Query("DELETE FROM recent_group_calls WHERE call_id IN (:callIds)")
    suspend fun deleteForCallIds(callIds: List<Long>)

    @Query(
        """
        SELECT call_id FROM recent_group_calls
        WHERE grouping_mode = :mode AND group_key = :groupKey
        ORDER BY call_id ASC
        """,
    )
    suspend fun getCallIdsForGroup(mode: Int, groupKey: String): List<Long>

    @Query(
        """
        SELECT COUNT(*) FROM recent_group_calls
        WHERE grouping_mode = :mode AND group_key = :groupKey
        """,
    )
    suspend fun countCallsForGroup(mode: Int, groupKey: String): Int

    @Query(
        """
        SELECT grouping_mode, group_key FROM recent_group_calls
        WHERE call_id = :callId
        """,
    )
    suspend fun getGroupsForCallId(callId: Long): List<RecentGroupCallRef>

    @Query("DELETE FROM recent_group_calls WHERE grouping_mode = :mode")
    suspend fun deleteMode(mode: Int)

    @Query("SELECT COUNT(*) FROM recent_group_calls WHERE grouping_mode = :mode")
    suspend fun countMemberships(mode: Int): Int
}

data class RecentGroupCallRef(
    @ColumnInfo(name = "grouping_mode")
    val groupingMode: Int,
    @ColumnInfo(name = "group_key")
    val groupKey: String,
)
