package com.goodwy.commons.helpers

/**
 * Helper class for phone number format pattern matching
 */
object PhoneNumberFormatHelper {
    /**
     * Checks if a district code matches a pattern
     * Pattern examples: "4XX" matches "412", "413", etc.
     *                   "7XX" matches "712", "713", etc.
     *                   "09" matches "09" exactly
     */
    fun matchesPattern(districtCode: String, pattern: String): Boolean {
        if (pattern.length != districtCode.length) {
            return false
        }
        
        for (i in pattern.indices) {
            val patternChar = pattern[i]
            val codeChar = districtCode[i]
            
            when (patternChar) {
                'X' -> {
                    // X matches any digit
                    if (!codeChar.isDigit()) return false
                }
                else -> {
                    // Exact match required
                    if (patternChar != codeChar) return false
                }
            }
        }
        
        return true
    }
    
    /**
     * Formats a phone number using a format template
     * Template examples: "01-XXX-XXXX", "1309-X-XXXX"
     * 
     * Template placeholders:
     * - {PREFIX}: Replaced with prefix (e.g., "01", "13")
     * - {DISTRICT}: Replaced with district code (e.g., "412", "09")
     * - {NUMBER}: Replaced with remaining number digits
     * - {NUMBER4}: Replaced with last 4 digits of number
     * - {NUMBER1}: Replaced with first digit of number
     * 
     * Or use simple pattern matching:
     * - XXX: district code (3 digits)
     * - XX: district code (2 digits)  
     * - XXXX: last 4 digits of number
     * - X: first digit of number (for single X patterns)
     */
    fun formatNumber(template: String, prefix: String, districtCode: String, number: String): String {
        var result = template
        
        // Replace named placeholders first
        result = result.replace("{PREFIX}", prefix)
        result = result.replace("{DISTRICT}", districtCode)
        result = result.replace("{NUMBER}", number)
        result = result.replace("{NUMBER4}", if (number.length >= 4) number.takeLast(4) else number)
        result = result.replace("{NUMBER1}", if (number.isNotEmpty()) number[0].toString() else "")
        
        // Replace pattern-based placeholders
        // Replace XXXX with last 4 digits
        if (number.length >= 4) {
            val lastFour = number.takeLast(4)
            result = result.replace("XXXX", lastFour)
        }
        
        // Replace XXX with district code (3 digits)
        if (districtCode.length == 3) {
            result = result.replace("XXX", districtCode)
        }
        
        // Replace XX with district code (2 digits) - but not if it's part of XXX
        if (districtCode.length == 2 && !result.contains("XXX")) {
            result = result.replace("XX", districtCode)
        }
        
        // Replace single X with first digit of number (for patterns like 1309-X-XXXX)
        if (number.isNotEmpty()) {
            val firstDigit = number[0].toString()
            // Replace single X that's not part of XX, XXX, or XXXX
            result = result.replace(Regex("(?<!X)X(?!X)"), firstDigit)
        }
        
        return result
    }
}

