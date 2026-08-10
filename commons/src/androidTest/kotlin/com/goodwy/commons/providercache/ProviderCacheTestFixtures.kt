package com.goodwy.commons.providercache

import com.goodwy.commons.providercache.entities.CallLogEntity
import com.goodwy.commons.providercache.entities.ContactDisplayCacheEntity
import com.goodwy.commons.providercache.entities.ContactPhoneIndexEntity
import com.goodwy.commons.providercache.entities.ContactSearchIndexEntity
import com.goodwy.commons.providercache.entities.ContactSummaryEntity
import com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity

object ProviderCacheTestFixtures {

    suspend fun seedContactGraph(
        database: ProviderCacheDatabase,
        contactId: Int = 100,
        rawId: Int = 200,
        displayName: String = "Alice Test",
        phone: String = "+15551234567",
    ) {
        val summary = ContactSummaryEntity(
            contactId = contactId,
            lookupKey = "lookup-$contactId",
            displayName = displayName,
            photoThumbnailUri = "",
            hasPhoneNumber = true,
            lastUpdatedTimestamp = System.currentTimeMillis(),
            primaryRawId = rawId,
            firstPhoneNormalized = phone,
        )
        database.contactDao().insertSummaries(listOf(summary))
        database.contactPhoneIndexDao().insertAll(
            listOf(
                ContactPhoneIndexEntity(
                    contactId = contactId,
                    normalizedNumber = phone,
                    digits = phone.filter { it.isDigit() },
                    phoneDigits = phone.filter { it.isDigit() },
                ),
            ),
        )
        database.contactSearchIndexDao().insertAll(
            listOf(
                ContactSearchIndexEntity(
                    contactId = contactId,
                    displayNameLower = displayName.lowercase(),
                    nameT9Key = displayName.lowercase(),
                ),
            ),
        )
        database.contactDisplayCacheDao().insertAll(
            listOf(
                ContactDisplayCacheEntity(
                    rawId = rawId,
                    contactId = contactId,
                    displayName = displayName,
                    thumbnailUri = "",
                    photoUri = "",
                    source = "Google",
                    accountType = "",
                    firstPhone = phone,
                    firstEmail = "",
                    sectionLetter = "A",
                    sortKey = displayName.lowercase(),
                    searchName = displayName.lowercase(),
                    t9Key = displayName.lowercase(),
                    phoneDigits = phone.filter { it.isDigit() },
                ),
            ),
        )
    }

    suspend fun seedCallLogWithDisplay(
        database: ProviderCacheDatabase,
        callId: Int = 1000,
        contactId: Int? = 100,
        displayName: String = "Alice Test",
        phone: String = "+15551234567",
        groupKey: String = phone.filter { it.isDigit() },
    ) {
        database.callLogDao().insertAll(
            listOf(
                CallLogEntity(
                    callId = callId,
                    phoneNumber = phone,
                    cachedName = displayName,
                    cachedPhotoUri = "",
                    startTS = System.currentTimeMillis(),
                    duration = 30,
                    type = 1,
                    simID = 0,
                    contactID = contactId,
                    normalizedNumber = phone,
                ),
            ),
        )
        database.recentDisplayCacheDao().insertAll(
            listOf(
                RecentDisplayCacheEntity(
                    callId = callId,
                    phoneNumber = phone,
                    cachedName = displayName,
                    photoUri = "",
                    startTS = System.currentTimeMillis(),
                    duration = 30,
                    type = 1,
                    simID = 0,
                    simTypeID = 1,
                    simColor = 0,
                    contactID = contactId,
                    callCount = 1,
                    groupedCallIds = callId.toString(),
                    normalizedNumber = phone,
                    groupKey = groupKey,
                    isUnknownNumber = false,
                    isVoiceMail = false,
                    blockReason = 0,
                    features = 0,
                    groupByContact = 0,
                    displayOrder = 0,
                    displayName = displayName,
                ),
            ),
        )
    }
}
