package com.goodwy.commons.providercache.pipeline

import com.goodwy.commons.providercache.display.RecentGroupingMode
import com.goodwy.commons.providercache.pending.CallLogChangeKind
import com.goodwy.commons.providercache.pending.EvidenceSource
import com.goodwy.commons.providercache.sync.CallLogSyncChangeSet

/**
 * External events submitted to [com.android.dialer.providercache.RecentsPipelineCoordinator].
 * Components must not independently drive sync/preview/reconcile once the coordinator is attached.
 */
sealed interface RecentsPipelineEvent {
    data class Startup(val startupGeneration: Long = 0L) : RecentsPipelineEvent

    data class AppResumed(val recentsVisible: Boolean) : RecentsPipelineEvent

    data object AppPaused : RecentsPipelineEvent

    data class TabVisibilityChanged(val visible: Boolean) : RecentsPipelineEvent

    data class CallLogObserverTriggered(
        val observedAt: Long,
        val source: ObserverSource,
    ) : RecentsPipelineEvent

    data class CallLogSyncCompleted(val result: CallLogSyncResult) : RecentsPipelineEvent

    data class OutgoingDialRegistered(val tokenId: Long) : RecentsPipelineEvent

    data class OutgoingCallDisconnected(
        val tokenId: Long?,
        val connected: Boolean,
        val disconnectReason: String?,
        val callAttemptId: String? = null,
    ) : RecentsPipelineEvent

    data class DisplayCacheCommitted(
        val version: Long,
        val mutationReason: String,
        val committedAtMillis: Long = System.currentTimeMillis(),
    ) : RecentsPipelineEvent

    data class ContactDisplayChanged(val contactIds: Set<Long>) : RecentsPipelineEvent

    data class GroupingModeChanged(val mode: RecentGroupingMode) : RecentsPipelineEvent

    data object SearchOpened : RecentsPipelineEvent
    data object SearchClosed : RecentsPipelineEvent
    data object DialpadOpened : RecentsPipelineEvent
    data object DialpadClosed : RecentsPipelineEvent
    data object ManualRefresh : RecentsPipelineEvent
    data object CallHistoryCleared : RecentsPipelineEvent

    data class FilterChanged(val reason: String = "filter") : RecentsPipelineEvent
}

enum class ObserverSource {
    GLOBAL,
    UI,
    RESUME,
    MANUAL,
    TAB_VISIBLE,
    OUTGOING_CATCH_UP,
}

/** Alias for ownership docs; same values as [ObserverSource] GLOBAL/UI. */
enum class CallLogObserverSource {
    GLOBAL,
    UI,
}

enum class RecentsSyncReason {
    GLOBAL_OBSERVER,
    UI_OBSERVER,
    APP_RESUME,
    TAB_VISIBLE,
    OUTGOING_CATCH_UP,
    MANUAL_REFRESH,
    STARTUP,
}

fun ObserverSource.toSyncReason(): RecentsSyncReason = when (this) {
    ObserverSource.GLOBAL -> RecentsSyncReason.GLOBAL_OBSERVER
    ObserverSource.UI -> RecentsSyncReason.UI_OBSERVER
    ObserverSource.RESUME -> RecentsSyncReason.APP_RESUME
    ObserverSource.MANUAL -> RecentsSyncReason.MANUAL_REFRESH
    ObserverSource.TAB_VISIBLE -> RecentsSyncReason.TAB_VISIBLE
    ObserverSource.OUTGOING_CATCH_UP -> RecentsSyncReason.OUTGOING_CATCH_UP
}

data class CallLogSyncResult(
    val syncGeneration: Long,
    val insertedCallIds: List<Long> = emptyList(),
    val deletedCallIds: Set<Long> = emptySet(),
    val clearedAll: Boolean = false,
    val wasColdRebuild: Boolean = false,
    val displayVersionAfter: Long? = null,
) {
    val changeKind: CallLogChangeKind
        get() = when {
            clearedAll || wasColdRebuild && insertedCallIds.isEmpty() && deletedCallIds.isEmpty() ->
                if (clearedAll) CallLogChangeKind.CLEAR_ALL else CallLogChangeKind.UNKNOWN
            clearedAll -> CallLogChangeKind.CLEAR_ALL
            deletedCallIds.isNotEmpty() && insertedCallIds.isEmpty() -> CallLogChangeKind.DELETE
            insertedCallIds.isNotEmpty() -> CallLogChangeKind.POSSIBLE_INSERT
            else -> CallLogChangeKind.UNKNOWN
        }

    companion object {
        fun fromChangeSet(
            generation: Long,
            changes: CallLogSyncChangeSet,
            displayVersionAfter: Long?,
        ): CallLogSyncResult = CallLogSyncResult(
            syncGeneration = generation,
            insertedCallIds = changes.insertedCallIds.map { it.toLong() },
            deletedCallIds = changes.deletedCallIds.map { it.toLong() }.toSet(),
            clearedAll = changes.wasColdRebuild &&
                changes.insertedCallIds.isEmpty() &&
                changes.deletedCallIds.isEmpty(),
            wasColdRebuild = changes.wasColdRebuild,
            displayVersionAfter = displayVersionAfter,
        )
    }
}

enum class RecentsUiMode {
    NORMAL,
    SEARCHING,
    DIALPAD,
}

enum class OutgoingCatchUpState {
    NONE,
    TOKEN_REGISTERED,
    PREVIEW_PENDING,
    PREVIEW_PUBLISHED,
    SYNCING,
    INSERT_CONFIRMED,
    WAITING_FOR_DISPLAY_COMMIT,
    WAITING_FOR_VISIBLE_PAINT,
    COMPLETED,
    CANCELLED,
    EXPIRED,
}

data class RecentsPipelineState(
    val generation: Long = 0L,
    val recentsVisible: Boolean = false,
    val appResumed: Boolean = false,
    val uiMode: RecentsUiMode = RecentsUiMode.NORMAL,
    val groupingMode: RecentGroupingMode = RecentGroupingMode.BY_NUMBER,
    val visibleDisplayVersion: Long = 0L,
    val targetDisplayVersion: Long = 0L,
    val activeSyncGeneration: Long? = null,
    val activeCatchUpTokenId: Long? = null,
    val activePreviewTokenId: Long? = null,
    val previewPublishedVersion: Long? = null,
    val latestPublishedGeneration: Long = 0L,
    val pendingAuthoritativeReconcile: Boolean = false,
    val catchUpState: OutgoingCatchUpState = OutgoingCatchUpState.NONE,
    val versionAtCatchUpStart: Long = 0L,
)

enum class PublishKind {
    PREVIEW,
    AUTHORITATIVE,
}

data class RecentsPublishEnvelope(
    val pipelineGeneration: Long,
    val displayVersion: Long?,
    val kind: PublishKind,
    val authoritativeBaseVersion: Long,
    val tokenId: Long? = null,
)

data class ConfirmedPendingInsert(
    val tokenId: Long,
    val callId: Long,
    val startTimestamp: Long,
    val source: EvidenceSource,
)

enum class PublishResult {
    ACCEPTED,
    DISCARDED_STALE_GENERATION,
    DISCARDED_AUTHORITATIVE_NEWER,
    DISCARDED_TOKEN_REPLACED,
}
