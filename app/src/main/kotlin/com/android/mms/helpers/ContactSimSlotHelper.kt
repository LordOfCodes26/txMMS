package com.android.mms.helpers

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.telephony.SubscriptionInfo
import androidx.annotation.RequiresPermission
import com.android.mms.extensions.subscriptionManagerCompat
import com.github.mangstadt.vinnie.io.Context
import com.goodwy.commons.activities.BaseSimpleActivity
import java.util.Locale


class ContactSimSlotHelper(
    private val activity: BaseSimpleActivity,
) {

    /** 0 = hide badge; 1 or 2 = SIM slot for drawable selection. */
    fun slotForSimContactAccount(
        accountName: String,
        accountType: String,
    ): Int = resolveSlot(accountName, accountType, subscriptionSlot = null)

    internal fun resolveSlot(
        accountName: String,
        accountType: String,
        subscriptionSlot: (() -> Int?)?
    ): Int {
        if (!isSimCardAccount(accountName,accountType)) return 0
        val name = accountName.lowercase(Locale.getDefault())
        val type = accountType.lowercase(Locale.getDefault())
        val combined = "$name $type"

        if (MATCH_SLOT_2.any { it.containsMatchIn(combined)}) return 2
        if (MATCH_SLOT_1.any { it.containsMatchIn(combined)}) return 1

        Regex("""sim[^\d]{0,8}?([12])(?!\d)""", RegexOption.IGNORE_CASE).find(combined)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

        subscriptionSlot?.invoke()?.takeIf { it in 1..2 }?.let { return it }

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

    private val MATCH_SLOT_2: List<Regex> = listOf(
        Regex("""sim\s*[_-]?\s*2\b"""),
        Regex("""\bsim2\b"""),
        Regex("""\bsim\b[^\d]{0,12}\b2\b"""),
        Regex("""\bslot\s*[:=]?\s*2\b"""),
        Regex("""\bsecond(?:ary)?\s+sim\b"""),
        Regex("""\b2nd\s+sim\b"""),
        Regex("""\bsim\s*card\s*2\b"""),
        Regex("""\bsim\s*#\s*2\b"""),
        Regex("""\bsub(?:scription)?\s*[_-]?\s*2\b"""),
        Regex("""\bsub\s*[_-]?\s*2\b"""),
        Regex("""\bsim\s*b\b"""),
        Regex("""[._-]sim[._-]?2\b"""),
    )

    private val MATCH_SLOT_1: List<Regex> = listOf(
        Regex("""sim\s*[_-]?\s*1\b"""),
        Regex("""\bsim1\b"""),
        Regex("""\bsim\b[^\d]{0,12}\b1\b"""),
        Regex("""\bslot\s*[:=]?\s*1\b"""),
        Regex("""\bfirst\s+sim\b"""),
        Regex("""\bsim\s*card\s*1\b"""),
        Regex("""\bsim\s*#\s*1\b"""),
        Regex("""\bsub(?:scription)?\s*[_-]?\s*1\b"""),
        Regex("""\bsub\s*[_-]?\s*1\b"""),
        Regex("""\bsim\s*a\b"""),
        Regex("""[._-]sim[._-]?1\b"""),
    )

    @SuppressLint("MissingPermission")
    fun resolveSlotForSimContactAccount(accountName: String, accountType: String): Int =
        resolveSlot(accountName,accountType) {
            slotFromSubscriptionLabels(accountName, accountType)
        }

    @SuppressLint("MissingPermission")
    private fun slotFromSubscriptionLabels(accountName: String, accountType: String): Int? {
        val keys = listOf(accountName.trim(), accountType.trim()).filter { it.length >= 2 }.map { it.lowercase(Locale.getDefault()) }
        if (keys.isEmpty()) return null
        val subs = activity.subscriptionManagerCompat().activeSubscriptionInfoList ?: return null

        if (subs.isEmpty()) return null
        val matchedSlot = subs.sortedBy { it.simSlotIndex }
            .filter { info -> subscriptionLabels(info).any { lbl -> keys.any { k -> looselyMatchPhoneLabel(k, lbl)}} }
            .map { it.simSlotIndex }
            .distinct()
        return when (matchedSlot.size) {
            1 -> matchedSlot.first() + 1
            else -> null
        }
    }
    private fun subscriptionLabels(info: SubscriptionInfo): List<String> = buildList {
        info.displayName?.toString()?.takeIf { it.length >= 2 }?.let { add(it.lowercase(Locale.getDefault())) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.carrierName?.toString()?.trim()?.takeIf { it.length >= 2 }?.let { add(it.lowercase(Locale.getDefault())) }
        }
    }

    private fun looselyMatchPhoneLabel(keyLower: String, labelLower: String): Boolean =
        keyLower.contains(labelLower) || labelLower.contains(keyLower)

}

