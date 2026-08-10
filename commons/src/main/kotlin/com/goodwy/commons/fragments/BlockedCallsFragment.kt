package com.goodwy.commons.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.android.common.R as CommonR
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.adapters.BlockedCallItem
import com.goodwy.commons.adapters.BlockedCallsListAdapter
import com.goodwy.commons.R
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.getBlockedNumbers
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.extensions.isDefaultDialer
import com.goodwy.commons.extensions.isNumberBlocked
import com.goodwy.commons.extensions.isPhoneNumber
import com.goodwy.commons.extensions.launchActivityIntent
import com.goodwy.commons.extensions.telecomManager
import com.goodwy.commons.extensions.trimToComparableNumber
import com.goodwy.commons.helpers.CONTACT_ID
import com.goodwy.commons.helpers.PERMISSION_READ_CALL_LOG
import com.goodwy.commons.helpers.PERMISSION_READ_CONTACTS
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.helpers.resolveSimAccountIconTintForSimCardIndex
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

    /**
     * List [paddingTop] is normally [CommonR.dimen.tx_top_bar_expand_height] under the app bar.
     * In action mode the CAB is shorter, so shrink the inset to avoid a gap under "N selected".
     */
    fun syncListTopInset(inActionMode: Boolean) {
        if (!::blockedCallsList.isInitialized) return
        val topInset = if (inActionMode) {
            val chrome = activity?.findViewById<View>(R.id.action_mode_toolbar_container)
            chrome?.takeIf { it.isLaidOut && it.bottom > 0 }?.bottom
                ?: (resources.getDimensionPixelSize(CommonR.dimen.tx_top_bar_toolbar_margin_top) +
                    resources.getDimensionPixelSize(CommonR.dimen.tx_top_bar_toolbar_height))
        } else {
            resources.getDimensionPixelSize(CommonR.dimen.tx_top_bar_expand_height)
        }
        if (blockedCallsList.paddingTop == topInset) return
        blockedCallsList.setPadding(
            blockedCallsList.paddingLeft,
            topInset,
            blockedCallsList.paddingRight,
            blockedCallsList.paddingBottom,
        )
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
            val simMaps = buildSimAccountMaps(ctx)
            val byId = LinkedHashMap<Long, BlockedCallItem>()

            readCallLogRows(
                ctx = ctx,
                selection = "${CallLog.Calls.TYPE} = ?",
                selectionArgs = arrayOf(CallLog.Calls.BLOCKED_TYPE.toString()),
                simMaps = simMaps,
            ).forEach { item ->
                if (item.callLogId > 0L) {
                    byId[item.callLogId] = item
                }
            }

            // Many OEMs never write BLOCKED_TYPE when CallScreening rejects a call (see issuetracker 130081372).
            if (byId.isEmpty() && ctx.isDefaultDialer()) {
                readRecentCallsForBlockedNumbers(ctx, simMaps).forEach { item ->
                    if (item.callLogId > 0L) {
                        byId.putIfAbsent(item.callLogId, item)
                    }
                }
            }

            val groupedBlockedCalls = resolveMissingContactNames(
                ctx = ctx,
                calls = groupBlockedCalls(calls = byId.values.toList()),
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
        ctx: Context,
        selection: String?,
        selectionArgs: Array<String>?,
        simMaps: SimAccountMaps,
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
                appendRowsFromCursor(cursor, simMaps, result)
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
        ctx: Context,
        simMaps: SimAccountMaps,
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
                    parseRow(cursor, simMaps)?.let { result.add(it) }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun appendRowsFromCursor(cursor: Cursor, simMaps: SimAccountMaps, out: ArrayList<BlockedCallItem>) {
        while (cursor.moveToNext()) {
            parseRow(cursor, simMaps)?.let { out.add(it) }
        }
    }

    private fun parseRow(cursor: Cursor, simMaps: SimAccountMaps): BlockedCallItem? {
        val callLogId = cursor.getLong(CALL_ID_INDEX)
        if (callLogId <= 0L) return null
        val number = cursor.getString(CALL_NUMBER_INDEX).orEmpty()
        val cachedName = cursor.getString(CALL_NAME_INDEX)
        val date = cursor.getLong(CALL_DATE_INDEX)
        val accountId = cursor.getString(CALL_ACCOUNT_INDEX)
        val (simId, simColor) = resolveSim(accountId, simMaps)
        return BlockedCallItem(
            callLogId = callLogId,
            displayName = cachedName,
            phoneNumber = number,
            timestamp = date,
            simId = simId,
            simColor = simColor,
            allCallLogIds = listOf(callLogId),
        )
    }

    /**
     * Build PHONE_ACCOUNT_ID → SIM slot / tint maps the same way RecentsHelper does via
     * call-capable phone accounts (not TelephonyManager.phoneCount).
     */
    @SuppressLint("MissingPermission")
    private fun buildSimAccountMaps(ctx: Context): SimAccountMaps {
        val idMap = HashMap<String, Int>()
        val colorMap = HashMap<String, Int>()
        val isHuawei = Build.MANUFACTURER.contains(Regex("huawei|honor", RegexOption.IGNORE_CASE))
        try {
            val handles = ctx.telecomManager.callCapablePhoneAccounts
            val subscriptionInfos = loadActiveSubscriptionInfos(ctx)
            handles.forEachIndexed { index, handle ->
                val simId = index + 1
                val phoneAccount = try {
                    ctx.telecomManager.getPhoneAccount(handle)
                } catch (_: Exception) {
                    null
                }
                val subInfo = matchSubscriptionInfo(handle.id, subscriptionInfos, phoneAccount?.extras)
                val tint = iconTintOrNull(subInfo)
                    ?: phoneAccount?.highlightColor?.takeIf { it != 0 }
                    ?: ctx.resolveSimAccountIconTintForSimCardIndex(simId)
                    ?: ctx.getProperPrimaryColor()
                fun putKey(key: String?) {
                    if (key.isNullOrBlank()) return
                    idMap[key] = simId
                    colorMap[key] = tint
                }
                putKey(handle.id)
                subInfo?.let {
                    putKey(it.subscriptionId.toString())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        putKey(it.iccId?.trim())
                    }
                    putKey(it.simSlotIndex.toString())
                }
            }
        } catch (_: Exception) {
        }
        return SimAccountMaps(
            accountIdToSimId = idMap,
            accountIdToSimColor = colorMap,
            simCount = idMap.values.toSet().size,
            isHuawei = isHuawei,
            fallbackPrimaryColor = ctx.getProperPrimaryColor(),
        )
    }

    @SuppressLint("MissingPermission")
    private fun loadActiveSubscriptionInfos(ctx: Context): List<SubscriptionInfo> {
        return try {
            val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            sm?.activeSubscriptionInfoList?.sortedBy { it.simSlotIndex }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun matchSubscriptionInfo(
        handleId: String,
        infos: List<SubscriptionInfo>,
        extras: android.os.Bundle?,
    ): SubscriptionInfo? {
        if (infos.isEmpty()) return null
        infos.firstOrNull { info ->
            info.subscriptionId.toString() == handleId ||
                info.simSlotIndex.toString() == handleId ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    !info.iccId.isNullOrBlank() && info.iccId == handleId)
        }?.let { return it }

        handleId.toIntOrNull()?.let { parsed ->
            infos.firstOrNull { it.subscriptionId == parsed }?.let { return it }
        }

        val subIdFromExtras = extras?.let { bundle ->
            val keys = listOf(
                "android.telecom.extra.SUBSCRIPTION_ID",
                "subscription_id",
                "SubscriptionId",
                "sub_id",
                "SUBSCRIPTION_ID",
            )
            for (key in keys) {
                if (!bundle.containsKey(key)) continue
                val raw = bundle.get(key) ?: continue
                val subId = when (raw) {
                    is Int -> raw
                    is Long -> raw.toInt()
                    is String -> raw.toIntOrNull() ?: continue
                    else -> continue
                }
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) return@let subId
            }
            null
        }
        if (subIdFromExtras != null) {
            infos.firstOrNull { it.subscriptionId == subIdFromExtras }?.let { return it }
        }
        return null
    }

    private fun iconTintOrNull(info: SubscriptionInfo?): Int? {
        val tint = info?.iconTint ?: return null
        if (tint == 0 || Color.alpha(tint) == 0) return null
        return tint
    }

    private fun resolveSim(accountId: String?, simMaps: SimAccountMaps): Pair<Int, Int> {
        if (simMaps.simCount <= 1 || accountId.isNullOrBlank()) {
            return -1 to 0
        }
        var simId = simMaps.accountIdToSimId[accountId] ?: -1
        var simColor = simMaps.accountIdToSimColor[accountId] ?: 0

        // Fallback: some OEMs store only the slot index in PHONE_ACCOUNT_ID.
        if (simId == -1 && accountId.length == 1) {
            accountId.toIntOrNull()?.let { slot ->
                val calculated = when {
                    slot < 0 -> -1
                    simMaps.isHuawei -> slot + 1
                    else -> slot
                }
                if (calculated in 1..simMaps.simCount) {
                    simId = calculated
                }
            }
        }

        if (simId == -1 && accountId.length >= 4) {
            for ((key, id) in simMaps.accountIdToSimId) {
                if (key.length < 4) continue
                if (accountId == key || accountId.contains(key) || key.contains(accountId)) {
                    simId = id
                    simColor = simMaps.accountIdToSimColor[key] ?: simColor
                    break
                }
            }
        }

        if (simId != -1 && simColor == 0) {
            simColor = simMaps.accountIdToSimId.entries
                .firstOrNull { it.value == simId }
                ?.let { simMaps.accountIdToSimColor[it.key] }
                ?: simMaps.fallbackPrimaryColor
        }
        return simId to simColor
    }

    private data class SimAccountMaps(
        val accountIdToSimId: Map<String, Int>,
        val accountIdToSimColor: Map<String, Int>,
        val simCount: Int,
        val isHuawei: Boolean,
        val fallbackPrimaryColor: Int,
    )

    /**
     * Group by dialable digits (same identity as Recents), never by [BlockedCallItem.displayName].
     * OEM call-log rows for one number often disagree on CACHED_NAME; name-keyed grouping
     * previously produced duplicate list rows (e.g. "Test" + raw number).
     */
    private fun groupBlockedCalls(calls: List<BlockedCallItem>): List<BlockedCallItem> {
        if (calls.isEmpty()) return emptyList()

        val grouped = LinkedHashMap<String, MutableList<BlockedCallItem>>()
        calls.forEach { call ->
            val key = call.phoneNumber.trimToComparableNumber()
            grouped.getOrPut(key) { mutableListOf() }.add(call)
        }

        return grouped.values.map { group ->
            val latest = group.maxByOrNull { it.timestamp }!!
            val preferredName = group
                .asSequence()
                .filter { !it.displayName.isNullOrBlank() }
                .maxByOrNull { it.timestamp }
                ?.displayName
            val mergedIds = group.flatMap { it.allCallLogIds }.filter { it > 0L }.distinct()
            latest.copy(
                displayName = preferredName ?: latest.displayName,
                groupedCount = group.size,
                allCallLogIds = mergedIds,
            )
        }.sortedByDescending { it.timestamp }
    }

    /**
     * A rejected call usually never gets a [CallLog.Calls.CACHED_NAME] written for it, so a blocked
     * contact would show as a raw number. Fill the name in from Contacts, the same source Recents
     * labels the number with. Runs after grouping so it is one lookup per number, not per row.
     */
    private fun resolveMissingContactNames(ctx: Context, calls: List<BlockedCallItem>): List<BlockedCallItem> {
        if (calls.isEmpty() || !ctx.hasPermission(PERMISSION_READ_CONTACTS)) return calls

        return calls.map { call ->
            val cachedName = call.displayName?.trim()
            if (!cachedName.isNullOrEmpty() && !cachedName.isPhoneNumber()) return@map call
            val contactName = lookupContactNameForPhone(ctx, call.phoneNumber)
            if (contactName == null) call else call.copy(displayName = contactName)
        }
    }

    /** Same gate as [lookupContactIdForPhone]: never fuzzy-match a short number onto a contact. */
    private fun lookupContactNameForPhone(ctx: Context, phone: String): String? {
        val dialedDigits = phone.filter { it.isDigit() }
        if (dialedDigits.length < 7) return null
        return try {
            val uri = android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI
                .buildUpon()
                .appendPath(android.net.Uri.encode(phone))
                .build()
            ctx.contentResolver.query(
                uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() && it != phone.trim() }
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openBlockedCallDetails(item: BlockedCallItem) {
        val activity = activity ?: return
        val phone = item.phoneNumber.trim()
        if (phone.isBlank()) return

        val displayName = item.displayName?.trim()?.takeIf {
            it.isNotEmpty() && it != phone && !it.isPhoneNumber()
        }

        ensureBackgroundThread {
            val contactId = lookupContactIdForPhone(activity, phone)
            activity.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val intent = Intent(ACTION_VIEW_DIALER_RECENT_DETAIL).apply {
                    setPackage(activity.packageName)
                    when {
                        contactId != null && contactId > 0 -> putExtra(CONTACT_ID, contactId)
                        else -> {
                            putExtra(EXTRA_DIALER_PHONE_NUMBER, phone)
                            if (displayName != null) {
                                putExtra(EXTRA_DIALER_DISPLAY_NAME, displayName)
                            }
                        }
                    }
                }
                try {
                    activity.launchActivityIntent(intent)
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Resolve a contact id for [phone] when PhoneLookup can match it (same gate as Recents:
     * skip fuzzy short-number lookups).
     */
    private fun lookupContactIdForPhone(ctx: Context, phone: String): Int? {
        val dialedDigits = phone.filter { it.isDigit() }
        if (dialedDigits.length < 7) return null
        return try {
            val uri = android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI
                .buildUpon()
                .appendPath(android.net.Uri.encode(phone))
                .build()
            ctx.contentResolver.query(
                uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup._ID),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0).takeIf { it > 0 } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val CALL_LOG_SCAN_LIMIT = 500

        /** Same action as dialer Recents → [com.android.contacts.activities.ViewContactActivity]. */
        private const val ACTION_VIEW_DIALER_RECENT_DETAIL =
            "com.android.contacts.action.VIEW_DIALER_RECENT_DETAIL"
        private const val EXTRA_DIALER_PHONE_NUMBER = "phone_number"
        private const val EXTRA_DIALER_DISPLAY_NAME = "display_name"

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
