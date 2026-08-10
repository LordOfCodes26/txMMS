package com.goodwy.commons.providercache.validation

import com.goodwy.commons.providercache.entities.CacheMetadataDomain
import com.goodwy.commons.providercache.entities.CacheMetadataEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheValidatorTest {

    private fun cleanEntity(domain: String) = CacheMetadataEntity(
        domain = domain,
        dirty = false,
        repairRequired = false,
    )

    @Test
    fun evaluateLight_cleanMetadata_noStructuralIssues_returnsEmpty() {
        val entities = CacheMetadataDomain.ALL.map { cleanEntity(it) }
        val issues = CacheValidator.evaluateLight(
            entities = entities,
            rawContacts = 100,
            displayContacts = 100,
            rawRecents = 50,
            displayRecents = 50,
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun evaluateLight_dirtyMetadata_flagsDomain() {
        val entities = CacheMetadataDomain.ALL.map { domain ->
            if (domain == CacheMetadataDomain.CONTACTS_DISPLAY) {
                cleanEntity(domain).copy(dirty = true, lastMutationReason = "in_flight")
            } else {
                cleanEntity(domain)
            }
        }
        val issues = CacheValidator.evaluateLight(
            entities = entities,
            rawContacts = 10,
            displayContacts = 10,
            rawRecents = 5,
            displayRecents = 5,
        )
        assertEquals(1, issues.size)
        assertEquals(CacheMetadataDomain.CONTACTS_DISPLAY, issues[0].domain)
        assertEquals(CacheValidator.IssueReason.METADATA_DIRTY, issues[0].reason)
    }

    @Test
    fun evaluateLight_repairRequired_flagsDomain() {
        val entities = listOf(
            cleanEntity(CacheMetadataDomain.RECENTS_RAW).copy(
                repairRequired = true,
                lastMutationReason = "mutation_failed",
            ),
        )
        val issues = CacheValidator.evaluateLight(
            entities = entities,
            rawContacts = 0,
            displayContacts = 0,
            rawRecents = 10,
            displayRecents = 10,
        )
        assertEquals(1, issues.size)
        assertEquals(CacheValidator.IssueReason.METADATA_REPAIR_REQUIRED, issues[0].reason)
    }

    @Test
    fun evaluateLight_displayEmptyRawPresent_flagsDisplayDomain() {
        val issues = CacheValidator.evaluateLight(
            entities = emptyList(),
            rawContacts = 42,
            displayContacts = 0,
            rawRecents = 20,
            displayRecents = 0,
        )
        assertEquals(2, issues.size)
        assertTrue(
            issues.any {
                it.domain == CacheMetadataDomain.CONTACTS_DISPLAY &&
                    it.reason == CacheValidator.IssueReason.DISPLAY_EMPTY_RAW_PRESENT
            },
        )
        assertTrue(
            issues.any {
                it.domain == CacheMetadataDomain.RECENTS_DISPLAY &&
                    it.reason == CacheValidator.IssueReason.DISPLAY_EMPTY_RAW_PRESENT
            },
        )
    }

    @Test
    fun validationReport_domainsNeedingRepair_collectsUniqueDomains() {
        val report = CacheValidator.ValidationReport(
            scope = CacheValidator.Scope.LIGHT,
            issues = listOf(
                CacheValidator.DomainIssue(
                    domain = CacheMetadataDomain.CONTACTS_DISPLAY,
                    reason = CacheValidator.IssueReason.METADATA_DIRTY,
                ),
                CacheValidator.DomainIssue(
                    domain = CacheMetadataDomain.CONTACTS_DISPLAY,
                    reason = CacheValidator.IssueReason.DISPLAY_EMPTY_RAW_PRESENT,
                ),
            ),
        )
        assertTrue(report.requiresRepair)
        assertEquals(setOf(CacheMetadataDomain.CONTACTS_DISPLAY), report.domainsNeedingRepair())
    }

    @Test
    fun evaluateLight_rawPresentDisplayEmpty_flagsRecentsOnlyWhenRawRecents() {
        val issues = CacheValidator.evaluateLight(
            entities = emptyList(),
            rawContacts = 0,
            displayContacts = 0,
            rawRecents = 20,
            displayRecents = 0,
        )
        assertEquals(1, issues.size)
        assertEquals(CacheMetadataDomain.RECENTS_DISPLAY, issues[0].domain)
    }

    @Test
    fun evaluateLight_displayPresentRawEmpty_noStructuralFlagFromLight() {
        val issues = CacheValidator.evaluateLight(
            entities = emptyList(),
            rawContacts = 0,
            displayContacts = 10,
            rawRecents = 0,
            displayRecents = 5,
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun evaluateLight_bothDirtyAndRepairRequired_flagsBothReasons() {
        val entities = listOf(
            CacheMetadataEntity(
                domain = CacheMetadataDomain.CONTACTS_DISPLAY,
                dirty = true,
                repairRequired = true,
                lastMutationReason = "interrupted",
            ),
        )
        val issues = CacheValidator.evaluateLight(
            entities = entities,
            rawContacts = 5,
            displayContacts = 5,
            rawRecents = 0,
            displayRecents = 0,
        )
        assertEquals(2, issues.size)
        assertTrue(issues.any { it.reason == CacheValidator.IssueReason.METADATA_DIRTY })
        assertTrue(issues.any { it.reason == CacheValidator.IssueReason.METADATA_REPAIR_REQUIRED })
    }
}
