package com.goodwy.commons.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.goodwy.commons.models.contacts.PosterEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for Contact Poster configuration operations.
 * Provides reactive database access using Kotlin Flow.
 */
@Dao
interface PosterDao {
    /**
     * Gets the poster configuration for a specific contact.
     * Returns a Flow that emits the entity whenever it changes in the database.
     *
     * @param contactId The unique identifier of the contact
     * @return Flow emitting the PosterEntity, or null if no poster exists for this contact
     */
    @Query("SELECT * FROM poster_configs WHERE contact_id = :contactId")
    fun getPoster(contactId: Long): Flow<PosterEntity?>

    /**
     * Inserts or updates a poster entity in the database.
     * Uses REPLACE strategy to handle conflicts (updates existing record if contactId matches).
     *
     * @param entity The poster entity to insert or update
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoster(entity: PosterEntity)

    /**
     * Deletes the poster configuration for a specific contact.
     *
     * @param contactId The unique identifier of the contact whose poster should be deleted
     */
    @Query("DELETE FROM poster_configs WHERE contact_id = :contactId")
    suspend fun deletePoster(contactId: Long)
}
