package com.goodwy.commons.activities

import android.os.Bundle
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import com.android.common.helper.IconItem
import com.goodwy.commons.R
import com.goodwy.commons.databinding.ActivityBlockedItemsBinding
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.onPageChangeListener
import com.goodwy.commons.extensions.viewBinding
import com.goodwy.commons.fragments.BlockedCallsFragment
import com.goodwy.commons.fragments.BlockedContactsFragment
import com.goodwy.commons.fragments.BlockedMessagesFragment
import com.goodwy.commons.helpers.APP_ICON_IDS
import com.goodwy.commons.helpers.APP_LAUNCHER_NAME
import com.goodwy.commons.helpers.NavigationIcon
import com.qmdeve.liquidglass.view.LiquidGlassTabs

class BlockedItemsActivity : BaseSimpleActivity() {
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
            padBottomSystem = listOf(binding.blockedItemsTabBar)
        )

        binding.mainMenu.requireCustomToolbar().apply {
            setNavigationIconViewVisible(true)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        updateTopMenuColors()
        setupPager()
        updateTitleForTab(0)
    }

    override fun onResume() {
        super.onResume()
        updateTopMenuColors()
    }

    private fun updateTopMenuColors() {
        binding.mainMenu.updateColors(getProperBackgroundColor())
    }

    private fun setupPager() {
        binding.blockedItemsViewPager.adapter = BlockedItemsPagerAdapter(supportFragmentManager)
        binding.blockedItemsViewPager.offscreenPageLimit = TAB_TITLES.size

        val tabItems = ArrayList<IconItem>()
        TAB_TITLES.forEach { titleId ->
            tabItems.add(
                IconItem().apply {
                    icon = R.drawable.ic_block_vector
                    title = getString(titleId)
                }
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

    private fun updateTitleForTab(index: Int) {
        val titleId = TAB_TITLES.getOrElse(index) { R.string.blocked_items }
        binding.mainMenu.updateTitle(getString(titleId))
    }

    private class BlockedItemsPagerAdapter(fragmentManager: FragmentManager) :
        FragmentPagerAdapter(fragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getCount() = TAB_TITLES.size

        override fun getItem(position: Int): Fragment {
            return when (position) {
                0 -> BlockedCallsFragment()
                1 -> BlockedMessagesFragment()
                else -> BlockedContactsFragment()
            }
        }
    }

    companion object {
        @StringRes
        private val TAB_TITLES = listOf(
            R.string.blocked_calls,
            R.string.blocked_messages,
            R.string.blocked_contacts
        )
    }
}
