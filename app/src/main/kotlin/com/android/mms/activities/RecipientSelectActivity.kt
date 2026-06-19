package com.android.mms.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.Toast
import com.android.common.view.MSearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.android.common.helper.IconItem
import com.android.common.view.MAppBarLayout
import com.android.common.view.MRippleToolBar
import com.android.common.view.MVSideFrame
import com.android.mms.R
import com.android.mms.extensions.setRippleTabEnabledWidthAlpha
import com.android.mms.helpers.RecipientSelectionHost
import com.android.mms.models.Contact
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.views.MyTextView
import eightbitlab.com.blurview.BlurTarget

class RecipientSelectActivity : SimpleActivity(), RecipientSelectionHost {

    private var rootView: View? = null
    private var appBar: MAppBarLayout? = null
    private var viewPager: ViewPager? = null
    private var filterBar: View? = null
    private var filterBarChild: View? = null
    private var filterRecent: MyTextView? = null
    private var filterContacts: MyTextView? = null
    private var filterRecentLine: ImageView? = null
    private var filterContactsLine: ImageView? = null
    private var confirmTab: MRippleToolBar? = null
    private var appBarVerticalOffset = 0
    private var listTopInsetPx = -1
    private var filterBarInsetListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var isSearchOpen = false
    private var searchString = ""
    private var filterBarExpandedTopMarginPx = Int.MIN_VALUE
    private var searchListTopInsetPx = -1
    private var searchFilterBarAligned = false
    private var searchAlignAttempts = 0
    private var searchOpenedFromExpanded = false
    private var searchContentOffsetUnsettled = false
    private var searchListPaddingFinalized = false
    private var recipientSearchChromeLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var lastLoggedFilterTranslation = Float.NaN
    private val recipientSearchLayoutSyncRunnable = Runnable {
        if (!isSearchOpen || isFinishing || isDestroyed) return@Runnable
        syncRecipientSearchChromeLayout()
        val searchView = appBar?.getSearchView()
        if (searchView != null && isSearchChromeLayoutReady(searchView) && isAppBarCollapseSettled()) {
            searchFilterBarAligned = true
            searchContentOffsetUnsettled = false
        }
    }
    private val recipientSearchRefineRunnable = Runnable {
        if (!isSearchOpen || isFinishing || isDestroyed) return@Runnable
        alignFilterBarChildBelowSearchViewRefine()
    }

    private val selectedByNormalized = LinkedHashMap<String, Contact>()
    private val pageFragments = arrayOfNulls<RecipientSelectListFragment>(2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recipient_select)
        rootView = findViewById(R.id.root_view)
        appBar = findViewById(R.id.recipient_select_appbar)
        viewPager = findViewById(R.id.recipient_select_pager)
        filterBar = findViewById(R.id.recipient_select_filter_bar)
        filterBarChild = findViewById(R.id.recipient_select_filter_bar_child)
        filterRecent = findViewById(R.id.filter_recent)
        filterContacts = findViewById(R.id.filter_contacts)
        filterRecentLine = findViewById(R.id.filter_recent_line)
        filterContactsLine = findViewById(R.id.filter_contacts_line)
        confirmTab = findViewById(R.id.confirm_tab)

