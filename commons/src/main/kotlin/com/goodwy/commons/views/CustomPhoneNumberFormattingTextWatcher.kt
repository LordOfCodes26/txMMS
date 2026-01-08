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

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
        // No-op
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
            val formatted = PhoneNumberFormatManager.formatPhoneNumber(
                originalText,
                normalizedNumber,
                null,
                4
            )
            
            if (formatted != originalText) {
                isFormatting = true
                val cursorPosition = s.length
                s.replace(0, s.length, formatted)
                // Try to maintain cursor position if Editable is also Spannable
                if (s is Spannable) {
                    val newCursorPosition = cursorPosition.coerceAtMost(s.length)
                    android.text.Selection.setSelection(s, newCursorPosition)
                }
                isFormatting = false
            }
        }
    }
    
    private fun String.normalizePhoneNumber(): String {
        return android.telephony.PhoneNumberUtils.normalizeNumber(this)
    }
}

