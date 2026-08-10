package com.goodwy.commons.providercache.debug

import android.content.Context
import android.util.Log
import com.goodwy.commons.providercache.display.RecentAuthorityMismatchStore
import com.goodwy.commons.providercache.display.RecentDisplayBuildAuthorityResolver
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.display.RelationalRecentsGroupingFlags
import com.goodwy.commons.providercache.display.RelationalRecentsReadMode
import com.goodwy.commons.providercache.grouping.AffectedBuildCounters
import com.goodwy.commons.providercache.grouping.RecentAuthorityPathLogger
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Explicit COMPARE_ONLY soak session. Counters are not reset on app restart unless
 * [resetSoakSession] / [startSoakSession] is invoked.
 */
data class RecentsAuthoritySoakSession(
    val sessionId: String,
    val startedAt: Long,
    val buildVersion: String,
    val databaseVersion: Int,
    val mode: RelationalRecentsReadMode,
    val semanticCompareTotal: Long,
    val semanticMismatch: Long,
    val displayCompareTotal: Long,
    val displayMismatch: Long,
    val checksumCompareTotal: Long,
    val checksumMismatch: Long,
    val dualWriteTotal: Long,
    val dualWriteMismatch: Long,
    val incrementalFallbackCount: Long,
    val noOpMutationCount: Long,
    val displayOnlyMutationCount: Long,
    val membershipChangedCount: Long,
    val authorityPathViolations: Long,
    val stoppedAt: Long? = null,
) {
    val elapsedMs: Long get() = (stoppedAt ?: System.currentTimeMillis()) - startedAt

    fun passesApprovalGates(): Boolean {
        if (semanticCompareTotal < 1000L) return false
        if (displayCompareTotal < 1000L) return false
        if (checksumCompareTotal < 1000L) return false
        if (dualWriteTotal < 1000L) return false
        if (semanticMismatch != 0L) return false
        if (checksumMismatch != 0L) return false
        if (dualWriteMismatch != 0L) return false
        if (displayMismatch != 0L) return false
        if (authorityPathViolations != 0L) return false
        return true
    }
}

enum class RecentsSoakMismatchClass {
    IDENTITY,
    MEMBERSHIP,
    COUNT,
    LATEST_CALL,
    TIMESTAMP,
    DISPLAY_CONTACT,
    AVATAR,
    ORDER,
    DISPLAY_COSMETIC,
    LIFECYCLE,
    CHECKSUM,
    LEGACY_PATH,
    UNKNOWN,
}

object RecentsAuthoritySoakSessionManager {

    private const val TAG = "RecentsAuthoritySoak"

    private val active = AtomicReference<RecentsAuthoritySoakSession?>(null)

    fun currentOrNull(): RecentsAuthoritySoakSession? = active.get()?.let { refresh(it) }

    fun startSoakSession(
        buildVersion: String,
        databaseVersion: Int,
        mode: RelationalRecentsReadMode = RelationalRecentsReadMode.COMPARE_ONLY,
    ): RecentsAuthoritySoakSession {
        CompareOnlySoakCounters.reset()
        AffectedBuildCounters.resetForDebug()
        RecentAuthorityPathLogger.resetViolations()
        RecentAuthorityMismatchStore.clear()
        RelationalRecentsGroupingFlags.readMode = mode
        val session = RecentsAuthoritySoakSession(
            sessionId = UUID.randomUUID().toString().take(8),
            startedAt = System.currentTimeMillis(),
            buildVersion = buildVersion,
            databaseVersion = databaseVersion,
            mode = mode,
            semanticCompareTotal = 0L,
            semanticMismatch = 0L,
            displayCompareTotal = 0L,
            displayMismatch = 0L,
            checksumCompareTotal = 0L,
            checksumMismatch = 0L,
            dualWriteTotal = 0L,
            dualWriteMismatch = 0L,
            incrementalFallbackCount = 0L,
            noOpMutationCount = 0L,
            displayOnlyMutationCount = 0L,
            membershipChangedCount = 0L,
            authorityPathViolations = 0L,
        )
        active.set(session)
        Log.i(TAG, "soakSessionStart id=${session.sessionId} mode=${mode.name} db=$databaseVersion")
        return session
    }

