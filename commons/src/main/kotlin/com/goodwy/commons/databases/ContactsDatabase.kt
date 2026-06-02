package com.goodwy.commons.databases

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.goodwy.commons.helpers.Converters
import com.goodwy.commons.helpers.FIRST_CONTACT_ID
import com.goodwy.commons.helpers.FIRST_GROUP_ID
import com.goodwy.commons.helpers.getEmptyLocalContact
import com.goodwy.commons.interfaces.AvatarStyleDao
import com.goodwy.commons.interfaces.ContactPosterDao
import com.goodwy.commons.interfaces.ContactsDao
import com.goodwy.commons.interfaces.GroupsDao
import com.goodwy.commons.interfaces.PosterDao
import com.goodwy.commons.models.contacts.AvatarStyleEntity
import com.goodwy.commons.models.contacts.ContactPoster
import com.goodwy.commons.models.contacts.Group
import com.goodwy.commons.models.contacts.LocalContact
import com.goodwy.commons.models.contacts.PosterEntity
import java.util.concurrent.Executors

@Database(entities = [LocalContact::class, Group::class, ContactPoster::class, PosterEntity::class, AvatarStyleEntity::class], version = 10, exportSchema = true)
@TypeConverters(Converters::class)
abstract class ContactsDatabase : RoomDatabase() {

    abstract fun ContactsDao(): ContactsDao

    abstract fun GroupsDao(): GroupsDao

    abstract fun ContactPosterDao(): ContactPosterDao

    abstract fun PosterDao(): PosterDao

    abstract fun AvatarStyleDao(): AvatarStyleDao

    companion object {
        private var db: ContactsDatabase? = null

        fun getInstance(context: Context): ContactsDatabase {
            if (db == null) {
                synchronized(ContactsDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(context.applicationContext, ContactsDatabase::class.java, "local_contacts.db")
                            .addCallback(object : Callback() {
                                override fun onCreate(db: SupportSQLiteDatabase) {
                                    super.onCreate(db)
                                    increaseAutoIncrementIds()
                                }
                            })
                            .addMigrations(MIGRATION_1_2)
                            .addMigrations(MIGRATION_2_3)
                            .addMigrations(MIGRATION_3_4)
                            .addMigrations(MIGRATION_4_5)
                            .addMigrations(MIGRATION_5_6)
                            .addMigrations(MIGRATION_6_7)
                            .addMigrations(MIGRATION_7_8)
                            .addMigrations(MIGRATION_8_9)
                            .addMigrations(MIGRATION_9_10)
                            .build()
                    }
                }
            }
            return db!!
        }

        fun destroyInstance() {
            db = null
        }

