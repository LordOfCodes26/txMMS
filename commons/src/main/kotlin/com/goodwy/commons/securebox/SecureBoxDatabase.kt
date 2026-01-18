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
                                .allowMainThreadQueries() // Allow queries on main thread for secure box operations
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
                                .allowMainThreadQueries() // Allow queries on main thread for secure box operations
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
            // On Android 11+ (especially 14+), prioritize app-specific directories
            // which don't require special permissions and work reliably
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+: Use app-specific external files directory first
                // This location works reliably without special permissions
                val externalFilesDir = context.getExternalFilesDir(null)
                if (externalFilesDir != null) {
                    val secureBoxDirExternal = File(externalFilesDir, FOLDER_NAME)
                    android.util.Log.i("SecureBoxDatabase", 
                        "Using external files dir (Android 11+): ${secureBoxDirExternal.absolutePath}")
                    return createSecureBoxDirectory(secureBoxDirExternal, DATABASE_NAME)
                }
                
                // Only try external storage root if we have MANAGE_EXTERNAL_STORAGE permission
                // This is required for reliable database access on Android 11+
                val hasManagePermission = try {
                    Environment.isExternalStorageManager()
                } catch (e: Exception) {
                    false
                }
                
                if (hasManagePermission) {
                    val externalStorage = Environment.getExternalStorageDirectory()
                    val secureBoxDirRoot = File(externalStorage, FOLDER_NAME)
                    // Verify we can actually write (not just create test files)
                    if (canWriteToDirectory(secureBoxDirRoot)) {
                        android.util.Log.i("SecureBoxDatabase", 
                            "Using external storage root (with MANAGE_EXTERNAL_STORAGE): ${secureBoxDirRoot.absolutePath}")
                        return createSecureBoxDirectory(secureBoxDirRoot, DATABASE_NAME)
                    }
                } else {
                    android.util.Log.d("SecureBoxDatabase", 
                        "MANAGE_EXTERNAL_STORAGE not granted, using app-specific directory")
                }
                
                // Fallback to internal storage if external files dir is not available
                val internalDir = File(context.getFilesDir(), FOLDER_NAME)
                android.util.Log.w("SecureBoxDatabase", 
                    "Using internal storage (fallback): ${internalDir.absolutePath}")
                return createSecureBoxDirectory(internalDir, DATABASE_NAME)
            }
            
            // Android 10 and below: Try external storage root first
            val externalStorage = Environment.getExternalStorageDirectory()
            val secureBoxDirRoot = File(externalStorage, FOLDER_NAME)
            
            // Check permissions based on Android version
            val canAccessRoot = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
                    // Android < 10: Need WRITE_EXTERNAL_STORAGE permission
                    context.hasPermission(PERMISSION_WRITE_STORAGE) && canWriteToDirectory(secureBoxDirRoot)
                }
                else -> {
                    // Android 10: Scoped storage, try anyway (may work on some devices)
                    canWriteToDirectory(secureBoxDirRoot)
                }
            }
            
            if (canAccessRoot) {
                android.util.Log.i("SecureBoxDatabase", 
                    "Using external storage root (Android < 11): ${secureBoxDirRoot.absolutePath}")
                return createSecureBoxDirectory(secureBoxDirRoot, DATABASE_NAME)
            }

            // Fallback to external files dir
            val externalFilesDir = context.getExternalFilesDir(null)
            if (externalFilesDir != null) {
                val secureBoxDirExternal = File(externalFilesDir, FOLDER_NAME)
                android.util.Log.w("SecureBoxDatabase", 
                    "Using external files dir (fallback): ${secureBoxDirExternal.absolutePath}")
                return createSecureBoxDirectory(secureBoxDirExternal, DATABASE_NAME)
            }

            // Final fallback: internal storage
            val internalDir = File(context.getFilesDir(), FOLDER_NAME)
            android.util.Log.w("SecureBoxDatabase", 
                "Using internal storage (final fallback): ${internalDir.absolutePath}")
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
                    val created = directory.mkdirs()
                    if (!created && !directory.exists()) {
                        android.util.Log.e("SecureBoxDatabase", "Failed to create directory: ${directory.absolutePath}")
                    }
                }
                // Verify directory is writable
                if (!directory.canWrite()) {
                    android.util.Log.w("SecureBoxDatabase", "Directory is not writable: ${directory.absolutePath}")
                }
                // Create .nomedia file to hide from media scanners (optional)
                try {
                    val nomediaFile = File(directory, ".nomedia")
                    if (!nomediaFile.exists()) {
                        nomediaFile.createNewFile()
                    }
                } catch (e: Exception) {
                    // Ignore - .nomedia file creation is optional
                    android.util.Log.d("SecureBoxDatabase", "Could not create .nomedia file", e)
                }
            } catch (e: Exception) {
                // Log error but continue - Room will handle the error if it can't create the database
                android.util.Log.e("SecureBoxDatabase", "Error creating secure box directory: ${directory.absolutePath}", e)
            }
            return File(directory, dbName)
        }

        fun destroyInstance() {
            db = null
        }
    }
}


