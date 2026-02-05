package com.goodwy.commons.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.goodwy.commons.models.contacts.ContactPoster

@Dao
interface ContactPosterDao {
    @Query("SELECT * FROM contact_posters WHERE contact_id = :contactId")
    suspend fun getPosterForContact(contactId: Int): ContactPoster?

    @Query("SELECT * FROM contact_posters WHERE contact_id = :contactId")
    fun getPosterForContactSync(contactId: Int): ContactPoster?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(poster: ContactPoster): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdateSync(poster: ContactPoster): Long

    @Query("DELETE FROM contact_posters WHERE contact_id = :contactId")
    suspend fun deletePosterForContact(contactId: Int)

    @Query("DELETE FROM contact_posters WHERE contact_id = :contactId")
    fun deletePosterForContactSync(contactId: Int)

    @Query("DELETE FROM contact_posters WHERE contact_id IN (:contactIds)")
    suspend fun deletePostersForContacts(contactIds: List<Int>)
}