        // start autoincrement ID from FIRST_CONTACT_ID/FIRST_GROUP_ID to avoid conflicts
        // Room doesn't seem to have a built in way for it, so just create a contact/group and delete it
        private fun increaseAutoIncrementIds() {
            Executors.newSingleThreadExecutor().execute {
                val emptyContact = getEmptyLocalContact()
                emptyContact.id = FIRST_CONTACT_ID
                db!!.ContactsDao().apply {
                    insertOrUpdate(emptyContact)
                    deleteContactId(FIRST_CONTACT_ID)
                }

                val emptyGroup = Group(FIRST_GROUP_ID, "")
                db!!.GroupsDao().apply {
                    insertOrUpdate(emptyGroup)
                    deleteGroupId(FIRST_GROUP_ID)
                }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL("ALTER TABLE contacts ADD COLUMN photo_uri TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL("ALTER TABLE contacts ADD COLUMN ringtone TEXT DEFAULT ''")
                }
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL("ALTER TABLE contacts ADD COLUMN relations TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    // Drop table if it exists (in case it was created with wrong schema)
                    execSQL("DROP TABLE IF EXISTS contact_posters")
                    execSQL("""
                        CREATE TABLE contact_posters (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            contact_id INTEGER NOT NULL,
                            image_uri TEXT NOT NULL,
                            scale REAL NOT NULL,
                            offset_x REAL NOT NULL,
                            offset_y REAL NOT NULL,
                            text_color INTEGER NOT NULL,
                            font_size REAL NOT NULL,
                            font_weight INTEGER NOT NULL,
                            text_alignment TEXT NOT NULL
                        )
                    """.trimIndent())
                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_contact_posters_contact_id ON contact_posters(contact_id)")
                }
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL("""
                        CREATE TABLE IF NOT EXISTS poster_configs (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            contact_id INTEGER NOT NULL,
                            background_type TEXT NOT NULL,
                            background_uri TEXT,
                            subject_mask_uri TEXT,
                            gradient_colors TEXT,
                            text_color INTEGER NOT NULL,
                            text_style TEXT NOT NULL,
                            name_layout_style INTEGER NOT NULL,
                            avatar_visible INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                    """.trimIndent())
                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_poster_configs_contact_id ON poster_configs(contact_id)")
                }
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL("""
                        CREATE TABLE IF NOT EXISTS avatar_style_configs (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            contact_id INTEGER NOT NULL,
                            source_type TEXT NOT NULL,
                            font_family TEXT,
                            font_weight INTEGER,
                            text_color INTEGER NOT NULL,
                            background_colors TEXT,
                            custom_photo_uri TEXT,
                            use_poster_subject INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                    """.trimIndent())
                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_avatar_style_configs_contact_id ON avatar_style_configs(contact_id)")
                }
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `contacts_new` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                            `prefix` TEXT NOT NULL,
                            `first_name` TEXT NOT NULL,
                            `middle_name` TEXT NOT NULL,
                            `surname` TEXT NOT NULL,
                            `suffix` TEXT NOT NULL,
                            `nickname` TEXT NOT NULL,
                            `photo` BLOB,
                            `photo_uri` TEXT NOT NULL,
                            `phone_numbers` TEXT NOT NULL,
                            `emails` TEXT NOT NULL,
                            `events` TEXT NOT NULL,
                            `starred` INTEGER NOT NULL,
                            `addresses` TEXT NOT NULL,
                            `notes` TEXT NOT NULL,
                            `groups` TEXT NOT NULL,
                            `company` TEXT NOT NULL,
                            `job_position` TEXT NOT NULL,
                            `relations` TEXT NOT NULL,
                            `ims` TEXT NOT NULL,
                            `ringtone` TEXT
                        )
                        """.trimIndent()
                    )
                    execSQL(
                        """
                        INSERT INTO `contacts_new` (
                            `id`, `prefix`, `first_name`, `middle_name`, `surname`, `suffix`, `nickname`,
                            `photo`, `photo_uri`, `phone_numbers`, `emails`, `events`, `starred`,
                            `addresses`, `notes`, `groups`, `company`, `job_position`,
                            `relations`, `ims`, `ringtone`
                        )
                        SELECT
                            `id`, `prefix`, `first_name`, `middle_name`, `surname`, `suffix`, `nickname`,
                            `photo`, `photo_uri`, `phone_numbers`, `emails`, `events`, `starred`,
                            `addresses`, `notes`, `groups`, `company`, `job_position`,
                            `relations`, `ims`, `ringtone`
                        FROM `contacts`
                        """.trimIndent()
                    )
                    execSQL("DROP TABLE `contacts`")
                    execSQL("ALTER TABLE `contacts_new` RENAME TO `contacts`")
                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_contacts_id` ON `contacts` (`id`)")
                }
            }
        }

        /**
         * Fixes 7->8 migration that used NOT NULL on [LocalContact.id]; Room expects nullable `id`
         * for `@PrimaryKey(autoGenerate = true) var id: Int?`.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `contacts_new` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                            `prefix` TEXT NOT NULL,
                            `first_name` TEXT NOT NULL,
                            `middle_name` TEXT NOT NULL,
                            `surname` TEXT NOT NULL,
                            `suffix` TEXT NOT NULL,
                            `nickname` TEXT NOT NULL,
                            `photo` BLOB,
                            `photo_uri` TEXT NOT NULL,
                            `phone_numbers` TEXT NOT NULL,
                            `emails` TEXT NOT NULL,
                            `events` TEXT NOT NULL,
                            `starred` INTEGER NOT NULL,
                            `addresses` TEXT NOT NULL,
                            `notes` TEXT NOT NULL,
                            `groups` TEXT NOT NULL,
                            `company` TEXT NOT NULL,
                            `job_position` TEXT NOT NULL,
                            `relations` TEXT NOT NULL,
                            `ims` TEXT NOT NULL,
                            `ringtone` TEXT
                        )
                        """.trimIndent()
                    )
                    execSQL(
                        """
                        INSERT INTO `contacts_new` (
                            `id`, `prefix`, `first_name`, `middle_name`, `surname`, `suffix`, `nickname`,
                            `photo`, `photo_uri`, `phone_numbers`, `emails`, `events`, `starred`,
                            `addresses`, `notes`, `groups`, `company`, `job_position`,
                            `relations`, `ims`, `ringtone`
                        )
                        SELECT
                            `id`, `prefix`, `first_name`, `middle_name`, `surname`, `suffix`, `nickname`,
                            `photo`, `photo_uri`, `phone_numbers`, `emails`, `events`, `starred`,
                            `addresses`, `notes`, `groups`, `company`, `job_position`,
                            `relations`, `ims`, `ringtone`
                        FROM `contacts`
                        """.trimIndent()
                    )
                    execSQL("DROP TABLE `contacts`")
                    execSQL("ALTER TABLE `contacts_new` RENAME TO `contacts`")
                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_contacts_id` ON `contacts` (`id`)")
                }
            }
        }

        /** Drops local `relations` column; relations are no longer stored in Room. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `contacts_new` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                            `prefix` TEXT NOT NULL,
                            `first_name` TEXT NOT NULL,
                            `middle_name` TEXT NOT NULL,
                            `surname` TEXT NOT NULL,
                            `suffix` TEXT NOT NULL,
                            `nickname` TEXT NOT NULL,
                            `photo` BLOB,
                            `photo_uri` TEXT NOT NULL,
                            `phone_numbers` TEXT NOT NULL,
                            `emails` TEXT NOT NULL,
                            `events` TEXT NOT NULL,
                            `starred` INTEGER NOT NULL,
                            `addresses` TEXT NOT NULL,
                            `notes` TEXT NOT NULL,
                            `groups` TEXT NOT NULL,
                            `company` TEXT NOT NULL,
                            `job_position` TEXT NOT NULL,
                            `ims` TEXT NOT NULL,
                            `ringtone` TEXT
                        )
                        """.trimIndent()
                    )
                    execSQL(
                        """
                        INSERT INTO `contacts_new` (
                            `id`, `prefix`, `first_name`, `middle_name`, `surname`, `suffix`, `nickname`,
                            `photo`, `photo_uri`, `phone_numbers`, `emails`, `events`, `starred`,
                            `addresses`, `notes`, `groups`, `company`, `job_position`,
                            `ims`, `ringtone`
                        )
                        SELECT
                            `id`, `prefix`, `first_name`, `middle_name`, `surname`, `suffix`, `nickname`,
                            `photo`, `photo_uri`, `phone_numbers`, `emails`, `events`, `starred`,
                            `addresses`, `notes`, `groups`, `company`, `job_position`,
                            `ims`, `ringtone`
                        FROM `contacts`
                        """.trimIndent()
                    )
                    execSQL("DROP TABLE `contacts`")
                    execSQL("ALTER TABLE `contacts_new` RENAME TO `contacts`")
                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_contacts_id` ON `contacts` (`id`)")
                }
            }
        }

    }
}
