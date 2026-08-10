package com.goodwy.commons.providercache.validation

import com.goodwy.commons.providercache.entities.CacheMetadataDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheDebugCommandsTest {

    @Test
    fun planMetadataRepairDomains_prefersRawOverDisplay() {
        val domains = setOf(
            CacheMetadataDomain.CONTACTS_RAW,
            CacheMetadataDomain.CONTACTS_DISPLAY,
            CacheMetadataDomain.RECENTS_RAW,
            CacheMetadataDomain.RECENTS_DISPLAY,
        )
        assertEquals(
            listOf(
                CacheMetadataDomain.CONTACTS_RAW,
                CacheMetadataDomain.RECENTS_RAW,
            ),
            CacheDebugCommands.planMetadataRepairDomains(domains),
        )
    }

    @Test
    fun planMetadataRepairDomains_displayOnlyWhenRawClean() {
        val domains = setOf(CacheMetadataDomain.CONTACTS_DISPLAY)
        assertEquals(
            listOf(CacheMetadataDomain.CONTACTS_DISPLAY),
            CacheDebugCommands.planMetadataRepairDomains(domains),
        )
    }

    @Test
    fun formatLightValidationReport_emptyIssues() {
        val text = CacheDebugCommands.formatLightValidationReport(
            CacheValidator.ValidationReport(
                scope = CacheValidator.Scope.LIGHT,
                issues = emptyList(),
            ),
        )
        assertTrue(text.contains("ok"))
    }

    @Test
    fun maskPhone_hidesMiddleDigits() {
        assertTrue(CacheDebugCommands.maskPhone("+15551234567").startsWith("****"))
        assertTrue(CacheDebugCommands.maskPhone("12").contains("****"))
    }

    @Test
    fun dumpPendingRecentDeltas_formatsStructuredLine() {
        val line = CacheDebugCommands.dumpPendingRecentDeltas(
            visibleVersion = 12L,
            targetVersion = 15L,
            deltas = listOf(
                com.goodwy.commons.providercache.display.PendingRecentDelta(
                    groupKey = "5551234",
                    latestCallId = 1,
                    mode = com.goodwy.commons.providercache.display.RecentDeltaMode.INSERT,
                    groupByContact = false,
                ),
            ),
        )
        assertTrue(line.contains("CacheDebug pendingDeltas"))
        assertTrue(line.contains("count=1"))
        assertTrue(line.contains("contiguous=true"))
    }
}