        seedAlreadySelected()
        initTheme()
        setupEdgeToEdge()
        makeSystemBarsTransparent()
        applyWindowSurfaces()
        setupAppBar()
        setupViewPager()
        setupFilterTabs()
        setupBottomActionTabs()
        initMVSideFrames()
        syncListInsets()
        appBar?.getSearchView()?.let { prewarmSearchViewLayout(it) }
        uiLog("onCreate complete")
    }

    override fun onBackPressedCompat(): Boolean {
        if (isSearchOpen) {
            appBar?.getSearchView()?.searchEnd()
            return true
        }
        return super.onBackPressedCompat()
    }

    override fun onResume() {
        super.onResume()
        if (isSystemInDarkMode()) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                )
        }
        applyWindowSurfaces()
        setupBottomActionTabs()
        refreshSideFrameBlurAndInsets()
        if (viewPager?.currentItem == PAGE_RECENT) {
            pageFragments[PAGE_RECENT]?.let { /* refresh call log times */ }
        }
    }

    override fun onDestroy() {
        cancelRecipientSearchLayoutSync()
        clearFilterBarInsetListener()
        clearRecipientSearchChromeLayoutListener()
        super.onDestroy()
    }

    override fun onRecipientToggled(pageIndex: Int, contactIndex: Int, contact: Contact, selected: Boolean) {
        val normalized = normalizePhone(contact.phoneNumber)
        if (normalized.isEmpty()) return
        if (selected) {
            selectedByNormalized[normalized] = contact
        } else {
            selectedByNormalized.remove(normalized)
        }
        onSelectionChanged()
    }

    override fun selectedNormalizedNumbers(): Set<String> = selectedByNormalized.keys.toSet()

    override fun onSelectionChanged() {
        updateConfirmTabEnable()
        pageFragments.forEach { it?.refreshSelectionFromHost() }
    }

    fun hasCallLogPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    }

    fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCallLogPermissionIfNeeded(): Boolean {
        if (hasCallLogPermission()) return true
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CALL_LOG), PERMISSION_REQUEST_READ_CALL_LOG)
        return false
    }

    private fun requestContactsPermissionIfNeeded(): Boolean {
        if (hasContactsPermission()) return true
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), PERMISSION_REQUEST_READ_CONTACTS)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_READ_CALL_LOG -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    pageFragments[PAGE_RECENT]?.reloadData()
                } else {
                    Toast.makeText(this, com.goodwy.commons.R.string.no_contacts_permission, Toast.LENGTH_LONG).show()
                }
            }
            PERMISSION_REQUEST_READ_CONTACTS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    pageFragments[PAGE_CONTACTS]?.reloadData()
                } else {
                    Toast.makeText(this, com.goodwy.commons.R.string.no_contacts_permission, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun seedAlreadySelected() {
        val alreadySelected = intent?.getParcelableArrayListExtra<Contact>(ContactPickerActivity.EXTRA_ALREADY_SELECTED_CONTACTS)
            ?: arrayListOf()
        alreadySelected.forEach { contact ->
            val normalized = normalizePhone(contact.phoneNumber)
            if (normalized.isNotEmpty()) {
                selectedByNormalized[normalized] = contact
            }
        }
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun applyWindowSurfaces() {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        rootView?.setBackgroundColor(backgroundColor)
        findViewById<BlurTarget>(R.id.mainBlurTarget)?.setBackgroundColor(backgroundColor)
        appBar?.apply {
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
            setLiftOnScrollColor(null)
        }
    }

    private fun setupAppBar() {
        val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget) ?: return
        appBar?.setTitle(getString(R.string.select_contacts))
        appBar?.getBackArrow()?.apply {
            bindBlurTarget(this@RecipientSelectActivity, blurTarget)
            setOnMenuItemClickListener { menuItem ->
                if (menuItem.itemId == com.android.common.R.id.back_arrow) {
                    if (isSearchOpen) {
                        appBar?.getSearchView()?.searchEnd()
                    } else {
                        setResult(RESULT_CANCELED)
                        finish()
                    }
                    true
                } else {
                    false
                }
            }
        }

        val searchView = appBar?.getSearchView()
        appBar?.getActionBarView()?.let { actionBar ->
            actionBar.bindBlurTarget(this@RecipientSelectActivity, blurTarget)
            actionBar.setPosition("right")
            actionBar.inflateMenu(R.menu.menu_contact_picker)
            if (searchView != null) {
                actionBar.setSearchView(searchView, R.id.search)
            }
        }

        searchView?.let { sv ->
            sv.bindBlurTarget(this@RecipientSelectActivity, blurTarget, 0)
            val existingListener = sv.onStateListener
            sv.setOnStateListener(object : MSearchView.OnSearchStateListener {
                override fun onState(state: Int) {
                    existingListener?.onState(state)
                    when (state) {
                        MSearchView.SEARCH_START -> onRecipientSearchStarted()
                        MSearchView.SEARCH_END -> finishRecipientSearchMode()
                    }
                }

                override fun onSearchTextChanged(newText: String) {
                    existingListener?.onSearchTextChanged(newText)
                    searchString = newText
                    currentFragment()?.applySearchQuery(newText)
                }
            })
            sv.findViewById<android.widget.EditText>(com.android.common.R.id.et_search_text)
                ?.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus && isSearchOpen) {
                        uiLog("searchFocus")
                        requestSearchLayoutSync()
                    }
                }
        }

        applyRecipientSearchModeChrome(isSearchOpen)
        updateSearchHintForPage(viewPager?.currentItem ?: PAGE_RECENT)

        appBar?.addOnOffsetChangedListener { layout, verticalOffset ->
            appBarVerticalOffset = verticalOffset
            syncFilterBarTranslation()
            if (isSearchOpen) {
                val range = layout.totalScrollRange
                if (range > 0 && verticalOffset > -range) {
                    layout.setExpanded(false, false)
                }
                clearRecipientSearchScrollContentTranslation()
                syncRecipientSearchFilterBarLayout()
                finalizeSearchListPaddingIfNeeded()
            }
        }
        blurTarget.post {
            appBar?.dismissCollapse()
            appBarVerticalOffset = 0
            syncListInsets()
            refreshSideFrameBlurAndInsets()
        }
    }

    private fun prewarmSearchViewLayout(searchView: MSearchView) {
        searchView.visibility = View.INVISIBLE
        searchView.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (searchView.width <= 0) return
                    searchView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    searchView.visibility = View.GONE
                }
            },
        )
    }

    private fun onRecipientSearchStarted() {
        uiLog("searchStarted")
        isSearchOpen = true
        if (listTopInsetPx < 0) {
            resolveListTopInsetIfNeeded()
        }
        searchListTopInsetPx = -1
        searchFilterBarAligned = false
        searchAlignAttempts = 0
        val menu = appBar
        val range = menu?.totalScrollRange ?: 0
        searchOpenedFromExpanded = range > 0 && -appBarVerticalOffset < range
        searchListPaddingFinalized = false
        cancelRecipientSearchLayoutSync()
        clearRecipientSearchChromeLayoutListener()
        applyRecipientSearchModeChrome(inSearch = true)
        menu?.forceKeepCollapse()
        menu?.setExpanded(false, false)
        clearFilterBarInsetListener()
        clearRecipientSearchScrollContentTranslation()
        scheduleRecipientSearchChromeLayoutListener()
        menu?.post {
            syncRecipientSearchChromeLayout()
            scheduleRecipientSearchLayoutSync()
        }
    }

    private fun finishRecipientSearchMode() {
        if (!isSearchOpen && searchString.isEmpty()) return
        uiLog("searchEnded")
        isSearchOpen = false
        searchString = ""
        searchListTopInsetPx = -1
        searchFilterBarAligned = false
        searchAlignAttempts = 0
        searchContentOffsetUnsettled = true
        searchListPaddingFinalized = false
        pageFragments.forEach { it?.resetListTranslation() }
        cancelRecipientSearchLayoutSync()
        clearRecipientSearchChromeLayoutListener()
        clearRecipientSearchScrollContentTranslation()
        pageFragments.forEach { it?.clearSearch() }
        applyRecipientSearchModeChrome(inSearch = false)
        applyFilterBarTopMarginForSearch(collapsedMenu = false)
        listTopInsetPx = -1
        clearFilterBarInsetListener()
        appBar?.dismissCollapse()
        appBarVerticalOffset = 0
        appBar?.translationY = 0f
        syncFilterBarTranslation()
        syncListInsets()
    }

    private fun closeSearchForTabSwitch() {
        searchString = ""
        pageFragments.forEach { it?.clearSearch() }
        if (isSearchOpen) {
            appBar?.getSearchView()?.searchEnd()
        }
    }

    private fun applyRecipientSearchModeChrome(inSearch: Boolean) {
        appBar?.findViewById<View>(com.android.common.R.id.m_app_bar_title)?.visibility =
            if (inSearch) View.INVISIBLE else View.VISIBLE
        appBar?.getBackArrow()?.visibility = if (inSearch) View.GONE else View.VISIBLE
    }

    private fun applyFilterBarTopMarginForSearch(collapsedMenu: Boolean) {
        val child = filterBarChild ?: return
        val lp = child.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (collapsedMenu) {
            if (filterBarExpandedTopMarginPx == Int.MIN_VALUE) {
                filterBarExpandedTopMarginPx = lp.topMargin
            }
            val target = resources.getDimensionPixelSize(com.android.common.R.dimen.tx_top_bar_toolbar_margin_top) +
                resources.getDimensionPixelSize(com.android.common.R.dimen.tx_top_bar_toolbar_height)
            if (lp.topMargin != target) {
                lp.topMargin = target
                child.layoutParams = lp
            }
            uiLog("filterBarCollapsedMargin=$target expandedSaved=$filterBarExpandedTopMarginPx")
        } else if (filterBarExpandedTopMarginPx != Int.MIN_VALUE) {
            lp.topMargin = filterBarExpandedTopMarginPx
            child.layoutParams = lp
            uiLog("filterBarRestoredMargin=${lp.topMargin}")
        }
    }

    private fun cancelRecipientSearchLayoutSync() {
        appBar?.removeCallbacks(recipientSearchLayoutSyncRunnable)
        appBar?.getSearchView()?.removeCallbacks(recipientSearchLayoutSyncRunnable)
        appBar?.getSearchView()?.removeCallbacks(recipientSearchRefineRunnable)
    }

    private fun clearRecipientSearchChromeLayoutListener() {
        recipientSearchChromeLayoutListener?.let { listener ->
            appBar?.viewTreeObserver?.removeOnGlobalLayoutListener(listener)
        }
        recipientSearchChromeLayoutListener = null
    }


    private fun shouldFreezeSearchListPadding(): Boolean =
        isSearchOpen && searchOpenedFromExpanded && !searchListPaddingFinalized && !isAppBarCollapseSettled()
    /**
     * MainActivity [animateTopOffsets]: after collapse from expanded, apply final list inset once with
     * translation compensation so recent/contacts rows do not jump.
     */
    private fun finalizeSearchListPaddingIfNeeded() {
        if (!isSearchOpen || searchListPaddingFinalized) return
        if (searchOpenedFromExpanded && !isAppBarCollapseSettled()) return
        val topPx = computeSearchListTopInsetPxOnce() ?: return
        searchListPaddingFinalized = true
        applyRecipientSearchListPaddingDirect(
            topPx = topPx,
            stabilizeTopPadding = true,
            animateStabilization = searchOpenedFromExpanded,
        )
    }

    /** CoordinatorLayout scroll behavior moves [R.id.mainBlurTarget]; list padding owns inset during search. */
    private fun clearRecipientSearchScrollContentTranslation() {
        findViewById<BlurTarget>(R.id.mainBlurTarget)?.translationY = 0f
        viewPager?.translationY = 0f
    }

    /**
     * Tracks search chrome + collapsed app bar on every layout/offset frame so the filter bar and list
     * move together (no delayed snap, no list jump from coordinator + padding fighting).
     */
    private fun syncRecipientSearchChromeLayout() {
        if (!isSearchOpen) return
        clearRecipientSearchScrollContentTranslation()
        syncRecipientSearchFilterBarLayout()
        if (shouldFreezeSearchListPadding()) {
            return
        }
        if (!searchListPaddingFinalized) {
            finalizeSearchListPaddingIfNeeded()
            return
        }
        val topPx = computeSearchListTopInsetPxOnce() ?: return
        applyRecipientSearchListPaddingDirect(topPx, stabilizeTopPadding = true)
    }

    private fun syncRecipientSearchFilterBarLayout() {
        if (!isSearchOpen) return
        val searchView = appBar?.getSearchView() ?: return
        val child = filterBarChild ?: return
        val bar = filterBar ?: return

        val chromeBottom = searchChromeBottomForSync(searchView)
        if (chromeBottom <= 0) return
        val filterBarLoc = IntArray(2)
        bar.getLocationInWindow(filterBarLoc)
        var targetMargin = chromeBottom - filterBarLoc[1]
        if (targetMargin <= 0) return
        val lp = child.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (filterBarExpandedTopMarginPx == Int.MIN_VALUE) {
            filterBarExpandedTopMarginPx = lp.topMargin
        }
        if (lp.topMargin != targetMargin) {
            lp.topMargin = targetMargin
            child.layoutParams = lp
        }
    }

    private fun searchChromeBottomForSync(searchView: MSearchView): Int {
        val container = searchView.findViewById<View>(com.android.common.R.id.search_container)
        if (
            container != null &&
            container.visibility == View.VISIBLE &&
            container.isLaidOut &&
            container.height > 0
        ) {
            val loc = IntArray(2)
            container.getLocationInWindow(loc)
            return loc[1] + container.height
        }
        if (searchView.visibility != View.VISIBLE || !searchView.isLaidOut || searchView.height <= 0) {
            return -1
        }
        val toolbar = appBar?.findViewById<View>(com.android.common.R.id.m_app_bar_toolbar) ?: return -1
        if (!toolbar.isLaidOut || toolbar.height <= 0) return -1
        val loc = IntArray(2)
        toolbar.getLocationInWindow(loc)
        return loc[1] + toolbar.height
    }

    private fun syncRecipientSearchListPaddingToFilterBar() {
        val inset = computeSearchListTopInsetPxOnce() ?: return
        applyRecipientSearchListPaddingDirect(inset)
    }

    private fun applyRecipientSearchListPaddingDirect(
        topPx: Int,
        stabilizeTopPadding: Boolean = false,
        animateStabilization: Boolean = false,
    ) {
        val resolved = maxOf(topPx, recipientSearchMinListTopPaddingPx())
        if (listTopInsetPx == resolved && !animateStabilization) return
        searchListTopInsetPx = resolved
        listTopInsetPx = resolved
        applyListTopInsetToFragments(
            topPx = resolved,
            stabilizeTopPadding = stabilizeTopPadding,
            animateTopPaddingStabilization = animateStabilization,
        )
    }

    private fun recipientSearchMinListTopPaddingPx(): Int =
        resources.getDimensionPixelSize(com.android.common.R.dimen.tx_nest_bouncy_content_padding_top)

    private fun scheduleRecipientSearchChromeLayoutListener() {
        if (recipientSearchChromeLayoutListener != null) return
        val menu = appBar ?: return
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!isSearchOpen || isFinishing || isDestroyed) return
                syncRecipientSearchChromeLayout()
                val searchView = menu.getSearchView() ?: return
                if (searchFilterBarAligned && isSearchChromeLayoutReady(searchView) && isAppBarCollapseSettled()) {
                    menu.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    recipientSearchChromeLayoutListener = null
                }
            }
        }
        recipientSearchChromeLayoutListener = listener
        menu.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun scheduleRecipientSearchLayoutSync() {
        val menu = appBar ?: return
        val searchView = menu.getSearchView() ?: return
        cancelRecipientSearchLayoutSync()
        val needsExtraSettle = searchOpenedFromExpanded || searchContentOffsetUnsettled
        val settleMs = SEARCH_LAYOUT_SETTLE_MS +
            if (needsExtraSettle) TX_SEARCH_CONTENT_OFFSET_ANIM_MS else 0L
        syncRecipientSearchChromeLayout()
        searchView.postDelayed(recipientSearchLayoutSyncRunnable, settleMs)
        searchView.postDelayed(recipientSearchRefineRunnable, settleMs + 80L)
    }

    private fun requestSearchLayoutSync() {
        scheduleRecipientSearchLayoutSync()
    }

    fun bindListScrollBehavior(recyclerView: com.goodwy.commons.views.MyRecyclerView) {
        recyclerView.clearOnScrollListeners()
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                onListScrolled()
            }
        })
        recyclerView.onOverscrollTranslationChanged = { overScrolledDistance ->
            if (!isSearchOpen) {
                val overscrollTranslation = overScrolledDistance * NEST_BOUNCY_OVERSCROLL_FACTOR
                appBar?.translationY = overscrollTranslation
                syncFilterBarTranslation()
            }
        }
    }

    fun onListScrolled() {
        if (isSearchOpen) {
            clearRecipientSearchScrollContentTranslation()
            syncRecipientSearchFilterBarLayout()
            finalizeSearchListPaddingIfNeeded()
        } else {
            syncListInsets()
        }
    }

    private fun alignFilterBarChildBelowSearchViewRefine() {
        if (!isSearchOpen) return
        syncRecipientSearchChromeLayout()
    }

    private fun applySearchListInsetsFromFilterBarLayout() {
        if (!isSearchOpen) return
        syncRecipientSearchListPaddingToFilterBar()
    }

    private fun isSearchChromeLayoutReady(searchView: MSearchView): Boolean {
        val container = searchView.findViewById<View>(com.android.common.R.id.search_container) ?: return false
        val editText = searchView.findViewById<View>(com.android.common.R.id.et_search_text) ?: return false
        if (container.visibility != View.VISIBLE) return false
        if (!container.isLaidOut || container.height <= 0) return false
        if (!editText.isLaidOut || editText.height <= 0) return false
        if (!searchView.isLaidOut || searchView.height <= 0) return false
        if (container.width > 0 && kotlin.math.abs(container.x) > 1f) return false
        return true
    }

    private fun isAppBarCollapseSettled(): Boolean {
        val bar = appBar ?: return true
        val range = bar.totalScrollRange
        if (range <= 0) return true
        return -appBarVerticalOffset >= range
    }

    private fun searchChromeBottomInWindow(searchView: MSearchView): Int {
        if (!isSearchChromeLayoutReady(searchView)) return -1
        val container = searchView.findViewById<View>(com.android.common.R.id.search_container) ?: return -1
        val loc = IntArray(2)
        container.getLocationInWindow(loc)
        return loc[1] + container.height
    }

    private fun computeSearchListTopInsetPxOnce(): Int? {
        val bar = filterBar ?: return null
        val rv = currentFragment()?.listRecyclerView() ?: return null
        if (!bar.isLaidOut || bar.height <= 0 || !rv.isLaidOut) return null
        val barInset = bar.height + dp(12)
        val rect = Rect()
        val chromeBottom = if (bar.getGlobalVisibleRect(rect) && !rect.isEmpty) {
            rect.bottom
        } else {
            val loc = IntArray(2)
            bar.getLocationOnScreen(loc)
            loc[1] + bar.height
        }
        val rvLoc = IntArray(2)
        rv.getLocationOnScreen(rvLoc)
        val geometryInset = (chromeBottom - rvLoc[1]).coerceAtLeast(0)
        return maxOf(barInset, geometryInset)
    }

    private fun updateSearchHintForPage(page: Int) {
//        val hint = when (page) {
//            PAGE_RECENT -> getString(com.goodwy.commons.R.string.pick_recent_number)
//            else -> getString(com.goodwy.commons.R.string.pick_contact_number)
//        }
//        appBar?.getSearchView()?.findViewById<android.widget.EditText>(com.android.common.R.id.et_search_text)?.hint = hint
    }

    private fun currentFragment(): RecipientSelectListFragment? {
        val page = viewPager?.currentItem ?: return null
        return pageFragments.getOrNull(page)
    }

    private fun setupViewPager() {
        val pager = viewPager ?: return
        pager.adapter = RecipientPagerAdapter(supportFragmentManager)
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                closeSearchForTabSwitch()
                updateFilterBar(position)
                updateSearchHintForPage(position)
                requestPermissionForPage(position)
            }
        })
        pager.currentItem = PAGE_RECENT
        updateFilterBar(PAGE_RECENT)
        requestPermissionForPage(PAGE_RECENT)
    }

    private fun requestPermissionForPage(page: Int) {
        when (page) {
            PAGE_RECENT -> requestCallLogPermissionIfNeeded()
            PAGE_CONTACTS -> requestContactsPermissionIfNeeded()
        }
    }

    private fun setupFilterTabs() {
        filterRecent?.setOnClickListener {
            viewPager?.currentItem = PAGE_RECENT
        }
        filterContacts?.setOnClickListener {
            viewPager?.currentItem = PAGE_CONTACTS
        }
        updateFilterBar(PAGE_RECENT)
    }

    private fun updateFilterBar(activePage: Int) {
        val textColor = getProperTextColor()
        val primaryColor = getProperPrimaryColor()
        val isRecent = activePage == PAGE_RECENT
        filterRecentLine?.setBackgroundColor(if (isRecent) primaryColor else textColor)
        filterContactsLine?.setBackgroundColor(if (isRecent) textColor else primaryColor)
        filterRecentLine?.visibility = if (isRecent) View.VISIBLE else View.GONE
        filterContactsLine?.visibility = if (isRecent) View.GONE else View.VISIBLE
    }

    private fun syncFilterBarTranslation() {
        val appbar = appBar ?: return
        
        val translation = appBarVerticalOffset.toFloat() + appbar.translationY
        filterBar?.translationY = translation
        if (translation != lastLoggedFilterTranslation) {
            lastLoggedFilterTranslation = translation
            uiLog("syncFilterBar translationY=$translation searchOpen=$isSearchOpen appBarOffset=$appBarVerticalOffset")
        }
        if (!isSearchOpen) {
            syncListInsets()
        }
    }

    private fun setupBottomActionTabs() {
        val rippleBlurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget) ?: return
        val items = ArrayList<IconItem>().apply {
            add(IconItem().apply {
                icon = com.android.common.R.drawable.ic_cmn_cancel_fill
                title = getString(com.android.common.R.string.cancel_common)
            })
            add(IconItem().apply {
                icon = com.android.common.R.drawable.ic_cmn_circle_check_fill
                title = getString(com.android.common.R.string.confirm_common)
            })
        }
        confirmTab?.setTabs(this, items, rippleBlurTarget)
        confirmTab?.setOnClickedListener { index ->
            when (index) {
                0 -> {
                    setResult(RESULT_CANCELED)
                    finish()
                }
                1 -> {
                    if (selectedByNormalized.isEmpty()) return@setOnClickedListener
                    returnSelectedRecipients()
                }
            }
        }
        updateConfirmTabEnable()
    }

    private fun updateConfirmTabEnable() {
        confirmTab?.setRippleTabEnabledWidthAlpha(1, selectedByNormalized.isNotEmpty())
    }

    private fun returnSelectedRecipients() {
        val selectedContacts = ArrayList(selectedByNormalized.values)
        val contactNumbersCount = HashMap<String, HashSet<String>>()
        selectedContacts.forEach { contact ->
            if (contact.contactId.isNotEmpty() && contact.phoneNumber.isNotEmpty()) {
                val normalized = normalizePhone(contact.phoneNumber)
                if (normalized.isNotEmpty()) {
                    contactNumbersCount.getOrPut(contact.contactId) { HashSet() }.add(normalized)
                }
            }
        }
        val multiNumberContactIds = contactNumbersCount.filterValues { it.size > 1 }.keys
        val displayTexts = ArrayList<String>()
        val normalizedNumbers = ArrayList<String>()
        val usedDisplayTexts = HashSet<String>()
        selectedContacts.forEach { contact ->
            val normalizedNumber = normalizePhone(contact.phoneNumber)
            val baseName = contact.name.ifEmpty { contact.phoneNumber }
            val shouldShowNumber = contact.contactId.isNotEmpty() && multiNumberContactIds.contains(contact.contactId)
            val baseDisplayText = if (shouldShowNumber && contact.phoneNumber.isNotEmpty()) {
                "$baseName (${contact.phoneNumber})"
            } else {
                baseName
            }
            val uniqueDisplayText = if (!usedDisplayTexts.contains(baseDisplayText)) {
                baseDisplayText
            } else if (contact.phoneNumber.isNotEmpty()) {
                "$baseDisplayText (${contact.phoneNumber})"
            } else {
                "$baseDisplayText ($normalizedNumber)"
            }
            usedDisplayTexts.add(uniqueDisplayText)
            displayTexts.add(uniqueDisplayText)
            normalizedNumbers.add(normalizedNumber)
        }
        val resultIntent = Intent().apply {
            putParcelableArrayListExtra(ContactPickerActivity.EXTRA_SELECTED_CONTACTS, selectedContacts)
            putStringArrayListExtra(ContactPickerActivity.EXTRA_SELECTED_DISPLAY_TEXTS, displayTexts)
            putStringArrayListExtra(ContactPickerActivity.EXTRA_SELECTED_PHONE_NUMBERS, normalizedNumbers)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun makeSystemBarsTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val bottomSideFrame = findViewById<MVSideFrame>(R.id.m_vertical_side_frame_bottom)
        ViewCompat.setOnApplyWindowInsetsListener(rootView!!) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val navHeight = nav.bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val dp5 = (5 * resources.displayMetrics.density).toInt()
            bottomSideFrame.layoutParams = bottomSideFrame.layoutParams.apply { height = navHeight + dp5 }
            
            applyListBottomInset(if (ime.bottom > 0) ime.bottom else navHeight)
            if (isSearchOpen && ime.bottom > 0) {
                scheduleRecipientSearchLayoutSync()
            }
            insets
        }
    }

    private fun applyListBottomInset(bottomInset: Int) {
        val activityMargin = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.activity_margin)
        val bottomPx = bottomInset + activityMargin + dp(90)
        resolveListTopInsetIfNeeded()
        val topPx = if (listTopInsetPx >= 0) listTopInsetPx else 0
        pageFragments.forEach { it?.applyListInsets(topPx, bottomPx) }
    }

    private fun syncListInsets() {
        if (isSearchOpen) {
            syncRecipientSearchChromeLayout()
            return
        }
        resolveListTopInsetIfNeeded()
        if (listTopInsetPx < 0) {
            scheduleListTopInsetAfterFilterBarLayout()
            return
        }
        val activityMargin = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.activity_margin)
        val nav = ViewCompat.getRootWindowInsets(rootView ?: return)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val bottomPx = nav + activityMargin + dp(90)
        uiLog("syncListInsets top=$listTopInsetPx bottom=$bottomPx searchOpen=false")
        applyListTopInsetToFragments(listTopInsetPx, bottomPx)
    }

    private fun applyListTopInsetToFragments(
        topPx: Int,
        bottomPx: Int? = null,
        stabilizeTopPadding: Boolean = false,
        animateTopPaddingStabilization: Boolean = false,
    ) {
        val activityMargin = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.activity_margin)
        val nav = ViewCompat.getRootWindowInsets(rootView ?: return)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val bottom = bottomPx ?: (nav + activityMargin + dp(90))
        pageFragments.forEach {
            it?.applyListInsets(
                topPx,
                bottom,
                stabilizeTopPadding,
                animateTopPaddingStabilization,
            )
        }
    }

    private fun resolveListTopInsetIfNeeded() {
        if (isSearchOpen) {
            if (searchListTopInsetPx >= 0) {
                listTopInsetPx = searchListTopInsetPx
            }
            if (listTopInsetPx >= 0) return
        }
        if (listTopInsetPx >= 0) return
        val bar = filterBar ?: return
        val barHeight = bar.height.takeIf { it > 0 }
            ?: if (bar.isLaidOut) bar.measuredHeight.takeIf { it > 0 } else null
        if (barHeight != null) {
            listTopInsetPx = barHeight + dp(12)
            uiLog("resolveBrowseInset barH=$barHeight inset=$listTopInsetPx")
            return
        }
        val widthPx = bar.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        bar.measure(widthSpec, heightSpec)
        val measured = bar.measuredHeight
        if (measured > 0) {
            listTopInsetPx = measured + dp(12)
            uiLog("resolveBrowseInset measuredH=$measured inset=$listTopInsetPx")
        }
    }

    private fun scheduleListTopInsetAfterFilterBarLayout() {
        val bar = filterBar ?: return
        if (filterBarInsetListener != null) return
        filterBarInsetListener = ViewTreeObserver.OnGlobalLayoutListener {
            resolveListTopInsetIfNeeded()
            if (listTopInsetPx >= 0) {
                clearFilterBarInsetListener()
                syncListInsets()
            }
        }
        bar.viewTreeObserver.addOnGlobalLayoutListener(filterBarInsetListener)
    }

    private fun clearFilterBarInsetListener() {
        val bar = filterBar ?: return
        filterBarInsetListener?.let { listener ->
            bar.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            filterBarInsetListener = null
        }
    }

    private fun initMVSideFrames() {
        val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
        findViewById<MVSideFrame>(R.id.m_vertical_side_frame_top)?.bindBlurTarget(blurTarget)
        findViewById<MVSideFrame>(R.id.m_vertical_side_frame_bottom)?.bindBlurTarget(blurTarget)
    }

    private fun refreshSideFrameBlurAndInsets() {
        rootView?.post {
            val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget) ?: return@post
            ViewCompat.requestApplyInsets(rootView!!)
            findViewById<MVSideFrame>(R.id.m_vertical_side_frame_top)?.bindBlurTarget(blurTarget)
            findViewById<MVSideFrame>(R.id.m_vertical_side_frame_bottom)?.bindBlurTarget(blurTarget)
            appBar?.getBackArrow()?.bindBlurTarget(this@RecipientSelectActivity, blurTarget)
            appBar?.getActionBarView()?.bindBlurTarget(this@RecipientSelectActivity, blurTarget)
            appBar?.getSearchView()?.bindBlurTarget(this@RecipientSelectActivity, blurTarget, 0)
            applyRecipientSearchModeChrome(isSearchOpen)
            if (isSearchOpen) {
                scheduleRecipientSearchLayoutSync()
            } else {
                syncListInsets()
            }
            findViewById<MVSideFrame>(R.id.m_vertical_side_frame_top)?.update()
        }
    }

    private fun uiLog(message: String) {
        Log.d(UI_LOG_TAG, message)
    }

    private fun normalizePhone(phone: String): String = phone.filter { it.isDigit() }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class RecipientPagerAdapter(fragmentManager: FragmentManager) :
        FragmentPagerAdapter(fragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getCount(): Int = 2

        override fun getItem(position: Int): Fragment {
            val fragment = RecipientSelectListFragment.newInstance(position)
            pageFragments[position] = fragment
            return fragment
        }
    }

    companion object {
        const val REQUEST_CODE_RECIPIENT_SELECT = 1013
        private const val PERMISSION_REQUEST_READ_CONTACTS = 200
        private const val PERMISSION_REQUEST_READ_CALL_LOG = 201
        private const val SEARCH_LAYOUT_SETTLE_MS = 320L
        private const val TX_SEARCH_CONTENT_OFFSET_ANIM_MS = 300L
        private const val NEST_BOUNCY_OVERSCROLL_FACTOR = 0.35f
        private const val UI_LOG_TAG = "RecipientSelectUi"
        private const val PAGE_RECENT = RecipientSelectListFragment.PAGE_RECENT
        private const val PAGE_CONTACTS = RecipientSelectListFragment.PAGE_CONTACTS
    }
}
