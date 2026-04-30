package com.android.mms.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.PhoneLookup
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.goodwy.commons.extensions.getProperAccentColor
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getColoredDrawableWithColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.getMyContactsCursor
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.helpers.MyContactsContentProvider
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.models.SimpleContact
import com.goodwy.commons.views.MyRecyclerView
import com.goodwy.commons.views.MyTextView
import com.android.common.helper.IconItem
import com.android.common.view.MRippleToolBar
import com.android.common.view.MVSideFrame
import com.android.mms.R
import com.android.mms.extensions.applyLargeTitleOnly
import com.android.mms.extensions.clearMySearchMenuSpringSync
import com.android.mms.extensions.config
import com.android.mms.extensions.postSyncMySearchMenuToolbarGeometry
import com.android.mms.extensions.syncBlurTargetTopMarginForMenu
import com.android.mms.extensions.syncTopSideFrameHeightForMenu
import com.android.mms.extensions.setupMySearchMenuSpringSync
import com.android.mms.adapters.ContactPickerAdapter
import com.android.mms.models.Contact
import com.android.mms.models.ContactPickerListRow
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beInvisible
import java.util.Calendar
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.views.MySearchMenu
import eightbitlab.com.blurview.BlurTarget

class ContactPickerActivity : SimpleActivity() {

    companion object {
        private const val PERMISSION_REQUEST_READ_CONTACTS = 100
        private const val PERMISSION_REQUEST_READ_CALL_LOG = 101
        private const val CALL_LOG_LIMIT = 500
        const val EXTRA_SELECTED_CONTACTS = "selected_contacts"
        const val EXTRA_ALREADY_SELECTED_CONTACTS = "already_selected_contacts"
        const val EXTRA_SELECTED_DISPLAY_TEXTS = "selected_display_texts"
        const val EXTRA_SELECTED_PHONE_NUMBERS = "selected_phone_numbers"
        /** Raw rows from [Contacts.CONTENT_URI] per DB fetch (one chunk per scroll / first load). */
        private const val SYSTEM_CONTACTS_DB_BATCH = 40
        /** Provider search row cap in contacts-tab search mode. */
        private const val CONTACTS_SEARCH_DB_LIMIT = 20
        /** Trigger browse pagination when this many rows or fewer remain past the last visible index. */
        private const val BROWSE_LOAD_MORE_NEAR_END_ITEMS = 5
        /** Coalesce rapid [onScrolled] / nested-scroll callbacks into one near-end check. */
        private const val BROWSE_LOAD_MORE_SCROLL_DEBOUNCE_MS = 120L
        /** Space out checks after a chunk append so layout can settle (avoids load-more storms at the bottom). */
        private const val BROWSE_LOAD_MORE_AFTER_CHUNK_MS = 280L

        /** Temporary: log scroll / load-more / chunk timings under [PERF_LOG_TAG]. Set to false to disable. */
        private const val CONTACT_PICKER_PERF_LOG = false
        private const val PERF_LOG_TAG = "ContactPickerPerf"

        fun getSelectedContacts(data: Intent?): ArrayList<Contact> {
            if (data != null && data.hasExtra(EXTRA_SELECTED_CONTACTS)) {
                @Suppress("UNCHECKED_CAST")
                val contacts = data.getParcelableArrayListExtra<Contact>(EXTRA_SELECTED_CONTACTS)
                return contacts ?: arrayListOf()
            }
            return arrayListOf()
        }

        fun getSelectedDisplayTexts(data: Intent?): ArrayList<String> {
            if (data != null && data.hasExtra(EXTRA_SELECTED_DISPLAY_TEXTS)) {
                return data.getStringArrayListExtra(EXTRA_SELECTED_DISPLAY_TEXTS) ?: arrayListOf()
            }
            return arrayListOf()
        }

        fun getSelectedPhoneNumbers(data: Intent?): ArrayList<String> {
            if (data != null && data.hasExtra(EXTRA_SELECTED_PHONE_NUMBERS)) {
                return data.getStringArrayListExtra(EXTRA_SELECTED_PHONE_NUMBERS) ?: arrayListOf()
            }
            return arrayListOf()
        }
    }

    private var scrollView: View? = null
    private var blurAppBarLayout: MySearchMenu? = null
    private var rootView: View? = null
    /** Host of the contact list; when it scrolls instead of the [RecyclerView], RV [onScrolled] does not run. */
    private var nestScrollView: NestedScrollView? = null
    private val browseLoadMoreFromScrollRunnable = Runnable { maybeLoadMoreBrowseContactsFromScroll() }
    private val browseLoadMoreAfterChunkRunnable = Runnable { maybeLoadMoreBrowseContactsFromScroll() }

    private fun perfLog(message: String) {
        if (CONTACT_PICKER_PERF_LOG) {
            Log.d(PERF_LOG_TAG, message)
        }
    }
    private var contactRecyclerView: MyRecyclerView? = null
    private var contactAdapter: ContactPickerAdapter? = null
    private val allContacts = ArrayList<Contact>()
    private val filteredContacts = ArrayList<Contact>()
    private val selectedPositions = HashSet<Int>()
    private var searchString = ""
    private var contactsCursor: android.database.Cursor? = null
    private var isLoadingMore = false
    private var hasMoreContacts = true
    private val addedContactIds = HashSet<String>()
    private val alreadySelectedContactIds = HashSet<String>()
    private var bottomBarContainer: View? = null
    private var tabBar: MRippleToolBar? = null
    private var isCallLogMode = false
    private var filterCallLog: MyTextView? = null
    private var filterContacts: MyTextView? = null
    private var filterCallLogLine: ImageView? = null
    private var filterContactsLiner: ImageView? = null
        private var callLogPlaceholder: View? = null
    private var contactPickerFilterBar: View? = null
    /** Filter bar height + 12dp; recomputed when search mode toggles (filter bar top margin changes). */
    private var contactPickerListTopInsetPx: Int = -1
    /**
     * [AppBarLayout] vertical offset while the inline search toolbar is open (search mode). The filter bar
     * is translated by this; list top padding uses [contactPickerListTopInsetPx] + this so content moves up
     * in sync with app bar collapse.
     */
    private var contactPickerSearchListTopAppBarOffsetPx: Int = 0
    private var contactPickerSearchListPaddingAppBarListener: AppBarLayout.OnOffsetChangedListener? = null
    /** Top margin of [R.id.contact_picker_filter_bar_child] when the large-title app bar is expanded. */
    private var contactPickerFilterBarExpandedTopMarginPx: Int = Int.MIN_VALUE
    private var contactPickerFilterBarInsetListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var contactPickerFilterBarAppBarOffsetListener: AppBarLayout.OnOffsetChangedListener? = null
    /** Letter rail disabled for performance on large address books; kept as plain [View] to hide in layout. */
    private var contactsLetterFastscroller: View? = null
    private var contactsLetterFastscrollerThumb: View? = null
    private val callLogMeta = ArrayList<CallLogEntryMeta>()
    private var mContent: ContactPickerActivity? = null
    /** O(1) resolve from filtered row to index in [allContacts] (avoids O(n²) indexOfFirst). */
    private val allContactKeyToIndex = HashMap<String, Int>(2048)
    /** SQL OFFSET into [Contacts.CONTENT_URI] for the next system page (SIM/phone-storage phones only). */
    private var systemContactsSqlOffset = 0
    /** MyContacts provider entries; loaded once per picker session for merge on the first DB page. */
    private val privateContactsForMerge = ArrayList<SimpleContact>()

