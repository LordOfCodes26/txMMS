package com.goodwy.commons.databases

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.goodwy.commons.helpers.Converters
import com.goodwy.commons.interfaces.PhoneDistrictDao
import com.goodwy.commons.interfaces.PhoneNumberFormatDao
import com.goodwy.commons.interfaces.PhonePrefixLocationDao
import com.goodwy.commons.models.PhoneDistrict
import com.goodwy.commons.models.PhoneNumberFormat
import com.goodwy.commons.models.PhonePrefixLocation

@Database(entities = [PhonePrefixLocation::class, PhoneDistrict::class, PhoneNumberFormat::class], version = 6, exportSchema = true)
@TypeConverters(Converters::class)
abstract class PhoneNumberDatabase : RoomDatabase() {

    abstract fun PhonePrefixLocationDao(): PhonePrefixLocationDao
    abstract fun PhoneDistrictDao(): PhoneDistrictDao
    abstract fun PhoneNumberFormatDao(): PhoneNumberFormatDao

    companion object {
        private var db: PhoneNumberDatabase? = null

        fun getInstance(context: Context): PhoneNumberDatabase {
            synchronized(PhoneNumberDatabase::class) {
                if (db == null) {
                    db = Room.databaseBuilder(context.applicationContext, PhoneNumberDatabase::class.java, "phone_number.db")
                        .fallbackToDestructiveMigration()
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                        .allowMainThreadQueries() // Allow queries on main thread for formatting (formats are cached anyway)
                        .build()
                }
            }
            return db!!
        }

        fun destroyInstance() {
            db = null
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `phone_prefix_locations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `prefix` TEXT NOT NULL,
                        `location` TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_phone_prefix_locations_prefix` ON `phone_prefix_locations` (`prefix`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `phone_districts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `prefix` TEXT NOT NULL,
                        `districtCode` TEXT NOT NULL,
                        `districtName` TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_phone_districts_prefix_districtCode` ON `phone_districts` (`prefix`, `districtCode`)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `phone_number_formats` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `prefix` TEXT NOT NULL,
                        `districtCodePattern` TEXT NOT NULL,
                        `formatTemplate` TEXT NOT NULL,
                        `districtCodeLength` INTEGER NOT NULL,
                        `description` TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_phone_number_formats_prefix_districtCodePattern` ON `phone_number_formats` (`prefix`, `districtCodePattern`)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `phone_number_formats` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `prefix` TEXT NOT NULL,
                        `prefixLength` INTEGER NOT NULL,
                        `districtCodePattern` TEXT NOT NULL,
                        `formatTemplate` TEXT NOT NULL,
                        `districtCodeLength` INTEGER NOT NULL,
                        `description` TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_phone_number_formats_prefix_districtCodePattern` ON `phone_number_formats` (`prefix`, `districtCodePattern`)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Check if prefixLength column exists, if not add it
                try {
                    db.execSQL("ALTER TABLE `phone_number_formats` ADD COLUMN `prefixLength` INTEGER NOT NULL DEFAULT 2")
                } catch (e: Exception) {
                    // Column might already exist, ignore
                }
                // Update existing records to set prefixLength based on prefix length
                db.execSQL("UPDATE `phone_number_formats` SET `prefixLength` = LENGTH(`prefix`) WHERE `prefixLength` = 2 OR `prefixLength` IS NULL")
            }
        }
    }
}

