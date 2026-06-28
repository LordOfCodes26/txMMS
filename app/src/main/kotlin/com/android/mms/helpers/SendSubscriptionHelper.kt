package com.android.mms.helpers

import android.telephony.SmsManager
import com.android.mms.BuildConfig
import com.klinker.android.send_message.Settings

object SendSubscriptionHelper {
    /** Lets debug builds send without a detected SIM; uses the default SMS manager. */
    fun testFallbackSubscriptionId(): Int? {
        return if (BuildConfig.DEBUG) Settings.DEFAULT_SUBSCRIPTION_ID else null
    }

    fun firstResolvedOrTestFallback(vararg candidates: Int?): Int? {
        for (candidate in candidates) {
            val resolved = candidate?.takeIf { it >= 0 }
            if (resolved != null) {
                return resolved
            }
        }
        return testFallbackSubscriptionId()
    }

    fun resolveForSend(
        explicitSubId: Int? = null,
        defaultSmsSubscriptionId: Int = SmsManager.getDefaultSmsSubscriptionId(),
    ): Int? = firstResolvedOrTestFallback(explicitSubId, defaultSmsSubscriptionId)
}