    private data class CallLogEntryMeta(val type: Int, val timestamp: Long, val groupedCount: Int = 1)

    private data class RawCallLogRow(
        val number: String,
        val normalized: String,
        val type: Int,
        val dateMillis: Long,
        val cachedName: String?,
    )

    private data class IndexedCallLogEntry(
        val contactIndex: Int,
        val contact: Contact,
        val type: Int,
        val date: Long,
        val groupedCount: Int,
    )

    private enum class CallLogDateSection {
        TODAY,
        YESTERDAY,
        BEFORE,
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_picker)
        mContent = this@ContactPickerActivity
        rootView = findViewById(R.id.root_view)
        initTheme()
        initMVSideFrames()
        initBouncy()
        initComponent()
        makeSystemBarsToTransparent()
        setupEdgeToEdge()
        applyContactPickerWindowSurfaces()

        findViewById<View>(R.id.nest_scroll).post {
            val menu = blurAppBarLayout ?: return@post
            val blur = findViewById<BlurTarget>(R.id.blurTarget)
            val top = findViewById<View>(R.id.m_vertical_side_frame_top)
            val rv = contactRecyclerView ?: return@post
            postSyncMySearchMenuToolbarGeometry(rootView!!, menu, blur, top, paddedList = null)
            syncContactPickerBlurGeometryAndListTopPadding()
            contactPickerFilterBarAppBarOffsetListener =
                setupMySearchMenuSpringSync(menu, rv, contactPickerFilterBar)
            contactPickerSearchListPaddingAppBarListener = AppBarLayout.OnOffsetChangedListener { _, vOffset ->
                val inSearch = menu.requireCustomToolbar().isSearchExpanded
                contactPickerSearchListTopAppBarOffsetPx = if (inSearch) vOffset else 0
                if (inSearch) applyContactPickerListTopPadding()
            }
            contactPickerSearchListPaddingAppBarListener?.let { menu.addOnOffsetChangedListener(it) }
            if (config.changeColourTopBar) {
                scrollingView = contactRecyclerView
                val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
                setupSearchMenuScrollListener(contactRecyclerView!!, menu, useSurfaceColor)
            }
        }

        if (checkContactsPermission()) {
            loadContacts()
        }
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun applyContactPickerWindowSurfaces() {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        rootView?.setBackgroundColor(backgroundColor)
        findViewById<BlurTarget>(R.id.blurTarget)?.setBackgroundColor(backgroundColor)
        scrollView?.setBackgroundColor(backgroundColor)
        contactRecyclerView?.setBackgroundColor(backgroundColor)
    }

    private fun initMVSideFrames() {
        val blurTarget = findViewById<BlurTarget>(R.id.blurTarget)
        findViewById<MVSideFrame>(R.id.m_vertical_side_frame_top).bindBlurTarget(blurTarget)
        findViewById<MVSideFrame>(R.id.m_vertical_side_frame_bottom).bindBlurTarget(blurTarget)
    }

    override fun onResume() {
        super.onResume()
        // Match ThreadActivity: layout fullscreen so content draws behind transparent status/nav bars
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
        applyContactPickerWindowSurfaces()
        setupTopBarNavigation()
        scrollingView = contactRecyclerView
        blurAppBarLayout?.updateColors(
            getStartRequiredStatusBarColor(),
            scrollingView?.computeVerticalScrollOffset() ?: 0,
        )
        setContactPickerTransparentAppBarBackground()
        if (isCallLogMode) {
            contactAdapter?.scheduleGroupedTodayTimeRefresh()
        }
    }

    override fun onPause() {
        super.onPause()
        contactAdapter?.pauseGroupedTodayTimeRefresh()
    }

    override fun onDestroy() {
        contactPickerFilterBarInsetListener?.let { listener ->
            contactPickerFilterBar?.viewTreeObserver?.removeOnGlobalLayoutListener(listener)
        }
        contactPickerFilterBarInsetListener = null
        blurAppBarLayout?.let { menu ->
            contactPickerSearchListPaddingAppBarListener?.let { menu.removeOnOffsetChangedListener(it) }
            clearMySearchMenuSpringSync(
                menu,
                contactRecyclerView,
                contactPickerFilterBarAppBarOffsetListener,
                contactPickerFilterBar,
            )
        }
        contactPickerFilterBarAppBarOffsetListener = null
        contactPickerSearchListPaddingAppBarListener = null
        contactsCursor?.takeIf { !it.isClosed }?.close()
        contactsCursor = null
        contactRecyclerView?.removeCallbacks(browseLoadMoreFromScrollRunnable)
        contactRecyclerView?.removeCallbacks(browseLoadMoreAfterChunkRunnable)
        super.onDestroy()
    }

    private fun makeSystemBarsToTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val bottomSideFrame = findViewById<MVSideFrame>(R.id.m_vertical_side_frame_bottom)

        ViewCompat.setOnApplyWindowInsetsListener(rootView!!) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val navHeight = nav.bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val dp5 = (5 * resources.displayMetrics.density).toInt()
            bottomSideFrame.layoutParams = bottomSideFrame.layoutParams.apply { height = navHeight + dp5 }

