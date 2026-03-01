package com.goodwy.commons.repositories

import com.goodwy.commons.interfaces.PosterDao
import com.goodwy.commons.models.contacts.ContactPosterConfig
import com.goodwy.commons.models.contacts.PosterEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for managing Contact Poster configurations.
 * Provides a clean abstraction layer between the data model ([ContactPosterConfig])
 * and the database entity ([PosterEntity]).
 *
 * This repository handles the mapping between the domain model and the persistence layer,
 * ensuring separation of concerns and making the codebase more maintainable.
 */
class PosterRepository(
    private val posterDao: PosterDao
) {
    /**
     * Gets the poster configuration for a specific contact.
     * Returns a Flow that emits the configuration whenever it changes in the database.
     *
     * @param contactId The unique identifier of the contact
     * @return Flow emitting the ContactPosterConfig, or null if no poster exists for this contact
     */
    fun getPoster(contactId: Long): Flow<ContactPosterConfig?> {
        return posterDao.getPoster(contactId).map { entity ->
            entity?.toConfig()
        }
    }

    /**
     * Saves a poster configuration to the database.
     * If a poster already exists for this contact, it will be updated.
     * The updatedAt timestamp is automatically set to the current time.
     *
     * @param config The poster configuration to save
     */
    suspend fun savePoster(config: ContactPosterConfig) {
        // Create a new config with updated timestamp
        val configWithTimestamp = config.copy(updatedAt = System.currentTimeMillis())
        val entity = PosterEntity.fromConfig(configWithTimestamp)
        posterDao.insertPoster(entity)
    }

    /**
     * Deletes the poster configuration for a specific contact.
     *
     * @param contactId The unique identifier of the contact whose poster should be deleted
     */
    suspend fun deletePoster(contactId: Long) {
        posterDao.deletePoster(contactId)
    }
}
