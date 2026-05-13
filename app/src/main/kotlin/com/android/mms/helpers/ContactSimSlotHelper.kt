package com.android.mms.helpers

import java.util.Locale


object ContactSimSlotHelper {

    /** 0 = hide badge; 1 or 2 = SIM slot for drawable selection. */
    fun slotForSimContactAccount(
        accountName: String,
        accountType: String,
    ): Int {
        if (!isSimCardAccount(accountName, accountType)) return 0
        val name = accountName.lowercase(Locale.getDefault())
        val type = accountType.lowercase(Locale.getDefault())
        val combined = "$name $type"

        if (Regex("""sim\s*[_-]?\s*2|sim2|\bsim\b[^\d]*\b2\b|slot\s*2|second\s*sim|sim\s*card\s*2|sim\s*2""").containsMatchIn(combined)) {
            return 2
        }
        if (Regex("""sim\s*[_-]?\s*1|sim1|\bsim\b[^\d]*\b1\b|slot\s*1|first\s*sim|sim\s*card\s*1|sim\s*1""").containsMatchIn(combined)) {
            return 1
        }
        Regex("""sim[^\d]{0,6}([12])""", RegexOption.IGNORE_CASE).find(accountName)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

        return 1
    }
    private fun isSimCardAccount(accountName: String, accountType: String): Boolean {

        val typeLower = accountType.lowercase(Locale.getDefault())
        val nameLower = accountName.lowercase(Locale.getDefault())
        val isPhoneStorageOnly = (accountName.isEmpty() && accountType.isEmpty()) || (nameLower == "phone" && accountType.isEmpty())
        if (isPhoneStorageOnly) return false
        if (typeLower.contains("sim") || typeLower.contains("icc")) return true
        if (Regex("""sim\s*\d""", RegexOption.IGNORE_CASE).containsMatchIn(nameLower)) return true
        return false
    }
}
