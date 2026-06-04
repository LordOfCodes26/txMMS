package com.goodwy.commons.activities

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.view.menu.MenuBuilder
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.android.common.R as CommonR
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import com.android.common.dialogs.MRenameDialog
import com.android.common.helper.IconItem
import com.android.common.interfaces.OnRenameListener
import com.android.common.view.MAppBarLayout
import com.android.common.view.MVSideFrame
import com.goodwy.commons.R
import com.goodwy.commons.databinding.ActivityBlockedItemsBinding
import com.goodwy.commons.dialogs.OptionListDialog
import com.goodwy.commons.extensions.addBlockedNumber
import com.goodwy.commons.extensions.blockContact
import com.goodwy.commons.extensions.onPageChangeListener
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.extensions.updateMarginWithBase
import com.goodwy.commons.extensions.updatePaddingWithBase
import com.goodwy.commons.extensions.viewBinding
import android.telephony.PhoneNumberUtils
import com.goodwy.commons.helpers.ContactsHelper
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.fragments.BlockedCallsFragment
import com.goodwy.commons.fragments.BlockListFragment
import com.goodwy.commons.fragments.BlockedContactsFragment
import com.goodwy.commons.fragments.BlockedMessagesFragment
import com.goodwy.commons.helpers.APP_ICON_IDS
import com.goodwy.commons.helpers.APP_LAUNCHER_NAME
import com.goodwy.commons.interfaces.ActionModeToolbarHost
import com.goodwy.commons.views.showMPopupMenu
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.qmdeve.liquidglass.view.LiquidGlassTabs
import eightbitlab.com.blurview.BlurTarget

open class BlockedItemsActivity : BaseSimpleActivity(), ActionModeToolbarHost {
    private val binding by viewBinding(ActivityBlockedItemsBinding::inflate)
    private var isProgrammaticTabSelection = false
    /** Saved while action mode drops [binding.blurTarget]'s scrolling behavior. */
    private var blurTargetScrollingBehavior: CoordinatorLayout.Behavior<*>? = null

