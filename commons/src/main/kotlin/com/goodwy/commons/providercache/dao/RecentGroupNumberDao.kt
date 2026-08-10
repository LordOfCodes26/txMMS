package com.goodwy.commons.providercache.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.goodwy.commons.providercache.entities.RecentGroupNumberEntity

@Dao
interface RecentGroupNumberDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<RecentGroupNumberEntity>)

    @Query(
        """
        DELETE FROM recent_group_numbers
        WHERE grouping_mode = :mode AND group_key = :groupKey
        """,
    )
    suspend fun deleteForGroup(mode: Int, groupKey: String)

    @Query(
        """
        SELECT normalized_number FROM recent_group_numbers
        WHERE grouping_mode = :mode AND group_key = :groupKey
        ORDER BY normalized_number ASC
        """,
    )
    suspend fun getNumbersForGroup(mode: Int, groupKey: String): List<String>

    @Query(
        """
        SELECT group_key FROM recent_group_numbers
        WHERE grouping_mode = :mode AND normalized_number = :normalizedNumber
        """,
    )
    suspend fun getGroupsForNumber(mode: Int, normalizedNumber: String): List<String>

    @Query("DELETE FROM recent_group_numbers WHERE grouping_mode = :mode")
    suspend fun deleteMode(mode: Int)
}
