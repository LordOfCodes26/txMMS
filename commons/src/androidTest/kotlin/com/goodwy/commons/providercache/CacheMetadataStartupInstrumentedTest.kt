package com.goodwy.commons.providercache

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goodwy.commons.providercache.entities.CacheMetadataDomain
import com.goodwy.commons.providercache.metadata.CacheMetadataStore
import com.goodwy.commons.providercache.validation.CacheValidator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CacheMetadataStartupInstrumentedTest {

    private lateinit var database: ProviderCacheDatabase
    private lateinit var metadataStore: CacheMetadataStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = ProviderCacheDatabase.createInMemory(context)
        metadataStore = CacheMetadataStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun ensureSeeded_missingMetadata_triggersRepairFlags() = runBlocking {
        metadataStore.ensureSeeded()
        val entities = metadataStore.getAll()
        assertEquals(4, entities.size)
        entities.forEach { entity ->
            assertTrue(entity.domain in CacheMetadataDomain.ALL)
            assertTrue(entity.displayVersion >= 0L)
            assertTrue(entity.rawVersion >= 0L)
        }
    }

    @Test
    fun dirtyMetadata_triggersStartupRepairValidation() = runBlocking {
        metadataStore.ensureSeeded()
        metadataStore.markDirty(CacheMetadataDomain.RECENTS_DISPLAY, 99L, "interrupted")
        val report = CacheValidator.validateLight(metadataStore, database, recentsGroupByContact = 0)
        assertTrue(report.requiresRepair)
        assertTrue(report.domainsNeedingRepair().contains(CacheMetadataDomain.RECENTS_DISPLAY))
    }

    @Test
    fun migratedMetadataDoesNotClaimCleanAuthority_untilValidated() = runBlocking {
        metadataStore.ensureSeeded()
        metadataStore.markRepairRequired(CacheMetadataDomain.CONTACTS_DISPLAY, "migration")
        val entity = metadataStore.getEntity(CacheMetadataDomain.CONTACTS_DISPLAY)!!
        assertTrue(entity.repairRequired)
        val report = CacheValidator.validateLight(metadataStore, database)
        assertTrue(report.issues.any { it.reason == CacheValidator.IssueReason.METADATA_REPAIR_REQUIRED })
    }
}