    fun stopSoakSession(): RecentsAuthoritySoakSession? {
        val current = active.get() ?: return null
        val stopped = refresh(current).copy(stoppedAt = System.currentTimeMillis())
        active.set(stopped)
        Log.i(TAG, "soakSessionStop id=${stopped.sessionId} elapsedMs=${stopped.elapsedMs}")
        return stopped
    }

    fun resetSoakSession(
        buildVersion: String,
        databaseVersion: Int,
    ): RecentsAuthoritySoakSession = startSoakSession(buildVersion, databaseVersion)

    fun dumpSoakSession(): String {
        val session = currentOrNull()
        if (session == null) {
            return "soakSession inactive\n${CompareOnlySoakCounters.dump()}"
        }
        return buildString {
            appendLine("=== Recents Authority Soak Session ===")
            appendLine("sessionId=${session.sessionId}")
            appendLine("startedAt=${session.startedAt}")
            appendLine("stoppedAt=${session.stoppedAt ?: "running"}")
            appendLine("elapsedMs=${session.elapsedMs}")
            appendLine("buildVersion=${session.buildVersion}")
            appendLine("databaseVersion=${session.databaseVersion}")
            appendLine("readMode=${session.mode.name}")
            appendLine("buildAuthorityBY_CONTACT=${RecentDisplayBuildAuthorityResolver.resolveForFullBuild(RecentGroupingMode.BY_CONTACT)}")
            appendLine("buildAuthorityBY_NUMBER=${RecentDisplayBuildAuthorityResolver.resolveForFullBuild(RecentGroupingMode.BY_NUMBER)}")
            appendLine("semanticCompareTotal=${session.semanticCompareTotal}")
            appendLine("semanticMismatch=${session.semanticMismatch}")
            appendLine("displayCompareTotal=${session.displayCompareTotal}")
            appendLine("displayMismatch=${session.displayMismatch}")
            appendLine("checksumCompareTotal=${session.checksumCompareTotal}")
            appendLine("checksumMismatch=${session.checksumMismatch}")
            appendLine("dualWriteTotal=${session.dualWriteTotal}")
            appendLine("dualWriteMismatch=${session.dualWriteMismatch}")
            appendLine("incrementalFallbackCount=${session.incrementalFallbackCount}")
            appendLine("noOpMutationCount=${session.noOpMutationCount}")
            appendLine("displayOnlyMutationCount=${session.displayOnlyMutationCount}")
            appendLine("membershipChangedCount=${session.membershipChangedCount}")
            appendLine("authorityPathViolations=${session.authorityPathViolations}")
            appendLine("passesApprovalGates=${session.passesApprovalGates()}")
            appendLine("relationalConsistency=${RecentAuthorityMismatchStore.lastConsistencyLabel()}")
            val last = RecentAuthorityMismatchStore.lastOrNull()
            appendLine("lastMismatchAtMs=${last?.capturedAtMs ?: 0}")
            appendLine("lastMismatchReason=${maskSensitive(last?.mismatchReason.orEmpty())}")
            appendLine("--- raw counters ---")
            appendLine(CompareOnlySoakCounters.dump())
            appendLine("--- performance ---")
            appendLine(RecentsSoakPerformanceTimers.dump())
        }
    }

    fun exportSoakReport(context: Context): File? {
        val session = currentOrNull()?.let { refresh(it) }
            ?: RecentsAuthoritySoakSession(
                sessionId = "nosession",
                startedAt = System.currentTimeMillis(),
                buildVersion = "unknown",
                databaseVersion = 16,
                mode = RelationalRecentsGroupingFlags.readMode,
                semanticCompareTotal = 0L,
                semanticMismatch = 0L,
                displayCompareTotal = 0L,
                displayMismatch = 0L,
                checksumCompareTotal = 0L,
                checksumMismatch = 0L,
                dualWriteTotal = 0L,
                dualWriteMismatch = 0L,
                incrementalFallbackCount = 0L,
                noOpMutationCount = 0L,
                displayOnlyMutationCount = 0L,
                membershipChangedCount = 0L,
                authorityPathViolations = RecentAuthorityPathLogger.violationCount(),
            ).let { refresh(it) }
        val file = File(
            context.cacheDir,
            "recents-authority-soak-${session.sessionId}.txt",
        )
        val body = dumpSoakSession() + "\n--- mismatches ---\n" +
            maskSensitive(RecentAuthorityMismatchStore.dump())
        file.writeText(body)
        Log.i(TAG, "soakReportExported path=${file.absolutePath}")
        return file
    }

