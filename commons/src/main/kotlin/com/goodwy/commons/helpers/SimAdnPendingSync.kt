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

private const val PREFS_NAME = "sim_adn_pending_sync"
private const val KEY_QUEUE = "queue"
private const val MAX_QUEUE = 40
private const val TAG = "SimContactSave"

/** Standard platform broadcast; not always available as a field on Intent. */
private const val ACTION_SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED"

private val flushLock = Any()

/**
 * Queued when Icc ADN insert/update fails (SIM out, airplane mode, radio off). Flushed when
 * [Intent.ACTION_SIM_STATE_CHANGED] or [Intent.ACTION_AIRPLANE_MODE_CHANGED] suggests the SIM may be usable again.
 */
data class SimAdnPendingEntry(
    val rawContactId: Int,
    val accountName: String,
    val accountType: String,
    val number: String,
    val displayName: String,
    val previousNumber: String? = null,
    val previousDisplayName: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("rawContactId", rawContactId)
        put("accountName", accountName)
        put("accountType", accountType)
        put("number", number)
        put("displayName", displayName)
        if (previousNumber != null) put("previousNumber", previousNumber)
        if (previousDisplayName != null) put("previousDisplayName", previousDisplayName)
    }

    companion object {
        fun fromJson(o: JSONObject): SimAdnPendingEntry {
            val prevNum = if (o.has("previousNumber") && !o.isNull("previousNumber")) o.optString("previousNumber") else null
            val prevName = if (o.has("previousDisplayName") && !o.isNull("previousDisplayName")) o.optString("previousDisplayName") else null
            return SimAdnPendingEntry(
                rawContactId = o.optInt("rawContactId", 0),
                accountName = o.optString("accountName", ""),
                accountType = o.optString("accountType", ""),
                number = o.optString("number", ""),
                displayName = o.optString("displayName", ""),
                previousNumber = prevNum?.takeIf { it.isNotEmpty() },
                previousDisplayName = prevName?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

object SimAdnPendingSync {

    @Volatile
    private var receiverRegistered = false

    private var installedReceiver: BroadcastReceiver? = null

    /**
     * Register once on [android.app.Application] for SIM / airplane events (process lifetime).
     */
    fun registerSimRetryReceivers(application: Context) {
        if (receiverRegistered) return
        synchronized(this) {
            if (receiverRegistered) return
            val appCtx = application.applicationContext
            val filter = IntentFilter().apply {
                addAction(ACTION_SIM_STATE_CHANGED)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            }
            val receiver = SimAdnRetryBroadcastReceiver()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    appCtx.registerReceiver(receiver, filter)
                }
                installedReceiver = receiver
                receiverRegistered = true
                Log.d(TAG, "SimAdnPendingSync: registered SIM/airplane receivers")
            } catch (e: Exception) {
                Log.e(TAG, "SimAdnPendingSync: registerReceiver failed", e)
            }
        }
    }

    fun enqueueAfterAdnFailure(context: Context, entry: SimAdnPendingEntry) {
        if (entry.rawContactId <= 0) {
            Log.w(TAG, "SimAdnPendingSync: skip enqueue invalid rawId=${entry.rawContactId}")
            return
        }
        val prefs = prefs(context.applicationContext)
        val list = readQueue(prefs).toMutableList()
        list.removeAll { it.rawContactId == entry.rawContactId }
        list.add(entry)
        while (list.size > MAX_QUEUE) list.removeAt(0)
        writeQueue(prefs, list)
        Log.d(TAG, "SimAdnPendingSync: enqueued rawId=${entry.rawContactId} queueSize=${list.size}")
        val appCtx = context.applicationContext
        appCtx.toast(
            appCtx.getString(
                R.string.sim_adn_progress_queued,
                list.size,
                entry.displayName.take(40),
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
                app.toast(app.getString(R.string.sim_adn_progress_sync_start, queue.size))
                val remaining = mutableListOf<SimAdnPendingEntry>()
                var okCount = 0
                for (entry in queue) {
                    val useUpdatePath = entry.previousNumber != null ||
                        !entry.previousDisplayName.isNullOrEmpty()
                    val ok = if (useUpdatePath) {
                        app.tryUpdateIccAdnAfterSimContactEdit(
                            accountName = entry.accountName,
                            accountType = entry.accountType,
                            previousPrimaryNumber = entry.previousNumber ?: "",
                            previousDisplayName = entry.previousDisplayName ?: "",
                            newPrimaryNumber = entry.number,
                            newDisplayName = entry.displayName,
                            logContext = "pendingFlush rawId=${entry.rawContactId}",
                        )
                    } else {
                        app.tryInsertIccAdnForSimContact(
                            accountName = entry.accountName,
                            accountType = entry.accountType,
                            number = entry.number,
                            displayName = entry.displayName,
                            logContext = "pendingFlush rawId=${entry.rawContactId}",
                        )
                    }
                    if (!ok) {
                        remaining.add(entry)
                    } else {
                        okCount++
                        Log.d(TAG, "SimAdnPendingSync: flush OK rawId=${entry.rawContactId}")
                    }
                }
                writeQueue(prefs, remaining)
                app.toast(app.getString(R.string.sim_adn_progress_sync_done, okCount, remaining.size))
            }
        }
    }

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readQueue(prefs: SharedPreferences): List<SimAdnPendingEntry> {
        val json = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { SimAdnPendingEntry.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "SimAdnPendingSync: readQueue failed, clearing", e)
            emptyList()
        }
    }

    private fun writeQueue(prefs: SharedPreferences, list: List<SimAdnPendingEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_QUEUE, arr.toString()).apply()
    }
}

private class SimAdnRetryBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_SIM_STATE_CHANGED -> {
                val state = intent.getStringExtra("ss")
                Log.d("SimContactSave", "SimAdnRetryBroadcastReceiver SIM_STATE_CHANGED ss=$state")
                if (state == "ABSENT") return
                SimAdnPendingSync.flushPending(context.applicationContext)
            }
            Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                if (!intent.getBooleanExtra("state", false)) {
                    SimAdnPendingSync.flushPending(context.applicationContext)
                }
            }
        }
    }
}
