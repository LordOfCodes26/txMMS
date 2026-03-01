package com.goodwy.commons.repositories

import com.goodwy.commons.interfaces.AvatarStyleDao
import com.goodwy.commons.models.contacts.AvatarStyleConfig
import com.goodwy.commons.models.contacts.AvatarStyleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for managing Avatar Style configurations.
 * Provides a clean abstraction layer between the data model ([AvatarStyleConfig])
 * and the database entity ([AvatarStyleEntity]).
 *
 * This repository handles the mapping between the domain model and the persistence layer,
 * ensuring separation of concerns and making the codebase more maintainable.
 */
class AvatarStyleRepository(
    private val avatarStyleDao: AvatarStyleDao
) {
    /**
     * Gets the avatar style configuration for a specific contact.
     * Returns a Flow that emits the configuration whenever it changes in the database.
     *
     * @param contactId The unique identifier of the contact
     * @return Flow emitting the AvatarStyleConfig, or null if no avatar style exists for this contact
     */
    fun getAvatarStyle(contactId: Long): Flow<AvatarStyleConfig?> {
        return avatarStyleDao.getAvatarStyle(contactId).map { entity ->
            entity?.toConfig()
        }
    }

    /**
     * Saves an avatar style configuration to the database.
     * If an avatar style already exists for this contact, it will be updated.
     * The updatedAt timestamp is automatically set to the current time.
     *
     * @param config The avatar style configuration to save
     */
    suspend fun saveAvatarStyle(config: AvatarStyleConfig) {
        // Create a new config with updated timestamp
        val configWithTimestamp = config.copy(updatedAt = System.currentTimeMillis())
        val entity = AvatarStyleEntity.fromConfig(configWithTimestamp)
        avatarStyleDao.insertAvatarStyle(entity)
    }

    /**
     * Deletes the avatar style configuration for a specific contact.
     *
     * @param contactId The unique identifier of the contact whose avatar style should be deleted
     */
    suspend fun deleteAvatarStyle(contactId: Long) {
        avatarStyleDao.deleteAvatarStyle(contactId)
    }
}
