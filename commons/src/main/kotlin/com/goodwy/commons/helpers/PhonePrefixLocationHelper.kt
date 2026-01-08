package com.goodwy.commons.helpers

import android.content.Context
import com.goodwy.commons.databases.PhoneNumberDatabase
import com.goodwy.commons.models.PhoneDistrict
import com.goodwy.commons.models.PhoneNumberFormat
import com.goodwy.commons.models.PhonePrefixLocation
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStream

/**
 * Helper class to manage phone prefix to location mappings and district mappings
 */
class PhonePrefixLocationHelper(private val context: Context) {
    private val db = PhoneNumberDatabase.getInstance(context)
    private val locationDao = db.PhonePrefixLocationDao()
    private val districtDao = db.PhoneDistrictDao()
    private val formatDao = db.PhoneNumberFormatDao()

    /**
     * Check if prefix locations are already loaded in the database
     */
    fun hasPrefixLocations(callback: (Boolean) -> Unit) {
        ensureBackgroundThread {
            val count = locationDao.getAllPrefixLocations().size
            callback(count > 0)
        }
    }

    /**
     * Check if districts are already loaded in the database
     */
    fun hasDistricts(callback: (Boolean) -> Unit) {
        ensureBackgroundThread {
            val count = districtDao.getAllDistricts().size
            callback(count > 0)
        }
    }

    /**
     * Load prefix locations from a JSON file in assets folder
     * JSON format: [{"prefix": "01", "location": ""}, {"prefix": "02", "location": "Busan"}, ...]
     */
    fun loadFromAssets(fileName: String = "phone_prefix_locations.json", callback: ((Int) -> Unit)? = null) {
        ensureBackgroundThread {
            try {
                val inputStream: InputStream = context.assets.open(fileName)
                val jsonString = inputStream.bufferedReader().use { it.readText() }
                
                val type = object : TypeToken<List<PhonePrefixLocationData>>() {}.type
                val locations: List<PhonePrefixLocationData> = Gson().fromJson(jsonString, type)
                
                val prefixLocations = locations.map { 
                    PhonePrefixLocation(id = null, prefix = it.prefix, location = it.location)
                }
                
                val inserted = locationDao.insertOrUpdatePrefixLocations(prefixLocations)
                
                callback?.invoke(inserted.size)
            } catch (e: Exception) {
                e.printStackTrace()
                callback?.invoke(0)
            }
        }
    }

    /**
     * Load prefix locations from a JSON file in raw folder
     * JSON format: [{"prefix": "01", "location": ""}, {"prefix": "02", "location": "Busan"}, ...]
     */
    fun loadFromRaw(resourceId: Int, callback: ((Int) -> Unit)? = null) {
        ensureBackgroundThread {
            try {
                val inputStream: InputStream = context.resources.openRawResource(resourceId)
                val jsonString = inputStream.bufferedReader().use { it.readText() }
                
                val type = object : TypeToken<List<PhonePrefixLocationData>>() {}.type
                val locations: List<PhonePrefixLocationData> = Gson().fromJson(jsonString, type)
                
                val prefixLocations = locations.map { 
                    PhonePrefixLocation(id = null, prefix = it.prefix, location = it.location)
                }
                
                val inserted = locationDao.insertOrUpdatePrefixLocations(prefixLocations)
                
                callback?.invoke(inserted.size)
            } catch (e: Exception) {
                e.printStackTrace()
                callback?.invoke(0)
            }
        }
    }

    /**
     * Insert prefix locations programmatically
     */
    fun insertPrefixLocations(locations: List<PhonePrefixLocation>, callback: ((Int) -> Unit)? = null) {
        ensureBackgroundThread {
            val inserted = locationDao.insertOrUpdatePrefixLocations(locations)
            callback?.invoke(inserted.size)
        }
    }

    /**
     * Insert a single prefix location
     */
    fun insertPrefixLocation(prefix: String, location: String, callback: ((Long) -> Unit)? = null) {
        ensureBackgroundThread {
            val prefixLocation = PhonePrefixLocation(id = null, prefix = prefix, location = location)
            val id = locationDao.insertOrUpdatePrefixLocation(prefixLocation)
            callback?.invoke(id)
        }
    }

    /**
     * Get all prefix locations
     */
    fun getAllPrefixLocations(callback: (List<PhonePrefixLocation>) -> Unit) {
        ensureBackgroundThread {
            val locations = locationDao.getAllPrefixLocations()
            callback(locations)
        }
    }

    /**
     * Delete all prefix locations
     */
    fun deleteAllPrefixLocations(callback: (() -> Unit)? = null) {
        ensureBackgroundThread {
            locationDao.deleteAllPrefixLocations()
            callback?.invoke()
        }
    }

    /**
     * Load districts from a JSON file in assets folder
     * JSON format: [{"prefix": "01", "districtCode": "234", "districtName": "Gangnam"}, ...]
     */
    fun loadDistrictsFromAssets(fileName: String = "phone_districts.json", callback: ((Int) -> Unit)? = null) {
        ensureBackgroundThread {
            try {
                val inputStream: InputStream = context.assets.open(fileName)
                val jsonString = inputStream.bufferedReader().use { it.readText() }
                
                val type = object : TypeToken<List<PhoneDistrictData>>() {}.type
                val districts: List<PhoneDistrictData> = Gson().fromJson(jsonString, type)
                
                val phoneDistricts = districts.map { 
                    PhoneDistrict(id = null, prefix = it.prefix, districtCode = it.districtCode, districtName = it.districtName)
                }
                
                val inserted = districtDao.insertOrUpdateDistricts(phoneDistricts)
                
                callback?.invoke(inserted.size)
            } catch (e: Exception) {
                e.printStackTrace()
                callback?.invoke(0)
            }
        }
    }