    private val pickContactsToBlockLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            result.data?.let { handleSelectContactsToBlockResult(it) }
        }

    private val pickContactNumberToBlockLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            result.data?.let { handleSelectContactNumberToBlockResult(it) }
        }

    private val pickRecentNumberToBlockLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            result.data?.let { handleSelectContactNumberToBlockResult(it) }
        }

    override fun getAppIconIDs() = intent.getIntegerArrayListExtra(APP_ICON_IDS) ?: ArrayList()

    override fun getAppLauncherName() = intent.getStringExtra(APP_LAUNCHER_NAME) ?: ""

    override fun getRepositoryName() = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padBottomSystem = listOf(binding.blockedItemsTabBar),
            moveBottomSystem = listOf(binding.actionModeRippleToolbar),
        )

        setupOptionsMenu()
        setupPager()
        binding.root.post {
            syncVerticalSideFrameBlurState()
            bindMainAppBarBlurTargets()
            syncMainAppBarLayer()
            syncMainHolderBottomPaddingForTabBar()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.root.post {
            syncVerticalSideFrameBlurState()
            bindMainAppBarBlurTargets()
            syncMainAppBarLayer()
            syncMainHolderBottomPaddingForTabBar()
        }
    }

    /** Wire glass blur on [binding.mainMenu]'s back arrow + action bar pills (inner [binding.blurTarget]). */
    private fun bindMainAppBarBlurTargets() {
        if (isDestroyed || isFinishing) return
        val blurTarget = binding.blurTarget
        val appBar = binding.mainMenu
        appBar.backArrow?.bindBlurTarget(this, blurTarget)
        appBar.getActionBarView()?.bindBlurTarget(this, blurTarget)
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

    /**
     * Keeps [binding.mainMenu] and [binding.blurTarget] in sync — same layering as
     * [com.android.dialer.activities.MainActivity.syncMainAppBarLayer].
     */
    private fun syncMainAppBarLayer() {
        if (isDestroyed || isFinishing) return
        syncBlurTargetScrollingBehavior()
        syncBlurTargetTopMarginForAppBar()
        binding.mainCoordinator.requestLayout()
    }

    /**
     * [ScrollingViewBehavior] pins [binding.blurTarget] below [binding.mainMenu] even when the bar
     * is transparent; drop it while action mode is shown so content starts at the coordinator top.
     */
    private fun syncBlurTargetScrollingBehavior() {
        val lp = binding.blurTarget.layoutParams as? CoordinatorLayout.LayoutParams ?: return
        val dropBehavior = isActionModeToolbarVisible()
        if (dropBehavior) {
            if (blurTargetScrollingBehavior == null) {
                blurTargetScrollingBehavior = lp.behavior
            }
            if (lp.behavior != null) {
                lp.behavior = null
                binding.blurTarget.layoutParams = lp
            }
        } else if (lp.behavior == null) {
            lp.behavior = blurTargetScrollingBehavior ?: ScrollingViewBehavior()
            blurTargetScrollingBehavior = lp.behavior
            binding.blurTarget.layoutParams = lp
        }
    }

    /**
     * Negative top margin pulls [binding.blurTarget] under [binding.mainMenu] so the glass blur
     * samples list content beneath the bar (see [CommonR.dimen.tx_top_bar_expand_height]).
     */
    private fun syncBlurTargetTopMarginForAppBar() {
        val appBarShown = !isActionModeToolbarVisible() && binding.mainMenu.visibility == View.VISIBLE
        val targetTop =
            if (appBarShown) {
                -resources.getDimensionPixelSize(CommonR.dimen.tx_top_bar_expand_height)
            } else {
                0
            }
        val lp = binding.blurTarget.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.topMargin == targetTop) return
        lp.topMargin = targetTop
        binding.blurTarget.layoutParams = lp
    }

    private fun isActionModeToolbarVisible(): Boolean =
        binding.actionModeToolbarContainer.visibility == View.VISIBLE

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

    /**
     * MAppBarLayout's inner CollapsingToolbarLayout still receives Material's default
     * statusBarScrim, which paints the tinted header when expanded. Strip both scrims plus
     * the inner background so only the glass blur shows through — copy of
     * [com.android.dialer.activities.MainActivity.clearMainAppBarScrims].
     */
    private fun clearMainAppBarScrims() {
        val transparent = ColorDrawable(Color.TRANSPARENT)
        binding.mainMenu.background = null
        for (i in 0 until binding.mainMenu.childCount) {
            val child = binding.mainMenu.getChildAt(i)
            if (child is CollapsingToolbarLayout) {
                child.background = null
                child.contentScrim = transparent
                child.statusBarScrim = transparent
            }
        }
    }

    /** Wire the [MAppBarLayout] action bar pill, popup overflow, and per-offset blur refresh. */
    private fun setupOptionsMenu() {
        val appBar = binding.mainMenu

        clearMainAppBarScrims()
        appBar.setTitle(getString(R.string.blocked_items))
        // Default back arrow stays visible; MAppBarLayout routes it through OnBackPressedDispatcher,
        // so [onBackPressedCompat] still gets a chance to finish selection mode first.
        appBar.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { _, _ ->
            binding.mVerticalSideFrameTop.update()
        })

        bindMainAppBarBlurTargets()
        appBar.getActionBarView()?.setPosition("right")
        appBar.getActionBarView()?.inflateMenu(R.menu.block_action_menu)
        appBar.getActionBarView()?.setOnMenuItemClickListener { item ->
            onBlockToolbarMenuItemClick(item)
        }

        // Action mode toolbar floats over the main content; bind it to blurTarget so its
        // controls (back arrow + overflow) blur the content beneath them. Same pattern as
        // [com.android.dialer.activities.MainActivity.setupOptionsMenu].
        binding.actionModeToolbar.bindBlurTarget(this, binding.blurTarget)
    }

    override fun getActionModeToolbar() = binding.actionModeToolbar

    override fun showActionModeToolbar() {
        binding.actionModeToolbarContainer.visibility = View.VISIBLE
        // Collapse the AppBarLayout's scroll state before hiding so any residual scrim/status-bar
        // foreground that MAppBarLayout could still draw is fully retracted, then mark it GONE so
        // it doesn't reserve layout space under the action mode toolbar.
        binding.mainMenu.setExpanded(false, false)
        binding.mainMenu.visibility = View.GONE
        binding.blockedItemsTabBar.visibility = View.GONE
        syncBlurTargetScrollingBehavior()
        syncBlurTargetTopMarginForAppBar()
        syncActionModeRippleToolbarInsetWithTabBar()
        syncVerticalSideFrameBlurState()
        binding.root.post {
            syncMainHolderBottomPaddingForTabBar()
            refreshActionModeRippleToolbarIfNeeded()
        }
    }

    override fun hideActionModeToolbar() {
        binding.actionModeToolbarContainer.visibility = View.GONE
        binding.mainMenu.visibility = View.VISIBLE
        binding.actionModeRippleToolbar.visibility = View.GONE
        binding.blockedItemsTabBar.visibility = View.VISIBLE
        binding.mainMenu.dismissCollapse()
        syncBlurTargetScrollingBehavior()
        binding.root.post {
            syncMainAppBarLayer()
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
                showAddBlockedItemOptionsDialog()
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
            R.id.more -> {
                showMoreActionsPopup()
                true
            }
            else -> false
        }
    }

    private fun showAddBlockedItemOptionsDialog() {
        OptionListDialog(
            activity = this,
            title = "",
            options = listOf(
                getString(R.string.add_phone_number) to { showAddBlockedNumberDialog() },
                getString(R.string.pick_contact_number) to { launchSelectContactNumberToBlock() },
                getString(R.string.pick_recent_number) to { launchSelectRecentNumberToBlock() },
                getString(R.string.add_contact) to { launchSelectContactsToBlock() },
            ),
            blurTarget = binding.mainBlurTarget,
        )
    }

    private fun showAddBlockedNumberDialog() {
        MRenameDialog(this, OnRenameListener { raw ->
            val number = normalizeBlockedNumberInput(raw)
            if (number.isEmpty()) return@OnRenameListener
            if (addBlockedNumber(number)) {
                refreshBlockedContactsTab()
            } else {
                toast(R.string.unknown_error_occurred)
            }
        }).apply {
            bindBlurTarget(binding.mainBlurTarget)
            setTitle(getString(R.string.add_a_blocked_number))
            setHintText(getString(R.string.number))
            setContentText("")
            show()
        }
    }

    private fun launchSelectContactsToBlock() {
        try {
            val intent = Intent().apply {
                component = ComponentName(packageName, SELECT_CONTACTS_ACTIVITY)
                putExtra(SELECT_CONTACTS_EXTRA_ALLOW_MULTIPLE, true)
                putExtra(SELECT_CONTACTS_EXTRA_SHOW_ONLY_WITH_NUMBER, true)
            }
            pickContactsToBlockLauncher.launch(intent)
        } catch (_: Exception) {
            toast(R.string.unknown_error_occurred)
        }
    }

    private fun launchSelectContactNumberToBlock() {
        try {
            val intent = Intent().apply {
                component = ComponentName(packageName, SELECT_CONTACT_NUMBERS_ACTIVITY)
                putExtra(SELECT_NUMBERS_EXTRA_ALLOW_MULTIPLE, true)
            }
            pickContactNumberToBlockLauncher.launch(intent)
        } catch (_: Exception) {
            toast(R.string.unknown_error_occurred)
        }
    }

    private fun launchSelectRecentNumberToBlock() {
        try {
            val intent = Intent().apply {
                component = ComponentName(packageName, SELECT_RECENT_NUMBERS_ACTIVITY)
                putExtra(SELECT_NUMBERS_EXTRA_ALLOW_MULTIPLE, true)
            }
            pickRecentNumberToBlockLauncher.launch(intent)
        } catch (_: Exception) {
            toast(R.string.unknown_error_occurred)
        }
    }

    private fun handleSelectContactNumberToBlockResult(data: Intent) {
        val phones = data.getStringArrayExtra(SELECT_NUMBERS_RESULT_ALL_PHONES)
        val list = if (phones != null && phones.isNotEmpty()) {
            phones.mapIndexed { i, phone ->
                val normalized = data.getStringArrayExtra(SELECT_NUMBERS_RESULT_ALL_NORMALIZED)?.getOrElse(i) { "" }
                    ?: phone
                PhoneNumberUtils.stripSeparators(normalized.ifEmpty { phone })
            }
        } else {
            val phone = data.getStringExtra(SELECT_CONTACT_NUMBERS_RESULT_PHONE) ?: return
            val normalized = data.getStringExtra(SELECT_CONTACT_NUMBERS_RESULT_NORMALIZED)?.takeIf { it.isNotEmpty() }
            listOf(PhoneNumberUtils.stripSeparators(normalized ?: phone))
        }
        var blockedAny = false
        for (number in list) {
            if (number.isNotEmpty() && addBlockedNumber(number)) {
                blockedAny = true
            }
        }
        when {
            blockedAny -> {
                toast(R.string.block_contact_success)
                refreshBlockedContactsTab()
            }
            list.isNotEmpty() -> toast(R.string.block_contact_fail)
        }
    }

    private fun handleSelectContactsToBlockResult(data: Intent) {
        val ids = data.getLongArrayExtra(SELECT_CONTACTS_RESULT_ALL_IDS)
            ?.takeIf { it.isNotEmpty() }
            ?: data.getLongArrayExtra(SELECT_CONTACTS_RESULT_ADDED_IDS)
            ?: return
        ensureBackgroundThread {
            val helper = ContactsHelper(this)
            var blockedAny = false
            var hadNumberless = false
            ids.forEach { id ->
                val contact = helper.getContactWithId(id.toInt()) ?: return@forEach
                if (contact.phoneNumbers.isEmpty()) {
                    hadNumberless = true
                    return@forEach
                }
                if (blockContact(contact)) {
                    blockedAny = true
                }
            }
            runOnUiThread {
                when {
                    blockedAny -> {
                        toast(R.string.block_contact_success)
                        refreshBlockedContactsTab()
                    }
                    hadNumberless -> toast(R.string.no_phone_number_found)
                    else -> toast(R.string.block_contact_fail)
                }
            }
        }
    }

    private fun normalizeBlockedNumberInput(raw: String): String {
        var number = raw.trim()
        if (number.contains(".*")) {
            number = number.replace(".*", "*")
        }
        return number
    }

    private fun refreshBlockedContactsTab() {
        val contactsPage = supportFragmentManager.fragments
            .filterIsInstance<BlockListFragment>()
            .firstOrNull()
        contactsPage?.refreshBlockedNumbersList()
        (currentBlockedPageFragment() as? BlockListFragment)?.refreshBlockedNumbersList()
    }

    /**
     * Shows [R.menu.block_menu] as an MPopup anchored to the [MAppBarLayout]'s action-bar pill.
     * Mirrors the dialer `MainActivity.showMoreActionsPopup` flow: build the menu, hide
     * settings on the messages tab, then dispatch through [onBlockToolbarMenuItemClick].
     */
    private fun showMoreActionsPopup() {
        val actionBar = binding.mainMenu.getActionBarView() ?: return
        val anchor: View = actionBar
        val blurTarget = binding.mainBlurTarget
        val menu = MenuBuilder(this)
        menuInflater.inflate(R.menu.block_menu, menu)
        val currentTab = binding.blockedItemsViewPager.currentItem
        menu.findItem(R.id.settings)?.isVisible = currentTab != TAB_BLOCKED_MESSAGES
        showMPopupMenu(
            context = this,
            anchor = anchor,
            menu = menu,
            gravity = Gravity.END,
            blurTarget = blurTarget,
            listener = { item -> onBlockToolbarMenuItemClick(item) },
        )
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
        binding.blockedItemsViewPager.post { refreshTabContent(initialTab) }

        binding.blockedItemsViewPager.onPageChangeListener { index ->
            updateTitleForTab(index)
            refreshTabContent(index)
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

    private fun refreshTabContent(index: Int) {
        binding.blockedItemsViewPager.post {
            val tag = "android:switcher:${binding.blockedItemsViewPager.id}:$index"
            when (val page = supportFragmentManager.findFragmentByTag(tag)) {
                is BlockedCallsFragment -> page.refreshBlockedCallLogs()
                is BlockListFragment -> page.refreshBlockedNumbersList()
            }
        }
    }

    private fun updateTitleForTab(index: Int) {
        val titleId = TITLES.getOrElse(index) { R.string.blocked_items }
        binding.mainMenu.setTitle(getString(titleId))
        // Only the "Contacts" tab supports adding a new blocked number directly from the bar.
        binding.mainMenu.getActionBarView()
            ?.setMenuItemVisible(R.id.add_blocked_number, index == TAB_BLOCKED_CONTACTS)
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

        /** Mirrors [com.android.contacts.activities.SelectContactsActivity] extras for picker results. */
        private const val SELECT_CONTACTS_ACTIVITY = "com.android.contacts.activities.SelectContactsActivity"
        private const val SELECT_CONTACTS_EXTRA_ALLOW_MULTIPLE = "allow_select_multiple"
        private const val SELECT_CONTACTS_EXTRA_SHOW_ONLY_WITH_NUMBER = "show_only_contacts_with_number"
        private const val SELECT_CONTACTS_RESULT_ADDED_IDS = "added_contact_ids"
        private const val SELECT_CONTACTS_RESULT_ALL_IDS = "all_selected_contact_ids"

        /** Mirrors [com.android.contacts.activities.SelectContactNumbersActivity] result extras. */
        private const val SELECT_CONTACT_NUMBERS_ACTIVITY = "com.android.contacts.activities.SelectContactNumbersActivity"
        private const val SELECT_CONTACT_NUMBERS_RESULT_PHONE = "phone_number"
        private const val SELECT_CONTACT_NUMBERS_RESULT_NORMALIZED = "normalized_phone_number"
        private const val SELECT_NUMBERS_EXTRA_ALLOW_MULTIPLE = "allow_select_multiple"
        private const val SELECT_NUMBERS_RESULT_ALL_PHONES = "all_phone_numbers"
        private const val SELECT_NUMBERS_RESULT_ALL_NORMALIZED = "all_normalized_phone_numbers"

        private const val SELECT_RECENT_NUMBERS_ACTIVITY = "com.android.contacts.activities.SelectRecentNumbersActivity"

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
