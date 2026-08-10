package com.goodwy.commons.providercache.validation

internal object ContactsCacheHash {
    fun computeIdsHash(ids: Collection<Int>): Long {
        var hash = 0L
        ids.sorted().forEach { id ->
            hash = hash * 31L + id
        }
        return hash
    }

    fun computeLookupKeysHash(lookupKeys: Collection<String>): Long {
        var hash = 0L
        lookupKeys.sorted().forEach { key ->
            hash = hash * 31L + key.hashCode()
        }
        return hash
    }
}
