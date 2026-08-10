package com.goodwy.commons.providercache.grouping

import android.util.Log
import com.goodwy.commons.providercache.debug.RecentsSoakMismatchClass
import com.goodwy.commons.providercache.display.RecentGroupingMode
import java.util.concurrent.atomic.AtomicLong

/**
 * Authority-path instrumentation for COMPARE_ONLY soak.
 */
object RecentAuthorityPathLogger {

    private const val TAG = "RecentAuthorityPath"

    enum class MembershipSource { ENGINE, SQL, LEGACY }
    enum class DisplaySource { ENRICHER, SQL, ADAPTER }

    private val authorityPathViolations = AtomicLong(0)

    fun violationCount(): Long = authorityPathViolations.get()

    fun resetViolations() {
        authorityPathViolations.set(0)
    }

    fun log(
        path: String,
        mode: RecentGroupingMode,
        membershipSource: MembershipSource,
        displaySource: DisplaySource,
    ) {
        Log.d(
            TAG,
            "recentAuthorityPath path=$path mode=${mode.name} " +
                "membershipSource=$membershipSource displaySource=$displaySource",
        )
    }

    fun recordViolation(
        path: String,
        detail: String,
        classification: RecentsSoakMismatchClass = RecentsSoakMismatchClass.LEGACY_PATH,
        includeStack: Boolean = true,
    ) {
        authorityPathViolations.incrementAndGet()
        val stack = if (includeStack) {
            Throwable().stackTraceToString().lineSequence().take(8).joinToString(" | ")
        } else {
            ""
        }
        Log.e(
            TAG,
            "recentAuthorityPathViolation path=$path class=$classification detail=$detail stack=$stack",
        )
    }

    fun assertEngineAuthoritativeDidNotCommitSql(
        path: String,
        committedSqlRows: Boolean,
    ) {
        if (committedSqlRows) {
            recordViolation(
                path = path,
                detail = "ENGINE_AUTHORITATIVE committed SQL-built display rows",
                classification = RecentsSoakMismatchClass.LEGACY_PATH,
            )
        }
    }

    fun assertValidAuthoritativeKey(groupKey: String, path: String) {
        when {
            groupKey.startsWith("name:") ->
                recordViolation(path, "name:* key=$groupKey", RecentsSoakMismatchClass.IDENTITY)
            groupKey.isNotEmpty() &&
                !groupKey.startsWith("contact:") &&
                !groupKey.startsWith("number:") &&
                groupKey.all { it.isDigit() } ->
                recordViolation(path, "raw digit-only key=$groupKey", RecentsSoakMismatchClass.IDENTITY)
        }
    }
}
