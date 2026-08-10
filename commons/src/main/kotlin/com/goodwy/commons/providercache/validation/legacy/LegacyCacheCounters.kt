package com.goodwy.commons.providercache.validation.legacy

import java.util.concurrent.atomic.AtomicInteger

/**
 * Temporary debug counters for legacy-cache authority tests (Phase R).
 */
object LegacyCacheCounters {

    private val legacyReadCount = AtomicInteger(0)
    private val legacyWriteCount = AtomicInteger(0)
    private val legacyPaintAttemptCount = AtomicInteger(0)
    private val legacyPaintBlockedCount = AtomicInteger(0)

    fun recordRead() {
        legacyReadCount.incrementAndGet()
    }

    fun recordWrite() {
        legacyWriteCount.incrementAndGet()
    }

    fun recordPaintAttempt(blocked: Boolean) {
        legacyPaintAttemptCount.incrementAndGet()
        if (blocked) legacyPaintBlockedCount.incrementAndGet()
    }

    fun reset() {
        legacyReadCount.set(0)
        legacyWriteCount.set(0)
        legacyPaintAttemptCount.set(0)
        legacyPaintBlockedCount.set(0)
    }

    fun dump(): String =
        "legacyReadCount=${legacyReadCount.get()} " +
            "legacyWriteCount=${legacyWriteCount.get()} " +
            "legacyPaintAttemptCount=${legacyPaintAttemptCount.get()} " +
            "legacyPaintBlockedCount=${legacyPaintBlockedCount.get()}"
}
