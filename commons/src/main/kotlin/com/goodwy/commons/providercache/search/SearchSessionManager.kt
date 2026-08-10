package com.goodwy.commons.providercache.search

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Tracks active UI search sessions and assigns monotonic generations. */
class SearchSessionManager {

    private val activeSessions = ConcurrentHashMap<SearchMode, SearchSession>()
    private val generationCounter = AtomicLong(0L)

    fun nextGeneration(): Long = generationCounter.incrementAndGet()

    fun register(session: SearchSession) {
        activeSessions[session.mode] = session
    }

    fun unregister(mode: SearchMode) {
        activeSessions.remove(mode)
    }

    fun activeSessions(): List<SearchSession> = activeSessions.values.toList()

    fun peek(mode: SearchMode): SearchSession? = activeSessions[mode]
}