    /**
     * Load districts from a JSON file in raw folder
     * JSON format: [{"prefix": "01", "districtCode": "234", "districtName": "Gangnam"}, ...]
     */
    fun loadDistrictsFromRaw(resourceId: Int, callback: ((Int) -> Unit)? = null) {
        ensureBackgroundThread {
            try {
                val inputStream: InputStream = context.resources.openRawResource(resourceId)
                val jsonString = inputStream.bufferedReader().use { it.readText() }
                
                val type = object : TypeToken<List<PhoneDistrictData>>() {}.type
                val districts: List<PhoneDistrictData> = Gson().fromJson(jsonString, type)
                
                val phoneDistricts = districts.map { 
                    PhoneDistrict(id = null, prefix = it.prefix, districtCode = it.districtCode, districtName = it.districtName)
                }
                
                val inserted = districtDao.insertOrUpdateDistricts(phoneDistricts)
                
                callback?.invoke(inserted.size)
            } catch (e: Exception) {
                e.printStackTrace()
                callback?.invoke(0)
            }
        }
    }

    /**
     * Insert districts programmatically
     */
    fun insertDistricts(districts: List<PhoneDistrict>, callback: ((Int) -> Unit)? = null) {
        ensureBackgroundThread {
            val inserted = districtDao.insertOrUpdateDistricts(districts)
            callback?.invoke(inserted.size)
        }
    }

    /**
     * Insert a single district
     */
    fun insertDistrict(prefix: String, districtCode: String, districtName: String, callback: ((Long) -> Unit)? = null) {
        ensureBackgroundThread {
            val district = PhoneDistrict(id = null, prefix = prefix, districtCode = districtCode, districtName = districtName)
            val id = districtDao.insertOrUpdateDistrict(district)
            callback?.invoke(id)
        }
    }

    /**
     * Get all districts
     */
    fun getAllDistricts(callback: (List<PhoneDistrict>) -> Unit) {
        ensureBackgroundThread {
            val districts = districtDao.getAllDistricts()
            callback(districts)
        }
    }

    /**
     * Get districts by prefix
     */
    fun getDistrictsByPrefix(prefix: String, callback: (List<PhoneDistrict>) -> Unit) {
        ensureBackgroundThread {
            val districts = districtDao.getDistrictsByPrefix(prefix)
            callback(districts)
        }
    }

    /**
     * Delete all districts
     */
    fun deleteAllDistricts(callback: (() -> Unit)? = null) {
        ensureBackgroundThread {
            districtDao.deleteAllDistricts()
            callback?.invoke()
        }
    }

    /**
     * Load phone number formats from a JSON file in raw folder
     * JSON format: [{"prefix": "01", "prefixLength": 2, "districtCodePattern": "4XX", "formatTemplate": "01-XXX-XXXX", "districtCodeLength": 3, "description": "Area format"}, ...]
     */
    fun loadFormatsFromRaw(resourceId: Int, callback: ((Int) -> Unit)? = null) {
        ensureBackgroundThread {
            try {
                val inputStream: InputStream = context.resources.openRawResource(resourceId)
                val jsonString = inputStream.bufferedReader().use { it.readText() }
                
                val type = object : TypeToken<List<PhoneNumberFormatData>>() {}.type
                val formats: List<PhoneNumberFormatData> = Gson().fromJson(jsonString, type)
                
                val phoneFormats = formats.map { 
                    PhoneNumberFormat(
                        id = null,
                        prefix = it.prefix,
                        prefixLength = it.prefixLength,
                        districtCodePattern = it.districtCodePattern,
                        formatTemplate = it.formatTemplate,
                        districtCodeLength = it.districtCodeLength,
                        description = it.description ?: ""
                    )
                }
                
                val inserted = formatDao.insertOrUpdateFormats(phoneFormats)
                
                // Invalidate the formatter cache so it reloads the new formats
                try {
                    // Try to invalidate cache if DatabasePhoneNumberFormatter is being used
                    val formatter = com.goodwy.commons.helpers.PhoneNumberFormatManager.customFormatter
                    if (formatter is com.goodwy.commons.helpers.DatabasePhoneNumberFormatter) {
                        formatter.invalidateCache()
                        android.util.Log.d("PhonePrefixLocationHelper", "Invalidated formatter cache after loading ${inserted.size} formats")
                    }
                } catch (e: Exception) {
                    // Ignore if formatter is not set or not DatabasePhoneNumberFormatter
                }
                
                callback?.invoke(inserted.size)
            } catch (e: Exception) {
                e.printStackTrace()
                callback?.invoke(0)
            }
        }
    }

    /**
     * Check if formats are already loaded in the database
     */
    fun hasFormats(callback: (Boolean) -> Unit) {
        ensureBackgroundThread {
            val count = formatDao.getAllFormats().size
            callback(count > 0)
        }
    }

    /**
     * Data class for JSON parsing - Prefix Locations
     */
    private data class PhonePrefixLocationData(
        val prefix: String,
        val location: String
    )

    /**
     * Data class for JSON parsing - Districts
     */
    private data class PhoneDistrictData(
        val prefix: String,
        val districtCode: String,
        val districtName: String
    )

    /**
     * Data class for JSON parsing - Phone Number Formats
     */
    private data class PhoneNumberFormatData(
        val prefix: String,
        val prefixLength: Int,
        val districtCodePattern: String,
        val formatTemplate: String,
        val districtCodeLength: Int,
        val description: String? = null
    )
}

