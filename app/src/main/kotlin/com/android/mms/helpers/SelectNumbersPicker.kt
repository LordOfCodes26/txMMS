package com.android.mms.helpers

import android.content.Intent
import android.telephony.PhoneNumberUtils

/**
 * Launches the contacts-app pickers for phone numbers and parses their results.
 * Mirrors [com.goodwy.commons.activities.BlockedItemsActivity] / CH320
 * [SelectContactNumbersActivity] and [SelectRecentNumbersActivity] contracts.
 */
object SelectNumbersPicker {
    const val ACTION_SELECT_CONTACT_NUMBERS = "com.android.contacts.action.SELECT_CONTACT_NUMBERS"
    const val ACTION_SELECT_RECENT_NUMBERS = "com.android.contacts.action.SELECT_RECENT_NUMBERS"

    const val EXTRA_ALLOW_MULTIPLE = "allow_select_multiple"
    const val RESULT_PHONE = "phone_number"
    const val RESULT_NORMALIZED = "normalized_phone_number"
    const val RESULT_ALL_PHONES = "all_phone_numbers"
    const val RESULT_ALL_NORMALIZED = "all_normalized_phone_numbers"

    const val REQUEST_SELECT_CONTACT_NUMBERS = 1011
    const val REQUEST_SELECT_RECENT_NUMBERS = 1012

    fun createContactNumbersIntent(allowMultiple: Boolean = true): Intent =
        Intent(ACTION_SELECT_CONTACT_NUMBERS).apply {
            putExtra(EXTRA_ALLOW_MULTIPLE, allowMultiple)
        }

    fun createRecentNumbersIntent(allowMultiple: Boolean = true): Intent =
        Intent(ACTION_SELECT_RECENT_NUMBERS).apply {
            putExtra(EXTRA_ALLOW_MULTIPLE, allowMultiple)
        }

    fun parseSelectedPhoneNumbers(resultData: Intent?): List<String> {
        if (resultData == null) return emptyList()
        val allPhones = resultData.getStringArrayExtra(RESULT_ALL_PHONES)
        if (!allPhones.isNullOrEmpty()) {
            val allNormalized = resultData.getStringArrayExtra(RESULT_ALL_NORMALIZED)
            return allPhones.mapIndexed { index, phone ->
                val normalized = allNormalized?.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: phone
                PhoneNumberUtils.stripSeparators(normalized)
            }.filter { it.isNotEmpty() }
        }
        val phone = resultData.getStringExtra(RESULT_PHONE) ?: return emptyList()
        val normalized = resultData.getStringExtra(RESULT_NORMALIZED)?.takeIf { it.isNotEmpty() } ?: phone
        return listOf(PhoneNumberUtils.stripSeparators(normalized))
    }
}
