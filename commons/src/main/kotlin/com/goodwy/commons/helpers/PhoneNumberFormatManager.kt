package com.goodwy.commons.helpers

import android.telephony.PhoneNumberUtils
import com.goodwy.commons.interfaces.PhoneNumberCountryDetector
import com.goodwy.commons.interfaces.PhoneNumberFormatter
import java.util.Locale

/**
 * Manager class for phone number formatting.
 * Country detection is disabled by default - formatting works without country code.
 * Provides default implementations and allows customization via interfaces.
 * 
 * To use database-based formatting (from phone_number_formats table):
 * ```kotlin
 * PhoneNumberFormatManager.customFormatter = DatabasePhoneNumberFormatter(context)
 * ```
 */
object PhoneNumberFormatManager {
    /**
     * Custom country detector. Not used by default - country detection is disabled.
     * Only used if explicitly set and called.
     */
    var customCountryDetector: PhoneNumberCountryDetector? = null

    /**
     * Custom phone number formatter. If null, uses default formatting logic without country detection.
     * 
     * To enable database-based formatting:
     * ```kotlin
     * PhoneNumberFormatManager.customFormatter = DatabasePhoneNumberFormatter(context)
     * ```
     */
    var customFormatter: PhoneNumberFormatter? = null

    /**
     * Default formatter that formats phone numbers starting with 191 or 195 as 191-xxx-xxxx or 195-xxx-xxxx.
     * Other numbers use Android's default formatting.
     */
    private val defaultFormatter = object : PhoneNumberFormatter {
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
                    val digits = normalizedNumber.substring(3) // Get digits after "191"
                    if (digits.length >= 7) {
                        val firstThree = digits.substring(0, 3)
                        val lastFour = digits.substring(3, 7)
                        "191-$firstThree-$lastFour"
                    } else {
                        phoneNumber
                    }
                }
                normalizedNumber.startsWith("195") && normalizedNumber.length >= 10 -> {
                    val digits = normalizedNumber.substring(3) // Get digits after "195"
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
                    PhoneNumberUtils.formatNumber(phoneNumber, null as String?)?.toString() ?: phoneNumber
                }
            }
        }
    }

    /**
     * Formats a phone number without country detection.
     * Uses custom formatter if set, otherwise uses default.
     */
    fun formatPhoneNumber(
        phoneNumber: String,
        normalizedNumber: String,
        countryCode: String? = null,
        minimumLength: Int = 4
    ): String {
        val formatter = customFormatter ?: defaultFormatter
        // Don't detect country - pass null or use provided countryCode if explicitly given
        return formatter.formatPhoneNumber(phoneNumber, normalizedNumber, countryCode, minimumLength)
    }
}