            val barContainer = bottomBarContainer
            if (barContainer != null) {
                val bottomBarLp = barContainer.layoutParams as ViewGroup.MarginLayoutParams
                val bottomOffset = dp(0)
                if (ime.bottom > 0) {
                    bottomBarLp.bottomMargin = ime.bottom + bottomOffset
                    contactRecyclerView?.scrollToPosition((contactAdapter?.itemCount ?: 1) - 1)
                } else {
                    bottomBarLp.bottomMargin = navHeight + bottomOffset
                }
                barContainer.layoutParams = bottomBarLp
            }
            applyContactPickerListBottomInset(navHeight, ime.bottom)
            insets
        }
    }

    private fun applyContactPickerListBottomInset(navHeight: Int, imeBottom: Int) {
        val rv = contactRecyclerView ?: return
        val activityMargin = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.activity_margin)
        val bottomInset = if (imeBottom > 0) imeBottom else navHeight
        rv.updatePadding(bottom = bottomInset + activityMargin + dp(90))
    }

    /**
     * After [MySearchMenu] height changes with [WRAP_CONTENT] (e.g. leaving search), sync blur/side-frame
     * once layout has settled, then re-apply list top inset.
     */
    private fun syncContactPickerBlurGeometryAndListTopPadding() {
        val menu = blurAppBarLayout ?: return
        val blur = findViewById<BlurTarget>(R.id.blurTarget) ?: return
        val top = findViewById<View>(R.id.m_vertical_side_frame_top)
        menu.post {
            menu.post {
                val h = menu.height.takeIf { it > 0 } ?: menu.measuredHeight.takeIf { it > 0 } ?: return@post
                syncBlurTargetTopMarginForMenu(blur, h)
                syncTopSideFrameHeightForMenu(top, menu, h)
                blur.invalidate()
                applyContactPickerListTopPadding()
            }
        }
    }

    /** Resolves filter bar height + 12dp without waiting for a layout pass (avoids wrong padding on first search). */
    private fun resolveContactPickerListTopInsetPxIfNeeded() {
        if (contactPickerListTopInsetPx >= 0) return
        val bar = contactPickerFilterBar ?: return
        val hLaidOut = bar.height.takeIf { it > 0 }
            ?: if (bar.isLaidOut) bar.measuredHeight.takeIf { it > 0 } else null
        if (hLaidOut != null) {
            contactPickerListTopInsetPx = hLaidOut + dp(12)
            return
        }
        val widthPx = bar.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        bar.measure(widthSpec, heightSpec)
        val mh = bar.measuredHeight
        if (mh > 0) {
            contactPickerListTopInsetPx = mh + dp(12)
        }
    }

    /**
     * List starts below [R.id.contact_picker_filter_bar] plus 12dp.
     *
     * Important: the list is inside a [NestedScrollView] while the filter bar is an overlay sibling.
     * So using on-screen geometry (getLocationOnScreen) will change as the parent scrolls and can
     * incorrectly inflate the list top padding.
     */
    private fun applyContactPickerListTopPadding() {
        val rv = contactRecyclerView ?: return
        val inSearch = blurAppBarLayout?.requireCustomToolbar()?.isSearchExpanded == true
        val bar = contactPickerFilterBar
        fun topPxForBaseInset(): Int {
            val base = contactPickerListTopInsetPx
            if (base < 0) return -1
            return (base + if (inSearch) contactPickerSearchListTopAppBarOffsetPx else 0).coerceAtLeast(0)
        }
        // In search mode the app bar is collapsed and the filter strip child top margin changes.
        // LinearLayout measured height includes child margins, so using the *current* filter bar height
        // keeps the list padding in sync with that margin change (no stale cached inset).
        if (inSearch && bar != null) {
            val baseNow = bar.height.takeIf { it > 0 }
                ?: if (bar.isLaidOut) bar.measuredHeight.takeIf { it > 0 } else null
                ?: run {
                    val widthPx = bar.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                    val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
                    val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    bar.measure(widthSpec, heightSpec)
                    bar.measuredHeight.takeIf { it > 0 }
                }
            if (baseNow != null) {
                rv.updatePadding(top = (baseNow + dp(12) + contactPickerSearchListTopAppBarOffsetPx).coerceAtLeast(0))
                return
            }
        }

        resolveContactPickerListTopInsetPxIfNeeded()
        if (contactPickerListTopInsetPx >= 0) {
            val top = topPxForBaseInset()
            if (top >= 0) rv.updatePadding(top = top)
            return
        }
        val safeBar = bar ?: return
        if (contactPickerFilterBarInsetListener != null) {
            return
        }
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val b = contactPickerFilterBar ?: return
                if (b.height <= 0) return
                contactPickerListTopInsetPx = b.height + dp(12)
                b.viewTreeObserver.removeOnGlobalLayoutListener(this)
                contactPickerFilterBarInsetListener = null
                applyContactPickerListTopPadding()
            }
        }
        contactPickerFilterBarInsetListener = listener
        safeBar.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    /**
     * Search mode uses a fixed collapsed app bar height; sync blur/side-frame in the same frame as
     * [MySearchMenu.collapseAndLockCollapsing] so the list does not jump before padding settles.
     */
    private fun syncContactPickerBlurForCollapsedSearchMenu() {
        val menu = blurAppBarLayout ?: return
        val blur = findViewById<BlurTarget>(R.id.blurTarget) ?: return
        val top = findViewById<View>(R.id.m_vertical_side_frame_top)
        val h = menu.getCollapsedHeightPx().takeIf { it > 0 } ?: return
        syncBlurTargetTopMarginForMenu(blur, h)
        syncTopSideFrameHeightForMenu(top, menu, h)
        blur.invalidate()
    }

    /**
     * The filter strip uses a large [R.id.contact_picker_filter_bar_child] top margin to clear the expanded
     * large title; in search mode the app bar is collapsed, so the margin must match [MySearchMenu] height.
     */
    private fun applyContactPickerFilterBarTopMarginForSearch(collapsedMenu: Boolean) {
        val child = findViewById<View>(R.id.contact_picker_filter_bar_child) ?: return
        val lp = child.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (collapsedMenu) {
            val menu = blurAppBarLayout ?: return
            if (contactPickerFilterBarExpandedTopMarginPx == Int.MIN_VALUE) {
                contactPickerFilterBarExpandedTopMarginPx = lp.topMargin
            }
            val target = menu.getCollapsedHeightPx().takeIf { it > 0 } ?: return
            if (lp.topMargin != target) {
                lp.topMargin = target
                child.layoutParams = lp
            }
        } else if (contactPickerFilterBarExpandedTopMarginPx != Int.MIN_VALUE) {
            lp.topMargin = contactPickerFilterBarExpandedTopMarginPx
            child.layoutParams = lp
        }
    }

    private fun setContactPickerTransparentAppBarBackground() {
        val menu = blurAppBarLayout ?: return
        menu.setBackgroundColor(Color.TRANSPARENT)
        menu.binding.searchBarContainer.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun initBouncy() {
        blurAppBarLayout = findViewById(R.id.blur_app_bar_layout)
        scrollView = findViewById(R.id.nest_scroll)
    }

    private fun initComponent() {
        blurAppBarLayout?.applyLargeTitleOnly(getString(R.string.select_contacts))
        setupTopBarNavigation()

        bottomBarContainer = findViewById(R.id.lyt_action)
        tabBar = findViewById(R.id.confirm_tab)

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
        val blurTarget = findViewById<BlurTarget>(R.id.blurTarget)
        tabBar?.setTabs(this, items, blurTarget)

        tabBar?.setOnClickedListener { index ->
            when (index) {
                0 -> {
                    setResult(RESULT_CANCELED)
                    finish()
                }
                1 -> returnSelectedContacts()
            }
        }

        blurAppBarLayout?.requireCustomToolbar()?.apply {
            inflateMenu(R.menu.menu_contact_picker)
            bindBlurTarget(this@ContactPickerActivity, blurTarget)
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.search) {
                    val bar = blurAppBarLayout ?: return@setOnMenuItemClickListener true
                    // Collapse the large-title region before expanding inline search (same order as MainActivity).
                    bar.collapseAndLockCollapsing()
                    syncContactPickerBlurForCollapsedSearchMenu()
                    bar.binding.collapsingTitle.visibility = View.GONE
                    expandSearch()
                    true
                } else false
            }
            setOnSearchExpandListener {
                val bar = blurAppBarLayout ?: return@setOnSearchExpandListener
                bar.collapseAndLockCollapsing()
                syncContactPickerBlurForCollapsedSearchMenu()
                applyContactPickerFilterBarTopMarginForSearch(collapsedMenu = true)
                contactPickerListTopInsetPx = -1
                resolveContactPickerListTopInsetPxIfNeeded()
                applyContactPickerListTopPadding()
                contactPickerFilterBar?.post { applyContactPickerListTopPadding() }
                bar.binding.collapsingTitle.visibility = View.GONE
                hideTopBarNavigation()
                contactRecyclerView?.isNestedScrollingEnabled = false
                contactRecyclerView?.scrollToPosition((contactAdapter?.itemCount ?: 1) - 1)
            }
            setOnSearchBackClickListener {
                val bar = blurAppBarLayout ?: return@setOnSearchBackClickListener
                applyContactPickerFilterBarTopMarginForSearch(collapsedMenu = false)
                contactPickerSearchListTopAppBarOffsetPx = 0
                contactPickerListTopInsetPx = -1
                bar.unlockCollapsing()
                bar.setExpanded(true, true)
                bar.binding.collapsingTitle.visibility = View.VISIBLE
                setupTopBarNavigation()
                contactRecyclerView?.isNestedScrollingEnabled = false
                syncContactPickerBlurGeometryAndListTopPadding()
            }
            setOnSearchTextChangedListener { s ->
                searchString = s ?: ""
                if (isCallLogMode) {
                    updateAdapterWithFilteredContacts()
                } else {
                    searchListByQuery(searchString)
                }
            }
        }

        contactRecyclerView = findViewById<MyRecyclerView>(R.id.contactRecyclerView).apply {
            layoutManager = LinearLayoutManager(this@ContactPickerActivity)
            setHasFixedSize(false)
            // NestedScrollView is the parent; nested scrolling on the list often steals scroll so
            // [RecyclerView.OnScrollListener.onScrolled] never runs and browse pagination never triggers.
            isNestedScrollingEnabled = false
        }
        contactAdapter = ContactPickerAdapter(this)
        contactRecyclerView?.adapter = contactAdapter

        contactsLetterFastscroller = findViewById(R.id.contactsLetterFastscroller)
        contactsLetterFastscrollerThumb = findViewById(R.id.contactsLetterFastscrollerThumb)
        hideContactsLetterFastScroller()

        contactRecyclerView?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                scheduleBrowseLoadMoreFromScrollDebounced()
            }
        })

        nestScrollView = findViewById(R.id.nest_scroll)
        nestScrollView?.setOnScrollChangeListener { _, _, _, _, _ ->
            scheduleBrowseLoadMoreFromScrollDebounced()
        }

        contactAdapter?.setListener(object : ContactPickerAdapter.ContactPickerAdapterListener {
            override fun onContactToggled(contactIndex: Int, isSelected: Boolean) {
                if (isCallLogMode) {
                    if (contactIndex in allContacts.indices) {
                        if (isSelected) selectedPositions.add(contactIndex) else selectedPositions.remove(contactIndex)
                    }
                    return
                }
                if (contactIndex !in filteredContacts.indices) return
                val contact = filteredContacts[contactIndex]
                val idx = allContactKeyToIndex[contactNumberKey(contact.contactId, contact.phoneNumber)] ?: return
                if (isSelected) selectedPositions.add(idx) else selectedPositions.remove(idx)
            }
        })

        contactPickerFilterBar = findViewById(R.id.contact_picker_filter_bar)
        val filterBar = contactPickerFilterBar as? ViewGroup
        callLogPlaceholder = findViewById(R.id.call_log_placeholder)
