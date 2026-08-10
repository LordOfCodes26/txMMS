package com.goodwy.commons.providercache.display

import android.util.Log
import com.goodwy.commons.providercache.debug.RecentsSoakMismatchClass
import com.goodwy.commons.providercache.grouping.RecentAuthorityPathLogger

/**
 * Guards digit-only SQL grouping queries from receiving authoritative engine keys such as contact:&lt;id&gt;.
 */
object CallLogDigitGroupingGuard {
    private const val TAG = "CallLogDigitGroupingGuard"

    fun requireDigitGroupKeys(groupKeys: Collection<String>, query: String) {
        val invalid = groupKeys.filter { key ->
            key.startsWith("contact:") || key.startsWith("name:")
        }
        if (invalid.isEmpty()) return
        val msg = "$query received non-digit group keys: $invalid"
        Log.e(TAG, msg)
        RecentAuthorityPathLogger.recordViolation(
            path = query,
            detail = msg,
            classification = RecentsSoakMismatchClass.LEGACY_PATH,
        )
        check(invalid.isEmpty()) { msg }
    }
}
