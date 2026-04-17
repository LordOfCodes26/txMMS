package com.android.mms.activities

import android.annotation.SuppressLint
import android.app.SearchManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.Telephony
import android.speech.RecognizerIntent
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuItemCompat
import androidx.recyclerview.widget.RecyclerView
import com.android.common.view.MVSideFrame
import com.goodwy.commons.dialogs.PermissionRequiredDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.views.MyRecyclerView
import com.goodwy.commons.views.TwoFingerSlideGestureDetector
import com.android.mms.BuildConfig
import com.android.mms.R
import com.android.mms.adapters.ConversationsAdapter
import com.android.mms.adapters.SearchResultsAdapter
import com.android.mms.databinding.ActivityMainBinding
import com.android.mms.extensions.*
import com.google.gson.Gson
import com.android.mms.helpers.NEW_CONVERSATION_RESUME_DRAFT
import com.android.mms.helpers.SEARCHED_MESSAGE_ID
import com.android.mms.helpers.THREAD_ID
import com.android.mms.helpers.THREAD_OPENED_FROM_SECURE_CONVERSATION_LIST
import com.android.mms.helpers.THREAD_NUMBER
import com.android.mms.helpers.THREAD_TITLE
import com.android.mms.helpers.refreshConversations
import com.android.mms.helpers.whatsNewList
import com.android.mms.models.Conversation
import com.android.mms.models.Events
import com.android.mms.models.Message
import com.android.mms.models.SearchResult
import com.android.mms.models.groupSearchResultsByDateSections
import com.goodwy.commons.activities.BlockedItemsActivity
import com.goodwy.commons.interfaces.ActionModeToolbarHost
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.*
import kotlin.text.toFloat

class MainActivity : SimpleActivity(), ActionModeToolbarHost {
    companion object {
        private const val TAG = "MessagesMainActivity"
        private const val SECRET_BOX_PACKAGE = "chonha.get.secret.number"
        private const val SECRET_NUMBER_EXTRA = "secret_number"
        private const val INVALID_CIPHER = -1
        private const val MIN_SWIPE_DISTANCE = 50f

        /** CAB menu for the main conversation list ([R.menu.cab_action_menu_select]): select-all only; delete stays on the bottom ripple. */
        @JvmField
        val ACTION_MODE_MENU_SELECT: Int = R.menu.cab_action_menu_select
    }

    override var isSearchBarEnabled = true

    private val MAKE_DEFAULT_APP_REQUEST = 1

    private var storedPrimaryColor = 0
    private var storedTextColor = 0
    private var storedBackgroundColor = 0
    private var storedFontSize = 0
    private var lastSearchedText = ""
    private var bus: EventBus? = null
    private var isSpeechToTextAvailable = false
    private var isSearchAlwaysShow = false
    private var isSearchOpen = false
    private var searchQuery = ""

    private var mSearchMenuItem: MenuItem? = null
    private var mSearchView: SearchView? = null
    private lateinit var twoFingerGestureDetector: TwoFingerSlideGestureDetector
    private var pendingThreadIdsToEncrypt: LongArray? = null
    private var shouldExitSecureModeOnResume = false
    private var isLaunchingSecretBox = false
    /** True while starting Thread/NewConversation from this screen; avoids treating that as leaving the app ([onUserLeaveHint]). */
    private var isLaunchingInternalConversationActivity = false

    var unreadCountHash = HashMap<Long, Int>(128)

    private val binding by viewBinding(ActivityMainBinding::inflate)

    private var menuHeightAnimator: android.animation.ValueAnimator? = null
    private var currentMenuHeight: Int = -1
    private var fullMenuHeight: Int = -1
    private val mainMenuOverscrollFactor = 0.35f
    private var isStartActionMode = false

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        initTheme()
        setupTwoFingerSwipeGesture()
        makeSystemBarsToTransparent()
        val isFirstLaunch = baseConfig.appRunCount == 0
        appLaunched(BuildConfig.APPLICATION_ID)
        // Initialize default quick texts if they haven't been initialized yet
        // The function internally checks if quick texts are empty to prevent re-initialization
        // if user deletes all quick texts later
        if (!config.quickTextsDefaultsInitialized) {
            config.initializeDefaultQuickTexts()
        }
        setupOptionsMenu()
        refreshMenuItemsAndTitle()
        setupEdgeToEdge()
        checkWhatsNewDialog()
        storeStateVariables()

        binding.mainMenu.apply {
            searchBeVisibleIf(isSearchAlwaysShow) //hide top search bar
        }

        checkAndDeleteOldRecycleBinMessages()
        clearAllMessagesIfNeeded {
            loadMessages()
        }

        unreadCountHash = getUnreadCountsByThread() as HashMap<Long, Int>

