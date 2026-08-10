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
    
    private var cachedFormats: List<PhoneNumberFormat>? = null
    // Pre-sorted list used directly in formatPhoneNumber to avoid re-sorting on every call.
    private var sortedFormatsCache: List<PhoneNumberFormat>? = null
    private var formatsCacheInitialized = false

    fun invalidateCache() {
        cachedFormats = null
        sortedFormatsCache = null
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
            val formats = formatDao.getAllFormats()
            if (formats.isNotEmpty()) {
                cachedFormats = formats
                sortedFormatsCache = formats.sortedWith(
                    compareByDescending<PhoneNumberFormat> { it.prefixLength }
                        .thenByDescending { PhoneNumberFormatHelper.getPatternSpecificity(it.districtCodePattern) }
                        .thenByDescending { it.districtCodeLength }
                )
                formatsCacheInitialized = true
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
                if (!formatsCacheInitialized) {
                    com.goodwy.commons.helpers.ensureBackgroundThread { getFormats() }
                }
                return formatWithDefault(phoneNumber, normalizedNumber, countryCode)
            }
            
            val sortedFormats = sortedFormatsCache ?: allFormats
            
            // Calculate maximum expected length from all formats
            // Format typically needs: prefix + district + 4 digits for NUMBER4
            val maxExpectedLength = sortedFormats.maxOfOrNull { 
                it.prefixLength + it.districtCodeLength + 4 
            } ?: Int.MAX_VALUE
            
            if (normalizedNumber.length > maxExpectedLength + 2) {
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
                
                if (PhoneNumberFormatHelper.matchesPattern(districtCode, format.districtCodePattern)) {
                    matchedFormat = format
                    matchedPrefix = prefix
                    matchedDistrictCode = districtCode
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
                val formattedNumber = PhoneNumberFormatHelper.formatNumber(
                    matchedFormat.formatTemplate,
                    matchedPrefix,
                    matchedDistrictCode,
                    numberPart
                )
                if (formattedNumber.any { it.equals('X', ignoreCase = true) }) {
                    phoneNumber
                } else {
                    formattedNumber
                }
            } else {
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

