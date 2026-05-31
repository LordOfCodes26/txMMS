package com.android.mms.activities

import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
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
import android.view.ViewTreeObserver
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.SearchView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.recyclerview.widget.RecyclerView
import android.widget.EditText
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.android.common.view.MSearchView
import com.android.common.view.MVSideFrame
import com.goodwy.commons.views.showMPopupMenu
import eightbitlab.com.blurview.BlurTarget
import com.goodwy.commons.views.CustomActionModeToolbar
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
import com.android.mms.helpers.THREAD_URI
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.text.toFloat

open class MainActivity : SimpleActivity(), ActionModeToolbarHost {
    companion object {
        private const val TAG = "MessagesMainActivity"
        private const val SECRET_BOX_PACKAGE = "chonha.get.secret.number"
        private const val SECRET_NUMBER_EXTRA = "secret_number"
        private const val INVALID_CIPHER = -1
        private const val MIN_SWIPE_DISTANCE = 50f

        /** CAB menu for the main conversation list ([R.menu.cab_action_menu_select]): select-all only; delete stays on the bottom ripple. */
        @JvmField
        val ACTION_MODE_MENU_SELECT: Int = R.menu.cab_action_menu_select

        /** txCommon [MAppBarLayout] scroll-content offset animation when search opens from expanded. */
        private const val TX_SEARCH_CONTENT_OFFSET_ANIM_MS = 300L
        private const val MAIN_SEARCH_LAYOUT_SETTLE_MS = 320L
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
    /** True while a search-expanded resume is in progress; blocks blur-margin updates until layout settles. */
    private var isSearchResumeInProgress = false

    private lateinit var twoFingerGestureDetector: TwoFingerSlideGestureDetector
    private var pendingThreadIdsToEncrypt: LongArray? = null
    private var shouldExitSecureModeOnResume = false
    private var isLaunchingSecretBox = false
    /** True while starting Thread/NewConversation from this screen; avoids treating that as leaving the app ([onUserLeaveHint]). */
    private var isLaunchingInternalConversationActivity = false

    var unreadCountHash = HashMap<Long, Int>(128)

    protected val binding by viewBinding(ActivityMainBinding::inflate)

    private val mainMenuOverscrollFactor = 0.35f
    /** Search-mode list top inset after chrome settles; -1 until [computeMainSearchListTopInsetPxOnce]. */
    private var mainSearchListTopInsetPx = -1
    /** True when search opened before the app bar was fully collapsed (extra layout settle). */
    private var mainSearchOpenedFromExpanded = false
    private var mainSearchAlignAttempts = 0
    private var mainSearchChromeLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private val mainSearchLayoutSyncRunnable = Runnable {
        if (!isSearchOpen || isFinishing || isDestroyed) return@Runnable
        alignMainSearchListTopPadding()
    }
    private val mainSearchRefineRunnable = Runnable {
        if (!isSearchOpen || isFinishing || isDestroyed) return@Runnable
        refineMainSearchListTopPadding()
    }
    private var isStartActionMode = false

    /** Saved [ScrollingViewBehavior] while action mode clears app-bar coupling from [R.id.blur_target]. */
    private var blurTargetScrollingBehavior: CoordinatorLayout.Behavior<View>? = null

    /** Mirrors [AppBarLayout] offset (0 expanded, negative collapsed); kept in sync while the bar is on screen. */
    private var mainMenuLastAppBarVerticalOffset: Int = 0

    /**
     * Incremented on each [initMessenger] refresh. Background loads compare against this so an older,
     * slower run cannot overwrite the list after mode switches (e.g. normal → secure box).
     */
    private val conversationsLoadSeq = AtomicInteger(0)

    /**
     * `config.selectedConversationPin` after the last full [setupConversations] apply.
     * Used to skip the empty cached staging step on secure refreshes (e.g. returning from [ThreadActivity])
     * so the list is not cleared and the progress bar is not shown before the async provider load.
     */
    private var lastMessengerAppliedPin: Int = -1

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Theme.Material3.Dark windowBackground is dark; paint window + decor before inflation so edge-to-edge does not flash behind transparent bars.
        paintMainScreenWindowBeforeContentView()
        setContentView(binding.root)
        binding.mainAppbar.addOnOffsetChangedListener { _, verticalOffset ->
            mainMenuLastAppBarVerticalOffset = verticalOffset
            binding.mVerticalSideFrameTop.update()
            applyLiveRecentsTopPaddingFromAppBarOffset()
        }
        initTheme()
        setupTwoFingerSwipeGesture()
//        makeSystemBarsToTransparent()
        val isFirstLaunch = baseConfig.appRunCount == 0
        appLaunched(BuildConfig.APPLICATION_ID)
        // Initialize default quick texts if they haven't been initialized yet
        // The function internally checks if quick texts are empty to prevent re-initialization
        // if user deletes all quick texts later
        if (!config.quickTextsDefaultsInitialized) {
            config.initializeDefaultQuickTexts()
        }
        setupOptionsMenu()
        applyMainScreenBackgroundAndTopChrome()
        refreshMenuItems()
        setupEdgeToEdge()
        checkWhatsNewDialog()
        storeStateVariables()

        checkAndDeleteOldRecycleBinMessages()
        clearAllMessagesIfNeeded {
            loadMessages()
        }

        unreadCountHash = getUnreadCountsByThread() as HashMap<Long, Int>

        binding.conversationsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                hideKeyboard()
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                onMainListScrolled(recyclerView.computeVerticalScrollOffset())
            }
        })
        binding.searchResultsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                hideKeyboard()
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                onMainListScrolled(recyclerView.computeVerticalScrollOffset())
            }
        })
        binding.conversationsNestedScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            onMainListScrolled(
                maxOf(
                    binding.conversationsList.computeVerticalScrollOffset(),
                    binding.conversationsNestedScroll.scrollY,
                ),
            )
        }
        binding.conversationsNestedScroll.post {
            binding.mainAppbar.dismissCollapse()
            mainMenuLastAppBarVerticalOffset = 0
            clearMainAppBarScrims()
            setupMainMenuSpringSync()
            refreshSideFrameBlurAndInsets()
            applyLiveRecentsTopPaddingFromAppBarOffset()
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

        // While MainActivity is paused under SecureMainActivity, RefreshConversations can apply an
        // empty PIN-scoped list (pin > 0). Reload when returning to normal mode (pin 0).
        if (config.selectedConversationPin != lastMessengerAppliedPin && lastMessengerAppliedPin >= 0) {
            initMessenger()
        }

        applyMainScreenBackgroundAndTopChrome()

        refreshMenuItems()

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

        val properPrimaryColor = getProperPrimaryColor()
//        binding.noConversationsPlaceholder2.setTextColor(getProperTextColor())
        // binding.noConversationsPlaceholder2.underlineText()
//        binding.conversationsFastscroller.updateColors(getProperAccentColor())
        // binding.conversationsProgressBar.setIndicatorColor(properPrimaryColor)
        binding.conversationsProgressBar.trackColor = properPrimaryColor.adjustAlpha(LOWER_ALPHA)
        checkShortcut()

        setFabIconColor()

        isSearchResumeInProgress = isSearchOpen

        binding.root.post {
            syncMainMenuActionModeToolbarWithAdapter()
            if (isActionModeToolbarVisible()) {
                binding.mVerticalSideFrameTop.visibility = View.GONE
                syncBlurTargetScrollingBehaviorForActionMode()
                syncBlurTargetTopMarginForAppBar()
                syncActionModeListTopPadding()
            } else {
                binding.mVerticalSideFrameTop.visibility = View.VISIBLE
                setupVerticalSideFrameBlur()
                binding.mVerticalSideFrameTop.update()
            }
        }

        val selectionMode = (binding.conversationsList.adapter as? ConversationsAdapter)?.isActionModeActive() == true
        if (!selectionMode) {
            binding.mainAppbar.post {
                clearMainAppBarScrims()
                syncTopSideFrameHeight()
                requestTopInsetSync()
            }
            refreshSideFrameBlurAndInsets()
            if (isSearchOpen) {
                mainSearchListTopInsetPx = -1
                binding.mainAppbar.postDelayed({
                    if (isFinishing || isDestroyed) return@postDelayed
                    isSearchResumeInProgress = false
                    scheduleMainSearchLayoutSync()
                }, 150L)
            }
        }

        (binding.searchResultsList.adapter as? SearchResultsAdapter)?.scheduleGroupedTodayTimeRefresh()
    }

    /** BlurView/BlurTarget links are invalidated when the activity is paused; re-bind like txDial [setupVerticalSideFrameBlur]. */
    private fun refreshSideFrameBlurAndInsets() {
        binding.root.post {
            ViewCompat.requestApplyInsets(binding.root)
            setupVerticalSideFrameBlur()
            binding.mainAppbar.getActionBarView()?.bindBlurTarget(this@MainActivity, binding.blurTarget)
            binding.mainAppbar.getSearchView()?.bindBlurTarget(this@MainActivity, binding.blurTarget, 0)
            binding.actionModeToolbar.bindBlurTarget(this@MainActivity, binding.blurTarget)
            clearMainAppBarScrims()
            applyToolbarSearchModeChrome(isSearchOpen)
            setMainMenuTransparentBackground()
            binding.mVerticalSideFrameTop.update()
        }
    }

    /** Toolbar glass on inner [blurTarget] (txDial); re-bind after [syncBlurTargetTopMargin]. */
