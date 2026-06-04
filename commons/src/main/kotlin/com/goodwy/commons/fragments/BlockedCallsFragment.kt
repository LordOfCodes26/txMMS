package com.goodwy.commons.fragments

import android.content.ContentUris
import android.content.Intent
import android.database.Cursor
import android.os.Bundle
import android.provider.CallLog
import android.telephony.TelephonyManager
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.adapters.BlockedCallItem
import com.goodwy.commons.adapters.BlockedCallsListAdapter
import com.goodwy.commons.R
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.getBlockedNumbers
import com.goodwy.commons.extensions.getSharedPrefs
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.extensions.isDefaultDialer
import com.goodwy.commons.extensions.isNumberBlocked
import com.goodwy.commons.extensions.trimToComparableNumber
import com.goodwy.commons.helpers.PERMISSION_READ_CALL_LOG
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.views.MyRecyclerView
import com.android.common.view.MRippleToolBar
import eightbitlab.com.blurview.BlurTarget

class BlockedCallsFragment : Fragment(R.layout.fragment_blocked_calls) {
    private lateinit var blockedCallsList: MyRecyclerView
    private lateinit var blockedCallsPlaceholder: View
    private lateinit var blockedCallsAdapter: BlockedCallsListAdapter

    private var appliedContactThumbnailSize: Int? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        blockedCallsList = view.findViewById(R.id.blocked_calls_list)
        blockedCallsPlaceholder = view.findViewById(R.id.blocked_calls_placeholder)
        val activity = requireActivity() as BaseSimpleActivity
        blockedCallsAdapter = BlockedCallsListAdapter(
            activity = activity,
            recyclerView = blockedCallsList,
            isMultiSimSupported = getPhoneCount() > 1,
            onOpenItem = { item -> openBlockedCallDetails(item) },
            onListRefresh = { loadBlockedCallLogs() },
        )

