package com.goodwy.commons.providercache.debug

import com.goodwy.commons.providercache.display.RelationalRecentsReadMode
import com.goodwy.commons.providercache.grouping.RecentAuthorityPathLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecentsAuthoritySoakSessionTest {

    @Before
    fun reset() {
        CompareOnlySoakCounters.reset()
        RecentAuthorityPathLogger.resetViolations()
        RecentsAuthoritySoakSessionManager.resetSoakSession(
            buildVersion = "test",
            databaseVersion = 16,
        )
    }

    @Test
    fun startSession_resetsCountersAndSetsMode() {
        CompareOnlySoakCounters.recordDualWrite(valid = true, mismatchCount = 0)
        val session = RecentsAuthoritySoakSessionManager.startSoakSession(
            buildVersion = "1.0",
            databaseVersion = 16,
            mode = RelationalRecentsReadMode.COMPARE_ONLY,
        )
        val dump = RecentsAuthoritySoakSessionManager.dumpSoakSession()
        assertTrue(dump.contains(session.sessionId))
        assertTrue(dump.contains("COMPARE_ONLY"))
        assertEquals(0L, CompareOnlySoakCounters.snapshot().dualWriteTotal)
    }

    @Test
    fun approvalGates_failUntilThresholdMet() {
        val session = RecentsAuthoritySoakSessionManager.currentOrNull()!!
        assertFalse(session.passesApprovalGates())
    }

    @Test
    fun pathViolation_incrementsCounter() {
        RecentAuthorityPathLogger.recordViolation(
            path = "test",
            detail = "digit sql with contact key",
            includeStack = false,
        )
        val refreshed = RecentsAuthoritySoakSessionManager.currentOrNull()!!
        assertEquals(1L, refreshed.authorityPathViolations)
        assertFalse(refreshed.passesApprovalGates())
    }

    @Test
    fun maskSensitive_redactsDigits() {
        val masked = RecentsAuthoritySoakSessionManager.maskSensitive(
            "number:1915882855 contact:42 key=5551000",
        )
        assertTrue(masked.contains("number:****"))
        assertTrue(masked.contains("contact:***"))
        assertFalse(masked.contains("1915882855"))
    }
}