    fun toQaPanelSection(): String {
        val session = currentOrNull()
        return buildString {
            appendLine("=== Soak Session ===")
            if (session == null) {
                appendLine("session: inactive")
            } else {
                appendLine("sessionId: ${session.sessionId}")
                appendLine("elapsedMs: ${session.elapsedMs}")
                appendLine("readMode: ${session.mode.name}")
                appendLine("dbVersion: ${session.databaseVersion}")
                appendLine("build: ${session.buildVersion}")
            }
            appendLine("authority BY_CONTACT: ${RecentDisplayBuildAuthorityResolver.resolveForFullBuild(RecentGroupingMode.BY_CONTACT)}")
            appendLine("authority BY_NUMBER: ${RecentDisplayBuildAuthorityResolver.resolveForFullBuild(RecentGroupingMode.BY_NUMBER)}")
            val soak = CompareOnlySoakCounters.snapshot()
            appendLine("semantic: ${soak.compareTotal}/${soak.compareMismatch}")
            appendLine("displayMismatch: ${soak.displayMismatch}")
            appendLine("checksum: ${soak.checksumCompareTotal}/${soak.checksumMismatch}")
            appendLine("dualWrite: ${soak.dualWriteTotal}/${soak.dualWriteMismatch}")
            appendLine("incrementalFallback: ${soak.incrementalFallbackCount}")
            appendLine("mutations noOp/display/membership: ${soak.noOpMutationCount}/${soak.displayOnlyMutationCount}/${soak.membershipChangedCount}")
            appendLine("authorityPathViolations: ${RecentAuthorityPathLogger.violationCount()}")
            appendLine("passesGates: ${session?.let { refresh(it).passesApprovalGates() } ?: false}")
        }
    }

    private fun refresh(base: RecentsAuthoritySoakSession): RecentsAuthoritySoakSession {
        val soak = CompareOnlySoakCounters.snapshot()
        return base.copy(
            mode = RelationalRecentsGroupingFlags.readMode,
            semanticCompareTotal = soak.compareTotal,
            semanticMismatch = soak.compareMismatch,
            displayCompareTotal = soak.compareTotal,
            displayMismatch = soak.displayMismatch,
            checksumCompareTotal = soak.checksumCompareTotal,
            checksumMismatch = soak.checksumMismatch,
            dualWriteTotal = soak.dualWriteTotal,
            dualWriteMismatch = soak.dualWriteMismatch,
            incrementalFallbackCount = soak.incrementalFallbackCount,
            noOpMutationCount = soak.noOpMutationCount,
            displayOnlyMutationCount = soak.displayOnlyMutationCount,
            membershipChangedCount = soak.membershipChangedCount,
            authorityPathViolations = RecentAuthorityPathLogger.violationCount(),
        )
    }

    fun maskSensitive(text: String): String =
        text
            .replace(Regex("\\b\\d{7,}\\b"), "****")
            .replace(Regex("number:\\d+"), "number:****")
            .replace(Regex("contact:\\d+"), "contact:***")
}

/**
 * Lightweight debug soak timers. No production behavior.
 */
object RecentsSoakPerformanceTimers {
    private val samples = linkedMapOf<String, MutableList<Long>>()

    @Synchronized
    fun record(stage: String, durationMs: Long) {
        samples.getOrPut(stage) { mutableListOf() }.add(durationMs)
        if (durationMs > thresholdsMs(stage)) {
            Log.w("RecentsSoakPerf", "perfFlag stage=$stage durationMs=$durationMs")
        }
    }

    @Synchronized
    fun dump(): String = buildString {
        if (samples.isEmpty()) {
            appendLine("perf: no samples")
            return@buildString
        }
        samples.forEach { (stage, values) ->
            val min = values.minOrNull() ?: 0L
            val max = values.maxOrNull() ?: 0L
            val avg = if (values.isEmpty()) 0L else values.sum() / values.size
            appendLine("perf stage=$stage count=${values.size} min=$min max=$max avg=$avg")
        }
    }

    @Synchronized
    fun reset() {
        samples.clear()
    }

    private fun thresholdsMs(stage: String): Long = when {
        stage.contains("payload", ignoreCase = true) -> 5L
        stage.contains("bind", ignoreCase = true) -> 8L
        else -> 250L
    }
}
