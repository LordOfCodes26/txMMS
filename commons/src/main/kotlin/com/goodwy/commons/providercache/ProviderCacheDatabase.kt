package com.goodwy.commons.providercache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.goodwy.commons.providercache.dao.CacheMetadataDao
import com.goodwy.commons.providercache.dao.CallLogDao
import com.goodwy.commons.providercache.dao.ContactDao
import com.goodwy.commons.providercache.dao.ContactDisplayCacheDao
import com.goodwy.commons.providercache.dao.ContactPhoneIndexDao
import com.goodwy.commons.providercache.dao.ContactSearchIndexDao
import com.goodwy.commons.providercache.dao.RecentDisplayCacheDao
import com.goodwy.commons.providercache.dao.RecentGroupCallDao
import com.goodwy.commons.providercache.dao.RecentGroupDao
import com.goodwy.commons.providercache.dao.RecentGroupNumberDao
import com.goodwy.commons.providercache.entities.CacheMetadataEntity
import com.goodwy.commons.providercache.entities.CallLogEntity
import com.goodwy.commons.providercache.entities.ContactDetailEntity
import com.goodwy.commons.providercache.entities.ContactDisplayCacheEntity
import com.goodwy.commons.providercache.entities.ContactEmailEntity
import com.goodwy.commons.providercache.entities.ContactPhoneEntity
import com.goodwy.commons.providercache.entities.ContactPhoneIndexEntity
import com.goodwy.commons.providercache.entities.ContactSearchIndexEntity
import com.goodwy.commons.providercache.entities.ContactSummaryEntity
import com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity
import com.goodwy.commons.providercache.entities.RecentGroupCallEntity
import com.goodwy.commons.providercache.entities.RecentGroupEntity
import com.goodwy.commons.providercache.entities.RecentGroupNumberEntity

@Database(
    entities = [
        ContactSummaryEntity::class,
        ContactDetailEntity::class,
        ContactPhoneEntity::class,
        ContactEmailEntity::class,
        ContactPhoneIndexEntity::class,
        ContactSearchIndexEntity::class,
        CallLogEntity::class,
        ContactDisplayCacheEntity::class,
        RecentDisplayCacheEntity::class,
        RecentGroupEntity::class,
        RecentGroupCallEntity::class,
        RecentGroupNumberEntity::class,
        CacheMetadataEntity::class,
    ],
    version = 19,
    exportSchema = true,
)
abstract class ProviderCacheDatabase : RoomDatabase() {

    abstract fun contactDao(): ContactDao

    abstract fun contactPhoneIndexDao(): ContactPhoneIndexDao

    abstract fun contactSearchIndexDao(): ContactSearchIndexDao

    abstract fun callLogDao(): CallLogDao

    abstract fun contactDisplayCacheDao(): ContactDisplayCacheDao

    abstract fun recentDisplayCacheDao(): RecentDisplayCacheDao

    abstract fun recentGroupDao(): RecentGroupDao

    abstract fun recentGroupCallDao(): RecentGroupCallDao

    abstract fun recentGroupNumberDao(): RecentGroupNumberDao

    abstract fun cacheMetadataDao(): CacheMetadataDao

    companion object {
        private const val DB_NAME = "provider_cache.db"

        @Volatile
        private var instance: ProviderCacheDatabase? = null

        fun getInstanceOrNull(): ProviderCacheDatabase? = instance

        fun getInstance(context: Context): ProviderCacheDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ProviderCacheDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(*ProviderCacheMigrations.ALL)
                    .build().also { instance = it }
            }
        }

        fun destroyInstance() {
            instance = null
        }

        /** In-memory database for instrumented/unit Room tests. */
        fun createInMemory(context: Context): ProviderCacheDatabase =
            Room.inMemoryDatabaseBuilder(context, ProviderCacheDatabase::class.java)
                .allowMainThreadQueries()
                .addMigrations(*ProviderCacheMigrations.ALL)
                .build()
    }
}
