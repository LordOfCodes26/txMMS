package com.goodwy.commons.providercache.filter

import android.content.Context
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.getVisibleContactSources
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.models.contacts.Email
import com.goodwy.commons.models.PhoneNumber
import com.goodwy.commons.providercache.datasource.ContactsMetadataLoader
import com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger
import com.goodwy.commons.providercache.entities.ContactSummaryEntity
import com.goodwy.commons.providercache.model.ContactSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Maps summaries/entities to [Contact] rows with metadata for paging filters.
 */
object ContactPagingMapper {

    fun entityToContact(entity: ContactSummaryEntity): Contact = Contact(
        id = if (entity.primaryRawId != 0) entity.primaryRawId else entity.contactId,
        contactId = entity.contactId,
        firstName = entity.displayName,
        source = entity.accountName,
        thumbnailUri = entity.photoThumbnailUri,
        photoUri = entity.photoThumbnailUri,
        phoneNumbers = phoneList(entity.firstPhoneNormalized),
        emails = emailList(entity.firstEmail),
    )

    fun summaryToContact(summary: ContactSummary, meta: ContactsMetadataLoader.ContactMetadata): Contact =
        Contact(
            id = meta.primaryRawId,
            contactId = summary.contactId,
            firstName = summary.displayName,
            source = meta.accountName,
            thumbnailUri = summary.photoThumbnailUri,
            photoUri = summary.photoThumbnailUri,
            phoneNumbers = phoneList(meta.firstPhoneNormalized),
            emails = emailList(meta.firstEmail),
        )

    fun summaryToContactFallback(summary: ContactSummary): Contact = Contact(
        id = summary.contactId,
        contactId = summary.contactId,
        firstName = summary.displayName,
        thumbnailUri = summary.photoThumbnailUri,
        photoUri = summary.photoThumbnailUri,
    )

    fun entityToSummaryEntity(
        summary: ContactSummary,
        meta: ContactsMetadataLoader.ContactMetadata,
    ): ContactSummaryEntity = ContactSummaryEntity(
        contactId = summary.contactId,
        lookupKey = summary.lookupKey,
        displayName = summary.displayName,
        photoThumbnailUri = summary.photoThumbnailUri,
        hasPhoneNumber = summary.hasPhoneNumber,
        lastUpdatedTimestamp = summary.lastUpdatedTimestamp,
        primaryRawId = meta.primaryRawId,
        accountName = meta.accountName,
        accountType = meta.accountType,
        firstPhoneNormalized = meta.firstPhoneNormalized,
        firstEmail = meta.firstEmail,
    )

    private fun phoneList(normalized: String): ArrayList<PhoneNumber> =
        if (normalized.isEmpty()) ArrayList()
        else arrayListOf(PhoneNumber(normalized, 0, "", normalized, true))

    private fun emailList(value: String): ArrayList<Email> =
        if (value.isEmpty()) ArrayList()
        else arrayListOf(Email(value, 0, ""))
}

/**
 * Applies source-account and duplicate-merge filters using app settings.
 */
class ContactListPagingFilters(
    private val context: Context,
    private val secureFilterProvider: () -> ContactPageFilter?,
) {
    private val duplicateSeenKeys = mutableSetOf<String>()
    private val metadataLoader = ContactsMetadataLoader(context)

    fun resetDuplicateKeys() {
        duplicateSeenKeys.clear()
    }

    suspend fun filterPage(
        contacts: List<Contact>,
        sqlFilters: ContactRoomQueryFilters? = null,
    ): List<Contact> = withContext(Dispatchers.Default) {
        if (contacts.isEmpty()) return@withContext contacts
        val inCount = contacts.size
        var result = contacts

        val enrichStart = System.currentTimeMillis()
        result = enrichMissingMetadata(result)
        ProviderCacheDebugLogger.logFilterStep(
            step = "metadataEnrich",
            durationMs = System.currentTimeMillis() - enrichStart,
            inCount = inCount,
            outCount = result.size,
        )

        if (sqlFilters?.sqlSecureFilterApplied != true) {
            val secureStart = System.currentTimeMillis()
            val beforeSecure = result.size
            secureFilterProvider()?.let { secure ->
                result = secure.filterPage(result)
            }
            ProviderCacheDebugLogger.logFilterStep(
                step = "secureFilter",
                durationMs = System.currentTimeMillis() - secureStart,
                inCount = beforeSecure,
                outCount = result.size,
            )
        }

        val afterSecure = result.size
        if (sqlFilters?.sqlAccountFilterApplied != true) {
            val sourceStart = System.currentTimeMillis()
            val visibleSources = context.getVisibleContactSources().toHashSet()
            result = ContactSourceAccountFilter.filter(result, visibleSources)
            ProviderCacheDebugLogger.logFilterStep(
                step = "sourceAccountFilter",
                durationMs = System.currentTimeMillis() - sourceStart,
                inCount = afterSecure,
                outCount = result.size,
            )
        }
        val afterSource = result.size

        if (context.baseConfig.mergeDuplicateContacts) {
            val dupStart = System.currentTimeMillis()
            val visibleSources = context.getVisibleContactSources().toHashSet()
            result = ContactDuplicateMergeFilter.mergePage(
                contacts = result,
                visibleSourceNames = visibleSources,
                mergeEnabled = true,
                seenKeys = duplicateSeenKeys,
            )
            ProviderCacheDebugLogger.logFilterStep(
                step = "duplicateMerge",
                durationMs = System.currentTimeMillis() - dupStart,
                inCount = afterSource,
                outCount = result.size,
            )
        }
        ProviderCacheDebugLogger.logFilterPage(
            inCount = inCount,
            afterSecure = afterSecure,
            afterSource = afterSource,
            afterDuplicate = result.size,
        )
        result
    }

    /**
     * Room rows synced before v3 metadata or provider rows without metadata use aggregate id as [Contact.id].
     * Load raw-contact metadata before secure-mode filtering.
     */
    private suspend fun enrichMissingMetadata(contacts: List<Contact>): List<Contact> {
        val needsMeta = contacts.filter { it.id == it.contactId && it.source.isEmpty() }
        if (needsMeta.isEmpty()) return contacts
        val summaries = needsMeta.map { contact ->
            com.goodwy.commons.providercache.model.ContactSummary(
                contactId = contact.contactId,
                lookupKey = "",
                displayName = contact.getNameToDisplay(),
                photoThumbnailUri = contact.thumbnailUri,
                hasPhoneNumber = contact.phoneNumbers.isNotEmpty(),
                lastUpdatedTimestamp = 0L,
            )
        }
        val meta = metadataLoader.loadForSummaries(summaries)
        if (meta.isEmpty()) return contacts
        return contacts.map { contact ->
            val m = meta[contact.contactId] ?: return@map contact
            if (contact.id == contact.contactId && contact.source.isEmpty()) {
                ContactPagingMapper.summaryToContact(
                    com.goodwy.commons.providercache.model.ContactSummary(
                        contactId = contact.contactId,
                        lookupKey = "",
                        displayName = contact.getNameToDisplay(),
                        photoThumbnailUri = contact.thumbnailUri,
                        hasPhoneNumber = contact.phoneNumbers.isNotEmpty(),
                        lastUpdatedTimestamp = 0L,
                    ),
                    m,
                )
            } else {
                contact
            }
        }
    }
}
