package com.goodwy.commons.providercache.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.goodwy.commons.providercache.entities.ContactSearchIndexEntity

@Dao
interface ContactSearchIndexDao {

    @Query("SELECT COUNT(*) FROM contact_search_index")
    suspend fun getCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<ContactSearchIndexEntity>)

    @Query("DELETE FROM contact_search_index WHERE contact_id IN (:contactIds)")
    suspend fun deleteForContacts(contactIds: List<Int>)

    @Query("DELETE FROM contact_search_index")
    suspend fun clearAll()
}
