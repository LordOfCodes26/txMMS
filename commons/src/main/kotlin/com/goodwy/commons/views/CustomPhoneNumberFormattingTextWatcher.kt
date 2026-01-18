package com.goodwy.commons.views

import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import com.goodwy.commons.helpers.PhoneNumberFormatManager

/**
 * Custom TextWatcher that formats phone numbers using PhoneNumberFormatManager.
 * This allows custom formatting logic (e.g., 191-xxx-xxxx, 195-xxx-xxxx) to be applied
 * as the user types.
 */
class CustomPhoneNumberFormattingTextWatcher : TextWatcher {
    private var isFormatting = false
    private var lastValidText: String = ""

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
        // Store the current text as last valid before change
        if (!isFormatting && s != null) {
            lastValidText = s.toString()
        }
    }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        // No-op
    }

    override fun afterTextChanged(s: Editable?) {
        if (isFormatting || s == null) {
            return
        }

        val originalText = s.toString()
        val normalizedNumber = originalText.normalizePhoneNumber()
        
        // Only format if the text has changed and meets minimum length
        if (normalizedNumber.length >= 4) {
            // Use normalized number as the base for formatting to ensure consistent formatting
            // even when user types in the middle of already-formatted text
            val formatted = PhoneNumberFormatManager.formatPhoneNumber(
                normalizedNumber,
                normalizedNumber,
                null,
                4
            )
            
            // Check if the formatted result matches the normalized input length
            // When a format is matched, the formatted text will have formatting characters (dashes)
            // The number of digits in the formatted text is the expected length for that format
            val digitsInFormatted = formatted.count { it.isDigit() }
            val digitsInNormalized = normalizedNumber.length
            
            // Determine what to display:
            // - If formatted has same number of digits as normalized, use formatted version
            // - If lengths don't match, use normalized version (just digits, no formatting)
            val displayText = if (digitsInFormatted == digitsInNormalized) {
                formatted
            } else {
                normalizedNumber
            }
            
            if (displayText != originalText) {
                isFormatting = true
                
                // Get current cursor position
                val cursorPosition = if (s is Spannable) {
                    android.text.Selection.getSelectionStart(s) ?: s.length
                } else {
                    s.length
                }
                
                // Normalize the text before cursor and count digits
                val textBeforeCursor = originalText.substring(0, cursorPosition.coerceAtMost(originalText.length))
                val normalizedBeforeCursor = textBeforeCursor.normalizePhoneNumber()
                val digitsBeforeCursor = normalizedBeforeCursor.length
                
                // Replace text with display version
                s.replace(0, s.length, displayText)
                
                // Update last valid text
                lastValidText = displayText
                
                // Find position in display text with same number of digits before it
                if (s is Spannable) {
                    var newCursorPosition = 0
                    var digitsCounted = 0
                    
                    for (i in displayText.indices) {
                        if (displayText[i].isDigit()) {
                            digitsCounted++
                            if (digitsCounted >= digitsBeforeCursor) {
                                newCursorPosition = i + 1
                                break
                            }
                        }
                    }
                    
                    // If we didn't find enough digits, place cursor at end
                    if (digitsCounted < digitsBeforeCursor) {
                        newCursorPosition = displayText.length
                    }
                    
                    android.text.Selection.setSelection(s, newCursorPosition)
                }
                isFormatting = false
            } else {
                // Text hasn't changed, update last valid text
                lastValidText = originalText
            }
        } else {
            // Text is too short, update last valid text
            lastValidText = originalText
        }
    }
    
    private fun String.normalizePhoneNumber(): String {
        return android.telephony.PhoneNumberUtils.normalizeNumber(this)
    }
}

