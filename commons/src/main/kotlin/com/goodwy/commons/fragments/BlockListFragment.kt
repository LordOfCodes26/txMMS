package com.goodwy.commons.fragments

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.android.common.R as CommonR
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.adapters.BlockedNumbersListAdapter
import com.goodwy.commons.R
import com.goodwy.commons.dialogs.OptionListDialog
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.getBlockedNumbersWithContact
import com.goodwy.commons.extensions.launchCallIntent
import com.goodwy.commons.extensions.launchSendSMSIntent
import com.goodwy.commons.extensions.removeBlockedNumberEntry
import com.goodwy.commons.models.BlockedNumber
import com.goodwy.commons.views.MyRecyclerView
import com.android.common.view.MRippleToolBar
import com.goodwy.commons.dialogs.toOptionListItems
import eightbitlab.com.blurview.BlurTarget

open class BlockListFragment : Fragment(R.layout.fragment_block_list) {
    private lateinit var blockListRecycler: MyRecyclerView
    private lateinit var blockListPlaceholder: View
    private lateinit var blockListAdapter: BlockedNumbersListAdapter

    /** Last applied contact thumbnail size from settings; used to refresh rows after settings change. */
    private var appliedContactThumbnailSize: Int? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        blockListRecycler = view.findViewById(R.id.block_list_recycler)
        blockListPlaceholder = view.findViewById(R.id.block_list_placeholder)
        val activity = requireActivity() as BaseSimpleActivity
        blockListAdapter = BlockedNumbersListAdapter(
            activity = activity,
            recyclerView = blockListRecycler,
            onOpenItem = { item -> showBlockedNumberOptions(item) },
            onListRefresh = { loadBlockedNumbers() },
        )

        blockListRecycler.layoutManager = LinearLayoutManager(requireContext())
        blockListRecycler.adapter = blockListAdapter
    }

    override fun onResume() {
        super.onResume()
        loadBlockedNumbers()
    }

    fun refreshBlockedNumbersList() {
        if (::blockListAdapter.isInitialized) {
            loadBlockedNumbers()
        }
    }

    fun bindRippleToolbarIfNeeded(ripple: MRippleToolBar, blurTarget: BlurTarget) {
        if (!::blockListAdapter.isInitialized || !blockListAdapter.isActionModeActive()) {
            ripple.visibility = View.GONE
            return
        }
        blockListAdapter.bindRippleToolbar(ripple, blurTarget)
    }

    /** @return true if action mode was started */
    fun tryStartSelectionActionMode(): Boolean {
        if (!::blockListAdapter.isInitialized || blockListAdapter.itemCount == 0) return false
        blockListAdapter.startActMode()
        return true
    }

    /** @return true if this fragment had selection mode and it was closed */
    fun finishSelectionActionModeIfActive(): Boolean {
        if (::blockListAdapter.isInitialized && blockListAdapter.isActionModeActive()) {
            blockListAdapter.finishActMode()
            return true
        }
        return false
    }

    /**
     * List [paddingTop] is normally [CommonR.dimen.tx_top_bar_expand_height] under the app bar.
     * In action mode the CAB is shorter (~toolbar margin + height), so shrink the inset or a
     * white band appears between "N selected" and the first row.
     */
    fun syncListTopInset(inActionMode: Boolean) {
        if (!::blockListRecycler.isInitialized) return
        val topInset = if (inActionMode) {
            val chrome = activity?.findViewById<View>(R.id.action_mode_toolbar_container)
            chrome?.takeIf { it.isLaidOut && it.bottom > 0 }?.bottom
                ?: (resources.getDimensionPixelSize(CommonR.dimen.tx_top_bar_toolbar_margin_top) +
                    resources.getDimensionPixelSize(CommonR.dimen.tx_top_bar_toolbar_height))
        } else {
            resources.getDimensionPixelSize(CommonR.dimen.tx_top_bar_expand_height)
        }
        if (blockListRecycler.paddingTop == topInset) return
        blockListRecycler.setPadding(
            blockListRecycler.paddingLeft,
            topInset,
            blockListRecycler.paddingRight,
            blockListRecycler.paddingBottom,
        )
    }

    private fun loadBlockedNumbers() {
        val ctx = context ?: return
        ctx.getBlockedNumbersWithContact { blockedNumbers ->
            view?.post {
                if (!isAdded) return@post
                val cfg = requireContext().baseConfig.contactThumbnailsSize
                val thumbDirty = appliedContactThumbnailSize != null && appliedContactThumbnailSize != cfg
                appliedContactThumbnailSize = cfg
                blockListAdapter.submitList(blockedNumbers) {
                    if (thumbDirty) blockListAdapter.notifyDataSetChanged()
                }
                val hasItems = blockedNumbers.isNotEmpty()
                blockListRecycler.isVisible = hasItems
                blockListPlaceholder.isVisible = !hasItems
            }
        }
    }

    private fun showBlockedNumberOptions(blockedNumber: BlockedNumber) {
        val activity = activity as? BaseSimpleActivity ?: return
        val raw = blockedNumber.number.takeIf { it.isNotBlank() } ?: blockedNumber.normalizedNumber
        if (raw.isBlank()) return
        val title = blockedNumber.contactName?.takeIf { it.isNotBlank() } ?: raw
        val blurTarget = activity.findViewById<BlurTarget>(R.id.mainBlurTarget)
        OptionListDialog(
            activity = activity,
            title = title,
            options = listOf(
                getString(R.string.call) to { activity.launchCallIntent(raw) },
                getString(R.string.send_sms) to { activity.launchSendSMSIntent(raw) },
                getString(R.string.unblock) to {
                    activity.removeBlockedNumberEntry(blockedNumber)
                    loadBlockedNumbers()
                },
            ).toOptionListItems(),
            blurTarget = blurTarget,
        )
    }
}
