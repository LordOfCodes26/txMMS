package com.goodwy.commons.providercache.display

/**
 * Replacement contacts resolved before Room purge for phones affected by contact deletion.
 */
data class ContactDeletedRecentsResolution(
    val replacementByNormalizedNumber: Map<String, ContactReplacementInfo?>,
    val deletedContactIds: Set<Int>,
    val groupByContact: Boolean = true,
) {
    fun replacementFor(normalizedOrPhone: String): ContactReplacementInfo? {
        if (normalizedOrPhone.isEmpty()) return null
        replacementByNormalizedNumber[normalizedOrPhone]?.let { return it }
        val digits = normalizedOrPhone.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        return replacementByNormalizedNumber.entries.firstOrNull { (key, _) ->
            val keyDigits = key.filter { it.isDigit() }
            keyDigits.isNotEmpty() && (keyDigits == digits || keyDigits.endsWith(digits) || digits.endsWith(keyDigits))
        }?.value
    }

    companion object {
        val EMPTY = ContactDeletedRecentsResolution(emptyMap(), emptySet())
    }
}
