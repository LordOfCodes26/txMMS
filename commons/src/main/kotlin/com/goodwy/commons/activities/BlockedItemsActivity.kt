package com.goodwy.commons.activities

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.android.common.R as CommonR
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import com.android.common.helper.IconItem
import com.android.common.view.MVSideFrame
import com.goodwy.commons.R
import com.goodwy.commons.databinding.ActivityBlockedItemsBinding
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.updateMarginWithBase
import com.goodwy.commons.extensions.updatePaddingWithBase
import com.goodwy.commons.extensions.onPageChangeListener
import com.goodwy.commons.extensions.viewBinding
import com.goodwy.commons.fragments.BlockedCallsFragment
import com.goodwy.commons.fragments.BlockListFragment
import com.goodwy.commons.fragments.BlockedContactsFragment
import com.goodwy.commons.fragments.BlockedMessagesFragment
import com.goodwy.commons.helpers.APP_ICON_IDS
import com.goodwy.commons.helpers.APP_LAUNCHER_NAME
import com.goodwy.commons.interfaces.ActionModeToolbarHost
import com.goodwy.commons.views.CustomToolbar
import com.qmdeve.liquidglass.view.LiquidGlassTabs
import eightbitlab.com.blurview.BlurTarget

open class BlockedItemsActivity : BaseSimpleActivity(), ActionModeToolbarHost {
    private val binding by viewBinding(ActivityBlockedItemsBinding::inflate)
    private var isProgrammaticTabSelection = false
    private var customToolbar: CustomToolbar? = null

    override fun getAppIconIDs() = intent.getIntegerArrayListExtra(APP_ICON_IDS) ?: ArrayList()

    override fun getAppLauncherName() = intent.getStringExtra(APP_LAUNCHER_NAME) ?: ""

    override fun getRepositoryName() = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
//        setupMVSideFrame()
        setupEdgeToEdge(
            padTopSystem = listOf(binding.mainMenu),
            padBottomSystem = listOf(binding.blockedItemsTabBar),
            moveBottomSystem = listOf(binding.actionModeRippleToolbar),
        )

        binding.mainMenu.requireCustomToolbar().apply {
            setNavigationIconViewVisible(true)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        setupOptionsMenu()
        updateTopMenuColors()
        setupPager()
        binding.root.post {
            syncVerticalSideFrameBlurState()
            scheduleTopSideFrameHeightSyncIfNeeded()
            syncMainHolderBottomPaddingForTabBar()
        }
    }

    override fun onResume() {
        super.onResume()
        updateTopMenuColors()
        binding.root.post {
            syncVerticalSideFrameBlurState()
            scheduleTopSideFrameHeightSyncIfNeeded()
            syncMainHolderBottomPaddingForTabBar()
        }
    }

    private fun updateTopMenuColors() {
        val surfaceColor = getProperBackgroundColor()
        binding.mainMenu.updateColors(surfaceColor)
        binding.mainMenu.setMenuBarBackgroundColor(Color.TRANSPARENT)
    }

    /**
     * Top strip blurs the scrolling surface ([binding.blurTarget]), same as [com.android.dialer.activities.MainActivity].
     * Bottom strip uses [binding.mainBlurTarget]: the inner target often has too little composited content
     * at the bottom edge (and sits under the sibling tab bar), so the outer target restores the bottom glass effect.
     */
    private fun setupVerticalSideFrameBlur() {
        binding.mVerticalSideFrameTop.bindBlurTarget(binding.blurTarget)
        binding.mVerticalSideFrameBottom.bindBlurTarget(binding.blurTarget)
    }

    private fun syncVerticalSideFrameBlurState() {
        if (isDestroyed || isFinishing) return
        binding.mVerticalSideFrameTop.visibility = View.VISIBLE
        binding.mVerticalSideFrameBottom.visibility = View.VISIBLE
        setupVerticalSideFrameBlur()
    }

