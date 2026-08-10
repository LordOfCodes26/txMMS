package com.goodwy.commons.providercache.validation

import android.content.Context
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.helpers.PERMISSION_READ_CONTACTS
import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.datasource.ContactsProviderDataSource
import com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ContactsCacheValidator {
    const val CURRENT_DISPLAY_CACHE_VERSION = 1

    data class ValidationResult(
        val isValid: Boolean,
        val invalidReason: String? = null,
        val providerCount: Int = 0,
        val roomCount: Int = 0,
        val displayCount: Int = 0,
        val providerMaxTs: Long = 0L,
        val cachedMaxTs: Long = 0L,
        val providerHash: Long = 0L,
        val cachedHash: Long = 0L,
        val cacheSchemaVersion: Int = 0,
    )

    suspend fun validate(
        context: Context,
        database: ProviderCacheDatabase,
        providerDataSource: ContactsProviderDataSource,
        metadataStore: ContactsCacheMetadataStore,
    ): ValidationResult = withContext(Dispatchers.IO) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return@withContext ValidationResult(
                isValid = false,
                invalidReason = "NO_PERMISSION",
            )
        }

        val contactDao = database.contactDao()
        val displayCacheDao = database.contactDisplayCacheDao()

        val providerSnapshot = providerDataSource.loadValidationSnapshot()
        val roomCount = contactDao.getSummaryCount()
        val displayCount = displayCacheDao.getCount()
        val cachedMaxTs = contactDao.getMaxSummaryTimestamp() ?: 0L
        val cachedIds = contactDao.getAllSummaryIds()
        val cachedHash = ContactsCacheHash.computeIdsHash(cachedIds)
        val cacheSchemaVersion = metadataStore.getCacheSchemaVersion()

        val invalidReasons = mutableListOf<String>()
        if (displayCount <= 0) {
            invalidReasons.add("EMPTY_DISPLAY_CACHE")
        }
        if (roomCount != providerSnapshot.contactsCount) {
            invalidReasons.add("COUNT_MISMATCH")
        }
        if (cachedMaxTs != providerSnapshot.maxLastUpdatedTimestamp) {
            invalidReasons.add("TIMESTAMP_MISMATCH")
        }
        if (cachedHash != providerSnapshot.idsHash) {
            invalidReasons.add("HASH_MISMATCH")
        }
        if (cacheSchemaVersion != CURRENT_DISPLAY_CACHE_VERSION) {
            invalidReasons.add("SCHEMA_VERSION")
        }

        val isValid = invalidReasons.isEmpty()
        val invalidReason = if (isValid) null else invalidReasons.distinct().joinToString("|")

        ProviderCacheDebugLogger.logContactsCacheValidation(
            providerCount = providerSnapshot.contactsCount,
            roomCount = roomCount,
            displayCount = displayCount,
            providerMaxTs = providerSnapshot.maxLastUpdatedTimestamp,
            cachedMaxTs = cachedMaxTs,
            providerHash = providerSnapshot.idsHash,
            cachedHash = cachedHash,
            result = if (isValid) "valid" else "invalid",
            reason = invalidReason.orEmpty(),
            action = if (isValid) "use_existing_cache" else "rebuild_required",
        )

        ValidationResult(
            isValid = isValid,
            invalidReason = invalidReason,
            providerCount = providerSnapshot.contactsCount,
            roomCount = roomCount,
            displayCount = displayCount,
            providerMaxTs = providerSnapshot.maxLastUpdatedTimestamp,
            cachedMaxTs = cachedMaxTs,
            providerHash = providerSnapshot.idsHash,
            cachedHash = cachedHash,
            cacheSchemaVersion = cacheSchemaVersion,
        )
    }
}
