package com.goodwy.commons.helpers

import java.util.concurrent.ConcurrentHashMap

/** Per-raw-contact Glide cache bust token updated when avatar URIs or [ContactsContract.Contacts.PHOTO_ID] change. */
object ContactAvatarPhotoVersionTracker {

    private data class PhotoVersion(val photoUpdatedAt: Long, val photoId: Long)

    private val versions = ConcurrentHashMap<Int, PhotoVersion>()

    fun getPhotoId(rawId: Int): Long = versions[rawId]?.photoId ?: 0L

    /** Glide signature derived from [photoUpdatedAt] xor [photoId]. */
    fun getSignature(rawId: Int): Long {
        val version = versions[rawId] ?: return 0L
        return version.photoUpdatedAt xor version.photoId
    }

    /** @deprecated Use [getSignature]; kept for call sites that treated the value as opaque. */
    fun get(rawId: Int): Long = getSignature(rawId)

    fun record(rawId: Int, photoId: Long): Long {
        val photoUpdatedAt = System.currentTimeMillis()
        versions[rawId] = PhotoVersion(photoUpdatedAt, photoId)
        return photoUpdatedAt xor photoId
    }

    fun bump(rawId: Int): Long = record(rawId, getPhotoId(rawId))

    fun removeRawId(rawId: Int) {
        versions.remove(rawId)
    }

    fun clearAll() {
        versions.clear()
    }
}
