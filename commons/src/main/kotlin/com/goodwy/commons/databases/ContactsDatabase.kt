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
import com.goodwy.commons.interfaces.ContactPosterDao
import com.goodwy.commons.interfaces.ContactsDao
import com.goodwy.commons.interfaces.GroupsDao
import com.goodwy.commons.models.contacts.ContactPoster
import com.goodwy.commons.models.contacts.Group
import com.goodwy.commons.models.contacts.LocalContact
import java.util.concurrent.Executors

@Database(entities = [LocalContact::class, Group::class, ContactPoster::class], version = 5, exportSchema = true)
@TypeConverters(Converters::class)
abstract class ContactsDatabase : RoomDatabase() {

    abstract fun ContactsDao(): ContactsDao

    abstract fun GroupsDao(): GroupsDao

    abstract fun ContactPosterDao(): ContactPosterDao

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

    }
}
