package com.goodwy.commons.providercache.debug

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Debug-session NDJSON logger (session 89a76a). Posts to the host ingest endpoint
 * (requires `adb reverse tcp:7454 tcp:7454`) and mirrors to logcat.
 */
object AgentDebugSessionLog {
    private const val TAG = "DBG89a76a"
    private const val ENDPOINT = "http://127.0.0.1:7454/ingest/11a82323-1a93-4d01-a480-bcf87c2fea4b"
    private const val SESSION_ID = "89a76a"
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Off by default — this harness belongs to a finished investigation and its call sites are
     * per-row.
     *
     * It builds a JSONObject and attempts an HTTP POST for every row it sees. With the ingest
     * endpoint absent (it needs `adb reverse tcp:7454 tcp:7454`) each POST still costs a connect
     * attempt against an 800ms timeout on a single-threaded executor, and the JSON build plus
     * `Log.i` land on the caller. Across 304 recents rows that showed up as ~3.1s inside the
     * ENRICH stage, on the pipeline's own worker, with `runId=pre-fix` still stamped on every line.
     *
     * Flip to `true` (and set up the reverse tunnel) to resume a session.
     */
    private const val ENABLED = false

    fun log(
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
        runId: String = "pre-fix",
    ) {
        @Suppress("KotlinConstantConditions")
        if (!ENABLED) return
        val payload = JSONObject()
            .put("sessionId", SESSION_ID)
            .put("runId", runId)
            .put("hypothesisId", hypothesisId)
            .put("location", location)
            .put("message", message)
            .put("timestamp", System.currentTimeMillis())
            .put("data", JSONObject(data.mapValues { (_, v) -> v ?: JSONObject.NULL }))
        val line = payload.toString()
        runCatching { Log.i(TAG, line) }
        executor.execute {
            runCatching {
                val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-Debug-Session-Id", SESSION_ID)
                    doOutput = true
                    connectTimeout = 800
                    readTimeout = 800
                }
                conn.outputStream.use { it.write(line.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            }
        }
    }
}
