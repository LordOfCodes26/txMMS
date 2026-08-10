package com.goodwy.commons.providercache.grouping

import com.goodwy.commons.providercache.display.StartupDomainOwner
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RecentGroupingRepairCoordinatorTest {

    @After
    fun tearDown() {
        RecentGroupingRepairCoordinator.resetForTests()
        StartupDomainOwner.reset()
    }

    @Test
    fun concurrentRequests_coalesceToOneRepair() = runBlocking {
        val runs = AtomicInteger(0)
        val results = (1..5).map {
            async {
                RecentGroupingRepairCoordinator.requestRepair(
                    mode = 1,
                    reason = RepairReason.STARTUP_EMPTY_MEMBERSHIP,
                    sourceVersion = 10L,
                ) {
                    runs.incrementAndGet()
                    delay(50)
                    true
                }
            }
        }.awaitAll()
        assertEquals(1, runs.get())
        assertTrue(
            results.count { it is RepairRequestResult.Started || it is RepairRequestResult.Coalesced } >= 1,
        )
    }
}
