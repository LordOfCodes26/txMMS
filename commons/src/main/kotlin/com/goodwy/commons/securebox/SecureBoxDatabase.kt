package com.goodwy.commons.securebox

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.helpers.PERMISSION_WRITE_STORAGE
import com.goodwy.commons.helpers.isQPlus
import java.io.File

@Database(
    entities = [SecureBoxCall::class, SecureBoxContact::class],
    version = 1,
    exportSchema = false
)
abstract class SecureBoxDatabase : RoomDatabase() {

    abstract fun SecureBoxCallDao(): SecureBoxCallDao
    abstract fun SecureBoxContactDao(): SecureBoxContactDao

    companion object {
        // Use hidden folder name (starts with dot) and non-obvious database name
        private const val DATABASE_NAME = ".sys_cache.db"
        private const val FOLDER_NAME = ".android_system"
        private var db: SecureBoxDatabase? = null

        fun getInstance(context: Context): SecureBoxDatabase {
            if (db == null) {
                synchronized(SecureBoxDatabase::class) {
                    if (db == null) {
                        // Try to get a persistent location, with fallbacks
                        val dbFile = getPersistentDatabaseFile(context)
                        
                        // Use RoomDatabase.Builder with custom database file path
                        // TODO: For production, consider using SQLCipher for encryption:
                        // .openHelperFactory(SQLiteOpenHelperFactory(SQLCipherUtils.getSQLCipherOptions()))
                        try {
                            db = Room.databaseBuilder(
                                context.applicationContext,
                                SecureBoxDatabase::class.java,
                                dbFile.absolutePath
                            )
                                .fallbackToDestructiveMigration()
                                .build()
                        } catch (e: Exception) {
                            // If we can't open the database at the preferred location,
                            // fall back to external files dir (works but will be deleted on app data clear)
                            android.util.Log.w("SecureBoxDatabase", 
                                "Failed to open database at ${dbFile.absolutePath}, falling back to external files dir", e)
                            
                            val fallbackDir = context.getExternalFilesDir(null) 
                                ?: File(context.getFilesDir(), FOLDER_NAME)
                            val fallbackFile = createSecureBoxDirectory(fallbackDir, DATABASE_NAME)
                            
                            db = Room.databaseBuilder(
                                context.applicationContext,
                                SecureBoxDatabase::class.java,
                                fallbackFile.absolutePath
                            )
                                .fallbackToDestructiveMigration()
                                .build()
                            
                            android.util.Log.w("SecureBoxDatabase", 
                                "Using fallback location (will be deleted on app data clear): ${fallbackFile.absolutePath}")
                        }
                    }
                }
            }
            return db!!
        }

        /**
         * Get persistent database file location that survives app data clear
         * Priority order:
         * 1. External storage root (/storage/emulated/0/.android_system/) - BEST persistence
         * 2. Documents directory (/storage/emulated/0/Documents/.android_system/) - Good persistence, more accessible
         * 3. External files dir - Fallback (will be deleted on app data clear)
         * 
         * Note: On Android 10+, writing to external storage root may require MANAGE_EXTERNAL_STORAGE permission.
         * Documents directory is more accessible and also persists after app data clear.
         */
        private fun getPersistentDatabaseFile(context: Context): File {
            val externalStorage = Environment.getExternalStorageDirectory()
            
            // Strategy 1: Try external storage root - BEST for persistence
            // This location is NOT in /Android/data/ so it survives app data clear
            // Path: /storage/emulated/0/.android_system/.sys_cache.db
            val secureBoxDirRoot = File(externalStorage, FOLDER_NAME)
            
            // Check permissions based on Android version
            val canAccessRoot = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
                    // Android < 10: Need WRITE_EXTERNAL_STORAGE permission
                    context.hasPermission(PERMISSION_WRITE_STORAGE) && canWriteToDirectory(secureBoxDirRoot)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    // Android 11+: Check if we have MANAGE_EXTERNAL_STORAGE or try anyway
                    val hasManagePermission = try {
                        Environment.isExternalStorageManager()
                    } catch (e: Exception) {
                        false
                    }
                    (hasManagePermission || canWriteToDirectory(secureBoxDirRoot))
                }
                else -> {
                    // Android 10: Scoped storage, try anyway (may work on some devices)
                    canWriteToDirectory(secureBoxDirRoot)
                }
            }
            
            if (canAccessRoot) {
                android.util.Log.i("SecureBoxDatabase", "Using external storage root (persists): ${secureBoxDirRoot.absolutePath}")
                return createSecureBoxDirectory(secureBoxDirRoot, DATABASE_NAME)
            }

            // Strategy 2: Skip Documents directory - SQLite cannot reliably open files there on Android 10+
            // Even though we can create test files, SQLite needs proper permissions to open database files
            // which are not available in Documents directory due to scoped storage restrictions

            // Strategy 3: Fallback to external files dir
            // WARNING: This is in /Android/data/package/files/ and WILL be deleted on app data clear
            // Only use if we can't access external storage root
            val externalFilesDir = context.getExternalFilesDir(null)
            if (externalFilesDir != null) {
                val secureBoxDirExternal = File(externalFilesDir, FOLDER_NAME)
                // Log warning that this location will be deleted
                android.util.Log.w("SecureBoxDatabase", 
                    "Using external files dir - database will be deleted when app data is cleared: ${secureBoxDirExternal.absolutePath}")
                return createSecureBoxDirectory(secureBoxDirExternal, DATABASE_NAME)
            }

            // Final fallback: internal storage (will definitely be deleted)
            val internalDir = File(context.getFilesDir(), FOLDER_NAME)
            android.util.Log.w("SecureBoxDatabase", 
                "Using internal storage - database will be deleted when app data is cleared: ${internalDir.absolutePath}")
            return createSecureBoxDirectory(internalDir, DATABASE_NAME)
        }

        private fun canWriteToDirectory(directory: File): Boolean {
            return try {
                // Ensure parent directory exists
                val parent = directory.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                
                if (!directory.exists()) {
                    val created = directory.mkdirs()
                    if (!created && !directory.exists()) {
                        return false
                    }
                }
                
                // Test write permission
                val testFile = File(directory, ".test_write_${System.currentTimeMillis()}")
                val created = testFile.createNewFile()
                if (created) {
                    testFile.delete()
                }
                created
            } catch (e: Exception) {
                // Log error for debugging but don't crash
                android.util.Log.w("SecureBoxDatabase", "Cannot write to directory: ${directory.absolutePath}", e)
                false
            }
        }

        private fun createSecureBoxDirectory(directory: File, dbName: String): File {
            try {
                if (!directory.exists()) {
                    directory.mkdirs()
                }
                // Create .nomedia file to hide from media scanners (optional)
                try {
                    val nomediaFile = File(directory, ".nomedia")
                    if (!nomediaFile.exists()) {
                        nomediaFile.createNewFile()
                    }
                } catch (e: Exception) {
                    // Ignore - .nomedia file creation is optional
                }
            } catch (e: Exception) {
                // If directory creation fails, try to use it anyway
                // Room will handle the error if it can't create the database
            }
            return File(directory, dbName)
        }

        fun destroyInstance() {
            db = null
        }
    }
}