//        if (filterBar != null && filterBar.childCount >= 2) {
//            filterCallLog = filterBar.getChildAt(0) as? MyTextView
//            filterContacts = filterBar.getChildAt(1) as? MyTextView
//        } else {
//            filterCallLog = findViewById(R.id.filter_call_log)
//            filterContacts = findViewById(R.id.filter_contacts)
//        }
        filterCallLog = mContent?.findViewById(R.id.filter_call_log)
        filterContacts = mContent?.findViewById(R.id.filter_contacts)
        filterCallLogLine = findViewById(R.id.filter_call_log_liner)
        filterContactsLiner = findViewById(R.id.filter_contacts_liner)

        filterCallLog?.let { callLogTab ->
            callLogTab.isClickable = true
            callLogTab.isFocusable = true
            callLogTab.setOnClickListener {
                if (!isCallLogMode) {
                    isCallLogMode = true
                    updateFilterBar()
                    if (checkCallLogPermission()) loadCallLog()
                }
            }
        }
        filterContacts?.let { contactsTab ->
            contactsTab.isClickable = true
            contactsTab.isFocusable = true
            contactsTab.setOnClickListener {
                if (isCallLogMode) {
                    isCallLogMode = false
                    updateFilterBar()
                    if (checkContactsPermission()) loadContacts()
                }
            }
        }
        updateFilterBar()
        resolveContactPickerListTopInsetPxIfNeeded()
        applyContactPickerListTopPadding()
    }

    private fun setupTopBarNavigation() {
        blurAppBarLayout?.requireCustomToolbar()?.apply {
            val textColor = getProperTextColor()
            navigationIcon = resources.getColoredDrawableWithColor(
                this@ContactPickerActivity,
                com.android.common.R.drawable.ic_cmn_arrow_left_fill,
                textColor
            )
            setNavigationContentDescription(com.goodwy.commons.R.string.back)
            setNavigationOnClickListener {
                finish()
            }
        }
    }

    private fun hideTopBarNavigation() {
        blurAppBarLayout?.requireCustomToolbar()?.apply {
            navigationIcon = null
            setNavigationOnClickListener(null)
        }
    }

    private fun updateFilterBar() {
        val textColor = getProperTextColor()
        val primaryColor = getProperPrimaryColor()
        if (isCallLogMode) {
//            filterCallLog?.setTextColor(primaryColor)
//            filterContacts?.setTextColor(textColor)
            filterCallLogLine?.setBackgroundColor(primaryColor)
            filterContactsLiner?.setBackgroundColor(textColor)
            filterCallLogLine?.visibility = View.VISIBLE
            filterContactsLiner?.visibility = View.GONE
        } else {
//            filterCallLog?.setTextColor(textColor)
//            filterContacts?.setTextColor(primaryColor)
            filterCallLogLine?.setBackgroundColor(textColor)
            filterContactsLiner?.setBackgroundColor(primaryColor)
            filterCallLogLine?.visibility = View.GONE
            filterContactsLiner?.visibility = View.VISIBLE
        }
    }

    private fun hideContactsLetterFastScroller() {
        contactsLetterFastscroller?.visibility = View.GONE
        contactsLetterFastscrollerThumb?.visibility = View.GONE
    }

    private data class ContactsDbChunk(
        val contacts: ArrayList<Contact>,
        /** Local indices within [contacts] for pre-selected rows. */
        val selectedLocalIndices: HashSet<Int>,
        val hasMoreFromDb: Boolean,
    )

    /** Same dedupe rules as [loadContacts] / NewConversationActivity merge. */
    private fun mergePrivateContactsIntoSystemList(
        mergedContacts: ArrayList<SimpleContact>,
        privateContacts: List<SimpleContact>,
    ) {
        if (privateContacts.isEmpty()) return
        val existingPhoneNumbers = HashSet<String>()
        val existingContactNamesWithoutPhone = HashSet<String>()
        mergedContacts.forEach { contact ->
            contact.phoneNumbers.forEach { phoneNumber ->
                existingPhoneNumbers.add(phoneNumber.normalizedNumber)
            }
            if (contact.name.isNotEmpty() && contact.phoneNumbers.isEmpty()) {
                existingContactNamesWithoutPhone.add(contact.name.lowercase().trim())
            }
        }
        privateContacts.forEach { privateContact ->
            val hasMatchingPhoneNumber = privateContact.phoneNumbers.isNotEmpty() &&
                privateContact.phoneNumbers.any { phoneNumber ->
                    existingPhoneNumbers.contains(phoneNumber.normalizedNumber)
                }
            val hasMatchingName = privateContact.phoneNumbers.isEmpty() &&
                privateContact.name.isNotEmpty() &&
                existingContactNamesWithoutPhone.contains(privateContact.name.lowercase().trim())
            if (!hasMatchingPhoneNumber && !hasMatchingName) {
                mergedContacts.add(privateContact)
                privateContact.phoneNumbers.forEach { phoneNumber ->
                    existingPhoneNumbers.add(phoneNumber.normalizedNumber)
                }
                if (privateContact.name.isNotEmpty() && privateContact.phoneNumbers.isEmpty()) {
                    existingContactNamesWithoutPhone.add(privateContact.name.lowercase().trim())
                }
            }
        }
        mergedContacts.sort()
    }

    private fun expandSimpleContactsToContactsWithSelection(
        merged: List<SimpleContact>,
    ): Pair<ArrayList<Contact>, HashSet<Int>> {
        val contactList = ArrayList<Contact>()
        val selectedLocal = HashSet<Int>()
        var localIndex = 0
        for (sc in merged) {
            val contactIdStr = sc.contactId.toString()
            val name = sc.name
            val org = sc.company ?: ""
            if (sc.phoneNumbers.isEmpty()) continue
            for (pn in sc.phoneNumbers) {
                val key = contactNumberKey(contactIdStr, pn.value)
                contactList.add(Contact(name, contactIdStr, -1, pn.value, "", org))
                if (alreadySelectedContactIds.contains(key)) {
                    selectedLocal.add(localIndex)
                }
                localIndex++
            }
        }
        return contactList to selectedLocal
    }

    /**
     * Loads one batch from the contacts DB (and merges private entries on the first chunk only).
     * Skips consecutive empty cursor pages in one call so the cursor offset still advances.
     * Must run on a background thread.
     */
    private fun loadNextContactsChunkFromDb(isFirstPage: Boolean): ContactsDbChunk {
        val chunkWallStart = SystemClock.elapsedRealtime()
        val helper = SimpleContactsHelper(this@ContactPickerActivity)
        val accum = ArrayList<SimpleContact>()
        var hasMoreFromDb = false
        var guard = 0
        var dbIterations = 0
        while (guard++ < 100) {
            dbIterations++
            val tPage = SystemClock.elapsedRealtime()
            val (page, nextOffset, hasMoreRows) = helper.getSystemContactsSortedPageFromDbSync(
                favoritesOnly = false,
                contactCursorOffset = systemContactsSqlOffset,
                maxCursorRows = SYSTEM_CONTACTS_DB_BATCH,
            )
            val pageMs = SystemClock.elapsedRealtime() - tPage
            systemContactsSqlOffset = nextOffset
            hasMoreFromDb = hasMoreRows
            perfLog(
                "loadNextChunk dbIter=$dbIterations getSystemPageMs=$pageMs simpleRows=${page.size} " +
                    "cursorOffset=$nextOffset hasMoreRows=$hasMoreRows",
            )
            if (page.isNotEmpty()) {
                accum.addAll(page)
                break
            }
            if (!hasMoreRows) break
        }
        if (accum.isEmpty()) {
            perfLog(
                "loadNextChunk EMPTY accum totalMs=${SystemClock.elapsedRealtime() - chunkWallStart} " +
                    "dbIters=$dbIterations hasMoreFromDb=$hasMoreFromDb",
            )
            return ContactsDbChunk(ArrayList(), HashSet(), hasMoreFromDb = hasMoreFromDb)
        }
        val tMerge = SystemClock.elapsedRealtime()
        if (isFirstPage && privateContactsForMerge.isNotEmpty()) {
            mergePrivateContactsIntoSystemList(accum, privateContactsForMerge)
        } else {
            accum.sort()
        }
        val mergeSortMs = SystemClock.elapsedRealtime() - tMerge
        val tExpand = SystemClock.elapsedRealtime()
        val (contacts, selectedLocal) = expandSimpleContactsToContactsWithSelection(accum)
        val expandMs = SystemClock.elapsedRealtime() - tExpand
        perfLog(
            "loadNextChunk DONE isFirst=$isFirstPage totalMs=${SystemClock.elapsedRealtime() - chunkWallStart} " +
                "simpleAccum=${accum.size} expandedContacts=${contacts.size} hasMore=$hasMoreFromDb " +
                "mergeSortMs=$mergeSortMs expandMs=$expandMs dbIters=$dbIterations",
        )
        return ContactsDbChunk(contacts, selectedLocal, hasMoreFromDb)
    }

    private fun appendKeyIndicesForRange(startIndex: Int, contacts: List<Contact>) {
        contacts.forEachIndexed { i, c ->
            allContactKeyToIndex[contactNumberKey(c.contactId, c.phoneNumber)] = startIndex + i
        }
    }

    private fun searchListByQuery(s: String) {
        searchString = s
        if (s.trim().isEmpty()) {
            hasMoreContacts = false
            isLoadingMore = false
            startBrowseContactsLoadFromDb()
            return
        }
        hasMoreContacts = false
        isLoadingMore = false
        val query = s.trim()
        ensureBackgroundThread {
            val helper = SimpleContactsHelper(this@ContactPickerActivity)
            val systemMatches = helper.getAvailableContactsMatchingSearchSync(
                favoritesOnly = false,
                searchText = query,
                limit = CONTACTS_SEARCH_DB_LIMIT,
            )
            val qLower = query.lowercase()
            val merged = ArrayList<SimpleContact>()
            merged.addAll(systemMatches)
            if (merged.isEmpty() && privateContactsForMerge.isNotEmpty()) {
                for (c in privateContactsForMerge) {
                    val nameMatch = c.name.lowercase().contains(qLower)
                    val phoneMatch = c.phoneNumbers.any { pn ->
                        pn.value.lowercase().contains(qLower) || pn.normalizedNumber.contains(qLower)
                    }
                    if (nameMatch || phoneMatch) merged.add(c)
                }
            }
            if (systemMatches.isNotEmpty() && privateContactsForMerge.isNotEmpty()) {
                mergePrivateContactsIntoSystemList(merged, privateContactsForMerge)
            } else {
                merged.sort()
            }
            val (contactList, selectedLocal) = expandSimpleContactsToContactsWithSelection(merged)
            val keyToIndex = HashMap<String, Int>(contactList.size * 2)
            contactList.forEachIndexed { i, c ->
                keyToIndex[contactNumberKey(c.contactId, c.phoneNumber)] = i
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allContacts.clear()
                allContacts.addAll(contactList)
                allContactKeyToIndex.clear()
                allContactKeyToIndex.putAll(keyToIndex)
                selectedPositions.clear()
                val globalSelected = HashSet<Int>()
                selectedLocal.forEach { globalSelected.add(it) }
                globalSelected.forEach { selectedPositions.add(it) }
                filteredContacts.clear()
                filteredContacts.addAll(contactList)
                contactAdapter?.setContactModeItems(ArrayList(filteredContacts), buildFilteredSelectedIndicesForAdapter())
                hideContactsLetterFastScroller()
            }
        }
    }

    /** First browse page from DB + private merge (same rules as [loadContacts]). */
    private fun startBrowseContactsLoadFromDb() {
        ensureBackgroundThread {
            val bgWall = SystemClock.elapsedRealtime()
            val tPrivate = SystemClock.elapsedRealtime()
            val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
            val privateList = MyContactsContentProvider.getSimpleContacts(this@ContactPickerActivity, privateCursor)
            val privateMs = SystemClock.elapsedRealtime() - tPrivate
            privateContactsForMerge.clear()
            privateContactsForMerge.addAll(privateList)
            systemContactsSqlOffset = 0
            val chunk = loadNextContactsChunkFromDb(isFirstPage = true)
            perfLog(
                "startBrowse BG done privateMs=$privateMs privateCount=${privateList.size} " +
                    "firstChunkContacts=${chunk.contacts.size} hasMore=${chunk.hasMoreFromDb} " +
                    "bgWallMs=${SystemClock.elapsedRealtime() - bgWall}",
            )
            runOnUiThread {
                val uiStart = SystemClock.elapsedRealtime()
                if (isFinishing || isDestroyed) return@runOnUiThread
                allContacts.clear()
                allContacts.addAll(chunk.contacts)
                allContactKeyToIndex.clear()
                appendKeyIndicesForRange(0, chunk.contacts)
                selectedPositions.clear()
                chunk.selectedLocalIndices.forEach { selectedPositions.add(it) }
                filteredContacts.clear()
                filteredContacts.addAll(allContacts)
                hasMoreContacts = chunk.hasMoreFromDb
                isLoadingMore = false
                contactAdapter?.setContactModeItems(
                    ArrayList(filteredContacts),
                    buildFilteredSelectedIndicesForAdapter(),
                )
                hideContactsLetterFastScroller()
                scheduleBrowseLoadMoreAfterChunk()
                perfLog(
                    "startBrowse UI applied contacts=${chunk.contacts.size} uiMs=${SystemClock.elapsedRealtime() - uiStart} " +
                        "sinceBgStartMs=${SystemClock.elapsedRealtime() - bgWall}",
                )
            }
        }
    }

    private fun contactMatchesQuery(contact: Contact, query: String): Boolean {
        return (contact.name.lowercase().contains(query)) ||
            (contact.phoneNumber.lowercase().contains(query)) ||
            (contact.address.lowercase().contains(query)) ||
            (contact.organizationName.lowercase().contains(query))
    }

    private fun groupCallsByDateSections(entries: List<IndexedCallLogEntry>): List<ContactPickerListRow> {
        if (entries.isEmpty()) return emptyList()
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val yesterdayStart = (todayStart.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val todayStartMillis = todayStart.timeInMillis
        val yesterdayStartMillis = yesterdayStart.timeInMillis
        val result = ArrayList<ContactPickerListRow>()
        var lastSection: CallLogDateSection? = null
        for (entry in entries) {
            val currentSection = when {
                entry.date >= todayStartMillis -> CallLogDateSection.TODAY
                entry.date >= yesterdayStartMillis -> CallLogDateSection.YESTERDAY
                else -> CallLogDateSection.BEFORE
            }
            if (currentSection != lastSection) {
                val dayCode = when (currentSection) {
                    CallLogDateSection.TODAY -> ContactPickerListRow.DateSection.SECTION_TODAY
                    CallLogDateSection.YESTERDAY -> ContactPickerListRow.DateSection.SECTION_YESTERDAY
                    CallLogDateSection.BEFORE -> ContactPickerListRow.DateSection.SECTION_BEFORE
                }
                result.add(ContactPickerListRow.DateSection(dayCode))
                lastSection = currentSection
            }
            result.add(
                ContactPickerListRow.ContactRow(
                    contactIndex = entry.contactIndex,
                    callType = entry.type,
                    callTimestamp = entry.date,
                    groupedCallCount = entry.groupedCount,
                ),
            )
        }
        return result
    }

    /** Maps [selectedPositions] (indices in [allContacts]) to adapter row indices in [filteredContacts]. */
    private fun buildFilteredSelectedIndicesForAdapter(): HashSet<Int> {
        val out = HashSet<Int>()
        for (i in filteredContacts.indices) {
            val contact = filteredContacts[i]
            val idx = allContactKeyToIndex[contactNumberKey(contact.contactId, contact.phoneNumber)] ?: continue
            if (selectedPositions.contains(idx)) out.add(i)
        }
        return out
    }

    private fun updateAdapterWithFilteredContacts() {
        if (isCallLogMode) {
            val query = searchString.lowercase().trim()
            val entries = ArrayList<IndexedCallLogEntry>()
            allContacts.forEachIndexed { i, contact ->
                if (query.isEmpty() || contactMatchesQuery(contact, query)) {
                    if (i in callLogMeta.indices) {
                        val m = callLogMeta[i]
                        entries.add(IndexedCallLogEntry(i, contact, m.type, m.timestamp, m.groupedCount))
                    }
                }
            }
            val rows = groupCallsByDateSections(entries)
            val filteredSelected = HashSet<Int>()
            entries.forEach { e ->
                if (selectedPositions.contains(e.contactIndex)) filteredSelected.add(e.contactIndex)
            }
            contactAdapter?.setCallLogModeItems(rows, allContacts, filteredSelected)
            hideContactsLetterFastScroller()
            return
        }
        if (searchString.trim().isEmpty()) {
            filteredContacts.clear()
            filteredContacts.addAll(allContacts)
        }
        val filteredSelected = buildFilteredSelectedIndicesForAdapter()
        contactAdapter?.setContactModeItems(ArrayList(filteredContacts), filteredSelected)
        hideContactsLetterFastScroller()
    }

    private fun checkContactsPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), PERMISSION_REQUEST_READ_CONTACTS)
            false
        } else true
    }

    private fun checkCallLogPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CALL_LOG), PERMISSION_REQUEST_READ_CALL_LOG)
            false
        } else true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_READ_CONTACTS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    loadContacts()
                } else {
                    Toast.makeText(this, com.goodwy.commons.R.string.no_contacts_permission, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            PERMISSION_REQUEST_READ_CALL_LOG -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    loadCallLog()
                } else {
                    Toast.makeText(this, R.string.call_log_permission_required, Toast.LENGTH_LONG).show()
                    isCallLogMode = false
                    updateFilterBar()
                }
            }
        }
    }

    private fun loadCallLog() {
        allContacts.clear()
        callLogMeta.clear()
        filteredContacts.clear()
        selectedPositions.clear()
        allContactKeyToIndex.clear()
        callLogPlaceholder?.visibility = View.GONE
        contactRecyclerView?.visibility = View.VISIBLE

        val alreadySelected = intent?.getParcelableArrayListExtra<Contact>(EXTRA_ALREADY_SELECTED_CONTACTS) ?: arrayListOf()
        val alreadyNumbers = alreadySelected.map { normalizePhoneNumber(it.phoneNumber) }.toSet()

        Thread {
            try {
                val projection = arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.DATE,
                    CallLog.Calls.TYPE,
                )
                var cursor: android.database.Cursor? = null
                try {
                    cursor = contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        projection,
                        null,
                        null,
                        "${CallLog.Calls.DATE} DESC"
                    )
                } catch (_: SecurityException) {
                    runOnUiThread {
                        callLogMeta.clear()
                        callLogPlaceholder?.visibility = View.VISIBLE
                        contactRecyclerView?.visibility = View.GONE
                        contactAdapter?.setCallLogModeItems(
                            listRows = emptyList(),
                            contactSource = emptyList(),
                            selectedIndices = emptySet(),
                        )
                        hideContactsLetterFastScroller()
                    }
                    return@Thread
                }

                val list = ArrayList<Contact>()
                val meta = ArrayList<CallLogEntryMeta>()
                val rawRows = ArrayList<RawCallLogRow>()
                cursor?.use { c ->
                    val nameCol = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    val numberCol = c.getColumnIndex(CallLog.Calls.NUMBER)
                    val dateCol = c.getColumnIndex(CallLog.Calls.DATE)
                    val typeCol = c.getColumnIndex(CallLog.Calls.TYPE)
                    if (numberCol < 0 || dateCol < 0) return@use
                    var count = 0
                    while (c.moveToNext() && count < CALL_LOG_LIMIT) {
                        val number = c.getString(numberCol) ?: continue
                        if (number.isBlank()) continue
                        val normalized = normalizePhoneNumber(number)
                        val dateMillis = c.getLong(dateCol)
                        val callType = if (typeCol >= 0) c.getInt(typeCol) else CallLog.Calls.INCOMING_TYPE
                        val cached = if (nameCol >= 0) c.getString(nameCol) else null
                        rawRows.add(RawCallLogRow(number, normalized, callType, dateMillis, cached))
                        count++
                    }
                }
                val countByNormalized = rawRows.groupingBy { it.normalized }.eachCount()
                val seenNumbers = HashSet<String>()
                for (raw in rawRows) {
                    if (seenNumbers.contains(raw.normalized)) continue
                    seenNumbers.add(raw.normalized)
                    var name = raw.number
                    if (!raw.cachedName.isNullOrBlank()) name = raw.cachedName
                    if (name == raw.number && ContextCompat.checkSelfPermission(this@ContactPickerActivity, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                        getDisplayNameForNumber(raw.number)?.let { resolvedName ->
                            if (resolvedName.isNotBlank()) name = resolvedName
                        }
                    }
                    val groupedCount = countByNormalized[raw.normalized] ?: 1
                    list.add(Contact(name = name, contactId = "", phoneNumber = raw.number))
                    meta.add(CallLogEntryMeta(type = raw.type, timestamp = raw.dateMillis, groupedCount = groupedCount))
                }

                val selected = HashSet<Int>()
                list.forEachIndexed { index, contact ->
                    if (alreadyNumbers.contains(normalizePhoneNumber(contact.phoneNumber))) {
                        selected.add(index)
                    }
                }
                runOnUiThread {
                    allContacts.clear()
                    allContacts.addAll(list)
                    callLogMeta.clear()
                    callLogMeta.addAll(meta)
                    filteredContacts.clear()
                    selectedPositions.clear()
                    selected.forEach { selectedPositions.add(it) }
                    if (searchString.trim().isEmpty()) {
                        filteredContacts.addAll(list)
                        updateAdapterWithFilteredContacts()
                    } else {
                        // In call-log mode, filtering is done locally (no contacts DB search).
                        updateAdapterWithFilteredContacts()
                    }
                    if (list.isEmpty()) {
                        callLogPlaceholder?.visibility = View.VISIBLE
                        contactRecyclerView?.visibility = View.GONE
                        hideContactsLetterFastScroller()
                    } else {
                        callLogPlaceholder?.visibility = View.GONE
                        contactRecyclerView?.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    callLogMeta.clear()
                    callLogPlaceholder?.visibility = View.VISIBLE
                    contactRecyclerView?.visibility = View.GONE
                    contactAdapter?.setCallLogModeItems(
                        listRows = emptyList(),
                        contactSource = emptyList(),
                        selectedIndices = emptySet(),
                    )
                    hideContactsLetterFastScroller()
                }
            }
        }.start()
    }

    private fun loadContacts() {
        allContacts.clear()
        callLogMeta.clear()
        filteredContacts.clear()
        selectedPositions.clear()
        addedContactIds.clear()
        hasMoreContacts = false
        allContactKeyToIndex.clear()
        contactsCursor?.takeIf { !it.isClosed }?.close()
        contactsCursor = null
        contactAdapter?.setContactModeItems(emptyList(), emptySet())
        callLogPlaceholder?.visibility = View.GONE
        contactRecyclerView?.visibility = View.VISIBLE

        val alreadySelected = intent?.getParcelableArrayListExtra<Contact>(EXTRA_ALREADY_SELECTED_CONTACTS) ?: arrayListOf()
        alreadySelectedContactIds.clear()
        alreadySelected.forEach { c ->
            if (c.contactId.isNotEmpty()) {
                val key = contactNumberKey(c.contactId, c.phoneNumber)
                alreadySelectedContactIds.add(key)
            }
        }

        // First chunk from DB (SIM/phone-storage phones); private entries merge on that first chunk.
        if (searchString.trim().isEmpty()) {
            startBrowseContactsLoadFromDb()
        } else {
            searchListByQuery(searchString)
        }
    }

    private fun browseLoadMoreNestScrollSlopPx(): Int =
        (resources.displayMetrics.density * 280f).toInt().coerceAtLeast(120)

    /**
     * When the list lives inside a [NestedScrollView], the [RecyclerView] is often as tall as all
     * rows so [LinearLayoutManager.findLastVisibleItemPosition] is always near [itemCount] even
     * though the user has not scrolled the outer view — that must not trigger pagination.
     */
    private fun browseRvHasIndependentVerticalScroll(rv: RecyclerView): Boolean {
        val range = rv.computeVerticalScrollRange()
        val extent = rv.computeVerticalScrollExtent()
        val slop = (rv.resources.displayMetrics.density * 32f).toInt().coerceAtLeast(24)
        return range > extent + slop
    }

    private fun scheduleBrowseLoadMoreFromScrollDebounced() {
        val rv = contactRecyclerView ?: return
        rv.removeCallbacks(browseLoadMoreFromScrollRunnable)
        rv.postDelayed(browseLoadMoreFromScrollRunnable, BROWSE_LOAD_MORE_SCROLL_DEBOUNCE_MS)
    }

    /** One delayed check after data changes so we do not synchronously chain [loadMoreContacts] while still at the bottom. */
    private fun scheduleBrowseLoadMoreAfterChunk() {
        val rv = contactRecyclerView ?: return
        rv.removeCallbacks(browseLoadMoreAfterChunkRunnable)
        rv.postDelayed(browseLoadMoreAfterChunkRunnable, BROWSE_LOAD_MORE_AFTER_CHUNK_MS)
    }

    /**
     * Browse-mode pagination: the list is inside a [NestedScrollView], so the outer view often
     * scrolls while the [RecyclerView] reports no internal scroll — [RecyclerView.OnScrollListener]
     * alone misses that. Also uses [LinearLayoutManager.findLastVisibleItemPosition] for a stable
     * “near end” check when the list does scroll internally.
     */
    private fun maybeLoadMoreBrowseContactsFromScroll() {
        if (isCallLogMode || searchString.isNotEmpty() || isLoadingMore || !hasMoreContacts) return
        val rv = contactRecyclerView ?: return
        if (rv.visibility != View.VISIBLE) return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val total = rv.adapter?.itemCount ?: return
        if (total == 0) return

        val lastVisible = lm.findLastVisibleItemPosition()
        val nearEndRv = browseRvHasIndependentVerticalScroll(rv) &&
            lastVisible != RecyclerView.NO_POSITION &&
            lastVisible >= total - 1 - BROWSE_LOAD_MORE_NEAR_END_ITEMS

        val nearEndNsv = nestScrollView?.let { ns ->
            val child = ns.getChildAt(0) ?: return@let false
            val contentHeight = child.height
            if (contentHeight <= ns.height) return@let false
            val distanceToBottom = contentHeight - ns.height - ns.scrollY
            distanceToBottom <= browseLoadMoreNestScrollSlopPx()
        } ?: false

        if (nearEndRv || nearEndNsv) {
            val distBottom = nestScrollView?.let { ns ->
                val child = ns.getChildAt(0)
                if (child == null || child.height <= ns.height) null
                else child.height - ns.height - ns.scrollY
            }
            val rvRange = rv.computeVerticalScrollRange()
            val rvExtent = rv.computeVerticalScrollExtent()
            perfLog(
                "maybeLoad TRIGGER nearEndRv=$nearEndRv nearEndNsv=$nearEndNsv lastVis=$lastVisible total=$total " +
                    "distBottom=$distBottom slopPx=${browseLoadMoreNestScrollSlopPx()} rvRange=$rvRange rvExtent=$rvExtent",
            )
            loadMoreContacts()
        }
    }

    private fun loadMoreContacts() {
        if (isCallLogMode || searchString.isNotEmpty() || isLoadingMore || !hasMoreContacts) {
            perfLog(
                "loadMore SKIP callLog=$isCallLogMode searchNonEmpty=${searchString.isNotEmpty()} " +
                    "loading=$isLoadingMore hasMore=$hasMoreContacts",
            )
            return
        }
        val wallStart = SystemClock.elapsedRealtime()
        val globalStart = allContacts.size
        perfLog("loadMore START listSize=$globalStart")
        isLoadingMore = true
        ensureBackgroundThread {
            val bgStart = SystemClock.elapsedRealtime()
            val chunk = loadNextContactsChunkFromDb(isFirstPage = false)
            val bgMs = SystemClock.elapsedRealtime() - bgStart
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    isLoadingMore = false
                    perfLog("loadMore ABORT finishing/destroyed bgMs=$bgMs")
                    return@runOnUiThread
                }
                val uiStart = SystemClock.elapsedRealtime()
                if (chunk.contacts.isEmpty()) {
                    hasMoreContacts = chunk.hasMoreFromDb
                    isLoadingMore = false
                    perfLog(
                        "loadMore EMPTY uiMs=${SystemClock.elapsedRealtime() - uiStart} bgMs=$bgMs " +
                            "hasMoreFromDb=${chunk.hasMoreFromDb} wallMs=${SystemClock.elapsedRealtime() - wallStart}",
                    )
                    if (chunk.hasMoreFromDb) {
                        scheduleBrowseLoadMoreAfterChunk()
                    }
                    return@runOnUiThread
                }
                appendKeyIndicesForRange(globalStart, chunk.contacts)
                chunk.selectedLocalIndices.forEach { local ->
                    selectedPositions.add(globalStart + local)
                }
                allContacts.addAll(chunk.contacts)
                filteredContacts.addAll(chunk.contacts)
                val tAddItems = SystemClock.elapsedRealtime()
                contactAdapter?.addItems(chunk.contacts, chunk.selectedLocalIndices)
                val addItemsMs = SystemClock.elapsedRealtime() - tAddItems
                hasMoreContacts = chunk.hasMoreFromDb
                isLoadingMore = false
                val uiMs = SystemClock.elapsedRealtime() - uiStart
                perfLog(
                    "loadMore DONE added=${chunk.contacts.size} hasMore=${chunk.hasMoreFromDb} bgMs=$bgMs " +
                        "uiMs=$uiMs addItemsMs=$addItemsMs wallMs=${SystemClock.elapsedRealtime() - wallStart}",
                )
                scheduleBrowseLoadMoreAfterChunk()
            }
        }
    }

    private fun contactNumberKey(contactId: String, phoneNumber: String): String {
        return "$contactId|${normalizePhoneNumber(phoneNumber)}"
    }

    /** Resolves display name from Contacts by phone number; returns null if not found or on error. */
    private fun getDisplayNameForNumber(number: String): String? {
        if (number.isBlank()) return null
        var cursor: android.database.Cursor? = null
        try {
            val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            cursor = contentResolver.query(uri, arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null)
            if (cursor?.moveToFirst() == true) {
                val idx = cursor.getColumnIndex(PhoneLookup.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) {
            // ignore
        } finally {
            cursor?.close()
        }
        return null
    }

    private fun getAllPhoneNumbersForContact(contactId: String): List<String> {
        val numbers = ArrayList<String>()
        var cursor: android.database.Cursor? = null
        try {
            cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId),
                "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC, ${ContactsContract.CommonDataKinds.Phone._ID} ASC"
            )
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    val number = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                    if (!number.isNullOrEmpty()) numbers.add(number)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return numbers
    }

    private fun getAddressForContact(contactId: String): String? {
        var cursor: android.database.Cursor? = null
        try {
            cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                    ContactsContract.CommonDataKinds.StructuredPostal.STREET,
                    ContactsContract.CommonDataKinds.StructuredPostal.CITY,
                    ContactsContract.CommonDataKinds.StructuredPostal.REGION,
                    ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE,
                    ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY
                ),
                "${ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID} = ?",
                arrayOf(contactId),
                "${ContactsContract.CommonDataKinds.StructuredPostal.IS_PRIMARY} DESC"
            )
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)
                if (idx >= 0) {
                    val addr = cursor.getString(idx)
                    if (!addr.isNullOrEmpty()) return addr
                }
                val parts = listOf(
                    cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.STREET)),
                    cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.CITY)),
                    cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.REGION)),
                    cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE)),
                    cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY))
                ).filter { !it.isNullOrEmpty() }
                return parts.joinToString(", ").trim().trimEnd(',')
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return ""
    }

    private fun getOrganizationNameForContact(contactId: String): String? {
        var cursor: android.database.Cursor? = null
        try {
            cursor = contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Organization.COMPANY,
                    ContactsContract.Data.IS_PRIMARY
                ),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
                "${ContactsContract.Data.IS_PRIMARY} DESC"
            )
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Organization.COMPANY)
                if (idx >= 0) {
                    val company = cursor.getString(idx)
                    if (!company.isNullOrEmpty()) return company
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return ""
    }

    private fun returnSelectedContacts() {
        val selectedContacts = ArrayList<Contact>()
        for (pos in selectedPositions.sorted()) {
            if (pos in allContacts.indices) selectedContacts.add(allContacts[pos])
        }

        val contactNumbersCount = HashMap<String, HashSet<String>>()
        allContacts.forEach { contact ->
            if (contact.contactId.isNotEmpty() && contact.phoneNumber.isNotEmpty()) {
                val normalized = normalizePhoneNumber(contact.phoneNumber)
                if (normalized.isNotEmpty()) {
                    contactNumbersCount.getOrPut(contact.contactId) { HashSet() }.add(normalized)
                }
            }
        }
        val multiNumberContactIds = contactNumbersCount
            .filterValues { it.size > 1 }
            .keys

        val displayTexts = ArrayList<String>()
        val normalizedNumbers = ArrayList<String>()
        val usedDisplayTexts = HashSet<String>()
        selectedContacts.forEach { c ->
            val normalizedNumber = normalizePhoneNumber(c.phoneNumber)
            val baseName = if (c.name.isNotEmpty()) c.name else c.phoneNumber
            val shouldShowNumberInDisplay = c.contactId.isNotEmpty() && multiNumberContactIds.contains(c.contactId)
            val baseDisplayText = if (shouldShowNumberInDisplay && c.phoneNumber.isNotEmpty()) {
                "$baseName (${c.phoneNumber})"
            } else {
                baseName
            }
            val uniqueDisplayText = if (!usedDisplayTexts.contains(baseDisplayText)) {
                baseDisplayText
            } else {
                // Keep chip labels unique when multiple selected numbers share the same contact name.
                if (c.phoneNumber.isNotEmpty()) "$baseDisplayText (${c.phoneNumber})" else "$baseDisplayText (${normalizedNumber})"
            }

            usedDisplayTexts.add(uniqueDisplayText)
            displayTexts.add(uniqueDisplayText)
            normalizedNumbers.add(normalizedNumber)
        }

        val resultIntent = Intent().apply {
            putParcelableArrayListExtra(EXTRA_SELECTED_CONTACTS, selectedContacts)
            putStringArrayListExtra(EXTRA_SELECTED_DISPLAY_TEXTS, displayTexts)
            putStringArrayListExtra(EXTRA_SELECTED_PHONE_NUMBERS, normalizedNumbers)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun normalizePhoneNumber(phone: String): String {
        return phone.filter { it.isDigit() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
