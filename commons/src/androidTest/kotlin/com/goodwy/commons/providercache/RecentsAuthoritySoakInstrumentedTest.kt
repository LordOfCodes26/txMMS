package com.goodwy.commons.providercache

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goodwy.commons.providercache.debug.CompareOnlySoakCounters
import com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger
import com.goodwy.commons.providercache.debug.RecentsAuthoritySoakSessionManager
import com.goodwy.commons.providercache.display.RecentGroupBuildContextLoader
import com.goodwy.commons.providercache.display.RecentGroupDualWriteValidator
import com.goodwy.commons.providercache.display.RecentGroupRelationalBuilder
import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.display.RelationalRecentDisplaySnapshotBuilder
import com.goodwy.commons.providercache.display.RelationalRecentsGroupingFlags
import com.goodwy.commons.providercache.display.RelationalRecentsReadMode
import com.goodwy.commons.providercache.entities.CallLogEntity
import com.goodwy.commons.providercache.grouping.RecentAuthorityPathLogger
import com.goodwy.commons.providercache.grouping.RecentGroupMembershipChecksum
import com.goodwy.commons.providercache.grouping.withMembershipChecksums
import com.goodwy.commons.providercache.transaction.ProviderCacheTransactions
import com.goodwy.commons.providercache.validation.RecentDisplayCacheValidator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator/device COMPARE_ONLY soak harness — generates many compare/checksum samples.
 * Does not enable release relational authority.
 */
@RunWith(AndroidJUnit4::class)
class RecentsAuthoritySoakInstrumentedTest {

    private lateinit var database: ProviderCacheDatabase

