package com.goodwy.commons.providercache.datasource

import android.database.Cursor
import android.provider.CallLog
import android.provider.ContactsContract
import com.goodwy.commons.extensions.getIntValueOr
import com.goodwy.commons.extensions.getLongValueOr
import com.goodwy.commons.extensions.getStringValueOr
import com.goodwy.commons.helpers.ContactListPhotoUriPolicy
import com.goodwy.commons.providercache.model.CallLogEntry
import com.goodwy.commons.providercache.model.ContactSummary

/**
 * Reads at most [limit] rows after skipping [offset] cursor rows.
 * Some providers/emulators ignore [android.content.ContentResolver.QUERY_ARG_LIMIT] /
 * [android.content.ContentResolver.QUERY_ARG_OFFSET]; this keeps paging and sync bounded.
 */
internal inline fun Cursor.readContactPage(
    offset: Int,
    limit: Int,
    consumer: (Cursor) -> Unit,
): Int {
    var skipped = 0
    var read = 0
    while (moveToNext()) {
        if (skipped < offset) {
            skipped++
            continue
        }
        if (read >= limit) break
        consumer(this)
        read++
    }
    return read
}

internal fun Cursor.toContactSummarySafe(): ContactSummary? {
    val idIndex = getColumnIndex(ContactsContract.Contacts._ID)
    if (idIndex == -1) return null
    val contactId = getInt(idIndex)
    // Prefer PHOTO_THUMBNAIL_URI; fall back to PHOTO_URI so cold-start sync (when deep probes
    // are gated) still stores a usable list avatar URI for contacts that only expose PHOTO_URI.
    val photoThumbnailUri = ContactListPhotoUriPolicy.resolveListPhotoThumbUri(
        thumbnailUri = getStringValueOr(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI, ""),
        photoUri = getStringValueOr(ContactsContract.Contacts.PHOTO_URI, ""),
    )
    return ContactSummary(
        contactId = contactId,
        lookupKey = getStringValueOr(ContactsContract.Contacts.LOOKUP_KEY, ""),
        displayName = getStringValueOr(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ""),
        photoThumbnailUri = photoThumbnailUri,
        hasPhoneNumber = getIntValueOr(ContactsContract.Contacts.HAS_PHONE_NUMBER, 0) == 1,
        lastUpdatedTimestamp = getLongValueOr(
            ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
            0L,
        ),
    )
}

internal fun Cursor.toCallLogEntrySafe(isQPlus: Boolean): CallLogEntry? {
    val idIndex = getColumnIndex(CallLog.Calls._ID)
    if (idIndex == -1) return null
    val presentation = getIntValueOr(CallLog.Calls.NUMBER_PRESENTATION, CallLog.Calls.PRESENTATION_ALLOWED)
    val number = getStringValueOr(CallLog.Calls.NUMBER, "")
    val isUnknown = presentation == CallLog.Calls.PRESENTATION_UNKNOWN ||
        presentation == CallLog.Calls.PRESENTATION_UNAVAILABLE
    val type = getIntValueOr(CallLog.Calls.TYPE, CallLog.Calls.MISSED_TYPE)
    val accountId = getStringValueOr(CallLog.Calls.PHONE_ACCOUNT_ID, "")
    return CallLogEntry(
        callId = getInt(idIndex),
        phoneNumber = number,
        cachedName = getStringValueOr(CallLog.Calls.CACHED_NAME, ""),
        cachedPhotoUri = getStringValueOr(CallLog.Calls.CACHED_PHOTO_URI, ""),
        startTS = getLongValueOr(CallLog.Calls.DATE, 0L) / 1000,
        duration = getIntValueOr(CallLog.Calls.DURATION, 0),
        type = type,
        simID = 0,
        features = getColumnIndex(CallLog.Calls.FEATURES).takeIf { it >= 0 }?.let { getInt(it) },
        isUnknownNumber = isUnknown,
        isVoiceMail = type == CallLog.Calls.VOICEMAIL_TYPE,
        blockReason = if (isQPlus) getIntValueOr(CallLog.Calls.BLOCK_REASON, 0) else 0,
        phoneAccountId = accountId,
        cachedNormalizedNumber = getStringValueOr(CallLog.Calls.CACHED_NORMALIZED_NUMBER, ""),
    )
}
