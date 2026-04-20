package com.goodwy.commons.fragments

import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.provider.CallLog
import android.telephony.TelephonyManager
import android.view.View
import androidx.fragment.app.Fragment
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.adapters.BlockedCallItem
import com.goodwy.commons.adapters.BlockedCallsListAdapter
import com.goodwy.commons.R
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.getSharedPrefs
import com.goodwy.commons.extensions.trimToComparableNumber
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
    }

    override fun onResume() {
        super.onResume()
        loadBlockedCallLogs()
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
        val ctx = context ?: return
        Thread {
            val rawBlockedCalls = ArrayList<BlockedCallItem>()
            val projection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.DATE,
                CallLog.Calls.TYPE,
                CallLog.Calls.PHONE_ACCOUNT_ID,
            )
            val selection = "${CallLog.Calls.TYPE} = ?"
            val selectionArgs = arrayOf(CallLog.Calls.BLOCKED_TYPE.toString())
            val sortOrder = "${CallLog.Calls.DATE} DESC"
            val phoneCount = getPhoneCount()

            try {
                ctx.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(CallLog.Calls._ID)
                    val numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                    val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                    val accountIdIdx = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)

                    while (cursor.moveToNext()) {
                        val callLogId = if (idIdx != -1) cursor.getLong(idIdx) else -1L
                        val number = if (numberIdx != -1) cursor.getString(numberIdx).orEmpty() else ""
                        val cachedName = if (nameIdx != -1) cursor.getString(nameIdx) else null
                        val date = if (dateIdx != -1) cursor.getLong(dateIdx) else 0L
                        val accountId = if (accountIdIdx != -1) cursor.getString(accountIdIdx) else null

                        rawBlockedCalls.add(
                            BlockedCallItem(
                                callLogId = callLogId,
                                displayName = cachedName,
                                phoneNumber = number,
                                timestamp = date,
                                simId = resolveSimId(accountId, phoneCount),
                                allCallLogIds = listOf(callLogId).filter { it > 0L },
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }

            val groupedBlockedCalls = groupBlockedCalls(
                calls = rawBlockedCalls,
                groupByContact = shouldGroupByContact()
            )

            view?.post {
                if (!isAdded) return@post
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
        }.start()
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
        val context = context ?: return
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
}