        blockedCallsList.layoutManager = LinearLayoutManager(requireContext())
        blockedCallsList.adapter = blockedCallsAdapter
        loadBlockedCallLogs()
    }

    override fun onResume() {
        super.onResume()
        if (::blockedCallsAdapter.isInitialized) {
            loadBlockedCallLogs()
        }
    }

    fun refreshBlockedCallLogs() {
        if (::blockedCallsAdapter.isInitialized) {
            loadBlockedCallLogs()
        }
    }

    /** @return true if action mode was started */
    fun tryStartSelectionActionMode(): Boolean {
        if (!::blockedCallsAdapter.isInitialized || blockedCallsAdapter.itemCount == 0) return false
        blockedCallsAdapter.startActMode()
        return true
    }

    fun bindRippleToolbarIfNeeded(ripple: MRippleToolBar, blurTarget: BlurTarget) {
        if (!::blockedCallsAdapter.isInitialized || !blockedCallsAdapter.isActionModeActive()) {
            ripple.visibility = View.GONE
            return
        }
        blockedCallsAdapter.bindRippleToolbar(ripple, blurTarget)
    }

    /** @return true if this fragment had selection mode and it was closed */
    fun finishSelectionActionModeIfActive(): Boolean {
        if (::blockedCallsAdapter.isInitialized && blockedCallsAdapter.isActionModeActive()) {
            blockedCallsAdapter.finishActMode()
            return true
        }
        return false
    }

    private fun loadBlockedCallLogs() {
        val activity = activity as? BaseSimpleActivity ?: return
        if (!activity.hasPermission(PERMISSION_READ_CALL_LOG)) {
            activity.handlePermission(PERMISSION_READ_CALL_LOG) { granted ->
                if (granted && isAdded) {
                    queryBlockedCallLogs()
                } else {
                    publishBlockedCallLogs(emptyList())
                }
            }
            return
        }
        queryBlockedCallLogs()
    }

    private fun queryBlockedCallLogs() {
        val ctx = context ?: return
        ensureBackgroundThread {
            val phoneCount = getPhoneCount()
            val byId = LinkedHashMap<Long, BlockedCallItem>()

            readCallLogRows(
                ctx = ctx,
                selection = "${CallLog.Calls.TYPE} = ?",
                selectionArgs = arrayOf(CallLog.Calls.BLOCKED_TYPE.toString()),
                phoneCount = phoneCount,
            ).forEach { item ->
                if (item.callLogId > 0L) {
                    byId[item.callLogId] = item
                }
            }

            // Many OEMs never write BLOCKED_TYPE when CallScreening rejects a call (see issuetracker 130081372).
            if (byId.isEmpty() && ctx.isDefaultDialer()) {
                readRecentCallsForBlockedNumbers(ctx, phoneCount).forEach { item ->
                    if (item.callLogId > 0L) {
                        byId.putIfAbsent(item.callLogId, item)
                    }
                }
            }

            val groupedBlockedCalls = groupBlockedCalls(
                calls = byId.values.toList(),
                groupByContact = shouldGroupByContact(),
            )
            publishBlockedCallLogs(groupedBlockedCalls)
        }
    }

    private fun publishBlockedCallLogs(groupedBlockedCalls: List<BlockedCallItem>) {
        val activity = activity ?: return
        activity.runOnUiThread {
            if (!isAdded || !::blockedCallsAdapter.isInitialized) return@runOnUiThread
            val cfg = requireContext().baseConfig.contactThumbnailsSize
            val thumbDirty = appliedContactThumbnailSize != null && appliedContactThumbnailSize != cfg
            appliedContactThumbnailSize = cfg
            blockedCallsAdapter.submitList(groupedBlockedCalls) {
                if (thumbDirty) blockedCallsAdapter.notifyDataSetChanged()
            }
            val hasBlockedCalls = groupedBlockedCalls.isNotEmpty()
            blockedCallsList.isVisible = hasBlockedCalls
            blockedCallsPlaceholder.isVisible = !hasBlockedCalls
        }
    }

    private fun readCallLogRows(
        ctx: android.content.Context,
        selection: String?,
        selectionArgs: Array<String>?,
        phoneCount: Int,
    ): List<BlockedCallItem> {
        val projection = CALL_LOG_PROJECTION
        val sortOrder = "${CallLog.Calls.DATE} DESC"
        val result = ArrayList<BlockedCallItem>()
        try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                appendRowsFromCursor(cursor, phoneCount, result)
            }
        } catch (_: Exception) {
        }
        return result
    }

    /**
     * Fallback when the call log has no [CallLog.Calls.BLOCKED_TYPE] rows: show recent incoming
     * missed/rejected calls whose numbers are on the blocked list.
     */
    private fun readRecentCallsForBlockedNumbers(
        ctx: android.content.Context,
        phoneCount: Int,
    ): List<BlockedCallItem> {
        val blockedNumbers = ctx.getBlockedNumbers()
        if (blockedNumbers.isEmpty()) return emptyList()

        val result = ArrayList<BlockedCallItem>()
        try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                CALL_LOG_PROJECTION,
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                var scanned = 0
                while (cursor.moveToNext() && scanned < CALL_LOG_SCAN_LIMIT) {
                    scanned++
                    val type = cursor.getInt(CALL_TYPE_INDEX)
                    if (type !in FALLBACK_CALL_TYPES) continue
                    val number = cursor.getString(CALL_NUMBER_INDEX).orEmpty()
                    if (number.isBlank() || !ctx.isNumberBlocked(number, blockedNumbers)) continue
                    parseRow(cursor, phoneCount)?.let { result.add(it) }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun appendRowsFromCursor(cursor: Cursor, phoneCount: Int, out: ArrayList<BlockedCallItem>) {
        while (cursor.moveToNext()) {
            parseRow(cursor, phoneCount)?.let { out.add(it) }
        }
    }

    private fun parseRow(cursor: Cursor, phoneCount: Int): BlockedCallItem? {
        val callLogId = cursor.getLong(CALL_ID_INDEX)
        if (callLogId <= 0L) return null
        val number = cursor.getString(CALL_NUMBER_INDEX).orEmpty()
        val cachedName = cursor.getString(CALL_NAME_INDEX)
        val date = cursor.getLong(CALL_DATE_INDEX)
        val accountId = cursor.getString(CALL_ACCOUNT_INDEX)
        return BlockedCallItem(
            callLogId = callLogId,
            displayName = cachedName,
            phoneNumber = number,
            timestamp = date,
            simId = resolveSimId(accountId, phoneCount),
            allCallLogIds = listOf(callLogId),
        )
    }

    private fun getPhoneCount(): Int {
        val context = context ?: return 1
        return try {
            val telephonyManager = context.getSystemService(TelephonyManager::class.java)
            telephonyManager?.phoneCount ?: 1
        } catch (_: Exception) {
            1
        }
    }

    private fun resolveSimId(accountId: String?, phoneCount: Int): Int {
        val numeric = accountId?.toIntOrNull() ?: return -1
        if (phoneCount <= 1) return -1
        return when {
            numeric in 1..phoneCount -> numeric
            numeric in 0 until phoneCount -> numeric + 1
            else -> -1
        }
    }

    private fun shouldGroupByContact(): Boolean {
        val context = context ?: return true
        return context.getSharedPrefs().getBoolean("group_all_calls_by_contact", true)
    }

    private fun groupBlockedCalls(
        calls: List<BlockedCallItem>,
        groupByContact: Boolean,
    ): List<BlockedCallItem> {
        if (calls.isEmpty()) return emptyList()

        val grouped = LinkedHashMap<String, MutableList<BlockedCallItem>>()
        calls.forEach { call ->
            val key = if (groupByContact) {
                call.displayName?.takeIf { it.isNotBlank() }?.lowercase()
                    ?: call.phoneNumber.trimToComparableNumber()
            } else {
                call.phoneNumber.trimToComparableNumber()
            }
            grouped.getOrPut(key) { mutableListOf() }.add(call)
        }

        return grouped.values.map { group ->
            val latest = group.maxByOrNull { it.timestamp }!!
            val mergedIds = group.flatMap { it.allCallLogIds }.filter { it > 0L }.distinct()
            latest.copy(
                groupedCount = group.size,
                allCallLogIds = mergedIds,
            )
        }.sortedByDescending { it.timestamp }
    }

    private fun openBlockedCallDetails(item: BlockedCallItem) {
        val activity = activity ?: return
        try {
            if (item.callLogId > 0) {
                val uri = ContentUris.withAppendedId(CallLog.Calls.CONTENT_URI, item.callLogId)
                activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
                return
            }
        } catch (_: Exception) {
        }

        if (item.phoneNumber.isNotBlank()) {
            try {
                val dialUri = android.net.Uri.fromParts("tel", item.phoneNumber, null)
                activity.startActivity(Intent(Intent.ACTION_DIAL, dialUri))
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val CALL_LOG_SCAN_LIMIT = 500

        private val CALL_LOG_PROJECTION = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE,
            CallLog.Calls.TYPE,
            CallLog.Calls.PHONE_ACCOUNT_ID,
        )

        private const val CALL_ID_INDEX = 0
        private const val CALL_NUMBER_INDEX = 1
        private const val CALL_NAME_INDEX = 2
        private const val CALL_DATE_INDEX = 3
        private const val CALL_TYPE_INDEX = 4
        private const val CALL_ACCOUNT_INDEX = 5

        /** Incoming attempts that were blocked but logged as missed/rejected instead of BLOCKED_TYPE. */
        private val FALLBACK_CALL_TYPES = setOf(
            CallLog.Calls.BLOCKED_TYPE,
            CallLog.Calls.MISSED_TYPE,
            CallLog.Calls.REJECTED_TYPE,
        )
    }
}
