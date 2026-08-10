package com.goodwy.commons.providercache.validation

import com.goodwy.commons.providercache.entities.CacheMetadataDomain
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheRepairOrchestratorTest {

    @Test
    fun repairPlan_rawPreferredOverDisplay() {
        val plan = CacheDebugCommands.planMetadataRepairDomains(
            setOf(
                CacheMetadataDomain.CONTACTS_RAW,
                CacheMetadataDomain.CONTACTS_DISPLAY,
                CacheMetadataDomain.RECENTS_DISPLAY,
            ),
        )
        assertTrue(plan.contains(CacheMetadataDomain.CONTACTS_RAW))
        assertTrue(!plan.contains(CacheMetadataDomain.CONTACTS_DISPLAY))
        assertTrue(plan.contains(CacheMetadataDomain.RECENTS_DISPLAY))
    }

    @Test
    fun resetStartupRecovery_allowsRerun() {
        CacheRepairOrchestrator.resetStartupRecoveryForDebug()
        CacheRepairOrchestrator.resetStartupRecoveryForDebug()
    }
}
