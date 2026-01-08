package com.goodwy.commons.examples

import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import com.goodwy.commons.helpers.PhoneNumberFormatManager
import com.goodwy.commons.interfaces.PhoneNumberCountryDetector
import com.goodwy.commons.interfaces.PhoneNumberFormatter
import java.util.Locale

/**
 * Example implementations of custom phone number formatting and country detection.
 * 
 * To use these custom implementations, set them in your Application class or main activity:
 * 
 * ```kotlin
 * // Example: Use SIM card country for detection
 * PhoneNumberFormatManager.customCountryDetector = SimCardCountryDetector(context)
 * 
 * // Example: Use custom formatting with specific rules
 * PhoneNumberFormatManager.customFormatter = CustomPhoneNumberFormatter()
 * ```
 */

/**
 * Example: Country detector that uses SIM card country code instead of device locale.
 * This is useful when the device locale doesn't match the SIM card's country.
 */
class SimCardCountryDetector(
    private val telephonyManager: TelephonyManager
) : PhoneNumberCountryDetector {
    override fun detectCountry(phoneNumber: String, normalizedNumber: String): String? {
        // Try to get country from SIM card
        val simCountry = telephonyManager.simCountryIso?.uppercase(Locale.getDefault())
        if (!simCountry.isNullOrEmpty()) {
            return simCountry
        }
        
        // Fallback to network country
        val networkCountry = telephonyManager.networkCountryIso?.uppercase(Locale.getDefault())
        if (!networkCountry.isNullOrEmpty()) {
            return networkCountry
        }
        
        // Final fallback to device locale
        return Locale.getDefault().country.takeIf { it.isNotEmpty() }
    }
}

/**
 * Example: Country detector that tries to detect country from phone number prefix.
 * This is useful for international numbers where the country code is embedded.
 */
class PrefixBasedCountryDetector : PhoneNumberCountryDetector {
    // Map of country calling codes to ISO country codes
    private val countryCallingCodes = mapOf(
        "+1" to "US", "+44" to "GB", "+33" to "FR", "+49" to "DE",
        "+81" to "JP", "+86" to "CN", "+91" to "IN", "+7" to "RU",
        "+61" to "AU", "+55" to "BR", "+52" to "MX", "+39" to "IT",
        "+34" to "ES", "+82" to "KR", "+31" to "NL", "+46" to "SE"
        // Add more as needed
    )
    
    override fun detectCountry(phoneNumber: String, normalizedNumber: String): String? {
        // Check if number starts with +
        if (normalizedNumber.startsWith("+")) {
            // Try to match country calling code (1-3 digits after +)
            for (i in 1..3) {
                if (normalizedNumber.length > i) {
                    val prefix = normalizedNumber.substring(0, i + 1) // Include the +
                    countryCallingCodes[prefix]?.let { return it }
                }
            }
        }
        
        // Fallback to device locale
        return Locale.getDefault().country.takeIf { it.isNotEmpty() }
    }
}

/**
 * Example: Custom formatter that applies specific formatting rules.
 * This example shows how to add custom formatting logic beyond Android's default.
 */
class CustomPhoneNumberFormatter : PhoneNumberFormatter {
    override fun formatPhoneNumber(
        phoneNumber: String,
        normalizedNumber: String,
        countryCode: String?,
        minimumLength: Int
    ): String {
        if (normalizedNumber.length < minimumLength) {
            return phoneNumber
        }
        
        // Use Android's default formatting as base
        val country = countryCode ?: Locale.getDefault().country
        val formatted = PhoneNumberUtils.formatNumber(phoneNumber, country)?.toString()
        
        // Apply custom rules if needed
        return formatted?.let {
            // Example: Custom formatting for specific countries
            when (country) {
                "US" -> formatUSNumber(it, normalizedNumber)
                "GB" -> formatUKNumber(it, normalizedNumber)
                else -> it
            }
        } ?: phoneNumber
    }
    
    private fun formatUSNumber(formatted: String, normalized: String): String {
        // Example: Ensure US numbers are formatted as (XXX) XXX-XXXX
        // Android's PhoneNumberUtils usually handles this, but you can add custom logic here
        return formatted
    }
    
    private fun formatUKNumber(formatted: String, normalized: String): String {
        // Example: Custom UK formatting rules
        return formatted
    }
}

/**
 * Example: Combined detector that tries multiple methods in order of preference.
 */
class SmartCountryDetector(
    private val telephonyManager: TelephonyManager
) : PhoneNumberCountryDetector {
    private val prefixDetector = PrefixBasedCountryDetector()
    
    override fun detectCountry(phoneNumber: String, normalizedNumber: String): String? {
        // 1. Try to detect from phone number prefix (for international numbers)
        prefixDetector.detectCountry(phoneNumber, normalizedNumber)?.let { return it }
        
        // 2. Try SIM card country
        telephonyManager.simCountryIso?.uppercase(Locale.getDefault())?.takeIf { it.isNotEmpty() }?.let { return it }
        
        // 3. Try network country
        telephonyManager.networkCountryIso?.uppercase(Locale.getDefault())?.takeIf { it.isNotEmpty() }?.let { return it }
        
        // 4. Fallback to device locale
        return Locale.getDefault().country.takeIf { it.isNotEmpty() }
    }
}

/**
 * Example: Simple formatter that always uses a specific country code.
 * Useful when you want to force a specific formatting regardless of detection.
 */
class FixedCountryFormatter(
    private val fixedCountryCode: String
) : PhoneNumberFormatter {
    override fun formatPhoneNumber(
        phoneNumber: String,
        normalizedNumber: String,
        countryCode: String?,
        minimumLength: Int
    ): String {
        if (normalizedNumber.length < minimumLength) {
            return phoneNumber
        }
        
        return PhoneNumberUtils.formatNumber(phoneNumber, fixedCountryCode)?.toString() ?: phoneNumber
    }
}