        setupVerticalSideFrameBlur()
        binding.conversationsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                hideKeyboard()
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                setMainMenuTransparentBackground()
            }
        })
        binding.searchResultsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                hideKeyboard()
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                setMainMenuTransparentBackground()
            }
        })
        binding.conversationsNestedScroll.post {
            setMainMenuHeight(null, animated = false)
            setupMainMenuSpringSync()
            if (config.changeColourTopBar) scrollChange()
        }
    }

    @SuppressLint("UnsafeIntentLaunch")
    override fun onResume() {
        super.onResume()
        isLaunchingInternalConversationActivity = false
        if (shouldExitSecureModeOnResume) {
            shouldExitSecureModeOnResume = false
            if (config.selectedConversationPin > 0) {
                closeSecureBox()
            }
        }

        if (config.needRestart || storedBackgroundColor != getProperBackgroundColor()) {
            finish()
            startActivity(intent)
            return
        }

        binding.mainMenu.updateColors(
            background = getStartRequiredStatusBarColor(),
            scrollOffset = scrollingView?.computeVerticalScrollOffset() ?: 0
        )
        setMainMenuTransparentBackground()
        binding.mainMenu.requireCustomToolbar().updateSearchColors()

        refreshMenuItemsAndTitle()

        getOrCreateConversationsAdapter().apply {
            if (storedPrimaryColor != getProperPrimaryColor()) {
                updatePrimaryColor()
            }

            if (storedTextColor != getProperTextColor()) {
                updateTextColor(getProperTextColor())
            }

            if (storedBackgroundColor != getProperBackgroundColor()) {
                updateBackgroundColor(getProperBackgroundColor())
            }

            if (storedFontSize != config.fontSize) {
                updateFontSize()
            }

            updateDrafts()
            scheduleGroupedTodayTimeRefresh()
        }

        updateTextColors(binding.conversationsNestedScroll)
        // Use same background logic as Contacts: surface color only for dynamic theme + light mode, else proper background
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        binding.searchHolder.setBackgroundColor(backgroundColor)
//        binding.conversationsFastscroller.setBackgroundColor(backgroundColor)
        binding.conversationsNestedScroll.setBackgroundColor(backgroundColor)
        binding.conversationsList.setBackgroundColor(backgroundColor)
        binding.searchResultsList.setBackgroundColor(backgroundColor)

        val properPrimaryColor = getProperPrimaryColor()
        binding.noConversationsPlaceholder2.setTextColor(getProperTextColor())
        // binding.noConversationsPlaceholder2.underlineText()
//        binding.conversationsFastscroller.updateColors(getProperAccentColor())
        // binding.conversationsProgressBar.setIndicatorColor(properPrimaryColor)
        binding.conversationsProgressBar.trackColor = properPrimaryColor.adjustAlpha(LOWER_ALPHA)
        checkShortcut()

        setFabIconColor()

        if (fullMenuHeight == -1) {
            val menu = binding.mainMenu
            if (menu.height == 0) {
                menu.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        menu.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        if (fullMenuHeight == -1 && menu.height > 0) {
                            fullMenuHeight = menu.height
                            if (currentMenuHeight == -1) {
                                currentMenuHeight = fullMenuHeight
                            }
                            syncTopSideFrameHeight(fullMenuHeight)
                        }
                    }
                })
            } else if (menu.height > 0) {
                fullMenuHeight = menu.height
                if (currentMenuHeight == -1) {
                    currentMenuHeight = fullMenuHeight
                }
                syncTopSideFrameHeight(fullMenuHeight)
            }
        }

        binding.mainMenu.post { setMainMenuHeight(null, animated = true) }

        binding.root.post { setupVerticalSideFrameBlur() }

        refreshSideFrameBlurAndInsets()

        (binding.searchResultsList.adapter as? SearchResultsAdapter)?.scheduleGroupedTodayTimeRefresh()
    }

    /** BlurView/BlurTarget links are invalidated when the activity is paused; re-bind like txDial [setupVerticalSideFrameBlur]. */
    private fun refreshSideFrameBlurAndInsets() {
        binding.root.post {
            ViewCompat.requestApplyInsets(binding.root)
            binding.mainBlurTarget.invalidate()
            binding.blurTarget.invalidate()
            setupVerticalSideFrameBlur()
            setMainMenuTransparentBackground()
        }
    }

    override fun onPause() {
        super.onPause()
        (binding.conversationsList.adapter as? ConversationsAdapter)?.pauseGroupedTodayTimeRefresh()
        (binding.searchResultsList.adapter as? SearchResultsAdapter)?.pauseGroupedTodayTimeRefresh()
        storeStateVariables()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (
            config.selectedConversationPin > 0 &&
            !isLaunchingSecretBox &&
            !isLaunchingInternalConversationActivity
        ) {
            shouldExitSecureModeOnResume = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clearMainMenuSpringSync()
        config.needRestart = false
        bus?.unregister(this)
    }

    override fun onBackPressedCompat(): Boolean {
        val customToolbar = binding.mainMenu.requireCustomToolbar()
        if (
            config.selectedConversationPin > 0 &&
            !isLaunchingSecretBox &&
            !isLaunchingInternalConversationActivity
        ) {
            shouldExitSecureModeOnResume = true
        }
        return if (customToolbar.isSearchExpanded) {
            endMainMenuSearchMode()
            true
        } else {
            appLockManager.lock()
            false
        }
    }

    fun getMainMenuVisibleHeight(): Int {
        return binding.mainMenu.height
            .takeIf { it > 0 }
            ?: binding.mainMenu.measuredHeight.takeIf { it > 0 }
            ?: currentMenuHeight.takeIf { it > 0 }
            ?: fullMenuHeight.takeIf { it > 0 }
            ?: 0
    }

    /**
     * Top padding for [R.id.conversations_list]: distance from the list’s top edge to the bottom of
     * [R.id.main_menu] on screen. Using [View.getHeight] alone double-counts with CoordinatorLayout /
     * negative [R.id.blur_target] margin and produced a large empty band under the title.
     *
     * Screens that use [com.goodwy.commons.views.MySearchMenu] over a [eightbitlab.com.blurview.BlurTarget]
     * must wire the same CoordinatorLayout scrolling as [R.layout.activity_main]: set
     * `app:layout_behavior="@string/appbar_scrolling_view_behavior"` on the blur target and on the
     * nested scrolling child (see [R.layout.activity_message_bubble_picker], [R.layout.activity_settings]).
     */
    fun getRecentsListTopInsetPx(): Int {
        val menu = binding.mainMenu
        val list = binding.conversationsList
        var base = getMainMenuVisibleHeight()
        if (
            list.visibility == View.VISIBLE &&
            menu.visibility == View.VISIBLE &&
            menu.isLaidOut &&
            list.isLaidOut &&
            menu.height > 0
        ) {
            val mLoc = IntArray(2)
            val lLoc = IntArray(2)
            menu.getLocationOnScreen(mLoc)
            list.getLocationOnScreen(lLoc)
            val inset = (mLoc[1] + menu.height) - lLoc[1]
            if (inset > 0 && inset < menu.height * 3) {
                base = inset
            }
        }
        // Locked AppBar height can be smaller than the visible search row; ensure we never under-pad.
        if (menu.requireCustomToolbar().isSearchExpanded) {
            val minSearchListTop = resources.getDimensionPixelSize(R.dimen.nest_bouncy_content_padding_top)
            return maxOf(base, minSearchListTop)
        }
        return base
    }

    fun getMainMenuHeightForRecentsInset(): Int = getRecentsListTopInsetPx()

    private fun getMainMenuHeightWithFallback(): Int = getRecentsListTopInsetPx()

    /** Debug: filter logcat with [TAG] and `recentsTopPadding`. */
    private fun logRecentsListTopPadding(phase: String, recentsList: MyRecyclerView) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            TAG,
            "recentsTopPadding phase=$phase paddingTop=${recentsList.paddingTop}px translationY=${recentsList.translationY} " +
                "mainMenuInset=${getMainMenuHeightWithFallback()}px mainMenuVisible=${getMainMenuVisibleHeight()}px",
        )
    }

    /** Re-apply when the app bar finishes layout (e.g. after unlockCollapsing); based on txDial MainActivityRecents (no dialpad in Messages). */
    private fun applyFinalRecentsListTopPadding(recentsList: MyRecyclerView) {
        var inset = getMainMenuHeightWithFallback()
        if (inset > 0) {
            recentsList.updatePadding(top = inset)
            logRecentsListTopPadding("applyFinal(immediate inset=$inset)", recentsList)
            return
        }
        findViewById<View>(R.id.main_menu)?.post {
            inset = getMainMenuHeightWithFallback()
            if (inset > 0) {
                recentsList.updatePadding(top = inset)
                logRecentsListTopPadding("applyFinal(posted inset=$inset)", recentsList)
            } else {
                logRecentsListTopPadding("applyFinal(posted inset still 0)", recentsList)
            }
        }
    }

    private fun animateTopOffsets(duration: Long) {
        if (duration > 0L) {
            return
        }
        val conv = binding.conversationsList as MyRecyclerView
        val searchRv = binding.searchResultsList as MyRecyclerView
        // Search mode: [getRecentsListTopInsetPx] uses max(geometry, nest_bouncy_content_padding_top)
        // so list padding clears the visible search row despite locked short AppBar height.
        applyFinalRecentsListTopPadding(conv)
        applyFinalRecentsListTopPadding(searchRv)
        if (binding.searchHolder.childCount > 0) {
            val inset = getMainMenuHeightWithFallback()
            binding.searchHolder.getChildAt(0)!!.updatePadding(top = if (inset > 0) inset else 0)
        }
        val mainMenuHeight = getMainMenuHeightWithFallback()
        logRecentsListTopPadding(
            "animateTopOffsets snap targetTopPad=${mainMenuHeight}px mainMenuHAtCall=${mainMenuHeight}px",
            conv,
        )
    }

    private fun syncRecentsTopInsetWithToolbar() {
        fun apply() {
            animateTopOffsets(duration = 0L)
        }
        apply()
        if (getMainMenuHeightWithFallback() == 0) {
            binding.root.post { apply() }
        }
        findViewById<View>(R.id.main_menu)?.post { apply() }
    }

    fun requestTopInsetSync() {
        binding.root.post {
            fun apply() {
                animateTopOffsets(duration = 0L)
            }
            apply()
            if (getMainMenuHeightWithFallback() == 0) {
                binding.root.post { apply() }
            }
            findViewById<View>(R.id.main_menu)?.post { apply() }
        }
    }

    override fun getActionModeToolbar(): com.goodwy.commons.views.CustomActionModeToolbar =
        binding.mainMenu.getActionModeToolbar()

    override fun showActionModeToolbar() {
        binding.mainMenu.showActionModeToolbar()
        binding.conversationsFab.beGone()
        binding.root.post {
            applyActionModeRippleToolbarForConversations()
            applyActionModeListBottomInset(true)
        }
    }

    override fun hideActionModeToolbar() {
        binding.mainMenu.hideActionModeToolbar()
        binding.actionModeRippleToolbar.visibility = View.GONE
        binding.conversationsFab.beVisible()
        applyActionModeListBottomInset(false)
        isStartActionMode = false
    }

    /**
     * Bottom [MRippleToolBar] for [ConversationsAdapter] selection (txDial MainActivity pattern).
     */
    private fun applyActionModeRippleToolbarForConversations() {
        val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget) ?: return
        val adapter = binding.conversationsList.adapter as? ConversationsAdapter ?: return
        if (!adapter.isActionModeActive()) {
            binding.actionModeRippleToolbar.visibility = View.GONE
            return
        }
        val (items, _) = adapter.buildConversationListRippleToolbar()
        if (items.isEmpty()) {
            binding.actionModeRippleToolbar.visibility = View.GONE
            return
        }
        binding.actionModeRippleToolbar.setTabs(this, items, blurTarget)
        binding.actionModeRippleToolbar.setOnClickedListener { index ->
            adapter.dispatchRippleToolbarAction(index)
        }
        binding.actionModeRippleToolbar.visibility = View.VISIBLE
    }

    fun refreshActionModeRippleToolbarIfNeeded() {
        if (isDestroyed || isFinishing) return
        applyActionModeRippleToolbarForConversations()
        applyActionModeListBottomInset(binding.actionModeRippleToolbar.visibility == View.VISIBLE)
    }

    private fun applyActionModeListBottomInset(enabled: Boolean) {
        val bottomPx = if (!enabled) {
            0
        } else {
            val rootInsets = ViewCompat.getRootWindowInsets(binding.root)
            val nav = rootInsets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            val ime = rootInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            val bottomInset = maxOf(nav, ime)
            val ripple = binding.actionModeRippleToolbar
            val h = ripple.height.takeIf { it > 0 } ?: ripple.measuredHeight.takeIf { it > 0 }
                ?: resources.getDimensionPixelSize(R.dimen.action_mode_bottom_inset_fallback)
            val margin = (25 * resources.displayMetrics.density).toInt()
            h + margin + bottomInset
        }
        binding.conversationsList.updatePadding(bottom = bottomPx)
        binding.searchResultsList.updatePadding(bottom = bottomPx)
    }

    override fun getBlurTargetView() = binding.mainBlurTarget

    private fun endMainMenuSearchMode() {
        val menu = binding.mainMenu
        val tb = menu.requireCustomToolbar()
        if (!tb.isSearchExpanded) return
        tb.collapseSearch()
        menu.unlockCollapsing()
        menu.setExpanded(true, true)
        menu.binding.collapsingTitle.visibility = View.VISIBLE
        isSearchOpen = false
        fadeOutSearch()
        menu.post { requestTopInsetSync() }
    }

    private fun setupOptionsMenu() {
        binding.apply {
            val menu = mainMenu
            val toolbar = menu.requireCustomToolbar()
            toolbar.inflateMenu(R.menu.action_menu_main)
            updateMenuItemColors(toolbar.menu)
            toolbar.updateSearchColors()

            toolbar.setOnSearchExpandListener {
                menuHeightAnimator?.cancel()
                menu.collapseAndLockCollapsing()
                menu.binding.collapsingTitle.visibility = View.GONE
                isSearchOpen = true
                menu.post { requestTopInsetSync() }
            }
            toolbar.setOnSearchBackClickListener {
                menu.unlockCollapsing()
                menu.setExpanded(true, true)
                menu.binding.collapsingTitle.visibility = View.VISIBLE
                fadeOutSearch()
                isSearchOpen = false
                menu.post { requestTopInsetSync() }
            }
            toolbar.setOnSearchTextChangedListener { s ->
                val text = s ?: ""
                searchQuery = text
                if (text.isNotEmpty()) {
                    if (searchHolder.alpha < 1f) {
                        searchHolder.fadeIn()
                    }
                } else {
                    fadeOutSearch()
                }
                searchTextChanged(text)
                if (isSearchAlwaysShow) toolbar.setSearchText("")
            }

            toolbar.setOnMenuItemClickListener { menuItem ->
                handleToolbarMenuItemClick(menuItem)
            }
            toolbar.getActionBar()?.bindBlurTarget(this@MainActivity, blurTarget)
            toolbar.setPopupForMoreItem(
                R.id.more,
                R.menu.menu_main,
                mainBlurTarget,
                object : MenuItem.OnMenuItemClickListener {
                    override fun onMenuItemClick(item: MenuItem): Boolean {
                        return handleToolbarMenuItemClick(item)
                    }
                },
            )
            toolbar.invalidateMenu()

            toolbar.setSearchText("")
        }
    }

    private fun handleToolbarMenuItemClick(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.search -> {
                if (!binding.mainMenu.requireCustomToolbar().isSearchExpanded) {
                    menuHeightAnimator?.cancel()
                    binding.mainMenu.collapseAndLockCollapsing()
                    binding.mainMenu.requireCustomToolbar().expandSearch()
                    binding.mainMenu.binding.collapsingTitle.visibility = View.GONE
                    isSearchOpen = true
                    binding.mainMenu.post { requestTopInsetSync() }
                }
            }
            R.id.select_conversations -> {
                if (binding.mainMenu.requireCustomToolbar().isSearchExpanded) {
                    endMainMenuSearchMode()
                }
                getOrCreateConversationsAdapter().startActMode()
                isStartActionMode = true
            }
//            R.id.show_recycle_bin -> launchRecycleBin()
//            R.id.show_archived -> launchArchivedConversations()
//            R.id.show_blocked_numbers -> showBlockedNumbers()
//            R.id.unlock_protected_contacts -> {
//                if (config.selectedConversationPin > 0) {
//                    closeSecureBox()
//                } else {
//                    launchSecretBoxForUnlock()
//                }
//            }
            R.id.all_reading -> {
                ensureBackgroundThread {
                    markAllMessagesRead()
                    runOnUiThread {
                        unreadCountHash = getUnreadCountsByThread() as HashMap<Long, Int>
                        refreshConversations()
                    }
                }
            }
            R.id.blocked_list -> {
                hideKeyboard()
                binding.mainMenu.closeSearch()
                Intent(this, MessagingBlockedItemsActivity::class.java).apply {
                    putExtra(APP_ICON_IDS, getAppIconIDs())
                    putExtra(APP_LAUNCHER_NAME, getAppLauncherName())
                    putExtra(
                        BlockedItemsActivity.EXTRA_INITIAL_TAB_INDEX,
                        BlockedItemsActivity.TAB_BLOCKED_MESSAGES,
                    )
                    startActivity(this)
                }
            }
            R.id.private_space -> {}
//            R.id.sim_card_message -> {}
            R.id.settings -> launchSettings()
//            R.id.about -> launchAbout()
            else -> return false
        }
        return true
    }

    private fun setupSearch(menu: Menu) {
        updateMenuItemColors(menu)
        val searchManager = getSystemService(SEARCH_SERVICE) as SearchManager
        mSearchMenuItem = menu.findItem(R.id.search)
        mSearchView = (mSearchMenuItem!!.actionView as SearchView).apply {
            val textColor = getProperTextColor()
            findViewById<TextView>(androidx.appcompat.R.id.search_src_text).apply {
                setTextColor(textColor)
                setHintTextColor(textColor)
                // Reduce left padding to a small value
                val smallPadding = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.small_margin)
                setPadding(smallPadding, paddingTop, paddingRight, paddingBottom)
            }
            findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn).apply {
                setImageResource(com.goodwy.commons.R.drawable.ic_clear_round)
                setColorFilter(textColor)
            }
            findViewById<View>(androidx.appcompat.R.id.search_plate)?.apply { // search underline
                background.setColorFilter(Color.TRANSPARENT, PorterDuff.Mode.MULTIPLY)
                // Reduce left padding on the search plate to a small value
                val smallPadding = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.small_margin)
                setPadding(smallPadding, paddingTop, paddingRight, paddingBottom)
            }
            setIconifiedByDefault(false)
            findViewById<ImageView>(androidx.appcompat.R.id.search_mag_icon).apply {
                setColorFilter(textColor)
            }

            setSearchableInfo(searchManager.getSearchableInfo(componentName))
            isSubmitButtonEnabled = false
            queryHint = getString(R.string.search)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String) = false

                override fun onQueryTextChange(newText: String): Boolean {
                    if (isSearchOpen) {
                        searchQuery = newText
                        if (newText.isNotEmpty()) {
                            if (binding.searchHolder.alpha < 1f) {
                                binding.searchHolder.fadeIn()
                            }
                        } else {
                            fadeOutSearch()
                        }
                        searchTextChanged(newText)
                    }
                    return true
                }
            })
        }

        @Suppress("DEPRECATION")
        MenuItemCompat.setOnActionExpandListener(mSearchMenuItem, object : MenuItemCompat.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem?): Boolean {
                isSearchOpen = true

                // Animate search bar appearance with smooth translation (slide in from right)
                mSearchView?.let { searchView ->
                    searchView.post {
                        // Get the parent toolbar width for smooth slide-in
                        val toolbar = binding.mainMenu.requireCustomToolbar()
                        val slideDistance = toolbar.width.toFloat()

                        // Start from right side
                        searchView.translationX = slideDistance
                        searchView.alpha = 0f

                        // Animate to center with smooth deceleration
                        searchView.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(350)
                            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                            .start()
                    }
                }

                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem?): Boolean {
                if (isSearchOpen) {
                    fadeOutSearch()
                }

                isSearchOpen = false

                // Animate search bar disappearance with smooth translation (slide out to right)
                mSearchView?.let { searchView ->
                    val toolbar = binding.mainMenu.requireCustomToolbar()
                    val slideDistance = toolbar.width.toFloat()

                    searchView.animate()
                        .translationX(slideDistance)
                        .alpha(0f)
                        .setDuration(300)
                        .setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))
                        .withEndAction {
                            searchView.translationX = 0f
                            searchView.alpha = 1f
                        }
                        .start()
                } ?: run {
                    // binding.mainDialpadButton.beVisible()
                }

                return true
            }
        })
    }

    private fun refreshMenuItemsAndTitle() {
        val isSecureMode = config.selectedConversationPin > 0
        binding.mainMenu.requireCustomToolbar().menu.apply {
//            findItem(R.id.unlock_protected_contacts)?.title = if (isSecureMode) {
//                getString(R.string.close_secure_box)
//            } else {
//                getString(R.string.secure_box)
//            }
//            findItem(R.id.show_blocked_numbers)?.isVisible = !isSecureMode
        }

        binding.mainMenu.applyLargeTitleOnly(
            if (isSecureMode) getString(R.string.secure_box)
            else getString(R.string.messages)
        )
        binding.mainMenu.requireCustomToolbar().invalidateMenu()
    }

    private fun showBlockedNumbers() {
        config.showBlockedNumbers = !config.showBlockedNumbers
//        runOnUiThread {
//            getRecentsFragment()?.refreshItems()
//        }
        initMessenger()
    }

    private fun getSecretNumberFromResult(data: Intent?): Int? {
        if (data == null) return null
        val asInt = data.getIntExtra(SECRET_NUMBER_EXTRA, INVALID_CIPHER)
        if (asInt > INVALID_CIPHER) return asInt
        val asString = data.getStringExtra(SECRET_NUMBER_EXTRA)
        return asString?.toIntOrNull()?.takeIf { it >= 0 }
    }

    private val startSecretBoxForUnlock = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isLaunchingSecretBox = false
        if (result.resultCode != RESULT_OK) {
            pendingThreadIdsToEncrypt = null
            return@registerForActivityResult
        }

        val cipher = getSecretNumberFromResult(result.data) ?: run {
            pendingThreadIdsToEncrypt = null
            return@registerForActivityResult
        }

        val pendingThreadIds = pendingThreadIdsToEncrypt
        pendingThreadIdsToEncrypt = null
        if (pendingThreadIds != null) {
            ensureBackgroundThread {
                updateConversationPins(pendingThreadIds, cipher)
                runOnUiThread {
                    getOrCreateConversationsAdapter().finishActMode()
                    com.android.mms.helpers.refreshConversations()
                    isStartActionMode = false
                    notifyDatasetChanged()
                }
            }
        } else {
            Handler(Looper.getMainLooper()).post {
                if (!isFinishing && !isDestroyed) {
                    if (setConversationPinScope(cipher)) {
                        initMessenger()
                        refreshMenuItemsAndTitle()
                    } else {
                        toast(com.goodwy.commons.R.string.unknown_error_occurred)
                    }
                }
            }
        }
    }

    private fun closeSecureBox() {
        setConversationPinScope(0)
        initMessenger()
        refreshMenuItemsAndTitle()
    }

    private fun setupTwoFingerSwipeGesture() {
        twoFingerGestureDetector = TwoFingerSlideGestureDetector(this,
            object : TwoFingerSlideGestureDetector.OnTwoFingerSlideGestureListener {
                override fun onTwoFingerSlide(
                    firstFingerX: Float,
                    firstFingerY: Float,
                    secondFingerX: Float,
                    secondFingerY: Float,
                    avgDeltaX: Float,
                    avgDeltaY: Float,
                    avgDistance: Float
                ) {
                    if (avgDeltaY > MIN_SWIPE_DISTANCE) {
                        launchSecretBoxForUnlock()
                    }
                }
            }
        )
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { twoFingerGestureDetector.onTouchEvent(it) }
        return super.dispatchTouchEvent(ev)
    }

    private fun launchSecretBoxForUnlock() {
        try {
            isLaunchingSecretBox = true
            startSecretBoxForUnlock.launch(Intent(SECRET_BOX_PACKAGE))
        } catch (_: ActivityNotFoundException) {
            isLaunchingSecretBox = false
            toast(R.string.secret_box_app_not_found)
        }
    }

    fun requestEncryptConversations(threadIds: LongArray) {
        val validThreadIds = threadIds.filter { it > 0L }.distinct().toLongArray()
        if (validThreadIds.isEmpty()) return
        pendingThreadIdsToEncrypt = validThreadIds
        launchSecretBoxForUnlock()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == MAKE_DEFAULT_APP_REQUEST) {
            if (resultCode == RESULT_OK) {
                askPermissions()
            } else {
                finish()
            }
        } else if (requestCode == REQUEST_CODE_SPEECH_INPUT && resultCode == RESULT_OK) {
            if (resultData != null) {
                val res: ArrayList<String> =
                    resultData.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) as ArrayList<String>

                val speechToText =  Objects.requireNonNull(res)[0]
                if (speechToText.isNotEmpty()) {
                    binding.mainMenu.requireCustomToolbar().setSearchText(speechToText)
                }
            }
        }
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun setMainMenuHeight(height: Int?, animated: Boolean = true) {
        binding.mainMenu.apply {
            if (height != 0) {
                beVisible()
            }

            val actualCurrentHeight = if (this.height > 0) this.height else {
                if (layoutParams.height > 0 && layoutParams.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                    layoutParams.height
                } else {
                    -1
                }
            }

            if (currentMenuHeight == -1 && actualCurrentHeight > 0) {
                currentMenuHeight = actualCurrentHeight
            }

            if (height == null) {
                menuHeightAnimator?.cancel()

                // Toolbar search uses collapseAndLockCollapsing() (fixed small height). Animating toward
                // [fullMenuHeight] overwrites that lock, resizes blur margin every frame, and re-runs
                // list inset sync — content jumps after ~300ms or the next onResume (e.g. IME).
                if (requireCustomToolbar().isSearchExpanded) {
                    val h = this.height.takeIf { it > 0 } ?: this.measuredHeight.takeIf { it > 0 }
                        ?: if (layoutParams.height > 0 && layoutParams.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                            layoutParams.height
                        } else {
                            0
                        }
                    if (h > 0) {
                        currentMenuHeight = h
                        syncTopSideFrameHeight(h)
                    }
                    return
                }

                if (animated && fullMenuHeight > 0) {
                    val menuView = this
                    val startHeight = if (currentMenuHeight > 0) currentMenuHeight else actualCurrentHeight.takeIf { it > 0 } ?: 0
                    val targetHeight = fullMenuHeight

                    if (startHeight != targetHeight) {
                        menuHeightAnimator = android.animation.ValueAnimator.ofInt(startHeight, targetHeight).apply {
                            duration = 300
                            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
                            addUpdateListener { animator ->
                                val animatedHeight = animator.animatedValue as Int
                                currentMenuHeight = animatedHeight
                                menuView.updateLayoutParams<ViewGroup.LayoutParams> {
                                    this.height = animatedHeight.coerceAtLeast(0)
                                }
                                syncTopSideFrameHeight(animatedHeight)
                            }
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    menuView.post {
                                        menuView.updateLayoutParams<ViewGroup.LayoutParams> {
                                            this.height = ViewGroup.LayoutParams.WRAP_CONTENT
                                        }
                                        menuView.post {
                                            if (menuView.height > 0) {
                                                fullMenuHeight = menuView.height
                                                currentMenuHeight = fullMenuHeight
                                                syncTopSideFrameHeight(fullMenuHeight)
                                            }
                                        }
                                    }
                                }
                            })
                            start()
                        }
                    } else {
                        updateLayoutParams<ViewGroup.LayoutParams> {
                            this.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                        post {
                            if (this.height > 0) {
                                fullMenuHeight = this.height
                                currentMenuHeight = fullMenuHeight
                                syncTopSideFrameHeight(fullMenuHeight)
                            }
                        }
                    }
                } else {
                    updateLayoutParams<ViewGroup.LayoutParams> {
                        this.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    post {
                        if (this.height > 0) {
                            fullMenuHeight = this.height
                            currentMenuHeight = fullMenuHeight
                            syncTopSideFrameHeight(fullMenuHeight)
                        }
                    }
                }
                return
            }

            val targetHeight = height

            if (currentMenuHeight == targetHeight && currentMenuHeight >= 0) {
                return
            }

            menuHeightAnimator?.cancel()

            if (animated && targetHeight > 0) {
                val menuView = this
                val startHeight = if (currentMenuHeight > 0) currentMenuHeight else actualCurrentHeight.takeIf { it > 0 } ?: targetHeight
                menuHeightAnimator = android.animation.ValueAnimator.ofInt(startHeight, targetHeight).apply {
                    duration = 300
                    interpolator = android.view.animation.DecelerateInterpolator(1.5f)
                    addUpdateListener { animator ->
                        val animatedHeight = animator.animatedValue as Int
                        currentMenuHeight = animatedHeight
                        menuView.updateLayoutParams<ViewGroup.LayoutParams> {
                            this.height = animatedHeight.coerceAtLeast(0)
                        }
                        syncTopSideFrameHeight(animatedHeight)
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            currentMenuHeight = targetHeight
                            syncTopSideFrameHeight(targetHeight)
                        }
                    })
                    start()
                }
            } else {
                updateLayoutParams<ViewGroup.LayoutParams> {
                    this.height = targetHeight
                }
                currentMenuHeight = targetHeight
                syncTopSideFrameHeight(targetHeight)

                if (targetHeight == 0) {
                    beGone()
                }
            }
        }
    }

    private fun syncTopSideFrameHeight(height: Int) {
        if (height < 0) return
        val collapsedMenuHeight = binding.mainMenu.getCollapsedHeightPx().takeIf { it > 0 } ?: height
        binding.mVerticalSideFrameTop.updateLayoutParams<ViewGroup.LayoutParams> {
            if (this.height != collapsedMenuHeight) {
                this.height = collapsedMenuHeight
            }
        }
        syncBlurTargetTopMargin(height)
        syncRecentsTopInsetWithToolbar()
    }

    private fun syncBlurTargetTopMargin(menuHeight: Int) {
        if (menuHeight < 0) return
        val targetTopMargin = -menuHeight
        binding.blurTarget.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            if (topMargin != targetTopMargin) {
                topMargin = targetTopMargin
            }
        }
    }

    private fun setupVerticalSideFrameBlur() {
        arrayOf(
            binding.mVerticalSideFrameTop,
            binding.mVerticalSideFrameBottom,
        ).forEach {
            it.bindBlurTarget(binding.blurTarget)
        }
    }

    private fun setupMainMenuSpringSync() {
        fun bindOverscrollSync(recyclerView: MyRecyclerView?) {
            recyclerView?.onOverscrollTranslationChanged = { translationY ->
                binding.mainMenu.translationY = translationY * mainMenuOverscrollFactor
            }
        }

        bindOverscrollSync(binding.conversationsList as? MyRecyclerView)
        bindOverscrollSync(binding.searchResultsList as? MyRecyclerView)
    }

    private fun clearMainMenuSpringSync() {
        (binding.conversationsList as? MyRecyclerView)?.onOverscrollTranslationChanged = null
        (binding.searchResultsList as? MyRecyclerView)?.onOverscrollTranslationChanged = null
        binding.mainMenu.translationY = 0f
    }

    private fun scrollChange() {
        scrollingView = binding.conversationsList
        val scrollingViewOffset = scrollingView?.computeVerticalScrollOffset() ?: 0
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        val statusBarColor = if (config.changeColourTopBar) getRequiredStatusBarColor(useSurfaceColor) else backgroundColor
        binding.mainMenu.updateColors(statusBarColor, scrollingViewOffset)
        setMainMenuTransparentBackground()
        binding.mainMenu.requireCustomToolbar().updateSearchColors()
        setupSearchMenuScrollListener(
            scrollingView = binding.conversationsList,
            searchMenu = binding.mainMenu,
            surfaceColor = useSurfaceColor
        )
    }

    private fun makeSystemBarsToTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val navHeight = nav.bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val dp5 = (5 * resources.displayMetrics.density).toInt()
            binding.mVerticalSideFrameBottom.layoutParams =
                binding.mVerticalSideFrameBottom.layoutParams.apply { height = navHeight + dp5 }
            val bottomOffset = (0 * resources.displayMetrics.density).toInt()
            val fabLp = binding.conversationsFab.layoutParams as? ViewGroup.MarginLayoutParams
            if (fabLp != null) {
                // Lists are not in padBottomImeAndSystem (txDial recents pattern); lift FAB above IME only.
                fabLp.bottomMargin = if (ime.bottom > 0) ime.bottom + bottomOffset else bottomOffset
                fabLp.rightMargin = (32 * resources.displayMetrics.density).toInt()
                binding.conversationsFab.layoutParams = fabLp
            }
            val rippleLp = binding.actionModeRippleToolbar.layoutParams as? ViewGroup.MarginLayoutParams
            if (rippleLp != null) {
                val rippleBase = resources.getDimensionPixelSize(R.dimen.ripple_bottom)
                val bottomInset = maxOf(nav.bottom, ime.bottom)
                rippleLp.bottomMargin = rippleBase + bottomInset
                binding.actionModeRippleToolbar.layoutParams = rippleLp
            }
            setFabIconColor()
            insets
        }
    }

    private  fun setFabIconColor() {
        binding.conversationsFab.setColors(
            resources.getColor(com.android.common.R.color.tx_content_text, theme),
            resources.getColor(com.goodwy.commons.R.color.default_primary_color, theme),
            resources.getColor(com.goodwy.commons.R.color.default_primary_color, theme)
        )
    }

    private fun storeStateVariables() {
        storedPrimaryColor = getProperPrimaryColor()
        storedTextColor = getProperTextColor()
        storedBackgroundColor = getProperBackgroundColor()
        storedFontSize = config.fontSize
        config.needRestart = false
    }

    /** After updateColors, restore transparent app bar so blur/glass shows (txDial MainActivity pattern). */
    private fun setMainMenuTransparentBackground() {
        binding.mainMenu.setBackgroundColor(Color.TRANSPARENT)
        binding.mainMenu.binding.searchBarContainer.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun loadMessages() {
        if (isQPlus()) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager!!.isRoleAvailable(RoleManager.ROLE_SMS)) {
                if (roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                    askPermissions()
                } else {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                    startActivityForResult(intent, MAKE_DEFAULT_APP_REQUEST)
                }
            } else {
                toast(com.goodwy.commons.R.string.unknown_error_occurred)
                finish()
            }
        } else {
            if (Telephony.Sms.getDefaultSmsPackage(this) == packageName) {
                askPermissions()
            } else {
                val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                startActivityForResult(intent, MAKE_DEFAULT_APP_REQUEST)
            }
        }
    }

    // while SEND_SMS and READ_SMS permissions are mandatory, READ_CONTACTS is optional.
    // If we don't have it, we just won't be able to show the contact name in some cases
    private fun askPermissions() {
        handlePermission(PERMISSION_READ_SMS) {
            if (it) {
                handlePermission(PERMISSION_SEND_SMS) {
                    if (it) {
                        handlePermission(PERMISSION_READ_CONTACTS) {
                            handleNotificationPermission { granted ->
                                if (!granted) {
                                    val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
                                        ?: throw IllegalStateException("mainBlurTarget not found")
                                    PermissionRequiredDialog(
                                        activity = this,
                                        textId = com.goodwy.commons.R.string.allow_notifications_incoming_messages,
                                        blurTarget = blurTarget,
                                        positiveActionCallback = { openNotificationSettings() })
                                }
                            }

                            initMessenger()
                            bus = EventBus.getDefault()
                            try {
                                bus!!.register(this)
                            } catch (_: Exception) {
                            }
                        }
                    } else {
                        finish()
                    }
                }
            } else {
                finish()
            }
        }
    }

    private fun initMessenger() {
        getCachedConversations()
//        binding.noConversationsPlaceholder2.setOnClickListener {
//            launchNewConversation()
//        }

        binding.conversationsFab.setOnClickListener {
            launchNewConversation()
        }
    }

    private fun getCachedConversations() {
        ensureBackgroundThread {
            // PIN-scoped mode should not render PIN=0 cached conversations first.
            val shouldUseCached = config.selectedConversationPin == 0
            val conversations = if (shouldUseCached) {
                try {
                    conversationsDB.getNonArchived().toMutableList() as ArrayList<Conversation>
                } catch (_: Exception) {
                    ArrayList()
                }
            } else {
                ArrayList()
            }

            val archived = if (shouldUseCached) {
                try {
                    conversationsDB.getAllArchived()
                } catch (_: Exception) {
                    listOf()
                }
            } else {
                listOf()
            }

            if (shouldUseCached) {
                // Load message counts only; lastMessageType already set from getNonArchived/getAllArchived snippet query
                conversations.forEach { conversation ->
                    try {
                        conversation.messageCount = messagesDB.getThreadMessageCount(conversation.threadId)
                    } catch (_: Exception) {
                        conversation.messageCount = 0
                        conversation.lastMessageType = null
                    }
                }
            }

            runOnUiThread {
                setupConversations(conversations, cached = true)
                getNewConversations((conversations + archived).toMutableList() as ArrayList<Conversation>)
            }
            if (shouldUseCached) {
                conversations.forEach {
                    clearExpiredScheduledMessages(it.threadId)
                }
            }
        }
    }

    private fun getNewConversations(cachedConversations: ArrayList<Conversation>) {
        val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ensureBackgroundThread {
            val privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)
            val conversations = getConversations(privateContacts = privateContacts)
            val isSecureMode = config.selectedConversationPin > 0

            if (isSecureMode) {
                // In secure mode, show only provider-filtered conversations for selected PIN.
                conversations.forEach { conversation ->
                    try {
                        conversation.messageCount = messagesDB.getThreadMessageCount(conversation.threadId)
                        conversation.lastMessageType = messagesDB.getLastMessageType(conversation.threadId)
                    } catch (_: Exception) {
                        conversation.messageCount = 0
                        conversation.lastMessageType = null
                    }
                }
                runOnUiThread {
                    setupConversations(conversations)
                }
                return@ensureBackgroundThread
            }

            conversations.forEach { clonedConversation ->
                val threadIds = cachedConversations.map { it.threadId }
                if (!threadIds.contains(clonedConversation.threadId)) {
                    // If this is a new conversation and title equals phone number (not in contacts),
                    // remove country code from the display. Keep title when it is already the thread address (e.g. "1912345678").
                    val normalizedTitle = clonedConversation.title.normalizePhoneNumber()
                    val normalizedPhoneNumber = clonedConversation.phoneNumber.normalizePhoneNumber()
                    val phoneNumberWithoutCountryCode = getDisplayNumberWithoutCountryCode(clonedConversation.phoneNumber)
                    val titleToUse = when {
                        clonedConversation.title == clonedConversation.phoneNumber -> clonedConversation.phoneNumber
                        normalizedTitle == normalizedPhoneNumber -> phoneNumberWithoutCountryCode
                        else -> clonedConversation.title
                    }
                    val updatedConversation = clonedConversation.copy(title = titleToUse)
                    conversationsDB.insertOrUpdate(updatedConversation)
                    cachedConversations.add(updatedConversation)
                }
            }

            cachedConversations.forEach { cachedConversation ->
                val threadId = cachedConversation.threadId

                val isTemporaryThread = cachedConversation.isScheduled
                val isConversationDeleted = !conversations.map { it.threadId }.contains(threadId)
                if (isConversationDeleted && !isTemporaryThread) {
                    conversationsDB.deleteThreadId(threadId)
                }

                val newConversation =
                    conversations.find { it.phoneNumber == cachedConversation.phoneNumber }
                if (isTemporaryThread && newConversation != null) {
                    // delete the original temporary thread and move any scheduled messages
                    // to the new thread
                    conversationsDB.deleteThreadId(threadId)
                    messagesDB.getScheduledThreadMessages(threadId)
                        .forEach { message ->
                            messagesDB.insertOrUpdate(
                                message.copy(threadId = newConversation.threadId)
                            )
                        }
                    insertOrUpdateConversation(newConversation, cachedConversation)
                }
            }

            cachedConversations.forEach { cachedConv ->
                val conv = conversations.find {
                    it.threadId == cachedConv.threadId && !Conversation.areContentsTheSame(
                        old = cachedConv, new = it
                    )
                }
                if (conv != null) {
                    // FIXME: Scheduled message date is being reset here. Conversations with
                    //  scheduled messages will have their original date.
                    insertOrUpdateConversation(conv)
                }
            }

            val allConversations = conversationsDB.getNonArchived() as ArrayList<Conversation>
            // Load message counts only; lastMessageType already set from getNonArchived snippet query
            allConversations.forEach { conversation ->
                try {
                    conversation.messageCount = messagesDB.getThreadMessageCount(conversation.threadId)
                } catch (_: Exception) {
                    conversation.messageCount = 0
                    conversation.lastMessageType = null
                }
            }
            runOnUiThread {
                setupConversations(allConversations)
            }

            if (config.appRunCount == 1) {
                conversations.map { it.threadId }.forEach { threadId ->
                    val messages = getMessages(threadId, includeScheduledMessages = false)
                    messages.chunked(30).forEach { currentMessages ->
                        messagesDB.insertMessages(*currentMessages.toTypedArray())
                    }
                }
            }
        }
    }

    private fun getOrCreateConversationsAdapter(): ConversationsAdapter {
        // Match Contacts: same background for list as activity (getProperBackgroundColor when !useSurfaceColor)
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        binding.conversationsList.setBackgroundColor(backgroundColor)

        var currAdapter = binding.conversationsList.adapter
        if (currAdapter == null) {
            hideKeyboard()
            currAdapter = ConversationsAdapter(
                activity = this,
                recyclerView = binding.conversationsList,
                onRefresh = { notifyDatasetChanged() },
                itemClick = { handleConversationClick(it) }
            )

            binding.conversationsList.adapter = currAdapter
            if (areSystemAnimationsEnabled) {
                binding.conversationsList.scheduleLayoutAnimation()
            }
        }
        return currAdapter as ConversationsAdapter
    }

    private fun setupConversations(
        conversations: ArrayList<Conversation>,
        cached: Boolean = false,
    ) {
        val sortedConversations = if (config.unreadAtTop) {
            conversations.sortedWith(
                compareByDescending<Conversation> {
                    config.pinnedConversations.contains(it.threadId.toString())
                }
                    .thenBy { it.read }
                    .thenByDescending { it.date }
            ).toMutableList() as ArrayList<Conversation>
        } else {
            conversations.sortedWith(
                compareByDescending<Conversation> {
                    config.pinnedConversations.contains(it.threadId.toString())
                }
                    .thenByDescending { it.date }
                    .thenByDescending { it.isGroupConversation } // Group chats at the top
            ).toMutableList() as ArrayList<Conversation>
        }

        if (cached) {
            // DB snapshot only; getNewConversations() always follows. Do not show empty-state
            // placeholders until setupConversations(..., cached = false) after the provider load.
            showOrHideProgress(conversations.isEmpty())
            showOrHidePlaceholder(false)
        } else {
            showOrHideProgress(false)
            showOrHidePlaceholder(conversations.isEmpty())
        }

        try {
            getOrCreateConversationsAdapter().apply {
                updateConversations(sortedConversations) {
                    if (!cached) {
                        showOrHidePlaceholder(currentList.isEmpty())
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun showOrHideProgress(show: Boolean) {
        if (show) {
            binding.conversationsProgressBar.show()
        } else {
            binding.conversationsProgressBar.hide()
        }
    }

    private fun showOrHidePlaceholder(show: Boolean) {
//        binding.conversationsFastscroller.beGoneIf(show)
        binding.noConversationsPlaceholder.beVisibleIf(show)
        binding.noConversationsPlaceholder2.text = getString(R.string.no_conversations_found)
        binding.noConversationsPlaceholder2.beVisibleIf(show)
    }

    private fun fadeOutSearch() {
        binding.searchHolder.animate()
            .alpha(0f)
            .setDuration(SHORT_ANIMATION_DURATION)
            .withEndAction {
                binding.searchHolder.beGone()
                searchTextChanged("", true)
            }.start()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun notifyDatasetChanged() {
        getOrCreateConversationsAdapter().notifyDataSetChanged()
    }

    private fun handleConversationClick(any: Any) {
        val conversation = any as Conversation
        hideKeyboard()
        ensureBackgroundThread {
            val draftBody = getSmsDraft(conversation.threadId)
            val telephonyMessageCount = getThreadTelephonyMessageCount(conversation.threadId)
            val openNewComposeForDraft =
                draftBody.isNotEmpty() && telephonyMessageCount == 0
            runOnUiThread {
                if (openNewComposeForDraft) {
                    var numbers = getThreadRecipientPhoneNumbers(conversation.threadId)
                    if (numbers.isEmpty() && conversation.phoneNumber.isNotEmpty()) {
                        numbers = arrayListOf(conversation.phoneNumber)
                    }
                    if (numbers.isNotEmpty()) {
                        val numberExtra = when (numbers.size) {
                            1 -> numbers[0]
                            else -> Gson().toJson(numbers.toSet())
                        }
                        Intent(this, NewConversationActivity::class.java).apply {
                            putExtra(NEW_CONVERSATION_RESUME_DRAFT, true)
                            putExtra(THREAD_ID, conversation.threadId)
                            putExtra(THREAD_TITLE, conversation.title)
                            putExtra(THREAD_NUMBER, numberExtra)
                            isLaunchingInternalConversationActivity = true
                            startActivity(this)
                        }
                    } else {
                        Intent(this, ThreadActivity::class.java).apply {
                            putExtra(THREAD_ID, conversation.threadId)
                            putExtra(THREAD_TITLE, conversation.title)
                            if (config.selectedConversationPin > 0) {
                                putExtra(THREAD_OPENED_FROM_SECURE_CONVERSATION_LIST, true)
                            }
                            isLaunchingInternalConversationActivity = true
                            startActivity(this)
                        }
                    }
                } else {
                    Intent(this, ThreadActivity::class.java).apply {
                        putExtra(THREAD_ID, conversation.threadId)
                        putExtra(THREAD_TITLE, conversation.title)
                        if (config.selectedConversationPin > 0) {
                            putExtra(THREAD_OPENED_FROM_SECURE_CONVERSATION_LIST, true)
                        }
                        isLaunchingInternalConversationActivity = true
                        startActivity(this)
                    }
                }
            }
        }
    }

    private fun launchNewConversation() {
        hideKeyboard()
        Intent(this, NewConversationActivity::class.java).apply {
            isLaunchingInternalConversationActivity = true
            startActivity(this)
        }
    }

    private fun checkShortcut() {
        val iconColor = getProperPrimaryColor()
        if (config.lastHandledShortcutColor != iconColor) {
            val newConversation = getCreateNewContactShortcut(iconColor)

            val manager = getSystemService(ShortcutManager::class.java)
            try {
                manager.dynamicShortcuts = listOf(newConversation)
                config.lastHandledShortcutColor = iconColor
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("NewApi")
    private fun getCreateNewContactShortcut(iconColor: Int): ShortcutInfo {
        val newEvent = getString(R.string.new_conversation)
        val drawable =
            AppCompatResources.getDrawable(this, R.drawable.shortcut_plus)

        (drawable as LayerDrawable).findDrawableByLayerId(R.id.shortcut_plus_background)
            .applyColorFilter(iconColor)
        val bmp = drawable.convertToBitmap()

        val intent = Intent(this, NewConversationActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        return ShortcutInfo.Builder(this, "new_conversation")
            .setShortLabel(newEvent)
            .setLongLabel(newEvent)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .setRank(0)
            .build()
    }

    private fun searchTextChanged(text: String, forceUpdate: Boolean = false) {
        val customToolbar = binding.mainMenu.requireCustomToolbar()
        if (isSearchAlwaysShow && !customToolbar.isSearchExpanded && !forceUpdate) {
            return
        }

        lastSearchedText = text
        binding.searchPlaceholder2.beGoneIf(text.isNotEmpty())

        if (text.isNotEmpty()) {
            ensureBackgroundThread {
                val searchQuery = "%$text%"
                val messages = messagesDB.getMessagesWithText(searchQuery)
                val conversations = conversationsDB.getConversationsWithText(searchQuery)
                // Load message counts for search results
                conversations.forEach { conversation ->
                    try {
                        conversation.messageCount = messagesDB.getThreadMessageCount(conversation.threadId)
                        conversation.lastMessageType = messagesDB.getLastMessageType(conversation.threadId)
                    } catch (_: Exception) {
                        conversation.messageCount = 0
                    }
                }
                if (text == lastSearchedText) {
                    showSearchResults(messages, conversations, text)
                }
            }
        } else {
            binding.searchPlaceholder.beVisible()
            binding.searchResultsList.beGone()
        }
        if (isSearchAlwaysShow)
            binding.mainMenu.requireCustomToolbar().setSearchText("")
    }

    private fun showSearchResults(
        messages: List<Message>,
        conversations: List<Conversation>,
        searchedText: String,
    ) {
        val flatResults = ArrayList<SearchResult>()
        conversations.forEach { conversation ->
            val dateMillis = conversation.date * 1000L

            val searchResult = SearchResult(
                messageId = -1,
                title = conversation.title,
                phoneNumber = conversation.phoneNumber,
                snippet = conversation.snippet,
                date = "",
                dateMillis = dateMillis,
                threadId = conversation.threadId,
                photoUri = conversation.photoUri,
                isCompany = conversation.isCompany,
                isBlocked = conversation.isBlocked,
                lastMessageType = conversation.lastMessageType
            )
            flatResults.add(searchResult)
        }

        fun getTypeFromMessage(message: Message): Int {
            return if (message.isReceivedMessage()) {
                Telephony.Sms.MESSAGE_TYPE_INBOX
            } else {
                Telephony.Sms.MESSAGE_TYPE_SENT
            }
        }

        messages.sortedByDescending { it.id }.forEach { message ->
            var recipient = message.senderName
            if (recipient.isEmpty() && message.participants.isNotEmpty()) {
                val participantNames = message.participants.map { it.name }
                recipient = TextUtils.join(", ", participantNames)
            }

            val phoneNumber = message.participants.firstOrNull()!!.phoneNumbers.firstOrNull()!!.normalizedNumber
            val dateMillis = message.date * 1000L
            val isCompany =
                if (message.participants.size == 1) message.participants.first().isABusinessContact() else false

            val searchResult = SearchResult(
                messageId = message.id,
                title = recipient,
                phoneNumber = phoneNumber,
                snippet = message.body,
                date = "",
                dateMillis = dateMillis,
                threadId = message.threadId,
                photoUri = message.senderPhotoUri,
                isCompany = isCompany,
                isBlocked = false,
                lastMessageType = getTypeFromMessage(message)
            )
            flatResults.add(searchResult)
        }

        val searchListItems = groupSearchResultsByDateSections(flatResults)

        runOnUiThread {
            binding.searchResultsList.beVisibleIf(flatResults.isNotEmpty())
            binding.searchPlaceholder.beVisibleIf(flatResults.isEmpty())

            val currAdapter = binding.searchResultsList.adapter
            if (currAdapter == null) {
                SearchResultsAdapter(this, searchListItems, binding.searchResultsList, searchedText) {
                hideKeyboard()
                    Intent(this, ThreadActivity::class.java).apply {
                        putExtra(THREAD_ID, (it as SearchResult).threadId)
                        putExtra(THREAD_TITLE, it.title)
                        putExtra(SEARCHED_MESSAGE_ID, it.messageId)
                        if (config.selectedConversationPin > 0) {
                            putExtra(THREAD_OPENED_FROM_SECURE_CONVERSATION_LIST, true)
                        }
                        isLaunchingInternalConversationActivity = true
                        startActivity(this)
                    }
                }.apply {
                    binding.searchResultsList.adapter = this
                    scheduleGroupedTodayTimeRefresh()
                }
            } else {
                (currAdapter as SearchResultsAdapter).updateItems(searchListItems, searchedText)
            }
        }
    }

    private fun launchRecycleBin() {
        hideKeyboard()
        startActivity(Intent(applicationContext, RecycleBinConversationsActivity::class.java))
    }

    private fun launchArchivedConversations() {
        hideKeyboard()
        startActivity(Intent(applicationContext, ArchivedConversationsActivity::class.java))
    }

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshConversations(@Suppress("unused") event: Events.RefreshConversations) {
        initMessenger()
        // Child activities persist drafts on a background thread; onResume may run before that
        // finishes. Reload local drafts whenever a refresh is posted so the list shows [draft]
        // immediately after returning from compose/thread.
        getOrCreateConversationsAdapter().updateDrafts()
    }

    private fun checkWhatsNewDialog() {
        whatsNewList().apply {
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }

    public fun getActionModeState() : Boolean {
        return isStartActionMode
    }
}
