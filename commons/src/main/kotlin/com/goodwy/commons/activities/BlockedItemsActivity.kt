package com.goodwy.commons.activities

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import com.android.common.R as CommonR
import com.android.common.helper.IconItem
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
import com.qmdeve.liquidglass.view.LiquidGlassTabs

open class BlockedItemsActivity : BaseSimpleActivity(), ActionModeToolbarHost {
    private val binding by viewBinding(ActivityBlockedItemsBinding::inflate)
    private var isProgrammaticTabSelection = false

    override fun getAppIconIDs() = intent.getIntegerArrayListExtra(APP_ICON_IDS) ?: ArrayList()

    override fun getAppLauncherName() = intent.getStringExtra(APP_LAUNCHER_NAME) ?: ""

    override fun getRepositoryName() = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

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
        applyInitialTabFromIntent()
    }

    override fun onResume() {
        super.onResume()
        updateTopMenuColors()
    }

    private fun updateTopMenuColors() {
        binding.mainMenu.updateColors(getProperBackgroundColor())
    }

    /** Same flow as the app’s `MainActivity.setupOptionsMenu` (action bar + more → overflow). */
    private fun setupOptionsMenu() {
        binding.mainMenu.apply {
            val toolbar = requireCustomToolbar()
            toolbar.inflateMenu(R.menu.block_action_menu)
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

    override fun getActionModeToolbar() = binding.mainMenu.getActionModeToolbar()

    override fun showActionModeToolbar() {
        binding.mainMenu.showActionModeToolbar()
        binding.blockedItemsTabBar.visibility = View.GONE
        syncActionModeRippleToolbarInsetWithTabBar()
        binding.root.post { refreshActionModeRippleToolbarIfNeeded() }
    }

    override fun hideActionModeToolbar() {
        binding.mainMenu.hideActionModeToolbar()
        binding.actionModeRippleToolbar.visibility = View.GONE
        binding.blockedItemsTabBar.visibility = View.VISIBLE
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
        binding.blockedItemsViewPager.adapter = BlockedItemsPagerAdapter(supportFragmentManager)
        binding.blockedItemsViewPager.offscreenPageLimit = TAB_TITLES.size

        val tabItems = ArrayList<IconItem>()
        for (i in TAB_TITLES.indices) {
            tabItems.add(
                IconItem().apply {
                    icon = TAB_ICONS[i]
                    title = getString(TAB_TITLES[i])
                },
            )
        }
        binding.blockedItemsTabBar.setTabs(this, tabItems, binding.mainBlurTarget)
        binding.blockedItemsTabBar.setSelection(0)

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
    }

    private fun applyInitialTabFromIntent() {
        val initialTab =
            intent.getIntExtra(EXTRA_INITIAL_TAB_INDEX, TAB_BLOCKED_CALLS).coerceIn(
                TAB_BLOCKED_CALLS,
                TAB_BLOCKED_CONTACTS,
            )
        binding.blockedItemsViewPager.setCurrentItem(initialTab, false)
        binding.blockedItemsTabBar.setSelection(initialTab)
        updateTitleForTab(initialTab)
    }

    private fun updateTitleForTab(index: Int) {
        val titleId = TAB_TITLES.getOrElse(index) { R.string.blocked_items }
        binding.mainMenu.updateTitle(getString(titleId))
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
        private val TAB_TITLES = listOf(
            R.string.blocked_calls,
            R.string.blocked_messages,
            R.string.blocked_contacts,
        )

        // ic_cmn_* drawables live in commons/libs/common.aar (com.android.common.R)
        private val TAB_ICONS = listOf(
            CommonR.drawable.ic_cmn_clock_fill,
            CommonR.drawable.ic_cmn_sms_send_fill,
            CommonR.drawable.ic_cmn_circle_profile_fill,
        )
    }
}
