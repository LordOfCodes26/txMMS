package com.goodwy.commons.providercache.display

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression: scheduleImmediate must release the mutex before runPendingRebuild.
 * Schedulers launch on Dispatchers.IO, so these use real-time waits (not TestDispatcher).
 */
class DisplayCacheRebuildSchedulerMutexTest {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    @After
    fun tearDown() {
        job.cancel()
        scope.cancel()
    }

    @Test
    fun recentDisplay_scheduleImmediate_completes() = runBlocking {
        val runs = AtomicInteger(0)
        val scheduler = RecentDisplayCacheRebuildScheduler(
            scope = scope,
            onComplete = { _, _ -> },
            debounceMs = 50L,
        )
        scheduler.rebuildHandler = {
            runs.incrementAndGet()
            RecentDisplayCacheRebuildResult.EMPTY
        }

        scheduler.scheduleImmediate(
            RecentDisplayRebuildRequest(
                reason = DisplayCacheRebuildReason.COLD_EMPTY_CACHE,
                groupByContact = true,
                forceFull = true,
            ),
        )
        withTimeout(3_000) {
            while (runs.get() < 1 || scheduler.isRebuildInProgress()) {
                delay(20)
            }
        }
        assertEquals(1, runs.get())
        assertFalse(scheduler.isRebuildInProgress())
    }

    @Test
    fun contactDisplay_scheduleImmediate_completes() = runBlocking {
        val runs = AtomicInteger(0)
        val scheduler = ContactDisplayCacheRebuildScheduler(
            scope = scope,
            onComplete = { },
            debounceMs = 50L,
        )
        scheduler.rebuildHandler = {
            runs.incrementAndGet()
        }

        scheduler.scheduleImmediate(
            ContactDisplayRebuildRequest(
                reason = DisplayCacheRebuildReason.COLD_EMPTY_CACHE,
                forceFull = true,
            ),
        )
        withTimeout(3_000) {
            while (runs.get() < 1 || scheduler.isRebuildInProgress()) {
                delay(20)
            }
        }
        assertEquals(1, runs.get())
        assertFalse(scheduler.isRebuildInProgress())
    }

    @Test
    fun recentDisplay_coalescedInsert_doesNotDowngradePendingColdFull() = runBlocking {
        val seen = mutableListOf<RecentDisplayRebuildRequest>()
        val scheduler = RecentDisplayCacheRebuildScheduler(
            scope = scope,
            onComplete = { _, _ -> },
            debounceMs = 80L,
        )
        scheduler.rebuildHandler = { request ->
            synchronized(seen) { seen += request }
            RecentDisplayCacheRebuildResult.EMPTY
        }

        scheduler.schedule(
            RecentDisplayRebuildRequest(
                reason = DisplayCacheRebuildReason.COLD_EMPTY_CACHE,
                groupByContact = true,
                forceFull = true,
            ),
        )
        delay(10)
        scheduler.schedule(
            RecentDisplayRebuildRequest(
                reason = DisplayCacheRebuildReason.CALL_LOG_INSERTED,
                groupByContact = true,
                insertedCallIds = setOf(99),
                forceFull = false,
            ),
        )
        withTimeout(3_000) {
            while (synchronized(seen) { seen.isEmpty() } || scheduler.isRebuildInProgress()) {
                delay(20)
            }
        }
        val request = synchronized(seen) { seen.single() }
        assertEquals(DisplayCacheRebuildReason.COLD_EMPTY_CACHE, request.reason)
        assertTrue(request.forceFull)
        assertTrue(request.insertedCallIds.contains(99))
    }
}
