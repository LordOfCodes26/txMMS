package com.goodwy.commons.interfaces

/**
 * Interface for custom phone number country detection logic.
 * Implement this to provide custom country code detection based on your requirements.
 */
interface PhoneNumberCountryDetector {
    /**
     * Detects the country code for a given phone number.
     * 
     * @param phoneNumber The phone number to detect country for (can be formatted or unformatted)
     * @param normalizedNumber The normalized version of the phone number (digits only)
     * @return ISO 3166-1 alpha-2 country code (e.g., "US", "GB", "FR"), or null if detection fails
     */
    fun detectCountry(phoneNumber: String, normalizedNumber: String): String?
}

/**
 * Interface for custom phone number formatting logic.
 * Implement this to provide custom formatting rules for phone numbers.
 */
interface PhoneNumberFormatter {
    /**
     * Formats a phone number according to custom rules.
     * 
     * @param phoneNumber The phone number to format (can be formatted or unformatted)
     * @param normalizedNumber The normalized version of the phone number (digits only)
     * @param countryCode The ISO 3166-1 alpha-2 country code (e.g., "US", "GB", "FR"), or null if unknown
     * @param minimumLength Minimum length required for formatting (default: 4)
     * @return The formatted phone number, or the original if formatting fails or is not applicable
     */
    fun formatPhoneNumber(
        phoneNumber: String,
        normalizedNumber: String,
        countryCode: String?,
        minimumLength: Int = 4
    ): String
}

