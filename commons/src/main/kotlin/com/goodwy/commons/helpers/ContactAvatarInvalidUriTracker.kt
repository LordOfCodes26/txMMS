package com.goodwy.commons.helpers

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory set of contact photo URIs that failed to load (e.g. [java.io.FileNotFoundException]).
 * Prevents repeated Glide attempts for the same broken URI during a session.
 */
object ContactAvatarInvalidUriTracker {

    private val invalidRawIds = ConcurrentHashMap.newKeySet<Int>()
    private val invalidUris = ConcurrentHashMap.newKeySet<String>()

    fun isInvalid(rawId: Int, uri: String): Boolean {
        if (rawId in invalidRawIds) return true
        return isInvalidUri(uri)
    }

    fun isInvalidUri(uri: String): Boolean = uri.isNotBlank() && uri in invalidUris

    fun markInvalid(rawId: Int, uri: String) {
        invalidRawIds.add(rawId)
        if (uri.isNotBlank()) {
            invalidUris.add(uri)
        }
    }

    fun removeRawId(rawId: Int) {
        invalidRawIds.remove(rawId)
    }

    fun clearAll() {
        invalidRawIds.clear()
        invalidUris.clear()
    }
}
