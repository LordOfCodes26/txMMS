package com.goodwy.commons.models

import android.telephony.PhoneNumberUtils
import com.goodwy.commons.extensions.normalizePhoneNumber
import com.goodwy.commons.extensions.normalizeString
import com.goodwy.commons.helpers.SORT_BY_FULL_NAME
import com.goodwy.commons.helpers.SORT_DESCENDING
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
        var sortingSymbolsFirst = false
        var collator: Collator? = null
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

        val firstCharType = getCharType(firstString.firstOrNull())
        val secondCharType = getCharType(secondString.firstOrNull())

        return if (sortingSymbolsFirst) {
            compareWithSymbolsFirst(firstCharType, secondCharType, firstString, secondString)
        } else {
            compareWithLettersFirst(firstCharType, secondCharType, firstString, secondString)
        }
    }

    private fun compareWithSymbolsFirst(
        firstCharType: CharType,
        secondCharType: CharType,
        firstString: String,
        secondString: String
    ): Int {
        return when {
            firstCharType == CharType.LETTER && secondCharType != CharType.LETTER -> 1
            firstCharType != CharType.LETTER && secondCharType == CharType.LETTER -> -1
            firstCharType == CharType.DIGIT && secondCharType == CharType.SYMBOL -> 1
            firstCharType == CharType.SYMBOL && secondCharType == CharType.DIGIT -> -1
            else -> collator?.compare(firstString, secondString) ?: firstString.compareTo(secondString, true)
        }
    }

    private fun compareWithLettersFirst(
        firstCharType: CharType,
        secondCharType: CharType,
        firstString: String,
        secondString: String
    ): Int {
        return when {
            firstCharType == CharType.LETTER && secondCharType != CharType.LETTER -> -1
            firstCharType != CharType.LETTER && secondCharType == CharType.LETTER -> 1
            else -> collator?.compare(firstString, secondString) ?: firstString.compareTo(secondString, true)
        }
    }

    private fun getCharType(char: Char?): CharType {
        return when {
            char == null -> CharType.SYMBOL
            char.isLetter() -> CharType.LETTER
            char.isDigit() -> CharType.DIGIT
            else -> CharType.SYMBOL
        }
    }

    private enum class CharType {
        LETTER, DIGIT, SYMBOL
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

