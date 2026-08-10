package com.goodwy.commons.providercache.debug

import android.util.Log

/**
 * Structured mutation tracing for provider-cache sync refactors.
 * Always logs at DEBUG; enable verbose inspection via [isVerbose].
 */
object CacheMutationLogger {

    const val TAG = "CacheMutation"

    @Volatile
    var isVerbose: Boolean = true

    enum class Domain {
        CONTACTS,
        RECENTS,
        BOTH,
    }

    enum class Stage {
        RAW_WRITE,
        DISPLAY_WRITE,
        VERSION,
        COMMIT,
        NOTIFY,
    }

    fun mutationStart(mutationId: Long, domain: Domain, reason: String) {
        Log.d(TAG, "cacheMutationStart id=$mutationId domain=$domain reason=$reason")
    }

    fun mutationStage(mutationId: Long, stage: Stage) {
        if (!isVerbose) return
        Log.d(TAG, "cacheMutationStage id=$mutationId stage=$stage")
    }

    fun mutationEnd(mutationId: Long, success: Boolean, version: Long, extra: String = "") {
        val suffix = if (extra.isEmpty()) "" else " $extra"
        Log.d(TAG, "cacheMutationEnd id=$mutationId success=$success version=$version$suffix")
    }

    fun mutationFailed(mutationId: Long, stage: Stage, error: String) {
        Log.w(TAG, "cacheMutationFailed id=$mutationId stage=$stage error=$error")
    }

    fun callLogIdDiff(
        provider: Int,
        room: Int,
        inserted: Int,
        deleted: Int,
        equalCountDifferentIds: Boolean,
    ) {
        Log.d(
            TAG,
            "callLogIdDiff provider=$provider room=$room inserted=$inserted deleted=$deleted " +
                "equalCountDifferentIds=$equalCountDifferentIds",
        )
    }

    fun callLogDeleteDetected(ids: Int, reason: String) {
        Log.d(TAG, "callLogDeleteDetected ids=$ids reason=$reason")
    }

    fun contactIdentityResolved(
        rawIds: Collection<Long>,
        aggregateId: Long,
        lookupKey: String?,
        phones: Int,
    ) {
        Log.d(
            TAG,
            "contactIdentityResolved rawIds=$rawIds aggregateId=$aggregateId " +
                "lookupKey=$lookupKey phones=$phones",
        )
    }

    fun contactIdentityMissing(inputType: String, id: Long) {
        Log.w(TAG, "contactIdentityMissing inputType=$inputType id=$id")
    }

    fun uiReconcileStart(domain: String, reason: String, visibleVersion: Long, targetVersion: Long) {
        Log.d(
            TAG,
            "uiReconcileStart domain=$domain reason=$reason visibleVersion=$visibleVersion " +
                "targetVersion=$targetVersion",
        )
    }

    fun uiReconcileNoOp(reason: String) {
        if (!isVerbose) return
        Log.d(TAG, "uiReconcileNoOp reason=$reason")
    }

    fun uiReconcileCoalesced(version: Long) {
        Log.d(TAG, "uiReconcileCoalesced version=$version")
    }

    fun uiReconcileEnd(visibleVersion: Long) {
        if (!isVerbose) return
        Log.d(TAG, "uiReconcileEnd visibleVersion=$visibleVersion")
    }

    fun cacheValidationStart(scope: String, issueCount: Int) {
        Log.d(TAG, "cacheValidationStart scope=$scope issues=$issueCount")
    }

    fun cacheValidationEnd(issueCount: Int, repaired: Boolean) {
        Log.d(TAG, "cacheValidationEnd issues=$issueCount repaired=$repaired")
    }
}