//    private fun refreshMainToolbarBlur() {
//        binding.blurTarget.invalidate()
//        binding.mainAppbar.requireCustomToolbar()
//            .bindBlurTarget(this, binding.blurTarget, getProperBlurOverlayColor())
//    }

    /** Selection-mode back / select-all pills use the same inner [blurTarget] as [refreshMainToolbarBlur]. */
    private fun refreshActionModeToolbarBlur() {
        val adapter = binding.conversationsList.adapter as? ConversationsAdapter ?: return
        if (!adapter.isActionModeActive()) return
        binding.blurTarget.invalidate()
        binding.actionModeToolbar.bindBlurTarget(this, binding.blurTarget)
    }

    /**
     * After AppBar height changes (search lock/unlock, CAB swap, resume), [m_vertical_side_frame_top] and
     * [binding.blurTarget] top margin must match the measured menu — otherwise MVSideFrame shows a dark
     * gradient strip. Uses one [doOnLayout] pass (no [setMainMenuHeight] forced relayout) to avoid flash.
     */
    private fun scheduleSyncMainMenuTopBlurGeometry() {
        val menu = binding.mainAppbar
        menu.doOnLayout {
            if (isFinishing || isDestroyed) return@doOnLayout
            menu.height.takeIf { it > 0 } ?: menu.measuredHeight.takeIf { it > 0 } ?: return@doOnLayout
            syncTopSideFrameHeight()
            ViewCompat.requestApplyInsets(binding.root)
//            refreshMainToolbarBlur()
            refreshActionModeToolbarBlur()
            setupVerticalSideFrameBlur()
            setMainMenuTransparentBackground()
            requestTopInsetSync()
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
        cancelMainSearchLayoutSync()
        clearMainSearchChromeLayoutListener()
        super.onDestroy()
        clearMainMenuSpringSync()
        config.needRestart = false
        bus?.unregister(this)
    }

    override fun onBackPressedCompat(): Boolean {
        if (isMSearchOpen() || isSearchOpen) {
            closeMSearchView()
            return true
        }
        if (finishConversationSelectionIfActive()) {
            return true
        }
        if (
            config.selectedConversationPin > 0 &&
            !isLaunchingSecretBox &&
            !isLaunchingInternalConversationActivity
        ) {
            closeSecureBox()
            return true
        }
        appLockManager.lock()
        return false
    }

    /** System back (e.g. 3-button nav) while the conversation list is in selection mode — same as CAB back. */
    private fun finishConversationSelectionIfActive(): Boolean {
        val adapter = binding.conversationsList.adapter as? ConversationsAdapter ?: return false
        if (!adapter.isActionModeActive()) return false
        adapter.finishActMode()
        return true
    }

    fun getMainMenuVisibleHeight(): Int {
        return binding.mainAppbar.height
            .takeIf { it > 0 }
            ?: binding.mainAppbar.measuredHeight.takeIf { it > 0 }
            ?: getCollapsedAppBarHeightPx()
    }

    private fun mainMenuListTopInsetSlackPx(): Int =
        (48 * resources.displayMetrics.density).toInt()

    /**
     * Bottom edge of the app bar as drawn on screen (0 expanded, negative [mainMenuLastAppBarVerticalOffset] when collapsed).
     * [View.getHeight] on [AppBarLayout] stays at the expanded height while collapsing, so geometry must add offset.
     */
    private fun mainMenuVisibleBottomOnScreenPx(menuTopOnScreen: Int): Int {
        val menu = binding.mainAppbar
        return menuTopOnScreen + menu.height + mainMenuLastAppBarVerticalOffset.coerceAtMost(0)
    }

    /** Visible [AppBarLayout] height from layout height + scroll offset (matches CoordinatorLayout draw). */
    private fun mainMenuVisibleHeightPx(): Int {
        val menu = binding.mainAppbar
        val collapsed = getCollapsedAppBarHeightPx()
        val laidOutHeight = menu.height.takeIf { it > 0 } ?: return collapsed
        return (laidOutHeight + mainMenuLastAppBarVerticalOffset.coerceAtMost(0)).coerceIn(collapsed, laidOutHeight)
    }

    /**
     * Lowest screen Y covered by toolbar + large title. The layout offset alone can sit above the drawn title
     * (large [com.android.common.R.id.m_app_bar_title] under collapsed toolbar chrome).
     */
    private fun mainMenuChromeBottomOnScreenPx(): Int {
        val menu = binding.mainAppbar
        val mLoc = IntArray(2)
        menu.getLocationOnScreen(mLoc)
        if (isActionModeToolbarVisible()) {
            val cabRect = Rect()
            val cab = binding.actionModeToolbar
            if (cab.getGlobalVisibleRect(cabRect) && !cabRect.isEmpty) {
                return cabRect.bottom
            }
            return mainMenuVisibleBottomOnScreenPx(mLoc[1])
        }
        if (isSearchOpen) {
            val searchBottom = mainSearchChromeBottomOnScreenPx()
            if (searchBottom > 0) return searchBottom
        }
        var bottom = mainMenuVisibleBottomOnScreenPx(mLoc[1])
        val title = menu.findViewById<View>(com.android.common.R.id.m_app_bar_title)
        if (title?.visibility == View.VISIBLE) {
            val titleRect = Rect()
            if (title.getGlobalVisibleRect(titleRect) && !titleRect.isEmpty) {
                bottom = maxOf(bottom, titleRect.bottom)
            }
        }
        val actionBar = menu.getActionBarView()
        val toolbarRect = Rect()
        if (actionBar?.getGlobalVisibleRect(toolbarRect) == true && !toolbarRect.isEmpty) {
            bottom = maxOf(bottom, toolbarRect.bottom)
        }
        return bottom
    }

    private fun isConversationListSelectionModeActive(): Boolean =
        (binding.conversationsList.adapter as? ConversationsAdapter)?.isActionModeActive() == true

    /**
     * List top inset from [mainMenuLastAppBarVerticalOffset] (expanded = tall, collapsed = short).
     * Uses collapse fraction so [onResume] remeasuring layout height does not grow padding while offset stays collapsed.
     */
    private fun mainMenuListTopInsetForCollapsePx(): Int {
        if (isActionModeToolbarVisible()) {
            return getCollapsedAppBarHeightPx()
        }
        return listTopPaddingForAppBarOffset(mainMenuLastAppBarVerticalOffset)
    }

    /** Pinned toolbar row height when [MAppBarLayout] is fully collapsed (txCommon). */
    private fun getCollapsedAppBarHeightPx(): Int =
        resources.getDimensionPixelSize(com.android.common.R.dimen.tx_top_bar_toolbar_margin_top) +
            resources.getDimensionPixelSize(com.android.common.R.dimen.tx_top_bar_toolbar_height)

    private fun getExpandedAppBarHeightPx(): Int =
        resources.getDimensionPixelSize(com.android.common.R.dimen.tx_nest_bouncy_content_padding_top)

    private fun listTopPaddingForAppBarOffset(verticalOffset: Int): Int {
        val expanded = getExpandedAppBarHeightPx()
        val collapsed = getCollapsedAppBarHeightPx()
        val totalRange = binding.mainAppbar.totalScrollRange
        if (totalRange <= 0) return expanded
        val collapseFraction = (
            kotlin.math.abs(verticalOffset).toFloat() / totalRange.toFloat()
            ).coerceIn(0f, 1f)
        return kotlin.math.round(collapsed + (expanded - collapsed) * (1f - collapseFraction)).toInt()
    }

    private fun isActionModeToolbarVisible(): Boolean =
        binding.actionModeToolbar.visibility == View.VISIBLE

    private fun mainSearchChromeBottomOnScreenPx(): Int {
        val searchView = binding.mainAppbar.getSearchView() ?: return -1
        val container = searchView.findViewById<View>(com.android.common.R.id.search_container)
        if (
            container != null &&
            container.visibility == View.VISIBLE &&
            container.isLaidOut &&
            container.height > 0
        ) {
            val loc = IntArray(2)
            container.getLocationOnScreen(loc)
            return loc[1] + container.height
        }
        if (searchView.visibility == View.VISIBLE && searchView.isLaidOut && searchView.height > 0) {
            val loc = IntArray(2)
            searchView.getLocationOnScreen(loc)
            return loc[1] + searchView.height
        }
        return -1
    }

    /** [MAppBarLayout] shifts scroll content via translation on search open; list padding handles inset instead. */
    private fun clearMainSearchScrollContentTranslation() {
        binding.blurTarget.translationY = 0f
        binding.conversationsNestedScroll.translationY = 0f
    }

    private fun mainSearchMinListTopPaddingPx(): Int =
        resources.getDimensionPixelSize(R.dimen.nest_bouncy_content_padding_top)

    private fun cancelMainSearchLayoutSync() {
        binding.mainAppbar.removeCallbacks(mainSearchLayoutSyncRunnable)
        binding.mainAppbar.getSearchView()?.removeCallbacks(mainSearchLayoutSyncRunnable)
        binding.mainAppbar.getSearchView()?.removeCallbacks(mainSearchRefineRunnable)
    }

    private fun clearMainSearchChromeLayoutListener() {
        mainSearchChromeLayoutListener?.let { listener ->
            binding.mainAppbar.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
        mainSearchChromeLayoutListener = null
    }

    private fun isMainSearchChromeLayoutReady(searchView: MSearchView): Boolean {
        val container = searchView.findViewById<View>(com.android.common.R.id.search_container) ?: return false
        val editText = searchView.findViewById<View>(com.android.common.R.id.et_search_text) ?: return false
        if (container.visibility != View.VISIBLE) return false
        if (!container.isLaidOut || container.height <= 0) return false
        if (!editText.isLaidOut || editText.height <= 0) return false
        if (!searchView.isLaidOut || searchView.height <= 0) return false
        if (container.width > 0 && kotlin.math.abs(container.x) > 1f) return false
        return true
    }

    private fun isMainAppBarCollapseSettled(): Boolean {
        val menu = binding.mainAppbar
        val range = menu.totalScrollRange
        if (range <= 0) return true
        return -mainMenuLastAppBarVerticalOffset >= range
    }

    /**
     * One-shot search inset: bottom of visible search chrome − list top on screen.
     * Cached in [mainSearchListTopInsetPx] so scroll / resume does not reapply a smaller stale value.
     */
    private fun computeMainSearchListTopInsetPxOnce(list: View): Int? {
        val menu = binding.mainAppbar
        val searchView = menu.getSearchView() ?: return null
        if (
            list.visibility != View.VISIBLE ||
            !menu.isLaidOut ||
            !list.isLaidOut ||
            menu.height <= 0 ||
            !isMainSearchChromeLayoutReady(searchView) ||
            !isMainAppBarCollapseSettled()
        ) {
            return null
        }
        clearMainSearchScrollContentTranslation()
        val listLoc = IntArray(2)
        list.getLocationOnScreen(listLoc)
        val geometryInset = (mainMenuChromeBottomOnScreenPx() - listLoc[1]).coerceAtLeast(0)
        return maxOf(geometryInset, mainSearchMinListTopPaddingPx())
    }

    private fun mainSearchListTopPaddingPx(list: View = binding.conversationsList): Int {
        if (mainSearchListTopInsetPx >= 0) {
            return mainSearchListTopInsetPx
        }
        val computed = computeMainSearchListTopInsetPxOnce(list) ?: return -1
        mainSearchListTopInsetPx = computed
        return mainSearchListTopInsetPx
    }

    private fun applyMainSearchListTopPadding(topPx: Int) {
        val resolved = when {
            mainSearchListTopInsetPx >= 0 -> maxOf(mainSearchListTopInsetPx, topPx, mainSearchMinListTopPaddingPx())
            else -> maxOf(topPx, mainSearchMinListTopPaddingPx())
        }
        listOf(binding.conversationsList, binding.searchResultsList).forEach { list ->
            val rv = list as MyRecyclerView
            if (rv.paddingTop != resolved) {
                rv.updatePadding(top = resolved)
            }
            if (isSearchOpen) {
                rv.post {
                    (rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                        ?.scrollToPositionWithOffset(0, 0)
                }
            }
        }
        if (binding.searchHolder.childCount > 0) {
            binding.searchHolder.getChildAt(0)!!.updatePadding(top = resolved)
        }
    }

    private fun alignMainSearchListTopPadding() {
        if (!isSearchOpen) return
        clearMainSearchScrollContentTranslation()
        val list = if (binding.searchResultsList.visibility == View.VISIBLE) {
            binding.searchResultsList
        } else {
            binding.conversationsList
        }
        val inset = computeMainSearchListTopInsetPxOnce(list)
        if (inset != null) {
            mainSearchAlignAttempts = 0
            mainSearchListTopInsetPx = inset
            applyMainSearchListTopPadding(inset)
            syncBlurTargetTopMargin()
            return
        }
        mainSearchAlignAttempts++
        if (mainSearchAlignAttempts >= 60) {
            applyMainSearchListTopPadding(mainSearchMinListTopPaddingPx())
            syncBlurTargetTopMargin()
            return
        }
        binding.mainAppbar.post { alignMainSearchListTopPadding() }
    }

    /** Second pass after search chrome / app bar finish moving (ContactPicker refine pattern). */
    private fun refineMainSearchListTopPadding() {
        if (!isSearchOpen) return
        val searchView = binding.mainAppbar.getSearchView() ?: return
        if (!isMainSearchChromeLayoutReady(searchView) || !isMainAppBarCollapseSettled()) return
        clearMainSearchScrollContentTranslation()
        val list = if (binding.searchResultsList.visibility == View.VISIBLE) {
            binding.searchResultsList
        } else {
            binding.conversationsList
        }
        val inset = computeMainSearchListTopInsetPxOnce(list) ?: return
        if (mainSearchListTopInsetPx < 0 || inset > mainSearchListTopInsetPx) {
            mainSearchListTopInsetPx = inset
            applyMainSearchListTopPadding(inset)
            syncBlurTargetTopMargin()
        }
    }

    private fun scheduleMainSearchChromeLayoutListener() {
        if (mainSearchChromeLayoutListener != null) return
        val menu = binding.mainAppbar
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!isSearchOpen || isFinishing || isDestroyed) return
                clearMainSearchScrollContentTranslation()
                val list = if (binding.searchResultsList.visibility == View.VISIBLE) {
                    binding.searchResultsList
                } else {
                    binding.conversationsList
                }
                val inset = computeMainSearchListTopInsetPxOnce(list) ?: return
                if (mainSearchListTopInsetPx < 0 || inset > mainSearchListTopInsetPx) {
                    mainSearchListTopInsetPx = inset
                    applyMainSearchListTopPadding(inset)
                    syncBlurTargetTopMargin()
                }
                val searchView = menu.getSearchView() ?: return
                if (mainSearchListTopInsetPx >= 0 && isMainSearchChromeLayoutReady(searchView)) {
                    menu.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    mainSearchChromeLayoutListener = null
                }
            }
        }
        mainSearchChromeLayoutListener = listener
        menu.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun applySearchModeListTopPadding() {
        clearMainSearchScrollContentTranslation()
        val topPad = mainSearchListTopPaddingPx()
        if (topPad >= 0) {
            applyMainSearchListTopPadding(topPad)
        } else {
            applyMainSearchListTopPadding(mainSearchMinListTopPaddingPx())
            alignMainSearchListTopPadding()
        }
        syncBlurTargetTopMargin()
    }

    private fun scheduleMainSearchLayoutSync() {
        cancelMainSearchLayoutSync()
        val menu = binding.mainAppbar
        val searchView = menu.getSearchView()
        val needsExtraSettle = mainSearchOpenedFromExpanded
        val settleMs = MAIN_SEARCH_LAYOUT_SETTLE_MS +
            if (needsExtraSettle) TX_SEARCH_CONTENT_OFFSET_ANIM_MS else 0L
        applyMainSearchListTopPadding(mainSearchMinListTopPaddingPx())
        scheduleMainSearchChromeLayoutListener()
        menu.post { applySearchModeListTopPadding() }
        menu.postDelayed({ if (isSearchOpen) clearMainSearchScrollContentTranslation() }, TX_SEARCH_CONTENT_OFFSET_ANIM_MS)
        menu.postDelayed(mainSearchLayoutSyncRunnable, settleMs)
        searchView?.postDelayed(mainSearchRefineRunnable, settleMs + 80L)
        menu.postDelayed({
            if (isFinishing || isDestroyed || !isSearchOpen) return@postDelayed
            applySearchModeListTopPadding()
        }, settleMs + TX_SEARCH_CONTENT_OFFSET_ANIM_MS)
    }

    /**
     * While the app bar scrolls, keep blur negative margin and list [paddingTop] tied to the same visible chrome
     * height so content is not left under the large title when the bar is collapsed.
     */
    private fun applyLiveRecentsTopPaddingFromAppBarOffset() {
        if (isFinishing || isDestroyed) return
        if (isSearchOpen) return
        if (isSearchResumeInProgress) return
        val insetPx = mainMenuListTopInsetForCollapsePx()
        syncBlurTargetTopMarginForAppBar()
        val conv = binding.conversationsList
        val topPad = insetPx.coerceAtLeast(0)
        if (conv.paddingTop != topPad) {
            conv.updatePadding(top = topPad)
        }
    }

    /**
     * Upper bound for list top padding / trusted screen geometry. Rejects stale half-screen insets after
     * resume while still allowing the large-title expanded chrome (scales with [mainMenuLastAppBarVerticalOffset]).
     */
    private fun maxTrustedListTopInsetPx(): Int {
        val menu = binding.mainAppbar
        val slack = mainMenuListTopInsetSlackPx()
        val minSearchListTop = resources.getDimensionPixelSize(R.dimen.nest_bouncy_content_padding_top)
        if (isSearchOpen) {
            val menuLoc = IntArray(2)
            menu.getLocationOnScreen(menuLoc)
            val searchChromeInset = (mainSearchChromeBottomOnScreenPx() - menuLoc[1]).coerceAtLeast(0)
            return maxOf(
                searchChromeInset + slack,
                minSearchListTop + slack,
                getCollapsedAppBarHeightPx() + slack,
                menu.height + slack,
            )
        }
        return mainMenuListTopInsetForCollapsePx() + slack
    }

    /**
     * Top padding for [R.id.conversations_list]: distance from the list’s top edge to the bottom of
     * [R.id.main_appbar] on screen. Using [View.getHeight] alone double-counts with CoordinatorLayout /
     * negative [R.id.blur_target] margin and produced a large empty band under the title.
     *
     * Geometry is only applied when it matches about one app bar height; stale [View.getLocationOnScreen]
     * values right after [onResume] (before the coordinator and blur negative margin settle) used to
     * pass the old `height * 3` bound and pushed the list down by a large fraction of the screen.
     *
     * Screens that use [com.android.common.view.MAppBarLayout] over a [eightbitlab.com.blurview.BlurTarget]
     * must wire the same CoordinatorLayout scrolling as [R.layout.activity_main]: set
     * `app:layout_behavior="@string/appbar_scrolling_view_behavior"` on the blur target and on the
     * nested scrolling child (see [R.layout.activity_message_bubble_picker], [R.layout.activity_settings]).
     *
     * Pass the same [View] you will pad (e.g. [R.id.conversations_list] vs [R.id.search_results_list]);
     * screen geometry differs between the conversation list and the search overlay list.
     */
    /**
     * Distance from the list’s top edge to the bottom of visible toolbar/title chrome on screen.
     */
    private fun recentsListTopPaddingPx(list: View): Int {
        val menu = binding.mainAppbar
        val minSearchListTop = resources.getDimensionPixelSize(R.dimen.nest_bouncy_content_padding_top)
        val collapseInset = mainMenuListTopInsetForCollapsePx()
        if (isSearchOpen) {
            mainSearchListTopPaddingPx(list).takeIf { it >= 0 }?.let { return it }
            if (
                list.visibility != View.VISIBLE ||
                menu.visibility != View.VISIBLE ||
                !menu.isLaidOut ||
                !list.isLaidOut ||
                menu.height <= 0
            ) {
                return maxOf(collapseInset, minSearchListTop)
            }
            val listLoc = IntArray(2)
            list.getLocationOnScreen(listLoc)
            val chromeInset = (mainMenuChromeBottomOnScreenPx() - listLoc[1]).coerceAtLeast(0)
            val maxTrustInset = maxTrustedListTopInsetPx()
            return maxOf(collapseInset, chromeInset.coerceAtMost(maxTrustInset), minSearchListTop)
        }
        if (
            isActionModeToolbarVisible() ||
            mainMenuLastAppBarVerticalOffset < 0
        ) {
            return collapseInset
        }
        if (
            list.visibility != View.VISIBLE ||
            menu.visibility != View.VISIBLE ||
            !menu.isLaidOut ||
            !list.isLaidOut ||
            menu.height <= 0
        ) {
            return collapseInset
        }
        val listLoc = IntArray(2)
        list.getLocationOnScreen(listLoc)
        val chromeInset = (mainMenuChromeBottomOnScreenPx() - listLoc[1]).coerceAtLeast(0)
        val maxTrustInset = maxTrustedListTopInsetPx()
        return maxOf(collapseInset, chromeInset.coerceAtMost(maxTrustInset))
    }

    private fun listTopInsetPx(list: View): Int = recentsListTopPaddingPx(list)

    fun getRecentsListTopInsetPx(): Int = listTopInsetPx(binding.conversationsList)

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

    /**
     * Same cap as [com.android.mms.extensions.applyMySearchMenuListTopPadding]: never apply a raw inset
     * larger than roughly one toolbar (stale inset values during layout/animation).
     */
    private fun capRecentsListTopPaddingPx(rawInset: Int): Int {
        if (rawInset <= 0) return 0
        val menu = binding.mainAppbar
        if (!menu.isLaidOut || menu.height <= 0) return 0
        if (isSearchOpen) {
            val minSearchListTop = resources.getDimensionPixelSize(R.dimen.nest_bouncy_content_padding_top)
            return maxOf(rawInset, minSearchListTop)
        }
        return minOf(rawInset, maxTrustedListTopInsetPx())
    }

    /** Padding to apply now, or -1 when the app bar is not measured yet (caller should post and retry). */
    private fun resolveRecentsListTopPaddingPx(rawInset: Int): Int {
        val menu = binding.mainAppbar
        if (!menu.isAttachedToWindow || !menu.isLaidOut || menu.height <= 0) return -1
        val capped = capRecentsListTopPaddingPx(rawInset)
        if (capped > 0) return capped
        return if (isSearchOpen) {
            resources.getDimensionPixelSize(R.dimen.nest_bouncy_content_padding_top)
        } else {
            getCollapsedAppBarHeightPx()
        }
    }

    /** Normal list: offset curve (stable across resume remeasure). Search: screen geometry. */
    private fun recentsListTopInsetForLayoutSync(recentsList: MyRecyclerView): Int {
        val menu = binding.mainAppbar
        return if (isSearchOpen) {
            recentsListTopPaddingPx(recentsList)
        } else {
            mainMenuListTopInsetForCollapsePx()
        }
    }

    /** Re-apply when the app bar finishes layout (e.g. after unlockCollapsing); based on txDial MainActivityRecents (no dialpad in Messages). */
    private fun applyFinalRecentsListTopPadding(recentsList: MyRecyclerView) {
        if (isActionModeToolbarVisible()) {
            val topPad = getCollapsedAppBarHeightPx()
            recentsList.updatePadding(top = topPad)
            logRecentsListTopPadding("applyFinal(actionMode topPad=$topPad)", recentsList)
            return
        }
        if (isSearchOpen) {
            val topPad = mainSearchListTopPaddingPx(recentsList)
            if (topPad >= 0) {
                applyMainSearchListTopPadding(topPad)
                logRecentsListTopPadding("applyFinal(search cached topPad=$topPad)", recentsList)
                return
            }
            applyMainSearchListTopPadding(mainSearchMinListTopPaddingPx())
            scheduleMainSearchChromeLayoutListener()
            alignMainSearchListTopPadding()
            return
        }
        val menu = binding.mainAppbar
        if (!menu.isAttachedToWindow || !menu.isLaidOut || menu.height <= 0) {
            menu.post {
                if (!isFinishing && !isDestroyed) {
                    applyFinalRecentsListTopPadding(recentsList)
                }
            }
            return
        }
        val rawInset = recentsListTopInsetForLayoutSync(recentsList)
        val topPad = resolveRecentsListTopPaddingPx(rawInset)
        if (topPad >= 0) {
            val maxAllowed = maxTrustedListTopInsetPx()
            val clamped = minOf(topPad, maxAllowed).coerceAtLeast(0)
            recentsList.updatePadding(top = clamped)
            logRecentsListTopPadding("applyFinal(immediate topPad=$clamped raw=$rawInset)", recentsList)
            return
        }
        menu.post {
            if (isFinishing || isDestroyed) return@post
            val retryInset = recentsListTopInsetForLayoutSync(recentsList)
            val retryTop = resolveRecentsListTopPaddingPx(retryInset)
            if (retryTop >= 0) {
                val maxAllowed = maxTrustedListTopInsetPx()
                val clamped = minOf(retryTop, maxAllowed).coerceAtLeast(0)
                recentsList.updatePadding(top = clamped)
                logRecentsListTopPadding("applyFinal(posted topPad=$clamped raw=$retryInset)", recentsList)
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
        // Search mode: [listTopInsetPx] uses max(geometry, nest_bouncy_content_padding_top)
        // so list padding clears the visible search row despite locked short AppBar height.
        applyFinalRecentsListTopPadding(conv)
        applyFinalRecentsListTopPadding(searchRv)
        if (binding.searchHolder.childCount > 0) {
            val holderTop = if (isSearchOpen) {
                mainSearchListTopPaddingPx().takeIf { it >= 0 }
                    ?: mainSearchMinListTopPaddingPx()
            } else {
                capRecentsListTopPaddingPx(listTopInsetPx(binding.conversationsList))
            }
            binding.searchHolder.getChildAt(0)!!.updatePadding(top = holderTop)
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
        findViewById<View>(R.id.main_appbar)?.post { apply() }
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
            findViewById<View>(R.id.main_appbar)?.post { apply() }
        }
    }

    override fun getActionModeToolbar(): CustomActionModeToolbar = binding.actionModeToolbar

    /**
     * After pause/resume the host toolbar layer can disagree with adapter selection state (orphaned
     * action bar or wrong height), which also confuses CollapsingToolbarLayout scrims. Reconcile —
     * but only when the visible state actually differs from the adapter's selection state, so the
     * AppBarLayout's user-driven collapse offset is preserved on a plain resume.
     */
    private fun syncMainMenuActionModeToolbarWithAdapter() {
        val inSelectionMode = isConversationListSelectionModeActive()
        val toolbarVisible = isActionModeToolbarVisible()
        when {
            inSelectionMode && !toolbarVisible -> showActionModeToolbar()
            !inSelectionMode && toolbarVisible -> hideActionModeToolbar()
        }
    }

    override fun showActionModeToolbar() {
        binding.mainAppbar.visibility = View.GONE
        binding.actionModeToolbar.visibility = View.VISIBLE
        binding.mVerticalSideFrameTop.visibility = View.GONE
        binding.conversationsFab.beGone()
        syncBlurTargetScrollingBehaviorForActionMode()
        syncBlurTargetTopMarginForAppBar()
        syncActionModeListTopPadding()
        binding.root.post {
            applyActionModeRippleToolbarForActiveAdapter()
            applyActionModeListBottomInset(true)
            refreshActionModeToolbarBlur()
            binding.mainCoordinator.requestLayout()
        }
    }

    override fun hideActionModeToolbar() {
        binding.actionModeToolbar.visibility = View.GONE
        binding.mainAppbar.visibility = View.VISIBLE
        binding.mVerticalSideFrameTop.visibility = View.VISIBLE
        binding.actionModeRippleToolbar.visibility = View.GONE
        binding.conversationsFab.beVisible()
        applyActionModeListBottomInset(false)
        isStartActionMode = false
        binding.mainAppbar.dismissCollapse()
        mainMenuLastAppBarVerticalOffset = 0
        applyToolbarSearchModeChrome(inSearch = isSearchOpen)
        applyToolbarExpandedFromConversationListScroll(animated = false)
        syncBlurTargetScrollingBehaviorForActionMode()
        syncBlurTargetTopMarginForAppBar()
        syncTopSideFrameHeight()
        applyLiveRecentsTopPaddingFromAppBarOffset()
        binding.mainCoordinator.requestLayout()
        binding.mainCoordinator.post {
            setupVerticalSideFrameBlur()
            applyLiveRecentsTopPaddingFromAppBarOffset()
            if (config.changeColourTopBar) scrollChange() else setMainMenuTransparentBackground()
            requestTopInsetSync()
        }
    }

    /** Keep list content aligned with visible top chrome (ManageQuickTextsActivity / txDial pattern). */
    private fun syncActionModeListTopPadding() {
        val topPad = if (isActionModeToolbarVisible()) {
            getCollapsedAppBarHeightPx()
        } else {
            mainMenuListTopInsetForCollapsePx()
        }
        binding.conversationsList.updatePadding(top = topPad)
        binding.searchResultsList.updatePadding(top = topPad)
    }

    private fun scrollOffsetForMainToolbarSync(): Int {
        val conv = binding.conversationsList.computeVerticalScrollOffset()
        val nested = binding.conversationsNestedScroll.scrollY
        val combined = maxOf(conv, nested)
        if (binding.searchHolder.visibility != View.VISIBLE) return combined
        return maxOf(combined, binding.searchResultsList.computeVerticalScrollOffset())
    }

    /** Conversation list only — when closing search the overlay can still be visible while fading. */
    private fun conversationListScrollOffsetOnly(): Int {
        val conv = binding.conversationsList.computeVerticalScrollOffset()
        val nested = binding.conversationsNestedScroll.scrollY
        return maxOf(conv, nested)
    }

    private fun applyToolbarExpandedFromConversationListScroll(animated: Boolean) {
        val menu = binding.mainAppbar
        val scroll = conversationListScrollOffsetOnly()
        val shouldExpand = scroll == 0 && mainMenuLastAppBarVerticalOffset >= 0
        menu.setExpanded(shouldExpand, animated)
        applyToolbarSearchModeChrome(inSearch = false)
    }

    /**
     * Bottom [MRippleToolBar] for [ConversationsAdapter] selection (txDial MainActivity pattern).
     */
    private fun applyActionModeRippleToolbarForActiveAdapter() {
        val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget) ?: return
        val adapter = binding.conversationsList.adapter as? ConversationsAdapter ?: return
        if (!adapter.isActionModeActive()) {
            binding.actionModeRippleToolbar.visibility = View.GONE
            return
        }
        val (items, _, tabEnabled) = adapter.buildConversationListRippleToolbar()
        if (items.isEmpty()) {
            binding.actionModeRippleToolbar.visibility = View.GONE
            return
        }
        binding.actionModeRippleToolbar.setTabs(this, items, blurTarget)
        binding.actionModeRippleToolbar.setOnClickedListener { index ->
            adapter.dispatchRippleToolbarAction(index)
        }
        binding.actionModeRippleToolbar.visibility = View.VISIBLE
        val hasSelection = adapter.getSelectedItems().isNotEmpty()
        for (i in 0 until items.size) {
            binding.actionModeRippleToolbar.setRippleTabEnabledWidthAlpha(i, hasSelection && tabEnabled.getOrElse(i) { true})
        }
    }

    fun refreshActionModeRippleToolbarIfNeeded() {
        if (isDestroyed || isFinishing) return
        applyActionModeRippleToolbarForActiveAdapter()
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

    protected open fun mainOverflowMenuRes(): Int = R.menu.menu_main

    /** txDial [MainActivity.setupOptionsMenu]: MAppBarLayout + MActionBar + MSearchView wiring. */
    protected open fun setupOptionsMenu() {
        val appBar = binding.mainAppbar
        val actionBar = appBar.getActionBarView()
        val searchView = appBar.getSearchView()
        val pillBlurTarget = binding.blurTarget

        clearMainAppBarScrims()
        appBar.setBackArrowDisabled()
        appBar.setTitle(getString(R.string.messages))

        appBar.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { layout, verticalOffset ->
            if (!isSearchOpen) return@OnOffsetChangedListener
            val totalScrollRange = layout.totalScrollRange
            if (totalScrollRange > 0 && verticalOffset > -totalScrollRange) {
                layout.setExpanded(false, false)
            }
        })

        actionBar?.bindBlurTarget(this, pillBlurTarget)
        actionBar?.setPosition("right")
        actionBar?.inflateMenu(R.menu.action_menu_main)
        actionBar?.setSearchView(searchView, R.id.search)
        // Do NOT use MActionBar.setPopupForMoreItem(): internal MPopup never gets setBlurTarget().
        actionBar?.setOnMenuItemClickListener { item -> handleToolbarMenuItemClick(item) }

        searchView?.let {
            it.bindBlurTarget(this, pillBlurTarget, 0)
            setupCustomSearch(it)
            prewarmSearchViewLayout(it)
        }
        binding.actionModeToolbar.bindBlurTarget(this, pillBlurTarget)
        applyToolbarSearchModeChrome(isSearchOpen)
    }

    private fun clearMainAppBarScrims() {
        val transparent = ColorDrawable(Color.TRANSPARENT)
        binding.mainAppbar.apply {
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
            setLiftOnScrollColor(null)
        }
        binding.actionModeToolbar.setBackgroundColor(Color.TRANSPARENT)
        for (i in 0 until binding.mainAppbar.childCount) {
            val child = binding.mainAppbar.getChildAt(i)
            if (child is CollapsingToolbarLayout) {
                child.background = null
                child.contentScrim = transparent
                child.statusBarScrim = transparent
            }
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

    private fun setupCustomSearch(searchView: MSearchView) {
        val existing = searchView.onStateListener
        searchView.setOnStateListener(object : MSearchView.OnSearchStateListener {
            override fun onState(state: Int) {
                when (state) {
                    MSearchView.SEARCH_START -> {
                        val menu = binding.mainAppbar
                        val range = menu.totalScrollRange
                        mainSearchOpenedFromExpanded =
                            range > 0 && -mainMenuLastAppBarVerticalOffset < range
                        mainSearchListTopInsetPx = -1
                        mainSearchAlignAttempts = 0
                        isSearchOpen = true
                        isSearchResumeInProgress = false
                        cancelMainSearchLayoutSync()
                        clearMainSearchChromeLayoutListener()
                        menu.forceKeepCollapse()
                        menu.setExpanded(false, false)
                        applyToolbarSearchModeChrome(inSearch = true)
                        binding.conversationsNestedScroll.scrollTo(0, 0)
                        menu.post {
                            existing?.onState(MSearchView.SEARCH_START)
                            dispatchCurrentToolbarSearchQuery()
                            scheduleMainSearchLayoutSync()
                        }
                    }
                    MSearchView.SEARCH_END -> {
                        isSearchOpen = false
                        searchQuery = ""
                        fadeOutSearch()
                    }
                }
                if (state != MSearchView.SEARCH_START) {
                    existing?.onState(state)
                }
                if (state == MSearchView.SEARCH_END) {
                    onToolbarSearchEnded()
                }
            }

            override fun onSearchTextChanged(newText: String?) {
                val text = newText.orEmpty()
                if (isToolbarSearchActive()) {
                    dispatchToolbarSearchQuery(text)
                }
                existing?.onSearchTextChanged(text)
            }
        })

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                val text = query.orEmpty()
                if (isToolbarSearchActive()) {
                    dispatchToolbarSearchQuery(text)
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                val text = newText.orEmpty()
                if (isToolbarSearchActive()) {
                    dispatchToolbarSearchQuery(text)
                }
                return true
            }
        })
    }

    private fun isToolbarSearchActive(): Boolean {
        if (isSearchOpen) return true
        return binding.mainAppbar.getSearchView()?.visibility == View.VISIBLE
    }

    private fun dispatchCurrentToolbarSearchQuery() {
        val searchView = binding.mainAppbar.getSearchView() ?: return
        val editText = searchView.findViewById<EditText>(com.android.common.R.id.et_search_text)
        dispatchToolbarSearchQuery(editText?.text?.toString().orEmpty())
    }

    private fun dispatchToolbarSearchQuery(text: String) {
        searchQuery = text
        if (text.isNotEmpty()) {
            if (binding.searchHolder.alpha < 1f) {
                binding.searchHolder.fadeIn()
            }
        } else {
            fadeOutSearch()
        }
        searchTextChanged(text)
    }

    private fun onToolbarSearchEnded() {
        isSearchResumeInProgress = false
        mainSearchListTopInsetPx = -1
        mainSearchOpenedFromExpanded = false
        mainSearchAlignAttempts = 0
        cancelMainSearchLayoutSync()
        clearMainSearchChromeLayoutListener()
        clearMainSearchScrollContentTranslation()
        binding.mainAppbar.dismissCollapse()
        mainMenuLastAppBarVerticalOffset = 0
        applyToolbarExpandedFromConversationListScroll(animated = false)
        applyToolbarSearchModeChrome(inSearch = false)
        applyLiveRecentsTopPaddingFromAppBarOffset()
        scheduleSyncMainMenuTopBlurGeometry()
        binding.mainAppbar.post { requestTopInsetSync() }
    }

    /** Hide large title while [MSearchView] is open. */
    private fun applyToolbarSearchModeChrome(inSearch: Boolean) {
        binding.mainAppbar.findViewById<View>(com.android.common.R.id.m_app_bar_title)?.visibility =
            if (inSearch) View.INVISIBLE else View.VISIBLE
    }

    private fun openMSearchView() {
        val actionBar = binding.mainAppbar.getActionBarView() ?: return
        val searchView = binding.mainAppbar.getSearchView() ?: return
        if (searchView.visibility == View.VISIBLE) return
        actionBar.visibility = View.GONE
        searchView.visibility = View.VISIBLE
        searchView.startSearch()
    }

    private fun closeMSearchView() {
        val searchView = binding.mainAppbar.getSearchView() ?: return
        if (searchView.visibility != View.VISIBLE) {
            isSearchOpen = false
            searchQuery = ""
            return
        }
        searchView.searchEnd()
    }

    private fun isMSearchOpen(): Boolean =
        binding.mainAppbar.getSearchView()?.visibility == View.VISIBLE

    /**
     * Shows [mainOverflowMenuRes] via [showMPopupMenu] (txDial [showMoreActionsPopup]).
     * Do not use [com.android.common.view.MActionBar.setPopupForMoreItem].
     */
    private fun showMoreActionsPopup() {
        val actionBar = binding.mainAppbar.getActionBarView() ?: return
        val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget) ?: return
        val menu = MenuBuilder(this)
        menuInflater.inflate(mainOverflowMenuRes(), menu)
        showMPopupMenu(
            context = this,
            anchor = actionBar,
            menu = menu,
            gravity = Gravity.END,
            blurTarget = blurTarget,
            listener = { item -> handleToolbarMenuItemClick(item) },
        )
    }

    private fun handleToolbarMenuItemClick(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.search -> return false
            R.id.more -> {
                showMoreActionsPopup()
                return true
            }
            R.id.select_conversations -> {
                if (isSearchOpen || isMSearchOpen()) {
                    closeMSearchView()
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
                if (isSearchOpen || isMSearchOpen()) closeMSearchView()
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
//            R.id.private_space -> {
//                hideKeyboard()
//                if (isSearchOpen || isMSearchOpen()) closeMSearchView()
//                launchPrivateSpace()
//            }
//            R.id.sim_card_message -> {}
            R.id.settings -> launchSettings()
//            R.id.about -> launchAbout()
            else -> return false
        }
        return true
    }

    protected open fun refreshMenuItems() {
        binding.mainAppbar.setTitle(getString(R.string.messages))
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
            runOnUiThread {
                getOrCreateConversationsAdapter().removeConversationsForThreadIds(pendingThreadIds)
                isStartActionMode = false
                notifyDatasetChanged()
            }
            ensureBackgroundThread {
                updateConversationPins(pendingThreadIds, cipher)
            }
        } else {
            Handler(Looper.getMainLooper()).post {
                if (!isFinishing && !isDestroyed) {
                    if (setConversationPinScope(cipher)) {
                        initMessenger()
                        refreshMenuItems()
                    } else {
                        toast(com.goodwy.commons.R.string.unknown_error_occurred)
                    }
                }
            }
        }
    }

    protected open fun closeSecureBox() {
        setConversationPinScope(0)
        initMessenger()
        refreshMenuItems()
    }

    protected open fun handleTwoFingerSwipeDown() {
        launchPrivateSpace()
    }

    protected fun launchPrivateSpace() {
        isLaunchingInternalConversationActivity = true
        startActivity(
            Intent(this, SecureMainActivity::class.java).apply {
                putExtra(SecureMainActivity.EXTRA_CIPHER_NUMBER, 1)
                putExtra(SecureMainActivity.EXTRA_LAUNCHED_FROM_MAIN_ACTIVITY, true)
            },
        )
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
                        handleTwoFingerSwipeDown()
                    }
                }
            }
        )
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { twoFingerGestureDetector.onTouchEvent(it) }
        return super.dispatchTouchEvent(ev)
    }

    protected fun launchSecretBoxForUnlock() {
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
                    openMSearchView()
                    binding.mainAppbar.getSearchView()
                        ?.findViewById<EditText>(com.android.common.R.id.et_search_text)
                        ?.setText(speechToText)
                    dispatchToolbarSearchQuery(speechToText)
                }
            }
        }
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun syncTopSideFrameHeight() {
        if (isActionModeToolbarVisible()) {
            return
        }
        val collapsed = getCollapsedAppBarHeightPx()
        val feather = resources.getDimensionPixelSize(R.dimen.tx_my_search_menu_top_blur_feather)
        binding.mVerticalSideFrameTop.updateLayoutParams<ViewGroup.LayoutParams> {
            height = collapsed + maxOf(0, feather)
        }
        binding.blurTarget.invalidate()
        if (!isSearchResumeInProgress) {
            syncBlurTargetTopMarginForAppBar()
            syncRecentsTopInsetWithToolbar()
        }
    }

    /**
     * [AppBarLayout.ScrollingViewBehavior] pins [R.id.blur_target] below [R.id.main_appbar] even when
     * the bar is [View.GONE], leaving a large empty gap. Drop the behavior during action mode (txDial dialpad pattern).
     */
    private fun syncBlurTargetScrollingBehaviorForActionMode() {
        val inActionMode = isActionModeToolbarVisible()
        val lp = binding.blurTarget.layoutParams as? CoordinatorLayout.LayoutParams ?: return
        if (inActionMode) {
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
     * [R.id.blurTarget] negative top margin must be cleared whenever the app bar is hidden for action mode;
     * otherwise MVSideFrame shows a dark strip at the top (txDial [MainActivity.syncBlurTargetTopMarginForAppBar]).
     */
    private fun syncBlurTargetTopMarginForAppBar() {
        val targetTopMargin = when {
            isSearchOpen -> {
                val insetPx = mainSearchListTopPaddingPx().takeIf { it >= 0 }
                    ?: mainSearchMinListTopPaddingPx()
                -insetPx
            }
            isActionModeToolbarVisible() -> 0
            binding.mainAppbar.visibility == View.VISIBLE -> -mainMenuListTopInsetForCollapsePx()
            else -> 0
        }
        binding.blurTarget.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            if (topMargin != targetTopMargin) {
                topMargin = targetTopMargin
            }
        }
    }

    private fun syncBlurTargetTopMargin() {
        syncBlurTargetTopMarginForAppBar()
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
            recyclerView ?: return
            recyclerView.onOverscrollTranslationChanged = { translationY ->
                binding.mainAppbar.translationY = translationY * mainMenuOverscrollFactor
            }
            // Belt-and-suspenders: if list snap-back completes without a final callback, reset the app bar.
            recyclerView.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        recyclerView.post {
                            if (recyclerView.translationY == 0f) {
                                binding.mainAppbar.translationY = 0f
                            }
                        }
                    }
                }
                false
            }
        }

        bindOverscrollSync(binding.conversationsList as? MyRecyclerView)
        bindOverscrollSync(binding.searchResultsList as? MyRecyclerView)
    }

    private fun clearMainMenuSpringSync() {
        (binding.conversationsList as? MyRecyclerView)?.apply {
            onOverscrollTranslationChanged = null
            setOnTouchListener(null)
        }
        (binding.searchResultsList as? MyRecyclerView)?.apply {
            onOverscrollTranslationChanged = null
            setOnTouchListener(null)
        }
        binding.mainAppbar.translationY = 0f
    }

    private var mainLastScrollOffsetForStatusBar = -1

    private fun onMainListScrolled(scrollOffset: Int) {
        setMainMenuTransparentBackground()
        binding.mVerticalSideFrameTop.update()
        if (!config.changeColourTopBar) return
        if (scrollOffset == 0 || mainLastScrollOffsetForStatusBar == 0) {
            val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
            val color = if (scrollOffset > 0) {
                getColoredMaterialStatusBarColor()
            } else {
                getRequiredStatusBarColor(useSurfaceColor)
            }
            window.setSystemBarsAppearance(color)
        }
        mainLastScrollOffsetForStatusBar = scrollOffset
    }

    private fun scrollChange() {
        scrollingView = binding.conversationsList
        onMainListScrolled(scrollOffsetForMainToolbarSync())
    }

    private fun makeSystemBarsToTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val navHeight = nav.bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
