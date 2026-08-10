package com.goodwy.commons.providercache.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationalReadAuthorityGateTest {

    @Test
    fun blocksRelationalDebugWhenEnrichmentIncomplete() {
        RelationalReadAuthorityGate.markEnrichmentComplete(false)
        val result = RelationalReadAuthorityGate.trySetReadModeForDebug(
            RelationalRecentsReadMode.RELATIONAL_DEBUG,
            null,
        )
        assertFalse(result.allowed)
        assertEquals(RelationalRecentsReadMode.LEGACY_ONLY, result.effectiveMode)
        assertEquals(RelationalReadBlockReason.ENRICHMENT_INCOMPLETE, result.blockReason)
        assertEquals(RelationalRecentsReadMode.LEGACY_ONLY, RelationalRecentsGroupingFlags.readMode)
    }

    @Test
    fun allowsCompareOnlyWithoutGate() {
        RelationalRecentsGroupingFlags.readMode = RelationalRecentsReadMode.LEGACY_ONLY
        val result = RelationalReadAuthorityGate.trySetReadModeForDebug(
            RelationalRecentsReadMode.COMPARE_ONLY,
        )
        assertTrue(result.allowed)
        assertEquals(RelationalRecentsReadMode.COMPARE_ONLY, RelationalRecentsGroupingFlags.readMode)
    }

    @Test
    fun blocksRelationalDebugWhenCompareInvalid() {
        RelationalReadAuthorityGate.markEnrichmentComplete(true)
        RelationalReadAuthorityGate.markCompareResult(false)
        val result = RelationalReadAuthorityGate.trySetReadModeForDebug(
            RelationalRecentsReadMode.RELATIONAL_DEBUG,
            RelationalReadGateResult(
                allowed = false,
                effectiveMode = RelationalRecentsReadMode.LEGACY_ONLY,
                blockReason = RelationalReadBlockReason.COMPARE_MISMATCH,
            ),
        )
        assertFalse(result.allowed)
        assertEquals(RelationalReadBlockReason.COMPARE_MISMATCH, result.blockReason)
    }
}
