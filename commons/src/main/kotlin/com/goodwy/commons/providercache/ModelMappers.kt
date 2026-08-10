package com.goodwy.commons.providercache

import com.goodwy.commons.providercache.dao.GroupedCallLogEntry
import com.goodwy.commons.providercache.entities.CallLogEntity
import com.goodwy.commons.providercache.entities.ContactSummaryEntity
import com.goodwy.commons.providercache.model.CallLogEntry
import com.goodwy.commons.providercache.model.ContactSummary
import com.goodwy.commons.models.PhoneNumber
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.models.contacts.Email
import com.goodwy.commons.models.contacts.Organization

fun ContactSummaryEntity.toDomain(): ContactSummary = ContactSummary(
    contactId = contactId,
    lookupKey = lookupKey,
    displayName = displayName,
    photoThumbnailUri = photoThumbnailUri,
    hasPhoneNumber = hasPhoneNumber,
    lastUpdatedTimestamp = lastUpdatedTimestamp,
)

fun ContactSummary.toEntity(): ContactSummaryEntity = ContactSummaryEntity(
    contactId = contactId,
    lookupKey = lookupKey,
    displayName = displayName,
    photoThumbnailUri = photoThumbnailUri,
    hasPhoneNumber = hasPhoneNumber,
    lastUpdatedTimestamp = lastUpdatedTimestamp,
)

fun CallLogEntity.toDomain(): CallLogEntry = CallLogEntry(
    callId = callId,
    phoneNumber = phoneNumber,
    cachedName = cachedName,
    cachedPhotoUri = cachedPhotoUri,
    startTS = startTS,
    duration = duration,
    type = type,
    simID = simID,
    simTypeID = simTypeID,
    simColor = simColor,
    contactID = contactID,
    features = features,
    isUnknownNumber = isUnknownNumber,
    isVoiceMail = isVoiceMail,
    blockReason = blockReason,
    phoneAccountId = phoneAccountId,
)

fun GroupedCallLogEntry.toDomain(): CallLogEntry = entry.toDomain().copy(
    cachedPhotoUri = resolvedPhotoUri.ifEmpty { entry.cachedPhotoUri },
    contactID = if (resolvedContactId > 0) resolvedContactId else entry.contactID,
    callCount = callCount,
    groupedCallIds = if (groupedIds.isNotEmpty()) {
        groupedIds.split(",").mapNotNull { it.trim().toIntOrNull() }
    } else {
        emptyList()
    },
)

fun CallLogEntry.toEntity(): CallLogEntity = CallLogEntity(
    callId = callId,
    phoneNumber = phoneNumber,
    cachedName = cachedName,
    cachedPhotoUri = cachedPhotoUri,
    startTS = startTS,
    duration = duration,
    type = type,
    simID = simID,
    simTypeID = simTypeID,
    simColor = simColor,
    contactID = contactID,
    features = features,
    isUnknownNumber = isUnknownNumber,
    isVoiceMail = isVoiceMail,
    blockReason = blockReason,
    phoneAccountId = phoneAccountId,
    // Digit-only identity — must match CallLogGroupKeySql / RecentGroupKey.
    normalizedNumber = callLogDigitsOnly(phoneNumber, cachedNormalizedNumber),
)

private fun callLogDigitsOnly(phoneNumber: String, cachedNormalized: String): String {
    val raw = cachedNormalized.ifEmpty {
        android.telephony.PhoneNumberUtils.normalizeNumber(phoneNumber).orEmpty()
    }.ifEmpty { phoneNumber }
    return raw.filter { it.isDigit() }
}

/** Minimal [Contact] for list UI — no phone numbers/emails loaded. */
fun ContactSummary.toListContact(): Contact = Contact(
    id = contactId,
    contactId = contactId,
    firstName = displayName,
    thumbnailUri = photoThumbnailUri,
    photoUri = photoThumbnailUri,
)

fun List<ContactSummary>.toListContacts(): ArrayList<Contact> =
    ArrayList(map { it.toListContact() })

fun ContactSummary.toListContactWithPhones(phones: List<PhoneNumber>): Contact {
    val base = toListContact()
    base.phoneNumbers = ArrayList(phones)
    return base
}

fun com.goodwy.commons.providercache.entities.ContactDetailEntity.toListContact(
    phones: List<com.goodwy.commons.providercache.entities.ContactPhoneEntity>,
    emails: List<com.goodwy.commons.providercache.entities.ContactEmailEntity>,
): Contact = Contact(
    id = contactId,
    contactId = contactId,
    prefix = prefix,
    firstName = firstName.ifEmpty { displayName },
    middleName = middleName,
    surname = surname,
    suffix = suffix,
    nickname = nickname,
    photoUri = photoUri,
    thumbnailUri = photoThumbnailUri,
    notes = notes,
    starred = starred,
    source = source,
    ringtone = ringtone,
    organization = Organization(company, jobPosition),
    phoneNumbers = ArrayList(
        phones.map { PhoneNumber(it.number, it.type, it.label, it.normalizedNumber, it.isPrimary) },
    ),
    emails = ArrayList(
        emails.map { Email(it.value, it.type, it.label) },
    ),
)
