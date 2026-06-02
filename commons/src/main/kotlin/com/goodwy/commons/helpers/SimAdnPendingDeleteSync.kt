package com.goodwy.commons.helpers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.goodwy.commons.R
import com.goodwy.commons.extensions.toast
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "sim_adn_pending_delete"
private const val KEY_QUEUE = "queue"
private const val MAX_QUEUE = 80
private const val TAG = "SimContactDelete"

/**
 * Hard cap on retries for one entry. Vendor IccProviders that consistently reject ADN deletes
 * (e.g. carrier-locked SIM, missing `MODIFY_PHONE_STATE`, unknown vendor error code) would
 * otherwise toast on every SIM_STATE_CHANGED forever — give up and drop the entry instead.
 */
private const val MAX_RETRY_ATTEMPTS = 5

/** Standard platform broadcast; not always available as a field on Intent. */
private const val ACTION_SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED"

private val flushLock = Any()

/**
 * One pending physical-SIM ADN delete. Captured before the matching `RawContacts` row is
 * removed so retries on SIM-ready broadcasts still have everything they need to address the
 * card entry — `RawContacts.ACCOUNT_NAME` / `index_in_sim` are gone after the row delete, and
 * the platform's automatic SIM-resync would otherwise re-create the contact in
 * `ContactsContract` from the still-present ADN entry, exactly the symptom users report as
 * "deleted SIM contact reappears after I reinsert the SIM".
 */
data class SimAdnPendingDeleteEntry(
    val rawContactId: Int,
    val accountName: String,
    val accountType: String,
    /** MediaTek subId from `indicate_phone_sim`; 0 on AOSP. Used by [Context.tryDeleteIccAdnByMtkSimIndex]. */
    val mtkSubId: Int,
    /** MediaTek `index_in_sim`; 0 on AOSP. */
    val indexInSim: Int,
    /** Already passed through [normalizeIccDialableNumber] — store digits only. */
    val normalizedNumber: String,
    val displayName: String,
    /** Times the retry loop has tried this entry; capped at [MAX_RETRY_ATTEMPTS]. */
    val attemptCount: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("rawContactId", rawContactId)
        put("accountName", accountName)
        put("accountType", accountType)
        put("mtkSubId", mtkSubId)
        put("indexInSim", indexInSim)
        put("normalizedNumber", normalizedNumber)
        put("displayName", displayName)
        put("attemptCount", attemptCount)
    }

    companion object {
        fun fromJson(o: JSONObject): SimAdnPendingDeleteEntry = SimAdnPendingDeleteEntry(
            rawContactId = o.optInt("rawContactId", 0),
            accountName = o.optString("accountName", ""),
            accountType = o.optString("accountType", ""),
            mtkSubId = o.optInt("mtkSubId", 0),
            indexInSim = o.optInt("indexInSim", 0),
            normalizedNumber = o.optString("normalizedNumber", ""),
            displayName = o.optString("displayName", ""),
            attemptCount = o.optInt("attemptCount", 0),
        )
    }
}

/**
 * Persistent retry queue for SIM ADN deletes that failed at delete time (SIM out, IccProvider
 * unavailable, transient permission denial, or no row matched the contact's number).
 *
 * Symmetrical to [SimAdnPendingSync] (which handles inserts/updates). Without this queue the
 * [android.provider.ContactsContract] `RawContacts` row is removed but the physical SIM ADN
 * entry remains, so the platform re-syncs it on next SIM ready and the "deleted" contact
 * reappears.
 */
object SimAdnPendingDeleteSync {

    @Volatile
    private var receiverRegistered = false

    private var installedReceiver: BroadcastReceiver? = null

