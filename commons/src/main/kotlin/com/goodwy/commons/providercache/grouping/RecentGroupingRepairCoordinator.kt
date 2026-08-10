package com.goodwy.commons.providercache.grouping

import android.util.Log
import com.goodwy.commons.providercache.display.StartupDomainOwner
import com.goodwy.commons.providercache.startup.StartupSessionLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RepairReason {
    STARTUP_EMPTY_MEMBERSHIP,
    STARTUP_DEEP_VALIDATION,
    DISPLAY_INVALID_GROUPS,
    METADATA_REPAIR,
}

sealed class RepairRequestResult {
    data object Started : RepairRequestResult()
    data object Coalesced : RepairRequestResult()
    data object SkippedAlreadyValid : RepairRequestResult()
    data class Superseded(val reason: String) : RepairRequestResult()
}

/**
 * Single-flight coordinator for relational Recents grouping repairs.
 */
object RecentGroupingRepairCoordinator {

    private const val TAG = "recentGroupingRepair"

    private val mutex = Mutex()

    @Volatile
    private var activeKey: RepairKey? = null

    @Volatile
    private var activeCompletion: CompletableDeferred<Boolean>? = null

    private data class RepairKey(
        val mode: Int,
        val sourceVersion: Long,
        val generation: Long,
    )

    suspend fun requestRepair(
        mode: Int,
        reason: RepairReason,
        sourceVersion: Long,
        alreadyValid: Boolean = false,
        currentVersionProvider: () -> Long = { sourceVersion },
        currentModeProvider: () -> Int = { mode },
        repair: suspend () -> Boolean,
    ): RepairRequestResult {
        if (alreadyValid) {
            log("SKIPPED", mode, reason, "already_valid")
            return RepairRequestResult.SkippedAlreadyValid
        }
        if (currentVersionProvider() != sourceVersion) {
            log("SUPERSEDED", mode, reason, "VERSION_CHANGED")
            return RepairRequestResult.Superseded("VERSION_CHANGED")
        }
        if (currentModeProvider() != mode) {
            log("SUPERSEDED", mode, reason, "MODE_CHANGED")
            return RepairRequestResult.Superseded("MODE_CHANGED")
        }

        val leader = mutex.withLock {
            val generation = StartupDomainOwner.currentStartupGeneration()
            val key = RepairKey(mode = mode, sourceVersion = sourceVersion, generation = generation)
            val existing = activeCompletion
            if (activeKey == key && existing != null && !existing.isCompleted) {
                log("COALESCED", mode, reason, "in_flight")
                return@withLock existing to false
            }
            val completion = CompletableDeferred<Boolean>()
            activeKey = key
            activeCompletion = completion
            log("STARTED", mode, reason, "version=$sourceVersion")
            completion to true
        }

        if (!leader.second) {
            leader.first.await()
            return RepairRequestResult.Coalesced
        }

        val start = System.currentTimeMillis()
        val repairResult = runCatching {
            if (currentVersionProvider() != sourceVersion || currentModeProvider() != mode) {
                false
            } else {
                repair()
            }
        }.getOrDefault(false)
        val duration = System.currentTimeMillis() - start
        leader.first.complete(repairResult)
        mutex.withLock {
            if (activeCompletion == leader.first) {
                activeCompletion = null
                activeKey = null
            }
        }
        if (currentVersionProvider() != sourceVersion || currentModeProvider() != mode) {
            log("SUPERSEDED", mode, reason, "post_repair_version_changed durationMs=$duration")
            return RepairRequestResult.Superseded("VERSION_CHANGED")
        }
        StartupSessionLogger.log(
            domain = "RECENTS",
            stage = "GROUPING_REPAIR_COMPLETED",
            extra = "mode=$mode valid=$repairResult durationMs=$duration reason=${reason.name}",
        )
        Log.d(TAG, "recentGroupingRepair completed mode=$mode durationMs=$duration valid=$repairResult")
        return RepairRequestResult.Started
    }

    fun shouldSkipValidation(): Boolean {
        val completion = activeCompletion ?: return false
        return !completion.isCompleted
    }

    fun resetForTests() {
        activeKey = null
        activeCompletion = null
    }

    private fun log(action: String, mode: Int, reason: RepairReason, extra: String) {
        val msg = "recentGroupingRepair request=$action mode=$mode reason=${reason.name} $extra"
        Log.d(TAG, msg)
        StartupSessionLogger.log(domain = "RECENTS", stage = "GROUPING_REPAIR_$action", extra = msg)
    }
}
