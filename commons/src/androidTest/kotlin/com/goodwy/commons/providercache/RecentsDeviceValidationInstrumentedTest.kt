package com.goodwy.commons.providercache

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goodwy.commons.providercache.debug.CompareOnlySoakCounters
import com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger
import com.goodwy.commons.providercache.display.CanonicalPhoneNumberResolver
import com.goodwy.commons.providercache.display.ContactDisplayChanged
import com.goodwy.commons.providercache.display.ContactDisplayDeleted
import com.goodwy.commons.providercache.display.RecentGroupBuildContextLoader
import com.goodwy.commons.providercache.display.RecentGroupDualWriteValidator
import com.goodwy.commons.providercache.display.RecentGroupRelationalBuilder
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.display.RecentMutationPlan
import com.goodwy.commons.providercache.display.RecentMutationPlanResolver
import com.goodwy.commons.providercache.display.RelationalRecentDisplaySnapshotBuilder
import com.goodwy.commons.providercache.display.RelationalRecentsGroupingFlags
import com.goodwy.commons.providercache.display.RelationalRecentsReadMode
import com.goodwy.commons.providercache.entities.CallLogEntity
import com.goodwy.commons.providercache.transaction.ProviderCacheTransactions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-validation scenarios for Recents mutation plans + authority compare.
 * Does not change relational grouping semantics — validates instrumentation surfaces.
 */
@RunWith(AndroidJUnit4::class)
class RecentsDeviceValidationInstrumentedTest {

    private lateinit var database: ProviderCacheDatabase