    /** Register once on [android.app.Application] for SIM / airplane events (process lifetime). */
    fun registerSimRetryReceivers(application: Context) {
        if (receiverRegistered) return
        synchronized(this) {
            if (receiverRegistered) return
            val appCtx = application.applicationContext
            val filter = IntentFilter().apply {
                addAction(ACTION_SIM_STATE_CHANGED)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            }
            val receiver = SimAdnPendingDeleteRetryReceiver()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    appCtx.registerReceiver(receiver, filter)
                }
                installedReceiver = receiver
                receiverRegistered = true
                Log.d(TAG, "SimAdnPendingDeleteSync: registered SIM/airplane receivers")
            } catch (e: Exception) {
                Log.e(TAG, "SimAdnPendingDeleteSync: registerReceiver failed", e)
            }
        }
    }

    fun enqueueAfterAdnDeleteFailure(context: Context, entry: SimAdnPendingDeleteEntry) {
        if (entry.normalizedNumber.isEmpty() && entry.displayName.isEmpty() && entry.indexInSim <= 0) {
            Log.w(TAG, "SimAdnPendingDeleteSync: skip enqueue — nothing addressable rawId=${entry.rawContactId}")
            return
        }
        val prefs = prefs(context.applicationContext)
        val list = readQueue(prefs).toMutableList()
        // De-dupe: same SIM index on the same sub OR same number+account is one logical row.
        list.removeAll { existing ->
            (entry.indexInSim > 0 && existing.indexInSim == entry.indexInSim && existing.mtkSubId == entry.mtkSubId &&
                existing.accountName == entry.accountName && existing.accountType == entry.accountType) ||
                (entry.normalizedNumber.isNotEmpty() && existing.normalizedNumber == entry.normalizedNumber &&
                    existing.accountName == entry.accountName && existing.accountType == entry.accountType)
        }
        list.add(entry)
        while (list.size > MAX_QUEUE) list.removeAt(0)
        writeQueue(prefs, list)
        Log.d(TAG, "SimAdnPendingDeleteSync: enqueued rawId=${entry.rawContactId} queueSize=${list.size}")
        val appCtx = context.applicationContext
        appCtx.toast(
            appCtx.getString(
                R.string.sim_adn_delete_queued,
                list.size,
                entry.displayName.take(40).ifEmpty { entry.normalizedNumber.take(40) },
            ),
        )
    }

    fun flushPending(context: Context) {
        ensureBackgroundThread {
            val app = context.applicationContext
            synchronized(flushLock) {
                val prefs = prefs(app)
                val queue = readQueue(prefs)
                if (queue.isEmpty()) return@ensureBackgroundThread
                // Surface "Retrying N…" only on the *first* attempt cycle for the queue (any
                // entry still at attemptCount == 0). After that we retry silently and only
                // toast when something actually changes — see the "result" toast below.
                val isFirstAttemptCycle = queue.any { it.attemptCount == 0 }
                if (isFirstAttemptCycle) {
                    app.toast(app.getString(R.string.sim_adn_delete_sync_start, queue.size))
                }
                val remaining = mutableListOf<SimAdnPendingDeleteEntry>()
                var okCount = 0
                var droppedCount = 0
                for (entry in queue) {
                    val ok = retryDeleteOne(app, entry)
                    if (ok) {
                        okCount++
                        Log.d(TAG, "SimAdnPendingDeleteSync: flush OK rawId=${entry.rawContactId}")
                    } else {
                        val nextAttempt = entry.attemptCount + 1
                        if (nextAttempt >= MAX_RETRY_ATTEMPTS) {
                            droppedCount++
                            Log.w(
                                TAG,
                                "SimAdnPendingDeleteSync: giving up on rawId=${entry.rawContactId} after $nextAttempt attempts",
                            )
                        } else {
                            remaining.add(entry.copy(attemptCount = nextAttempt))
                        }
                    }
                }
                writeQueue(prefs, remaining)
                // Only toast the result when something actually moved — either we deleted an
                // entry from the SIM, or we gave up on one. A no-progress retry stays silent.
                if (okCount > 0 || droppedCount > 0) {
                    app.toast(app.getString(R.string.sim_adn_delete_sync_done, okCount, remaining.size))
                }
            }
        }
    }

    private fun retryDeleteOne(app: Context, entry: SimAdnPendingDeleteEntry): Boolean {
        // Strategy 1: MediaTek `index = N` on the subscription-scoped URI (most reliable).
        if (entry.indexInSim > 0 && entry.mtkSubId > 0) {
            if (
                app.tryDeleteIccAdnByMtkSimIndex(
                    mtkSubId = entry.mtkSubId,
                    indexInSim = entry.indexInSim,
                    accountName = entry.accountName,
                    accountType = entry.accountType,
                    logContext = "pendingDeleteFlush rawId=${entry.rawContactId}",
                )
            ) {
                return true
            }
        }
        // Strategy 2: AOSP — search ADN by phone number (with suffix fallback) / display name.
        if (entry.normalizedNumber.isNotEmpty() || entry.displayName.isNotEmpty()) {
            return app.tryDeleteIccAdnForSimContact(
                accountName = entry.accountName,
                accountType = entry.accountType,
                phoneNumber = entry.normalizedNumber,
                displayName = entry.displayName,
                logContext = "pendingDeleteFlush rawId=${entry.rawContactId}",
            )
        }
        return false
    }

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readQueue(prefs: SharedPreferences): List<SimAdnPendingDeleteEntry> {
        val json = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { SimAdnPendingDeleteEntry.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "SimAdnPendingDeleteSync: readQueue failed, clearing", e)
            emptyList()
        }
    }

    private fun writeQueue(prefs: SharedPreferences, list: List<SimAdnPendingDeleteEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_QUEUE, arr.toString()).apply()
    }
}

private class SimAdnPendingDeleteRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_SIM_STATE_CHANGED -> {
                val state = intent.getStringExtra("ss")
                Log.d(TAG, "SimAdnPendingDeleteRetryReceiver SIM_STATE_CHANGED ss=$state")
                if (state == "ABSENT") return
                SimAdnPendingDeleteSync.flushPending(context.applicationContext)
            }
            Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                if (!intent.getBooleanExtra("state", false)) {
                    SimAdnPendingDeleteSync.flushPending(context.applicationContext)
                }
            }
        }
    }
}
