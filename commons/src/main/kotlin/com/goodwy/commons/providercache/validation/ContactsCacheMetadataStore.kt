package com.goodwy.commons.providercache.validation

import android.content.Context

class ContactsCacheMetadataStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getCacheSchemaVersion(): Int = prefs.getInt(KEY_SCHEMA_VERSION, 0)

    fun setCacheSchemaVersion(version: Int) {
        prefs.edit().putInt(KEY_SCHEMA_VERSION, version).apply()
    }

    companion object {
        private const val PREFS_NAME = "contacts_cache_meta"
        private const val KEY_SCHEMA_VERSION = "cache_schema_version"
    }
}
