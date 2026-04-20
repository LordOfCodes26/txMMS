package com.goodwy.commons.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.adapters.BlockedNumbersListAdapter
import com.goodwy.commons.R
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.getBlockedNumbersWithContact
import com.goodwy.commons.models.BlockedNumber
import com.goodwy.commons.views.MyRecyclerView
import com.android.common.view.MRippleToolBar
import eightbitlab.com.blurview.BlurTarget

class BlockListFragment : Fragment(R.layout.fragment_block_list) {
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
            onOpenItem = { item -> openDialerForNumber(item) },
            onListRefresh = { loadBlockedNumbers() },
        )

        blockListRecycler.layoutManager = LinearLayoutManager(requireContext())
        blockListRecycler.adapter = blockListAdapter
    }

    override fun onResume() {
        super.onResume()
        loadBlockedNumbers()
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

    private fun openDialerForNumber(blockedNumber: BlockedNumber) {
        val activity = activity ?: return
        val raw = blockedNumber.number.takeIf { it.isNotBlank() } ?: blockedNumber.normalizedNumber
        if (raw.isBlank()) return
        try {
            val dialUri = Uri.fromParts("tel", raw, null)
            activity.startActivity(Intent(Intent.ACTION_DIAL, dialUri))
        } catch (_: Exception) {
        }
    }
}