    @Before
    fun setUp() {
        ProviderCacheDebugLogger.isEnabled = true
        CompareOnlySoakCounters.reset()
        RelationalRecentsGroupingFlags.readMode = RelationalRecentsReadMode.COMPARE_ONLY
        database = ProviderCacheDatabase.createInMemory(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
    }

    @After
    fun tearDown() {
        CompareOnlySoakCounters.reset()
        RelationalRecentsGroupingFlags.readMode = RelationalRecentsReadMode.LEGACY_ONLY
        ProviderCacheDebugLogger.isEnabled = false
        ProviderCacheDatabase.destroyInstance()
    }

    @Test
    fun emptyRecents_dualWriteAndCompareValid() = runBlocking {
        rebuildBothModes()
        val dual = RecentGroupDualWriteValidator.validateDualWrite(database, RecentGroupingMode.BY_NUMBER)
        assertTrue(dual.valid)
        val compare = RelationalRecentDisplaySnapshotBuilder.compareWithLegacyDisplay(
            database = database,
            mode = RecentGroupingMode.BY_NUMBER,
            includeDisplayFields = true,
        )
        CompareOnlySoakCounters.recordAuthorityCompare(compare.valid, compare.cosmeticMismatchCount)
        android.util.Log.d(
            TAG,
            "recentAuthorityCompare mode=BY_NUMBER legacy=${compare.legacyGroupCount} " +
                "relational=${compare.relationalGroupCount} mismatches=${compare.allMismatches} valid=${compare.valid}",
        )
        assertTrue(compare.valid)
        assertEquals(0, compare.legacyGroupCount)
    }

    @Test
    fun populatedRecents_authorityCompareValid() = runBlocking {
        ProviderCacheTestFixtures.seedContactGraph(database, contactId = 1, phone = "5551000")
        database.callLogDao().insertAll(listOf(call(10, "5551000", ts = 3000L, contactId = 1)))
        rebuildBothModes()

        val dual = RecentGroupDualWriteValidator.validateDualWrite(database, RecentGroupingMode.BY_NUMBER)
        assertTrue("dual=${dual.mismatches}", dual.valid)
        val compare = RelationalRecentDisplaySnapshotBuilder.compareWithLegacyDisplay(
            database = database,
            mode = RecentGroupingMode.BY_NUMBER,
            includeDisplayFields = true,
        )
        CompareOnlySoakCounters.recordAuthorityCompare(compare.valid, compare.cosmeticMismatchCount)
        android.util.Log.d(
            TAG,
            "recentAuthorityCompare mode=BY_NUMBER legacy=${compare.legacyGroupCount} " +
                "relational=${compare.relationalGroupCount} mismatches=${compare.allMismatches} valid=${compare.valid}",
        )
        compare.displayMismatches.forEach { mismatch ->
            android.util.Log.d(
                TAG,
                "recentAuthorityDisplayMismatch mode=BY_NUMBER key=${mismatch.semanticKey} " +
                    "field=${mismatch.field} old=${mismatch.oldValue} new=${mismatch.newValue}",
            )
        }
        assertTrue("authorityMismatches=${compare.mismatches}", compare.valid)
        assertTrue(CompareOnlySoakCounters.snapshot().compareTotal >= 1L)
    }

    @Test
    fun contactDelete_mutationPlanIsMultiGroup() {
        val plan = RecentMutationPlanResolver.forContactDelete(
            deleted = ContactDisplayDeleted(
                contactId = 1,
                lookupKey = "lk1",
                phoneDigits = listOf("5551000"),
                normalizedNumbers = listOf("5551000"),
            ),
            oldGroupKeys = setOf("contact:1", CanonicalPhoneNumberResolver.numberGroupKey("5551000")),
        )
        assertTrue(plan is RecentMutationPlan.MultiGroupRebuild)
        assertEquals("CONTACT_DELETE", plan.reason)
        android.util.Log.d(TAG, "recentMutationPlan reason=${plan.reason} plan=${plan.planKind}")
    }

    @Test
    fun sharedNumberDelete_ownerChangePlan() {
        val plan = RecentMutationPlanResolver.forSharedNumberOwnerChange(
            numberDigits = "5551000",
            oldOwnerContactId = 1L,
            newOwnerContactId = 2L,
        )
        assertTrue(plan is RecentMutationPlan.MultiGroupRebuild)
        assertEquals("SHARED_NUMBER_OWNER_CHANGE", plan.reason)
        android.util.Log.d(TAG, "recentMutationPlan reason=${plan.reason} plan=${plan.planKind}")
    }

    @Test
    fun phoneEdit_mutationPlanCoversOldAndNewNumbers() {
        val plan = RecentMutationPlanResolver.forContactDisplayChange(
            ContactDisplayChanged(
                contactId = 1,
                lookupKey = "lk",
                oldName = "Alice",
                newName = "Alice",
                oldPhotoThumbUri = "",
                newPhotoThumbUri = "",
                phoneDigits = listOf("5559999"),
                normalizedNumbers = listOf("5559999"),
                oldPhoneDigits = listOf("5551000"),
                oldNormalizedNumbers = listOf("5551000"),
            ),
        )
        assertTrue(plan is RecentMutationPlan.MultiGroupRebuild)
        assertEquals("CONTACT_PHONE_EDIT", plan.reason)
        val affected = (plan as RecentMutationPlan.MultiGroupRebuild).affected
        assertTrue(affected.affectedNumbers.contains("5551000"))
        assertTrue(affected.affectedNumbers.contains("5559999"))
        android.util.Log.d(TAG, "recentMutationPlan reason=${plan.reason} plan=${plan.planKind}")
    }

    @Test
    fun mergeInference_fromSharedNumberBatchDelete() {
        val plan = RecentMutationPlanResolver.forContactDeleteBatch(
            deleted = listOf(
                ContactDisplayDeleted(1, "lk1", phoneDigits = listOf("555"), normalizedNumbers = listOf("555")),
                ContactDisplayDeleted(2, "lk2", phoneDigits = listOf("555"), normalizedNumbers = listOf("555")),
            ),
            oldGroupKeysByContact = mapOf(
                1 to setOf("contact:1"),
                2 to setOf("contact:2"),
            ),
        )
        assertTrue(plan is RecentMutationPlan.MultiGroupRebuild)
        assertEquals("CONTACT_MERGE_INFERRED", plan.reason)
        android.util.Log.d(TAG, "recentMutationPlan reason=${plan.reason} plan=${plan.planKind}")
    }

    private suspend fun rebuildBothModes() {
        val calls = RecentGroupBuildContextLoader.loadCalls(database, 1000)
        val context = RecentGroupBuildContextLoader.load(database)
        listOf(RecentGroupingMode.BY_NUMBER, RecentGroupingMode.BY_CONTACT).forEach { mode ->
            val relational = if (calls.isEmpty()) {
                RecentGroupRelationalBuilder.BuildResult(emptyList(), emptyList(), emptyList())
            } else {
                RecentGroupRelationalBuilder.build(calls, mode, context)
            }
            ProviderCacheTransactions.replaceRecentGroupingTables(database, mode, relational)
            val displayRows = RelationalRecentDisplaySnapshotBuilder.buildDisplayRowsFromRelationalGroups(
                database = database,
                mode = mode,
                groupByContactFlag = mode == RecentGroupingMode.BY_CONTACT,
            )
            database.recentDisplayCacheDao().replaceAllCold(displayRows, mode.dbValue)
        }
    }

    private fun call(id: Int, number: String, ts: Long, contactId: Int? = null) = CallLogEntity(
        callId = id,
        phoneNumber = number,
        cachedName = "",
        cachedPhotoUri = "",
        startTS = ts,
        duration = 0,
        type = 1,
        simID = 0,
        normalizedNumber = number,
        contactID = contactId,
    )

    companion object {
        private const val TAG = "RecentsDeviceValidation"
    }
}