//            val dp5 = (5 * resources.displayMetrics.density).toInt()
//            binding.mVerticalSideFrameBottom.layoutParams =
//                binding.mVerticalSideFrameBottom.layoutParams.apply { height = navHeight + dp5 }
            val bottomOffset = (0 * resources.displayMetrics.density).toInt()
            val fabLp = binding.conversationsFab.layoutParams as? ViewGroup.MarginLayoutParams
            if (fabLp != null) {
                // Lists are not in padBottomImeAndSystem (txDial recents pattern); lift FAB above IME only.
                fabLp.bottomMargin = if (ime.bottom > 0) ime.bottom + bottomOffset else bottomOffset
                fabLp.rightMargin = (32 * resources.displayMetrics.density).toInt()
                binding.conversationsFab.layoutParams = fabLp
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

    /** Same surface / background logic as list chrome — single source for pre-content window paint and views. */
    private fun mainContentBackgroundColor(): Int {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        return if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
    }

    /**
     * Runs after [super.onCreate] and **before** [setContentView]: replaces the dark Material3 Dark
     * `windowBackground` so transparent system bars do not reveal it before the conversation list loads.
     */
    private fun paintMainScreenWindowBeforeContentView() {
        val backgroundColor = mainContentBackgroundColor()
        window.setBackgroundDrawable(ColorDrawable(backgroundColor))
        window.decorView.setBackgroundColor(backgroundColor)
    }

    /** Restore transparent app bar so blur/glass shows (txDial MainActivity pattern). */
    private fun setMainMenuTransparentBackground() {
        clearMainAppBarScrims()
    }

    /** Root, blur host, lists, and top app bar chrome — used from [onCreate] before first draw and in [onResume]. */
    private fun applyMainScreenBackgroundAndTopChrome() {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        window.setSystemBarsAppearance(getStartRequiredStatusBarColor())
        clearMainAppBarScrims()

        val backgroundColor = mainContentBackgroundColor()
        window.setBackgroundDrawable(ColorDrawable(backgroundColor))
        window.decorView.setBackgroundColor(backgroundColor)
        binding.root.setBackgroundColor(backgroundColor)
        binding.mainBlurTarget.setBackgroundColor(backgroundColor)
//        findViewById<CoordinatorLayout>(R.id.main_coordinator)?.setBackgroundColor(backgroundColor)
        binding.searchHolder.setBackgroundColor(backgroundColor)
        binding.conversationsNestedScroll.setBackgroundColor(backgroundColor)
        binding.conversationsList.setBackgroundColor(backgroundColor)
        binding.searchResultsList.setBackgroundColor(backgroundColor)
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

    /**
     * Fast path when only local Room changed (e.g. new draft row). Skips Telephony [getConversations] merge.
     */
    private fun reloadConversationsFromLocalDatabase() {
        val loadSeq = conversationsLoadSeq.incrementAndGet()
        ensureBackgroundThread {
            if (loadSeq != conversationsLoadSeq.get()) {
                return@ensureBackgroundThread
            }
            val conversations = try {
                conversationsDB.getNonArchived().toMutableList() as ArrayList<Conversation>
            } catch (_: Exception) {
                ArrayList()
            }
            filterHiddenBlockedConversationsIfNeeded(conversations)
            applyNonScheduledMessageCounts(conversations)
            runOnUiThread {
                if (loadSeq != conversationsLoadSeq.get()) {
                    return@runOnUiThread
                }
                setupConversations(conversations, cached = false)
            }
        }
    }

    private fun initMessenger() {
        val loadSeq = conversationsLoadSeq.incrementAndGet()
        getCachedConversations(loadSeq)
//        binding.noConversationsPlaceholder2.setOnClickListener {
//            launchNewConversation()
//        }

        binding.conversationsFab.setOnClickListener {
            launchNewConversation()
        }
    }

    /**
     * When blocked threads are hidden, keep them out of the recents list even if Room or the
     * telephony merge has not yet dropped the row (same rules as [getConversations]).
     */
    private fun filterHiddenBlockedConversationsIfNeeded(conversations: ArrayList<Conversation>) {
        if (config.showBlockedNumbers) {
            return
        }
        val blockedNumbers = getBlockedNumbers()
        conversations.removeAll { conv ->
            if (conv.isGroupConversation) {
                getThreadRecipientPhoneNumbers(conv.threadId).any { isNumberBlocked(it, blockedNumbers) }
            } else {
                isNumberBlocked(conv.phoneNumber, blockedNumbers)
            }
        }
    }

    private fun getCachedConversations(loadSeq: Int) {
        ensureBackgroundThread {
            if (loadSeq != conversationsLoadSeq.get()) {
                return@ensureBackgroundThread
            }
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
            if (shouldUseCached) {
                filterHiddenBlockedConversationsIfNeeded(conversations)
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
                // Message counts in one query; lastMessageType already set from getNonArchived/getAllArchived snippet query
                applyNonScheduledMessageCounts(conversations)
            }

            runOnUiThread {
                if (loadSeq != conversationsLoadSeq.get()) {
                    return@runOnUiThread
                }
                val currentPin = config.selectedConversationPin
                val skipSecureEmptyCache =
                    currentPin > 0 && lastMessengerAppliedPin == currentPin
                if (shouldUseCached) {
                    setupConversations(conversations, cached = true)
                } else if (!skipSecureEmptyCache) {
                    // First time entering PIN scope (or PIN changed): empty list + progress is correct.
                    // While already showing that scope, keep the visible list until [getNewConversations] finishes.
                    setupConversations(conversations, cached = true)
                }
                getNewConversations((conversations + archived).toMutableList() as ArrayList<Conversation>, loadSeq)
            }
            if (shouldUseCached) {
                conversations.forEach {
                    clearExpiredScheduledMessages(it.threadId)
                }
            }
        }
    }

    private fun getNewConversations(cachedConversations: ArrayList<Conversation>, loadSeq: Int) {
        val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ensureBackgroundThread {
            if (loadSeq != conversationsLoadSeq.get()) {
                return@ensureBackgroundThread
            }
            val privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)
            // Decide mode before getConversations(): it calls syncConversationPinScope(), which can
            // update config from the provider and would otherwise mis-route the non-secure branch.
            val isSecureMode = config.selectedConversationPin > 0
            val conversations = getConversations(privateContacts = privateContacts)

            if (isSecureMode) {
                // In secure mode, show only provider-filtered conversations for selected PIN.
                applyNonScheduledMessageCounts(conversations)
                conversations.forEach { conversation ->
                    try {
                        conversation.lastMessageType = messagesDB.getLastMessageType(conversation.threadId)
                    } catch (_: Exception) {
                        conversation.lastMessageType = null
                    }
                }
                runOnUiThread {
                    if (loadSeq != conversationsLoadSeq.get()) {
                        return@runOnUiThread
                    }
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
                        clonedConversation.title.isBlank() -> phoneNumberWithoutCountryCode.ifBlank { clonedConversation.phoneNumber }
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
            // lastMessageType already set from getNonArchived snippet query
            applyNonScheduledMessageCounts(allConversations)
            filterHiddenBlockedConversationsIfNeeded(allConversations)
            runOnUiThread {
                if (loadSeq != conversationsLoadSeq.get()) {
                    return@runOnUiThread
                }
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
        lastMessengerAppliedPin = config.selectedConversationPin
    }

    private fun showOrHideProgress(show: Boolean) {
        if (show) {
            binding.conversationsProgressBar.show()
        } else {
            binding.conversationsProgressBar.hide()
        }
    }

    @SuppressLint("ResourceAsColor")
    protected open fun showOrHidePlaceholder(show: Boolean) {
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

        fun startThreadActivity() {
            Intent(this, ThreadActivity::class.java).apply {
                putExtra(THREAD_ID, conversation.threadId)
                putExtra(THREAD_TITLE, conversation.title)
                putExtra(THREAD_NUMBER, conversation.phoneNumber)
                putExtra(THREAD_URI, conversation.photoUri)
                if (config.selectedConversationPin > 0) {
                    putExtra(THREAD_OPENED_FROM_SECURE_CONVERSATION_LIST, true)
                }
                isLaunchingInternalConversationActivity = true
                startActivity(this)
            }
        }

        // When the list already has messages, route to NewConversation vs Thread on a background check
        // but start the thread immediately after that check when we do open it.
        if (conversation.messageCount > 0) {
            startThreadActivity()
            return
        }

        ensureBackgroundThread {
            val telephonyMessageCount = getThreadTelephonyMessageCount(conversation.threadId)
            val openNewComposeForDraft =
                hasMeaningfulLocalDraft(conversation.threadId) && telephonyMessageCount == 0
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
                        startThreadActivity()
                    }
                } else {
                    startThreadActivity()
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
        if (isSearchAlwaysShow && !isSearchOpen && !forceUpdate) {
            return
        }

        lastSearchedText = text
        binding.searchPlaceholder2.beGoneIf(text.isNotEmpty())

        if (text.isNotEmpty()) {
            ensureBackgroundThread {
                val searchQuery = "%$text%"
                val messages = messagesDB.getMessagesWithText(searchQuery)
                val conversations = conversationsDB.getConversationsWithText(searchQuery)
                applyNonScheduledMessageCounts(conversations)
                conversations.forEach { conversation ->
                    try {
                        conversation.lastMessageType = messagesDB.getLastMessageType(conversation.threadId)
                    } catch (_: Exception) {
                        conversation.lastMessageType = null
                    }
                }
                if (text == lastSearchedText) {
                    showSearchResults(messages, conversations, text)
                }
            }
        } else {
            binding.searchPlaceholder.beVisible()
            binding.searchPlaceholderImg.beVisible()
            binding.searchResultsList.beGone()
        }
        if (isSearchAlwaysShow) {
            binding.mainAppbar.getSearchView()
                ?.findViewById<EditText>(com.android.common.R.id.et_search_text)
                ?.setText("")
        }
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

            val phoneNumber = message.getSender()?.phoneNumbers?.firstOrNull()?.normalizedNumber
                ?: message.senderPhoneNumber.takeIf { it.isNotEmpty() }
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
            binding.searchPlaceholderImg.beVisibleIf(flatResults.isEmpty())
            // Re-sync top padding whenever results become visible so the search list aligns
            // with convList regardless of when animateTopOffsets last ran.
            if (flatResults.isNotEmpty()) {
                binding.searchResultsList.post { requestTopInsetSync() }
            }

            val currAdapter = binding.searchResultsList.adapter
            if (currAdapter == null) {
                SearchResultsAdapter(this, searchListItems, binding.searchResultsList, searchedText) {
                hideKeyboard()
                    Intent(this, ThreadActivity::class.java).apply {
                        putExtra(THREAD_ID, (it as SearchResult).threadId)
                        putExtra(THREAD_TITLE, it.title)
                        putExtra(THREAD_NUMBER, it.phoneNumber)
                        putExtra(THREAD_URI, it.photoUri)
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
    fun refreshConversations(event: Events.RefreshConversations) {
        if (event.localListRefreshOnly && config.selectedConversationPin == 0) {
            reloadConversationsFromLocalDatabase()
        } else {
            initMessenger()
        }
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

    /**
     * Title used by the conversation long-press actions dialog.
     *
     * For group threads, match [NewConversationActivity] formatting:
     * - 2 recipients: "First user and 1 other"
     * - n recipients (n > 2): "First user and (n-1) others"
     *
     * For 1:1 threads, keep the existing title behavior.
     */
    fun getConversationActionsDialogTitle(conversation: Conversation): String {
        if (!conversation.isGroupConversation) {
            return conversation.title
        }

        val numbers = getThreadRecipientPhoneNumbers(conversation.threadId)
        if (numbers.size <= 1) {
            return conversation.title
        }

        val firstNumber = numbers.firstOrNull().orEmpty()
        val firstDisplay = getNameAndPhotoFromPhoneNumber(firstNumber).name
            .takeIf { it.isNotEmpty() }
            ?: firstNumber

        val othersCount = numbers.size - 1
        val othersPhrase = resources.getQuantityString(R.plurals.and_other_contacts, othersCount, othersCount)
        return getString(R.string.thread_title_multiple_format, firstDisplay, othersPhrase).trim()
    }
}
