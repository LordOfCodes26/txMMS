package com.goodwy.commons.helpers

import android.util.Log

object AvatarBindLogger {

    private const val TAG = "AvatarBind"

    enum class Surface {
        CONTACTS,
        RECENTS,
        SEARCH,
        DETAIL,
    }

    enum class SourceType {
        PHOTO,
        MONOGRAM,
        PROFILE,
        DRAWABLE,
    }

    fun bind(
        surface: Surface,
        contactId: Int,
        source: SourceType,
        version: Long,
        rawContactId: Int = 0,
        photoUri: String = "",
        drawableIndex: Int? = null,
    ) {
        Log.d(
            TAG,
            "avatarBind surface=$surface contactId=$contactId rawId=$rawContactId source=$source " +
                "drawableIndex=$drawableIndex version=$version uri=${photoUri.take(60)}",
        )
    }

    fun photoRejected(contactId: Int, uri: String, reason: String) {
        Log.d(TAG, "avatarPhotoRejected contactId=$contactId uri=${uri.take(80)} reason=$reason")
    }

    fun photoLoadFailed(surface: Surface, contactId: Int, uri: String) {
        Log.d(TAG, "avatarPhotoLoadFailed surface=$surface contactId=$contactId uri=${uri.take(80)}")
    }

    fun invalidated(contactId: Int, versionOld: Long, versionNew: Long) {
        Log.d(TAG, "avatarInvalidated contactId=$contactId versionOld=$versionOld versionNew=$versionNew")
    }

    fun photoEditApplied(contactId: Int, uriChanged: Boolean, version: Long) {
        Log.d(TAG, "avatarPhotoEditApplied contactId=$contactId uriChanged=$uriChanged version=$version")
    }

    fun bindSkipped(reason: String) {
        Log.d(TAG, "avatarBindSkipped reason=$reason")
    }

    fun payloadRebind(surface: Surface, contactId: Int) {
        Log.d(TAG, "avatarPayloadRebind surface=$surface contactId=$contactId")
    }
}
