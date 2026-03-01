package com.goodwy.commons.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.goodwy.commons.models.contacts.AvatarStyleEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for Avatar Style configuration operations.
 * Provides reactive database access using Kotlin Flow.
 */
@Dao
interface AvatarStyleDao {
    /**
     * Gets the avatar style configuration for a specific contact.
     * Returns a Flow that emits the entity whenever it changes in the database.
     *
     * @param contactId The unique identifier of the contact
     * @return Flow emitting the AvatarStyleEntity, or null if no avatar style exists for this contact
     */
    @Query("SELECT * FROM avatar_style_configs WHERE contact_id = :contactId")
    fun getAvatarStyle(contactId: Long): Flow<AvatarStyleEntity?>

    /**
     * Inserts or updates an avatar style entity in the database.
     * Uses REPLACE strategy to handle conflicts (updates existing record if contactId matches).
     *
     * @param entity The avatar style entity to insert or update
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAvatarStyle(entity: AvatarStyleEntity)

    /**
     * Deletes the avatar style configuration for a specific contact.
     *
     * @param contactId The unique identifier of the contact whose avatar style should be deleted
     */
    @Query("DELETE FROM avatar_style_configs WHERE contact_id = :contactId")
    suspend fun deleteAvatarStyle(contactId: Long)
}
