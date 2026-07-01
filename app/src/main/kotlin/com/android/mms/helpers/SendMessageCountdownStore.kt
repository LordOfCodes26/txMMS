package com.android.mms.helpers

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

data class PendingSendCountdown(
    val threadId: Long,
    val messageText: String,
    val subscriptionId: Int,
    val attachmentsJson: String?,
    val startedAtMs: Long,
    val totalDelaySeconds: Int,
    /** JSON array of recipient numbers when compose started from [com.android.mms.activities.NewConversationActivity]. */
    val recipientNumbersJson: String? = null,
    val resumeInNewConversation: Boolean = false,
)

/**
 * In-memory pending send countdown keyed by thread id. Survives leaving [com.android.mms.activities.ThreadActivity]
 * so the delay keeps running and the compose UI can be restored on return.
 */
object SendMessageCountdownStore {
    private val pendingByThread = ConcurrentHashMap<Long, PendingSendCountdown>()
    private val finishListeners = ConcurrentHashMap<Long, (PendingSendCountdown) -> Unit>()
    private val scheduledFinishByThread = ConcurrentHashMap<Long, Runnable>()
    private val handler = Handler(Looper.getMainLooper())
    private var defaultFinishListener: ((PendingSendCountdown) -> Unit)? = null

    fun setDefaultFinishListener(listener: (PendingSendCountdown) -> Unit) {
        defaultFinishListener = listener
    }

    fun getRemainingSeconds(entry: PendingSendCountdown): Int {
        val elapsedSec = ((System.currentTimeMillis() - entry.startedAtMs) / 1000L).toInt()
        return (entry.totalDelaySeconds - elapsedSec).coerceAtLeast(0)
    }

    fun getElapsedSeconds(entry: PendingSendCountdown): Int {
        return (entry.totalDelaySeconds - getRemainingSeconds(entry)).coerceAtLeast(0)
    }

    fun isActive(threadId: Long): Boolean {
        val entry = pendingByThread[threadId] ?: return false
        return getRemainingSeconds(entry) > 0
    }

    fun get(threadId: Long): PendingSendCountdown? = pendingByThread[threadId]

    fun findActiveNewConversationPending(): PendingSendCountdown? {
        return pendingByThread.values.firstOrNull { pending ->
            pending.resumeInNewConversation && getRemainingSeconds(pending) > 0
        }
    }

    fun setFinishListener(threadId: Long, listener: (PendingSendCountdown) -> Unit) {
        if (threadId > 0L) {
            finishListeners[threadId] = listener
        }
    }

    fun removeFinishListener(threadId: Long) {
        finishListeners.remove(threadId)
    }

    fun start(entry: PendingSendCountdown) {
        cancel(threadId = entry.threadId, notifyListener = false)
        pendingByThread[entry.threadId] = entry
        scheduleFinish(entry.threadId)
    }

    fun cancel(threadId: Long, notifyListener: Boolean = true) {
        scheduledFinishByThread.remove(threadId)?.let { handler.removeCallbacks(it) }
        pendingByThread.remove(threadId)
        if (notifyListener) {
            finishListeners.remove(threadId)
        }
    }

    private fun scheduleFinish(threadId: Long) {
        val entry = pendingByThread[threadId] ?: return
        scheduledFinishByThread.remove(threadId)?.let { handler.removeCallbacks(it) }

        val remainingMs = getRemainingSeconds(entry) * 1000L
        val runnable = Runnable {
            val current = pendingByThread.remove(threadId)
            scheduledFinishByThread.remove(threadId)
            if (current != null) {
                finishListeners[current.threadId]?.invoke(current)
                    ?: defaultFinishListener?.invoke(current)
            }
        }
        scheduledFinishByThread[threadId] = runnable
        if (remainingMs <= 0L) {
            handler.post(runnable)
        } else {
            handler.postDelayed(runnable, remainingMs)
        }
    }

    /** Re-sync the wall-clock finish callback after the activity/view was recreated. */
    fun refreshScheduledFinish(threadId: Long) {
        if (pendingByThread.containsKey(threadId)) {
            scheduleFinish(threadId)
        }
    }
}