    @Before
    fun setUp() {
        ProviderCacheDebugLogger.isEnabled = true
        RecentAuthorityPathLogger.resetViolations()
        RecentsAuthoritySoakSessionManager.startSoakSession(
            buildVersion = "instrumented",
            databaseVersion = 17,
            mode = RelationalRecentsReadMode.COMPARE_ONLY,
        )
        database = ProviderCacheDatabase.createInMemory(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
    }

    @After
    fun tearDown() {
        val dump = RecentsAuthoritySoakSessionManager.dumpSoakSession()
        android.util.Log.i(TAG, dump)
        RecentsAuthoritySoakSessionManager.stopSoakSession()
        RelationalRecentsGroupingFlags.readMode = RelationalRecentsReadMode.LEGACY_ONLY
        ProviderCacheDebugLogger.isEnabled = false
        ProviderCacheDatabase.destroyInstance()
    }

    @Test
    fun soak_manyRebuildCompareAndChecksumCycles() = runBlocking {
        ProviderCacheTestFixtures.seedContactGraph(database, contactId = 1, phone = "5551000")
        ProviderCacheTestFixtures.seedContactGraph(database, contactId = 2, phone = "5552000")
        val baseCalls = (1..80).map { idx ->
            call(
                id = idx,
                number = if (idx % 2 == 0) "5551000" else "5552000",
                ts = 10_000L - idx,
                contactId = if (idx % 2 == 0) 1 else 2,
            )
        }
        database.callLogDao().insertAll(baseCalls)

        // Produce 1200+ compare samples across modes.
        repeat(600) { cycle ->
            // mutate a call lightly so rebuild work isn't a pure no-op every time
            if (cycle % 10 == 0) {
                val mutated = baseCalls.first().copy(startTS = 20_000L + cycle)
                database.callLogDao().insertAll(listOf(mutated))
            }
            rebuildBothModesWithChecksum()
            listOf(RecentGroupingMode.BY_NUMBER, RecentGroupingMode.BY_CONTACT).forEach { mode ->
                val dual = RecentGroupDualWriteValidator.validateDualWrite(database, mode)
                CompareOnlySoakCounters.recordDualWrite(dual.valid, dual.mismatches.size)
                val compare = RelationalRecentDisplaySnapshotBuilder.compareWithLegacyDisplay(
                    database = database,
                    mode = mode,
                    includeDisplayFields = true,
                )
                CompareOnlySoakCounters.recordAuthorityCompare(
                    compare.valid,
                    compare.cosmeticMismatchCount,
                )
                val validation = RecentDisplayCacheValidator.validate(database, mode.dbValue)
                assertTrue(
                    "cycle=$cycle mode=$mode issues=${validation.issues.take(3)}",
                    validation.issues.none {
                        it.reason == RecentDisplayCacheValidator.IssueReason.MEMBERSHIP_CHECKSUM_MISMATCH
                    },
                )
            }
        }

        val session = RecentsAuthoritySoakSessionManager.currentOrNull()
        requireNotNull(session)
        android.util.Log.i(
            TAG,
            "soakTotals semantic=${session.semanticCompareTotal}/${session.semanticMismatch} " +
                "checksum=${session.checksumCompareTotal}/${session.checksumMismatch} " +
                "dual=${session.dualWriteTotal}/${session.dualWriteMismatch} " +
                "violations=${session.authorityPathViolations} " +
                "displayMismatch=${session.displayMismatch} gates=${session.passesApprovalGates()}",
        )
        // Diagnose last BY_NUMBER dual-write shape once for the soak report.
        val dualNumber = RecentGroupDualWriteValidator.validateDualWrite(
            database,
            RecentGroupingMode.BY_NUMBER,
        )
        dualNumber.semanticMismatches.take(5).forEach { mismatch ->
            android.util.Log.w(
                TAG,
                "soakMismatchClass=IDENTITY/MEMBERSHIP field=${mismatch.field} " +
                    "key=${mismatch.semanticKey} old=${mismatch.oldValue} new=${mismatch.newValue}",
            )
        }
        assertTrue("semanticCompareTotal=${session.semanticCompareTotal}", session.semanticCompareTotal >= 1000L)
        assertTrue("checksumCompareTotal=${session.checksumCompareTotal}", session.checksumCompareTotal >= 1000L)
        assertTrue("dualWriteTotal=${session.dualWriteTotal}", session.dualWriteTotal >= 1000L)
        assertEquals("semanticMismatch", 0L, session.semanticMismatch)
        assertEquals("checksumMismatch", 0L, session.checksumMismatch)
        assertEquals("dualWriteMismatch", 0L, session.dualWriteMismatch)
        assertEquals("authorityPathViolations", 0L, session.authorityPathViolations)
        assertTrue("approval gates", session.passesApprovalGates())
    }

    private suspend fun rebuildBothModesWithChecksum() {
        val calls = RecentGroupBuildContextLoader.loadCalls(database, 1000)
        val context = RecentGroupBuildContextLoader.load(database)
        listOf(RecentGroupingMode.BY_NUMBER, RecentGroupingMode.BY_CONTACT).forEach { mode ->
            val relational = RecentGroupRelationalBuilder.build(calls, mode, context)
                .withMembershipChecksums(mode)
            ProviderCacheTransactions.replaceRecentGroupingTables(database, mode, relational)
            database.recentDisplayCacheDao().clearForMode(mode.dbValue)
            val displayRows = RelationalRecentDisplaySnapshotBuilder.buildDisplayRowsFromRelationalGroups(
                database = database,
                mode = mode,
                groupByContactFlag = mode == RecentGroupingMode.BY_CONTACT,
            )
            if (displayRows.isNotEmpty()) {
                database.recentDisplayCacheDao().insertAll(displayRows)
            }
            // Explicit checksum recording parity with production validator.
            relational.groups.forEach { group ->
                val callIds = relational.calls.filter { it.groupKey == group.groupKey }.map { it.callId }
                val numbers = relational.numbers.filter { it.groupKey == group.groupKey }.map { it.normalizedNumber }
                val expected = RecentGroupMembershipChecksum.computeForGroup(mode, group, callIds, numbers)
                CompareOnlySoakCounters.recordChecksumCompare(expected == group.membershipChecksum)
            }
        }
    }

    private fun call(id: Int, number: String, ts: Long, contactId: Int?) = CallLogEntity(
        callId = id,
        phoneNumber = number,
        cachedName = "",
        cachedPhotoUri = "",
        startTS = ts,
        duration = 0,
        type = 1,
        simID = -1,
        simTypeID = 1,
        simColor = 0,
        contactID = contactId,
        features = null,
        isUnknownNumber = contactId == null,
        isVoiceMail = false,
        blockReason = null,
        phoneAccountId = "",
        normalizedNumber = number,
    )

    companion object {
        private const val TAG = "RecentsAuthoritySoak"
    }
}
