package com.goodwy.commons.models

import android.telephony.PhoneNumberUtils
import com.goodwy.commons.extensions.normalizePhoneNumber
import com.goodwy.commons.extensions.normalizeString
import com.goodwy.commons.helpers.SORT_BY_FULL_NAME
import com.goodwy.commons.helpers.SORT_DESCENDING
import com.goodwy.commons.models.contacts.Contact
import java.text.Collator

data class SimpleContact(
        val rawId: Int,
        val contactId: Int,
        var name: String,
        var photoUri: String,
        var phoneNumbers: ArrayList<PhoneNumber>,
        var birthdays: ArrayList<String>,
        var anniversaries: ArrayList<String>,
        var company: String = "",
        var jobPosition: String = ""
    ) : Comparable<SimpleContact> {

    companion object {
        var sorting = -1
        var collator: Collator? = null
        private const val SORT_CAT_SYMBOL = 0
        private const val SORT_CAT_KOREAN = 1
        private const val SORT_CAT_ENGLISH = 2
        private const val SORT_CAT_OTHER = 3
        private const val SORT_CAT_NUMBER = 4
    }

    override fun hashCode(): Int {
        var result = rawId.hashCode()
        result = 31 * result + contactId
        result = 31 * result + (name ?: "").hashCode()
        result = 31 * result + (photoUri ?: "").hashCode()
        result = 31 * result + phoneNumbers.hashCode()
        result = 31 * result + birthdays.hashCode()
        result = 31 * result + anniversaries.hashCode()
        result = 31 * result + (company ?: "").hashCode()
        result = 31 * result + (jobPosition ?: "").hashCode()
        return result
    }

    override fun compareTo(other: SimpleContact): Int {
        if (sorting == -1) {
            return compareByFullName(other)
        }

        var result = when {
            sorting and SORT_BY_FULL_NAME != 0 -> compareByFullName(other)
            else -> rawId.compareTo(other.rawId)
        }

        if (sorting and SORT_DESCENDING != 0) {
            result *= -1
        }

        return result
    }

    private fun compareByFullName(other: SimpleContact): Int {
        val firstString = name.normalizeString()
        val secondString = other.name.normalizeString()
        if (firstString.isEmpty() && secondString.isEmpty()) return 0
        if (firstString.isEmpty()) return 1
        if (secondString.isEmpty()) return -1
        // Same order as Contacts app: symbols, Korean, English, other, numbers (Korean alphabet first, then English)
        val cat1 = getSortCategory(firstString.first())
        val cat2 = getSortCategory(secondString.first())
        val categoryCompare = cat1.compareTo(cat2)
        if (categoryCompare != 0) return categoryCompare
        return when (cat1) {
            SORT_CAT_SYMBOL, SORT_CAT_OTHER, SORT_CAT_NUMBER ->
                firstString.compareTo(secondString, true)
            else ->
                Contact.collator?.compare(firstString, secondString) ?: firstString.compareTo(secondString, true)
        }
    }

    /** Sort category for fixed order: symbols, Korean, English, other (e.g. ASCII), numbers at last. Matches Contact.getSortCategory. */
    private fun getSortCategory(char: Char): Int {
        return when {
            char.isDigit() -> SORT_CAT_NUMBER
            !char.isLetter() -> SORT_CAT_SYMBOL
            isHangul(char) -> SORT_CAT_KOREAN
            isLatinLetter(char) -> SORT_CAT_ENGLISH
            else -> SORT_CAT_OTHER
        }
    }

    private fun isHangul(c: Char): Boolean {
        val code = c.code
        return (code in 0xAC00..0xD7A3) || (code in 0x1100..0x11FF) || (code in 0x3130..0x318F)
    }

    private fun isLatinLetter(c: Char): Boolean {
        val code = c.code
        return (code in 0x41..0x5A) || (code in 0x61..0x7A)
    }

    fun doesContainPhoneNumber(text: String, search: Boolean = false): Boolean {
        return if (text.isNotEmpty()) {
            val normalizedText = text.normalizePhoneNumber()
            if (normalizedText.isEmpty()) {
                phoneNumbers.map { it.normalizedNumber }.any { phoneNumber ->
                    phoneNumber.contains(text)
                }
            } else if (search) {
                phoneNumbers.map { it.normalizedNumber }.any { phoneNumber ->
                    PhoneNumberUtils.compare(phoneNumber.normalizePhoneNumber(), normalizedText) ||
                            phoneNumber.contains(text) ||
                            phoneNumber.normalizePhoneNumber().contains(normalizedText) ||
                            phoneNumber.contains(normalizedText)
                }
            } else {
                phoneNumbers.map { it.normalizedNumber }.any { phoneNumber ->
                    PhoneNumberUtils.compare(phoneNumber.normalizePhoneNumber(), normalizedText)
                        // does not work correctly if only some digits of the number match
                        || (phoneNumber.contains(text) && text.length > 7)
                        || (phoneNumber.normalizePhoneNumber().contains(normalizedText) && normalizedText.length > 7)
                        || (phoneNumber.contains(normalizedText) && normalizedText.length > 7)
                }
            }
        } else {
            false
        }
    }

    fun doesHavePhoneNumber(text: String): Boolean {
        return if (text.isNotEmpty()) {
            val normalizedText = text.normalizePhoneNumber()
            if (normalizedText.isEmpty()) {
                phoneNumbers.map { it.normalizedNumber }.any { phoneNumber ->
                    phoneNumber == text
                }
            } else {
                phoneNumbers.map { it.normalizedNumber }.any { phoneNumber ->
                    PhoneNumberUtils.compare(phoneNumber.normalizePhoneNumber(), normalizedText) ||
                        phoneNumber == text ||
                        phoneNumber.normalizePhoneNumber() == normalizedText ||
                        phoneNumber == normalizedText
                }
            }
        } else {
            false
        }
    }

    fun isABusinessContact() =
        (name == "$company, $jobPosition" && company.isNotBlank() && jobPosition.isNotBlank())
            || (name == company && company.isNotBlank())
            || (name == jobPosition && jobPosition.isNotBlank())
}