    /** Align top glass strip and blur target with [binding.mainMenu] like MainActivity. */
    private fun syncTopSideFrameHeightWithToolbar() {
        val menuHeight = binding.mainMenu.height
        if (menuHeight <= 0) return
        val collapsedMenuHeight =
            binding.mainMenu.getCollapsedHeightPx().takeIf { it > 0 } ?: menuHeight
        binding.mVerticalSideFrameTop.updateLayoutParams<ViewGroup.LayoutParams> {
            if (height != collapsedMenuHeight) {
                height = collapsedMenuHeight
            }
        }
        val targetTopMargin = -menuHeight
        binding.blurTarget.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            if (topMargin != targetTopMargin) {
                topMargin = targetTopMargin
            }
        }
        // blurTarget is shifted up for MVSideFrame blur sampling; keep scroll content below toolbar like Recents.
        binding.mainHolder.updatePadding(
            left = binding.mainHolder.paddingLeft,
            top = menuHeight,
            right = binding.mainHolder.paddingRight,
            bottom = binding.mainHolder.paddingBottom,
        )
        syncMainHolderBottomPaddingForTabBar()
    }

    /**
     * The bottom tab bar overlays the scroll area; without bottom inset the placeholder centers in the
     * full window and sits visually low. Reserve the tab strip height so empty states center above it.
     */
    private fun syncMainHolderBottomPaddingForTabBar() {
        if (isDestroyed || isFinishing) return
        val tabBar = binding.blockedItemsTabBar
        val tabBarInset =
            if (tabBar.isVisible && tabBar.height > 0) {
                val lp = tabBar.layoutParams as? ViewGroup.MarginLayoutParams
                tabBar.height + (lp?.bottomMargin ?: 0)
            } else {
                0
            }
        binding.mainHolder.updatePadding(
            left = binding.mainHolder.paddingLeft,
            top = binding.mainHolder.paddingTop,
            right = binding.mainHolder.paddingRight,
            bottom = tabBarInset,
        )
    }

    private fun scheduleTopSideFrameHeightSyncIfNeeded() {
        if (binding.mainMenu.height > 0) {
            syncTopSideFrameHeightWithToolbar()
            return
        }
        binding.mainMenu.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (binding.mainMenu.height <= 0) return
                binding.mainMenu.viewTreeObserver.removeOnGlobalLayoutListener(this)
                syncTopSideFrameHeightWithToolbar()
            }
        })
    }

    /** Same flow as the app’s `MainActivity.setupOptionsMenu` (action bar + more → overflow). */
    private fun setupOptionsMenu() {
        binding.mainMenu.apply {
            val toolbar = requireCustomToolbar()
            toolbar.inflateMenu(R.menu.block_action_menu)
            this@BlockedItemsActivity.customToolbar = toolbar
            toolbar.setOnMenuItemClickListener { item ->
                onBlockToolbarMenuItemClick(item)
            }
            val blurTarget = this@BlockedItemsActivity.binding.blurTarget
            toolbar.bindBlurTarget(this@BlockedItemsActivity, blurTarget)

            toolbar.setPopupForMoreItem(
                R.id.more,
                R.menu.block_menu,
                this@BlockedItemsActivity.binding.mainBlurTarget,
                object : MenuItem.OnMenuItemClickListener {
                    override fun onMenuItemClick(item: MenuItem): Boolean {
                        return onBlockToolbarMenuItemClick(item)
                    }
                }
            )
        }
    }
    private fun setupMVSideFrame() {
        val blurTarget = findViewById<BlurTarget>(R.id.blurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        val topSideFrame = findViewById<MVSideFrame>(R.id.m_vertical_side_frame_top)
            ?: throw IllegalStateException("Top MVSideFrame not found")
        val bottomSideFrame = findViewById<MVSideFrame>(R.id.m_vertical_side_frame_bottom)
            ?: throw IllegalStateException("Bottom MVSideFrame not found")
        topSideFrame.bindBlurTarget(blurTarget)
        bottomSideFrame.bindBlurTarget(blurTarget)
    }
    override fun getActionModeToolbar() = binding.mainMenu.getActionModeToolbar()

    override fun showActionModeToolbar() {
        // showActionModeToolbar() locks the current (expanded) height for later restoration.
        // After that, override the height to collapsed so the action-mode bar is compact and
        // syncTopSideFrameHeightWithToolbar() aligns the content edge with the shorter bar.
        binding.mainMenu.showActionModeToolbar()
        val collapsedH = binding.mainMenu.getCollapsedHeightPx()
        if (collapsedH > 0) {
            binding.mainMenu.updateLayoutParams<ViewGroup.LayoutParams> { height = collapsedH }
        }
        binding.blockedItemsTabBar.visibility = View.GONE
        syncActionModeRippleToolbarInsetWithTabBar()
        binding.root.post {
            syncTopSideFrameHeightWithToolbar()
            syncMainHolderBottomPaddingForTabBar()
            refreshActionModeRippleToolbarIfNeeded()
        }
    }

    override fun hideActionModeToolbar() {
        // hideActionModeToolbar() restores the saved expanded height and calls setExpanded(true).
        binding.mainMenu.hideActionModeToolbar()
        binding.actionModeRippleToolbar.visibility = View.GONE
        binding.blockedItemsTabBar.visibility = View.VISIBLE
        binding.root.post {
            syncTopSideFrameHeightWithToolbar()
            syncMainHolderBottomPaddingForTabBar()
        }
    }

    private fun syncActionModeRippleToolbarInsetWithTabBar() {
        val tabBarBottomMargin =
            (binding.blockedItemsTabBar.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        val tabBarBottomPadding = binding.blockedItemsTabBar.paddingBottom
        binding.actionModeRippleToolbar.updateMarginWithBase(bottom = tabBarBottomMargin)
        binding.actionModeRippleToolbar.updatePaddingWithBase(bottom = tabBarBottomPadding)
    }

    fun refreshActionModeRippleToolbarIfNeeded() {
        if (isDestroyed || isFinishing) return
        applyActionModeRippleToolbarForBlockedItems()
    }

    private fun applyActionModeRippleToolbarForBlockedItems() {
        when (val page = currentBlockedPageFragment()) {
            is BlockedCallsFragment ->
                page.bindRippleToolbarIfNeeded(binding.actionModeRippleToolbar, binding.mainBlurTarget)

            is BlockListFragment ->
                page.bindRippleToolbarIfNeeded(binding.actionModeRippleToolbar, binding.mainBlurTarget)

            is BlockedMessagesFragment ->
                page.bindRippleToolbarIfNeeded(binding.actionModeRippleToolbar, binding.mainBlurTarget)

            else -> binding.actionModeRippleToolbar.visibility = View.GONE
        }
    }

    override fun getBlurTargetView() = binding.mainBlurTarget

    override fun onBackPressedCompat(): Boolean {
        if (finishSelectionOnCurrentPage()) return true
        return super.onBackPressedCompat()
    }

    private fun finishSelectionOnCurrentPage(): Boolean {
        return when (val page = currentBlockedPageFragment()) {
            is BlockedCallsFragment -> page.finishSelectionActionModeIfActive()
            is BlockListFragment -> page.finishSelectionActionModeIfActive()
            is BlockedMessagesFragment -> page.finishSelectionActionModeIfActive()
            else -> false
        }
    }

    private fun currentBlockedPageFragment(): Fragment? {
        val vp = binding.blockedItemsViewPager
        val tag = "android:switcher:${vp.id}:${vp.currentItem}"
        return supportFragmentManager.findFragmentByTag(tag)
    }

    private fun tryStartBlockedSelection(): Boolean {
        return when (val page = currentBlockedPageFragment()) {
            is BlockedCallsFragment -> page.tryStartSelectionActionMode()
            is BlockListFragment -> page.tryStartSelectionActionMode()
            is BlockedMessagesFragment -> page.tryStartSelectionActionMode()
            else -> false
        }
    }

    private fun onBlockToolbarMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.select -> {
                tryStartBlockedSelection()
            }
            R.id.add_blocked_number -> {
                Intent(this, ManageBlockedNumbersActivity::class.java).apply {
                    putExtra(APP_ICON_IDS, getAppIconIDs())
                    putExtra(APP_LAUNCHER_NAME, getAppLauncherName())
                    startActivity(this)
                }
                true
            }
            R.id.settings -> {
                try {
                    startActivity(
                        Intent().setComponent(
                            ComponentName(packageName, "$packageName.activities.SettingsActivity")
                        )
                    )
                } catch (_: Exception) {
                }
                true
            }
            else -> false
        }
    }


    private fun setupPager() {
        val initialTab =
            intent.getIntExtra(EXTRA_INITIAL_TAB_INDEX, TAB_BLOCKED_CALLS).coerceIn(
                TAB_BLOCKED_CALLS,
                TAB_BLOCKED_CONTACTS,
            )

        binding.blockedItemsViewPager.adapter = BlockedItemsPagerAdapter(supportFragmentManager)
        binding.blockedItemsViewPager.offscreenPageLimit = TAB_TITLES.size
        // Open the requested tab immediately so the blocked-messages list is not preceded by tab 0.
        binding.blockedItemsViewPager.setCurrentItem(initialTab, false)

        val tabItems = ArrayList<IconItem>()
        TAB_TITLES.zip(TAB_ICONS).forEach { (titleId, iconRes) ->
            tabItems.add(
                IconItem().apply {
                    icon = iconRes
                    title = getString(titleId)
                }
            )
        }
        binding.blockedItemsTabBar.setTabs(this, tabItems, binding.mainBlurTarget)
        binding.blockedItemsTabBar.setSelection(initialTab)
        updateTitleForTab(initialTab)

        binding.blockedItemsViewPager.onPageChangeListener { index ->
            updateTitleForTab(index)
            isProgrammaticTabSelection = true
            binding.blockedItemsTabBar.setSelection(index)
            binding.blockedItemsTabBar.post {
                isProgrammaticTabSelection = false
            }
        }

        binding.blockedItemsTabBar.setOnTabSelectedListener(object : LiquidGlassTabs.OnTabSelectedListener {
            override fun onTabSelected(index: Int) {
                updateTitleForTab(index)
                if (isProgrammaticTabSelection) return
                binding.blockedItemsViewPager.currentItem = index
            }

            override fun onTabUnselected(position: Int) {}

            override fun onTabReselected(position: Int) {}
        })

        binding.blockedItemsTabBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!isDestroyed && !isFinishing) {
                syncMainHolderBottomPaddingForTabBar()
            }
        }
    }

    private fun updateTitleForTab(index: Int) {
        val titleId = TITLES.getOrElse(index) { R.string.blocked_items }
        binding.mainMenu.updateTitle(getString(titleId))
        val menuRes = if (index == TAB_BLOCKED_CONTACTS) R.menu.block_action_menu else R.menu.block_action_menu_no_add
        customToolbar?.inflateMenu(menuRes)
        customToolbar?.menu?.findItem(R.id.settings)?.isVisible = index != TAB_BLOCKED_MESSAGES
    }

    private inner class BlockedItemsPagerAdapter(fragmentManager: FragmentManager) :
        FragmentPagerAdapter(fragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getCount() = TAB_TITLES.size

        override fun getItem(position: Int): Fragment {
            return when (position) {
                0 -> BlockedCallsFragment()
                1 -> createBlockedMessagesFragment()
                else -> BlockedContactsFragment()
            }
        }
    }

    /** Apps may override to supply a richer blocked-messages tab (e.g. conversation list). */
    protected open fun createBlockedMessagesFragment(): Fragment = BlockedMessagesFragment()

    companion object {
        const val EXTRA_INITIAL_TAB_INDEX = "com.goodwy.commons.BlockedItemsActivity.INITIAL_TAB_INDEX"

        const val TAB_BLOCKED_CALLS = 0
        const val TAB_BLOCKED_MESSAGES = 1
        const val TAB_BLOCKED_CONTACTS = 2

        @StringRes
        private val TITLES = listOf(
            R.string.blocked_calls,
            R.string.blocked_messages,
            R.string.blocked_contacts
        )
        private val TAB_TITLES = listOf(
            R.string.recents,
            R.string.message,
            R.string.contacts_tab
        )

        private val TAB_ICONS = listOf(
            CommonR.drawable.ic_cmn_clock_fill,
            CommonR.drawable.ic_cmn_sms_send_fill, // TODO: should change the icon
            CommonR.drawable.ic_cmn_circle_profile_fill
        )
    }
}
