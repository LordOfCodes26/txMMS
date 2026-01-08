package com.goodwy.commons.helpers

import android.content.Context
import android.telephony.PhoneNumberUtils
import com.goodwy.commons.databases.PhoneNumberDatabase
import com.goodwy.commons.interfaces.PhoneNumberFormatter
import com.goodwy.commons.models.PhoneNumberFormat

/**
 * PhoneNumberFormatter implementation that uses database-based format definitions.
 * This formatter tries to match phone numbers against formats stored in the database,
 * and falls back to Android's default formatting if no match is found.
 * 
 * Usage:
 * ```kotlin
 * PhoneNumberFormatManager.customFormatter = DatabasePhoneNumberFormatter(context)
 * ```
 */
class DatabasePhoneNumberFormatter(
    private val context: Context
) : PhoneNumberFormatter {
    
    // Cache formats in memory to avoid database queries on main thread
    private var cachedFormats: List<PhoneNumberFormat>? = null
    private var formatsCacheInitialized = false
    
    /**
     * Invalidate the cache to force reload of formats
     * Call this after formats are loaded/updated in the database
     */
    fun invalidateCache() {
        cachedFormats = null
        formatsCacheInitialized = false
        android.util.Log.d("DatabasePhoneNumberFormatter", "Cache invalidated, formats will be reloaded")
    }
    
    /**
     * Load formats from database and cache them in memory
     * This allows formatting to work on the main thread without database queries
     */
    private fun getFormats(): List<PhoneNumberFormat> {
        if (formatsCacheInitialized && cachedFormats != null) {
            return cachedFormats!!
        }
        
        return try {
            val db = PhoneNumberDatabase.getInstance(context)
            val formatDao = db.PhoneNumberFormatDao()
            
            // Get formats (database now allows main thread queries, but we cache for performance)
            val formats = formatDao.getAllFormats()
            
            android.util.Log.d("DatabasePhoneNumberFormatter", "Loaded ${formats.size} formats from database")
            if (formats.isNotEmpty()) {
                // Log first few formats for debugging
                formats.take(5).forEach { format ->
                    android.util.Log.d("DatabasePhoneNumberFormatter", "Format: prefix=${format.prefix}, pattern=${format.districtCodePattern}, template=${format.formatTemplate}")
                }
                cachedFormats = formats
                formatsCacheInitialized = true
            } else {
                android.util.Log.w("DatabasePhoneNumberFormatter", "No formats found in database - formats may not be loaded yet")
            }
            
            formats
        } catch (e: Exception) {
            android.util.Log.e("DatabasePhoneNumberFormatter", "Error loading formats", e)
            emptyList()
        }
    }
    
    /**
     * Formats a phone number using database format definitions.
     * First tries to match against database formats, then falls back to Android's default formatting.
     * Uses cached formats to work on main thread.
     */
    override fun formatPhoneNumber(
        phoneNumber: String,
        normalizedNumber: String,
        countryCode: String?,
        minimumLength: Int
    ): String {
        if (normalizedNumber.length < minimumLength) {
            return phoneNumber
        }
        
        // Try database-based formatting first
        return try {
            val allFormats = getFormats()
            if (allFormats.isEmpty()) {
                // No formats in database or cache, fall back to default
                // Try to load formats in background for next time
                if (!formatsCacheInitialized) {
                    com.goodwy.commons.helpers.ensureBackgroundThread {
                        getFormats() // Load formats in background
                    }
                }
                return formatWithDefault(phoneNumber, normalizedNumber, countryCode)
            }
            
            // Sort by prefix length descending to try longer prefixes first (e.g., 0219 before 021)
            val sortedFormats = allFormats.sortedByDescending { it.prefixLength }
            
            // Calculate maximum expected length from all formats
            // Format typically needs: prefix + district + 4 digits for NUMBER4
            val maxExpectedLength = sortedFormats.maxOfOrNull { 
                it.prefixLength + it.districtCodeLength + 4 
            } ?: Int.MAX_VALUE
            
            // If number is significantly longer than any format expects, return normalized number
            // Allow some buffer (e.g., 2 extra digits) before considering it overflow
            if (normalizedNumber.length > maxExpectedLength + 2) {
                android.util.Log.d("DatabasePhoneNumberFormatter", "Number length overflow: $normalizedNumber (length=${normalizedNumber.length}, max expected=$maxExpectedLength)")
                return normalizedNumber
            }
            
            var matchedFormat: PhoneNumberFormat? = null
            var matchedPrefix: String? = null
            var matchedDistrictCode: String? = null
            
            for (format in sortedFormats) {
                // Check if number is long enough for this format
                // Need at least prefix + district code, and ideally enough for NUMBER4 (4 digits)
                val minRequiredLength = format.prefixLength + format.districtCodeLength
                if (normalizedNumber.length < minRequiredLength) {
                    continue
                }
                
                // Extract prefix based on format's prefix length
                val prefix = normalizedNumber.substring(0, format.prefixLength)
                
                // Check if prefix matches (exact match or "all")
                if (format.prefix != "all" && prefix != format.prefix) {
                    continue
                }
                
                // Extract district code
                val districtCode = normalizedNumber.substring(
                    format.prefixLength, 
                    format.prefixLength + format.districtCodeLength
                )
                
                // Check if district code matches the pattern
                val patternMatches = PhoneNumberFormatHelper.matchesPattern(districtCode, format.districtCodePattern)
                android.util.Log.v("DatabasePhoneNumberFormatter", "Trying format: prefix=${format.prefix}, pattern=${format.districtCodePattern}, extracted prefix=$prefix, district=$districtCode, matches=$patternMatches")
                
                if (patternMatches) {
                    matchedFormat = format
                    matchedPrefix = prefix
                    matchedDistrictCode = districtCode
                    android.util.Log.d("DatabasePhoneNumberFormatter", "✓ Matched: $normalizedNumber -> prefix=$prefix, district=$districtCode, pattern=${format.districtCodePattern}, template=${format.formatTemplate}")
                    break
                }
            }
            
            if (matchedFormat != null && matchedPrefix != null && matchedDistrictCode != null) {
                // Extract the remaining number part
                val numberStart = matchedFormat.prefixLength + matchedFormat.districtCodeLength
                val numberPart = if (normalizedNumber.length > numberStart) {
                    normalizedNumber.substring(numberStart)
                } else {
                    ""
                }
                
                // Format using template
                PhoneNumberFormatHelper.formatNumber(
                    matchedFormat.formatTemplate,
                    matchedPrefix,
                    matchedDistrictCode,
                    numberPart
                )
            } else {
                // No database format matched, fall back to default
                android.util.Log.d("DatabasePhoneNumberFormatter", "No format matched for: $normalizedNumber (length=${normalizedNumber.length})")
                formatWithDefault(phoneNumber, normalizedNumber, countryCode)
            }
        } catch (e: Exception) {
            // Database error, fall back to default
            android.util.Log.e("DatabasePhoneNumberFormatter", "Error formatting $normalizedNumber", e)
            formatWithDefault(phoneNumber, normalizedNumber, countryCode)
        }
    }
    
    /**
     * Fallback to PhoneNumberFormatManager's default formatter (handles 191/195 patterns)
     * This creates a temporary instance of the default formatter to use as fallback
     */
    private fun formatWithDefault(
        phoneNumber: String,
        normalizedNumber: String,
        countryCode: String?
    ): String {
        // Create a temporary default formatter instance (same logic as PhoneNumberFormatManager.defaultFormatter)
        val defaultFormatter = object : PhoneNumberFormatter {
            override fun formatPhoneNumber(
                phoneNumber: String,
                normalizedNumber: String,
                countryCode: String?,
                minimumLength: Int
            ): String {
                if (normalizedNumber.length < minimumLength) {
                    return phoneNumber
                }
                
                // Format numbers starting with 191 or 195 as 191-xxx-xxxx or 195-xxx-xxxx
                return when {
                    normalizedNumber.startsWith("191") && normalizedNumber.length >= 10 -> {
                        val digits = normalizedNumber.substring(3)
                        if (digits.length >= 7) {
                            val firstThree = digits.substring(0, 3)
                            val lastFour = digits.substring(3, 7)
                            "191-$firstThree-$lastFour"
                        } else {
                            phoneNumber
                        }
                    }
                    normalizedNumber.startsWith("195") && normalizedNumber.length >= 10 -> {
                        val digits = normalizedNumber.substring(3)
                        if (digits.length >= 7) {
                            val firstThree = digits.substring(0, 3)
                            val lastFour = digits.substring(3, 7)
                            "195-$firstThree-$lastFour"
                        } else {
                            phoneNumber
                        }
                    }
                    else -> {
                        // Use Android's default formatting for other numbers
                        android.telephony.PhoneNumberUtils.formatNumber(phoneNumber, countryCode)?.toString() ?: phoneNumber
                    }
                }
            }
        }
        
        return defaultFormatter.formatPhoneNumber(phoneNumber, normalizedNumber, countryCode, 4)
    }
}

