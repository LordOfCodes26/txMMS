package com.android.mms.activities

import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.provider.Telephony.Sms.MESSAGE_TYPE_QUEUED
import android.provider.Telephony.Sms.STATUS_NONE
import android.speech.RecognizerIntent
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.format.DateUtils.FORMAT_NO_YEAR
import android.text.format.DateUtils.FORMAT_SHOW_DATE
import android.text.format.DateUtils.FORMAT_SHOW_TIME
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.os.Handler
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.android.common.dialogs.MConfirmDialog
import com.android.common.view.MDialog
import com.goodwy.commons.dialogs.OptionListDialog
import com.goodwy.commons.dialogs.OptionListItem
import com.goodwy.commons.dialogs.PermissionRequiredDialog
import com.goodwy.commons.dialogs.RadioGroupDialog
import com.goodwy.commons.dialogs.RadioGroupIconDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.interfaces.ActionModeToolbarHost
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.PhoneNumber
import com.goodwy.commons.models.RadioItem
import com.goodwy.commons.models.SimpleContact
import com.android.mms.BuildConfig
import com.android.mms.R
import com.android.mms.adapters.ThreadAdapter
import com.android.mms.helpers.MessageHolderHelper
import com.android.mms.databinding.ActivityThreadBinding
import com.android.mms.dialogs.InvalidNumberDialog
import com.android.mms.dialogs.RenameConversationDialog
import com.android.mms.dialogs.showScheduleDateTimePicker
import com.android.mms.extensions.*
import com.android.mms.extensions.getDisplayNumberWithoutCountryCode
import com.android.mms.dialogs.SelectSIMDialog
import com.android.mms.dialogs.SelectSimDialogAnchorPlacement
import com.android.mms.helpers.*
import com.android.mms.messaging.*
import com.android.mms.models.*
import com.android.mms.models.ThreadItem.ThreadDateTime
import com.goodwy.commons.views.MyRecyclerView
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.joda.time.DateTime
import eightbitlab.com.blurview.BlurTarget
import java.io.File
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.set

class ThreadActivity : SimpleActivity(), ActionModeToolbarHost {
    private val debugTag = "ThreadActivityFee"
    private var threadId = 0L
    private var currentSIMCardIndex = 0
    private var isActivityVisible = false
    private var refreshedSinceSent = false
    private var threadItems = ArrayList<ThreadItem>()
    private var bus: EventBus? = null
    private var conversation: Conversation? = null
    private var participants = ArrayList<SimpleContact>()
    private var privateContacts = ArrayList<SimpleContact>()
    private var messages = ArrayList<Message>()
    private val availableSIMCards = ArrayList<SIMCard>()
    private var pendingAttachmentsToSave: List<Attachment>? = null
    private var capturedImageUri: Uri? = null
    private var loadingOlderMessages = false
    private var allMessagesFetched = false
    private var isJumpingToMessage = false
    private var isRecycleBin = false
    private var isLaunchedFromShortcut = false
    /** Main list was in PIN-scoped (secure) mode when this thread was opened; leave that scope when the whole app returns from the background. */
    private var openedFromSecureConversationList = false
    /** Opened from blocked-messages list: load provider SMS/MMS from blocked numbers even when they are hidden elsewhere. */
    private var showBlockedMessagesInThread = false
    private var appEnteredBackgroundWhileOnThisThread = false

    private val secureConversationListProcessObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            if (openedFromSecureConversationList) {
                appEnteredBackgroundWhileOnThisThread = true
            }
        }

        override fun onStart(owner: LifecycleOwner) {
            if (!openedFromSecureConversationList) return
            if (!appEnteredBackgroundWhileOnThisThread) return
            appEnteredBackgroundWhileOnThisThread = false
            if (config.selectedConversationPin <= 0 || isFinishing) return
            openMainInNormalModeAfterAppResumeFromSecureThread()
        }
    }

    private var isScheduledMessage: Boolean = false
    private var messageToResend: Long? = null
    private var scheduledMessage: Message? = null
    private var scheduledDateTime: DateTime = DateTime.now().plusMinutes(5)

    private var isAttachmentPickerVisible = false

    /** When true, [threadTypeMessage] focus loss is from opening the attachment picker; skip inset sync. */
    private var ignoreInputFocusLossInsetSync = false
    /**
     * Stabilizes compose-bar bottom padding while swapping IME and the in-layout attachment picker so the
     * message list does not jump (IME animates out before the picker is shown, or picker hides before IME insets apply).
     */
    private enum class ComposeBarBottomInsetLatch {
        NONE,
        KEYBOARD_TO_ATTACHMENT_PICKER,
        ATTACHMENT_PICKER_TO_KEYBOARD,
    }

    private var composeBarBottomInsetLatch = ComposeBarBottomInsetLatch.NONE
    private var composeBarBottomInsetLatchPx = 0
    private var wasKeyboardVisible = false
    /** Set while the IME was shown this session; cleared when the keyboard is dismissed (not when pausing). */
    private var hadKeyboardLayoutWhenLeaving = false
    /** True while IME is visible; used so [applyThreadMessagesListWindowInsets] only scrolls on IME open, not on every inset re-dispatch (e.g. resume). */
    private var wasImeVisibleForThreadListInsets = false
    /** Keyboard was up on pause: keep exact list/compose padding until resume layout stabilizes (no inset recompute). */
    private var freezeThreadListLayoutOnResume = false
    private var frozenThreadListPadding: IntArray? = null
    private var frozenComposeBarInsetBottom = 0
    private var threadListLayoutFreezePreDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    /** Holds list bottom padding while swapping IME and the attachment picker to prevent a visible flash. */
    private var overlayTransitionListPadding: IntArray? = null
    /** Set when + is tapped with the keyboard up; picker opens after IME insets report hidden. */
    private var pendingAttachmentPickerAfterKeyboardHide = false
    private var isSpeechToTextAvailable = false
    private var expandedMessageFragment: com.android.mms.fragments.ExpandedMessageFragment? = null
    private var messageHolderHelper: MessageHolderHelper? = null
    private var attachmentIntentLauncher: AttachmentIntentLauncher? = null
    private var isFeeInfoReceiverRegistered = false
    private val feeInfoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_FEE_INFO_SET) {
                updateAvailableMessageCountForCurrentSim()
            }
        }
    }

    private val binding by viewBinding(ActivityThreadBinding::inflate)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        finish()
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Theme.Material3.Dark windowBackground is dark; paint window + decor before inflation so edge-to-edge does not flash behind transparent bars.
        paintThreadWindowBeforeContentView()
        setContentView(binding.root)
        if (config.changeColourTopBar) {
            scrollingView = binding.threadMessagesList
        }
        initTheme()
        initThreadAppBar()
        setupOptionsMenu()
        refreshMenuItems()

        makeSystemBarsToTransparent()
        // Compose-bar IME padding is handled here (applyComposeBarImePaddingFromInsets + root insets) so
        // EdgeToEdge does not reset it on resume before the message list padding is frozen.
        setupEdgeToEdge(
            padTopSystem = listOf(binding.topDetailsCompact.root),
            animateIme = false
        )
        setupMessagingEdgeToEdge()
//        setupMaterialScrollListener(null, binding.threadAppbar)

        val extras = intent.extras
        if (extras == null) {
            toast(com.goodwy.commons.R.string.unknown_error_occurred)
            finish()
            return
        }

        isSpeechToTextAvailable = if (config.useSpeechToText) isSpeechToTextAvailable() else false

        threadId = intent.getLongExtra(THREAD_ID, 0L)
        SendMessageCountdownStore.setDefaultFinishListener { pending ->
            PendingSendCountdownFinisher.finish(applicationContext, pending)
        }
        applyInitialThreadHeaderFromIntent()
        isRecycleBin = intent.getBooleanExtra(IS_RECYCLE_BIN, false)
        isLaunchedFromShortcut = intent.getBooleanExtra(IS_LAUNCHED_FROM_SHORTCUT, false)
        openedFromSecureConversationList =
            intent.getBooleanExtra(THREAD_OPENED_FROM_SECURE_CONVERSATION_LIST, false)
        showBlockedMessagesInThread = intent.getBooleanExtra(THREAD_SHOW_BLOCKED_MESSAGES, false)
        if (openedFromSecureConversationList) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(secureConversationListProcessObserver)
        }

        bus = EventBus.getDefault()
        bus!!.register(this)

        ensureDefaultBubbleType()
        // While [setupCachedMessages] loads many rows from disk, the list is still empty: keep blur
        // host + window filled so MVSideFrame does not flash a dark band over the transparent app bar.
        applyThreadListBackgroundColors()
        applyThreadTopBarChrome()
        loadConversation()
        maybeSetupRecycleBinView()
    }

    override fun onResume() {
        applyFrozenThreadListLayoutOnResumeIfNeeded()
        super.onResume()
        if (!isThreadMessageSelectionModeActive()) {
            if (config.threadTopStyle == THREAD_TOP_LARGE) binding.topDetailsCompact.root.beGone()
            else binding.topDetailsLarge.beGone()
        }

        applyThreadListBackgroundColors()
        applyThreadTopBarChrome()

        isActivityVisible = true
        setVisibleThreadId(threadId)

        notificationManager.cancel(threadId.hashCode())

        ensureBackgroundThread {
            val newConv = conversationsDB.getConversationWithThreadId(threadId)
            if (newConv != null) {
                conversation = newConv
                runOnUiThread {
                    setupThreadTitle()
                }
            }

            runOnUiThread {
                if (SendMessageCountdownStore.isActive(threadId)) {
                    restorePendingSendCountdownIfNeeded()
                    return@runOnUiThread
                }
                ensureBackgroundThread {
                    val freshDraft = getSmsDraftEntity(threadId)
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        if (freshDraft != null) {
                            applyComposeDraftIfStillValid(freshDraft)
                        }
                    }
                }
            }

            markThreadMessagesRead(threadId)
        }

        val bottomBarColor = getBottomBarColor()
//        binding.shortCodeHolder.root.setBackgroundColor(bottomBarColor)
//        binding.messageHolder.attachmentPickerHolder.setBackgroundColor(
//            ResourcesCompat.getColor(resources, com.goodwy.commons.R.color.md_grey_100, theme)
//        )

        updateAvailableMessageCountForCurrentSim()

        binding.root.post { restorePendingSendCountdownIfNeeded() }

        refreshSideFrameBlurAndInsets()
    }

    /** BlurView + MVSideFrame can stop updating after another activity was shown; re-apply insets and re-bind. */
    private fun refreshSideFrameBlurAndInsets() {
        binding.root.post {
            if (!freezeThreadListLayoutOnResume) {
                ViewCompat.requestApplyInsets(binding.root)
            }
            binding.mVerticalSideFrameTop.bindBlurTarget(binding.mainBlurTarget)
            binding.mVerticalSideFrameBottom.bindBlurTarget(binding.mainBlurTarget)
            syncThreadTopBlurStripGeometry()
            refreshActionModeToolbarBlur()
        }
    }

    /** Selection-mode back / select-all pills — same [mainBlurTarget] as [threadToolbar]. */
    private fun refreshActionModeToolbarBlur() {
        val adapter = binding.threadMessagesList.adapter as? ThreadAdapter ?: return
        if (!adapter.isActionModeActive()) return
        binding.mainBlurTarget.invalidate()
        binding.threadActionModeToolbar.bindBlurTarget(this, binding.mainBlurTarget)
    }

    /**
     * Same idea as [com.android.mms.extensions.postSyncMySearchMenuToolbarGeometry] / MainActivity blur
     * sync: [mainBlurTarget] needs a negative top margin and the top [MVSideFrame] height must match the
     * app bar so BlurView samples the list region under transparent toolbar chrome correctly.
     */
    private fun syncThreadTopBlurStripGeometry() {
        binding.threadAppbar.post {
            val appBar = binding.threadAppbar
            val h = appBar.height.takeIf { it > 0 } ?: appBar.measuredHeight.takeIf { it > 0 } ?: return@post
            val feather = resources.getDimensionPixelSize(R.dimen.tx_my_search_menu_top_blur_feather)
            binding.mVerticalSideFrameTop.updateLayoutParams<ViewGroup.LayoutParams> {
                val newHeight = h + maxOf(0, feather)
                if (height != newHeight) height = newHeight
            }
            syncBlurTargetTopMarginForMenu(binding.mainBlurTarget, h)
            binding.mainBlurTarget.invalidate()
            refreshActionModeToolbarBlur()
        }
    }

    override fun onStart() {
        super.onStart()
        registerFeeInfoReceiverIfNeeded()
    }

    override fun onPause() {
        captureThreadLayoutForKeyboardResume()
        super.onPause()
        composeBarBottomInsetLatch = ComposeBarBottomInsetLatch.NONE
        messageHolderHelper?.pauseCountdownUi()
        messageHolderHelper?.stopAttachmentAudio()
        // Persist first, then notify: save runs on a background thread; posting before it completes
        // leaves MainActivity's list without the new draft until the next resume.
        saveDraftMessage(notifyConversationsAfter = true, showDraftSavedToast = isFinishing)
        isActivityVisible = false
        clearVisibleThreadIdIfMatches(threadId)
    }

    override fun onStop() {
        super.onStop()
        unregisterFeeInfoReceiverIfNeeded()
        saveDraftMessage()
    }

//    override fun onBackPressedCompat(): Boolean {
//        isAttachmentPickerVisible = false
//        return if (binding.messageHolder.attachmentPickerHolder.isVisible()) {
//            hideAttachmentPicker()
//            true
//        } else {
//            false
//        }
//    }

    override fun onDestroy() {
        messageHolderHelper?.releaseSendMessageCountdownStore()
        clearVisibleThreadIdIfMatches(threadId)
        releaseThreadListLayoutFreeze(recalculatePadding = false)
        unregisterFeeInfoReceiverIfNeeded()
        if (openedFromSecureConversationList) {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(secureConversationListProcessObserver)
        }
        super.onDestroy()
        bus?.unregister(this)
    }

    /**
     * After the whole app was in the background, leave PIN-scoped mode and show [MainActivity]
     * (normal list) instead of staying on this thread.
     */
    private fun openMainInNormalModeAfterAppResumeFromSecureThread() {
        if (isFinishing) return
        if (config.selectedConversationPin > 0) {
            setConversationPinScope(0)
        }
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
        finish()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerFeeInfoReceiverIfNeeded() {
        if (isFeeInfoReceiverRegistered) return
        runCatching {
            registerReceiver(feeInfoReceiver, IntentFilter(ACTION_FEE_INFO_SET))
            isFeeInfoReceiverRegistered = true
        }
    }

    private fun unregisterFeeInfoReceiverIfNeeded() {
        if (!isFeeInfoReceiverRegistered) return
        runCatching {
            unregisterReceiver(feeInfoReceiver)
        }
        isFeeInfoReceiverRegistered = false
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    /**
     * Applies app bar background and behavior like [com.goodwy.commons.views.MySearchMenu]:
     * - Sets appBarBackground drawable programmatically (AppBarLayout may not apply it from XML).
     * - Zero elevation and disables lift-on-scroll so the bar stays consistent.
     */
    private fun initThreadAppBar() {
        val appBar = binding.threadAppbar
        // appBar.setBackgroundResource(com.android.common.R.drawable.bg_cmn_appbar_up)
        appBar.elevation = 0f
        ViewCompat.setElevation(appBar, 0f)
        appBar.stateListAnimator = null
        appBar.isLiftOnScroll = false
        appBar.isLifted = false
        binding.collapsingToolbar.apply {
            statusBarScrim = ColorDrawable(Color.TRANSPARENT)
            contentScrim = ColorDrawable(Color.TRANSPARENT)
            scrimVisibleHeightTrigger = Int.MAX_VALUE
            setScrimsShown(false, false)
        }
    }

    /** Status bar + toolbar chrome; safe to call before messages have bound (heavy DB load). */
    private fun applyThreadTopBarChrome() {
        val topBarColor = getStartRequiredStatusBarColor()
        updateTopBarColors(binding.threadToolbar, topBarColor)
        val stateListAnimator = StateListAnimator()
        stateListAnimator.addState(
            IntArray(0),
            ObjectAnimator.ofFloat(binding.threadAppbar, "elevation", 0.0f),
        )
        binding.threadAppbar.stateListAnimator = stateListAnimator
        val toolbar = binding.threadToolbar
        val contrastColor = topBarColor.getContrastColor()
        val itemColor = if (baseConfig.topAppBarColorIcon) getProperPrimaryColor() else contrastColor
        setupThreadToolbarNavigation(color = itemColor)
        val overflowIconRes = getOverflowIcon(baseConfig.overflowIcon)
        toolbar.overflowIcon = resources.getColoredDrawableWithColor(this, overflowIconRes, itemColor)
        updateMenuItemIconColors()
    }

    private fun isDarkTheme(): Boolean {
        return (getSystemService(UI_MODE_SERVICE) as UiModeManager).nightMode == UiModeManager.MODE_NIGHT_YES
    }

    private fun ensureDefaultBubbleType() {
        if (getBubbleDrawableOption(config.bubbleDrawableSet) == null) {
            config.bubbleDrawableSet = 1
        }
    }

    private fun makeSystemBarsToTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val barContainer = binding.messageHolder.root
        val dp5 = (15 * resources.displayMetrics.density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val navHeight = nav.bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val dp5 = (5 * resources.displayMetrics.density).toInt()
            binding.mVerticalSideFrameBottom.layoutParams =
                binding.mVerticalSideFrameBottom.layoutParams.apply { height = navHeight + dp5 }
//            val barLp = binding.lytAction.layoutParams as ViewGroup.MarginLayoutParams
//            val activityMargin = dp(0)
//            if (ime.bottom > 0) {
//                barLp.bottomMargin = ime.bottom + activityMargin
//            } else {
//                barLp.bottomMargin = navHeight + activityMargin
//            }
//            binding.lytAction.layoutParams = barLp

            if (barContainer != null) {
                val imeVisible =
                    ime.bottom > 0 && insets.isVisible(WindowInsetsCompat.Type.ime())
                if (!imeVisible && !freezeThreadListLayoutOnResume) {
                    wasImeVisibleForThreadListInsets = false
                }
                val scrollToBottomForIme = imeVisible && !wasImeVisibleForThreadListInsets
                if (imeVisible) {
                    wasImeVisibleForThreadListInsets = true
                }
                applyThreadMessagesListWindowInsets(
                    navHeight = navHeight,
                    imeBottom = ime.bottom,
                    scrollToBottomForIme = scrollToBottomForIme,
                )
            }
            // Toolbar / popups often hide the IME without clearing EditText focus; re-sync compose bar
            // padding from root insets so it cannot stay stuck between keyboard and nav bar.
            if (composeBarBottomInsetLatch != ComposeBarBottomInsetLatch.NONE) {
                applyComposeBarImePaddingFromInsets()
            }
            applyComposeBarImePaddingFromInsets()
            if (!freezeThreadListLayoutOnResume) {
                binding.root.post { applyComposeBarImePaddingFromInsets() }
            }
            if (!insets.isVisible(WindowInsetsCompat.Type.ime()) && pendingAttachmentPickerAfterKeyboardHide) {
                binding.root.post { finishOpeningAttachmentPickerAfterKeyboardHide() }
            }
            insets
        }

        binding.messageHolder.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!isRecycleBin && overlayTransitionListPadding == null) {
                binding.messageHolder.root.post { refreshThreadMessagesListPaddingForComposeBarHeight() }
            }
        }
    }

    private fun beginOverlayTransitionListPaddingFreeze() {
        if (isRecycleBin || overlayTransitionListPadding != null) return
        val list = binding.threadMessagesList
        overlayTransitionListPadding = intArrayOf(
            list.paddingLeft,
            list.paddingTop,
            list.paddingRight,
            list.paddingBottom,
        )
        list.suppressLayout(true)
    }

    private fun applyOverlayTransitionListPaddingFreeze() {
        val padding = overlayTransitionListPadding ?: return
        binding.threadMessagesList.setPadding(padding[0], padding[1], padding[2], padding[3])
    }

    private fun releaseOverlayTransitionListPaddingFreeze(recalculate: Boolean = true) {
        if (overlayTransitionListPadding == null) return
        val frozenBottom = overlayTransitionListPadding!![3]
        overlayTransitionListPadding = null
        binding.threadMessagesList.suppressLayout(false)
        if (recalculate && !isRecycleBin) {
            val barContainer = binding.messageHolder.root
            barContainer.post {
                val composeHeight = resolveComposeStripHeightForThreadList(barContainer)
                val targetBottom = composeHeight + dp(6)
                if (kotlin.math.abs(targetBottom - frozenBottom) <= dp(4)) {
                    return@post
                }
                refreshThreadMessagesListPaddingForComposeBarHeight()
            }
        }
    }

    private fun scheduleKeyboardToAttachmentPickerTransitionComplete() {
        binding.messageHolder.root.post {
            binding.messageHolder.root.post {
                releaseOverlayTransitionListPaddingFreeze(recalculate = true)
                ignoreInputFocusLossInsetSync = false
                ViewCompat.requestApplyInsets(binding.root)
            }
        }
    }

    private fun finishOpeningAttachmentPickerAfterKeyboardHide() {
        if (!pendingAttachmentPickerAfterKeyboardHide) return
        pendingAttachmentPickerAfterKeyboardHide = false
        messageHolderHelper?.showAttachmentPicker()
        if (composeBarBottomInsetLatch == ComposeBarBottomInsetLatch.KEYBOARD_TO_ATTACHMENT_PICKER) {
            composeBarBottomInsetLatch = ComposeBarBottomInsetLatch.NONE
        }
        applyComposeBarImePaddingFromInsets()
        binding.messageHolder.root.requestLayout()
        scheduleKeyboardToAttachmentPickerTransitionComplete()
    }

    /**
     * Keeps [binding.threadMessagesList] bottom padding in sync with the compose strip height
     * ([binding.messageHolder]) so multi-line [binding.messageHolder.threadTypeMessage] growth moves
     * message content up (and shrinking moves it back down). Window insets alone do not fire on line wraps.
     */
    private fun refreshThreadMessagesListPaddingForComposeBarHeight() {
        if (isRecycleBin) return
        if (freezeThreadListLayoutOnResume) {
            applyFrozenThreadListLayout()
            return
        }
        if (overlayTransitionListPadding != null) {
            applyOverlayTransitionListPaddingFreeze()
            return
        }
        val rootInsets = ViewCompat.getRootWindowInsets(binding.root) ?: return
        val navHeight = rootInsets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        val imeBottom = rootInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        applyThreadMessagesListWindowInsets(
            navHeight = navHeight,
            imeBottom = imeBottom,
            scrollToBottomForIme = false,
        )
    }

    private fun applyThreadMessagesListWindowInsets(
        navHeight: Int,
        imeBottom: Int,
        scrollToBottomForIme: Boolean,
    ) {
        if (isRecycleBin) return
        if (freezeThreadListLayoutOnResume) {
            applyFrozenThreadListLayout()
            return
        }
        if (overlayTransitionListPadding != null) {
            applyOverlayTransitionListPaddingFreeze()
            return
        }
        val barContainer = binding.messageHolder.root
        val messagesList = binding.threadMessagesList
        val bottomBarLp = barContainer.layoutParams as ViewGroup.MarginLayoutParams
        val bottomOffset = dp(3).toInt()
        val appBarHeightPx = resources.getDimensionPixelSize(com.android.common.R.dimen.tx_top_bar_expand_height)
        val listTopPadding = appBarHeightPx + dp(10)
        val rootInsets = ViewCompat.getRootWindowInsets(binding.root)
        val imeInsetsApplyToList =
            imeBottom > 0 &&
                rootInsets?.isVisible(WindowInsetsCompat.Type.ime()) == true
        val composeBottomGap = dp(6)

        fun applyWithComposeHeight(composeHeight: Int) {
            if (isFinishing || isDestroyed) return
            // Compose bar height already includes system-bar padding via [applyComposeBarImePaddingFromInsets].
            val bottomPadding = composeHeight + composeBottomGap
            messagesList.setPadding(0, listTopPadding, 0, bottomPadding)
            if (imeInsetsApplyToList) {
                // Only pin to bottom when IME just opened (same as legacy behavior). Do not auto-scroll on
                // compose-bar relayouts: findLastCompletelyVisibleItemPosition() is often NO_POSITION (-1),
                // which compared against (lastIndex - SCROLL_TO_BOTTOM_FAB_LIMIT) wrongly looked "near bottom"
                // and prevented scrolling up to read older messages.
                if (scrollToBottomForIme) {
                    messagesList.scrollToPosition((messagesList.adapter?.itemCount ?: 1) - 1)
                }
            }
            bottomBarLp.bottomMargin = bottomOffset
            barContainer.layoutParams = bottomBarLp
        }

        messagesList.apply {
            setOnTouchListener(getOrCreateThreadAdapter().pinchToZoomTouchListener)
        }

        barContainer.post {
            val composeHeight = resolveComposeStripHeightForThreadList(barContainer)
            applyWithComposeHeight(composeHeight)
        }
    }

    private fun captureThreadLayoutForKeyboardResume() {
        if (isRecycleBin) {
            releaseThreadListLayoutFreeze(recalculatePadding = false)
            return
        }
        val keyboardOpen = hadKeyboardLayoutWhenLeaving ||
            wasKeyboardVisible ||
            isThreadKeyboardVisible()
        if (!keyboardOpen) {
            releaseThreadListLayoutFreeze(recalculatePadding = false)
            return
        }
        val list = binding.threadMessagesList
        frozenThreadListPadding = intArrayOf(
            list.paddingLeft,
            list.paddingTop,
            list.paddingRight,
            list.paddingBottom,
        )
        frozenComposeBarInsetBottom = captureComposeBarInsetBottom()
        freezeThreadListLayoutOnResume = true
        wasImeVisibleForThreadListInsets = true
    }

    private fun captureComposeBarInsetBottom(): Int {
        val compose = binding.messageHolder.root
        val base = compose.ensureBasePadding()
        return compose.paddingBottom - base[3]
    }

    private fun applyFrozenThreadListLayout() {
        val padding = frozenThreadListPadding ?: return
        binding.threadMessagesList.setPadding(padding[0], padding[1], padding[2], padding[3])
        if (frozenComposeBarInsetBottom > 0) {
            binding.messageHolder.root.updatePaddingWithBase(bottom = frozenComposeBarInsetBottom)
            binding.shortCodeHolder.root.updatePaddingWithBase(bottom = frozenComposeBarInsetBottom)
        }
        binding.threadMessagesList.suppressLayout(true)
    }

    private fun applyFrozenThreadListLayoutOnResumeIfNeeded() {
        if (!freezeThreadListLayoutOnResume || isRecycleBin) return
        applyFrozenThreadListLayout()
        binding.messageHolder.threadTypeMessage.requestFocus()
        @Suppress("DEPRECATION")
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        attachThreadListLayoutFreezePreDrawListener()
    }

    private fun attachThreadListLayoutFreezePreDrawListener() {
        removeThreadListLayoutFreezePreDrawListener()
        var preDrawFrames = 0
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (!freezeThreadListLayoutOnResume || isFinishing || isDestroyed) {
                removeThreadListLayoutFreezePreDrawListener()
                return@OnPreDrawListener true
            }
            applyFrozenThreadListLayout()
            preDrawFrames++
            val keyboardVisible = isThreadKeyboardVisible()
            if (keyboardVisible && preDrawFrames >= 2) {
                releaseThreadListLayoutFreeze(recalculatePadding = false)
                return@OnPreDrawListener true
            }
            if (preDrawFrames >= 5) {
                releaseThreadListLayoutFreeze(recalculatePadding = keyboardVisible)
                return@OnPreDrawListener true
            }
            true
        }
        threadListLayoutFreezePreDrawListener = listener
        binding.root.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun removeThreadListLayoutFreezePreDrawListener() {
        val listener = threadListLayoutFreezePreDrawListener ?: return
        if (binding.root.viewTreeObserver.isAlive) {
            binding.root.viewTreeObserver.removeOnPreDrawListener(listener)
        }
        threadListLayoutFreezePreDrawListener = null
    }

    private fun releaseThreadListLayoutFreeze(recalculatePadding: Boolean) {
        if (!freezeThreadListLayoutOnResume && frozenThreadListPadding == null) {
            removeThreadListLayoutFreezePreDrawListener()
            binding.threadMessagesList.suppressLayout(false)
            return
        }
        freezeThreadListLayoutOnResume = false
        frozenThreadListPadding = null
        frozenComposeBarInsetBottom = 0
        removeThreadListLayoutFreezePreDrawListener()
        binding.threadMessagesList.suppressLayout(false)
        if (recalculatePadding && !isRecycleBin) {
            refreshThreadMessagesListPaddingForComposeBarHeight()
            binding.root.post { ViewCompat.requestApplyInsets(binding.root) }
        }
    }

    /**
     * When the thread compose strip is [View.GONE] (selection / CAB), padding must match the bottom overlay:
     * [R.id.lyt_action] sits above [R.dimen/ripple_bottom] with the CAB ripple bar — not just the inner view height.
     */
    private fun resolveComposeStripHeightForThreadList(barContainer: View): Int {
        if (barContainer.visibility != View.VISIBLE) {
            return if (barContainer.height > 0) barContainer.height else dp(30)
        }

        val hasBottomPanel = isAttachmentPickerVisible ||
            messageHolderHelper?.isEmojiPickerPaneVisible() == true ||
            composeBarBottomInsetLatch != ComposeBarBottomInsetLatch.NONE ||
            isThreadKeyboardVisible()

        if (!hasBottomPanel) {
            return if (barContainer.height > 0) barContainer.height else dp(30)
        }

        val inputStripHeight = resolveComposeInputStripHeight(barContainer)
        val bottomInsetPadding = barContainer.paddingBottom
        val overlayHeight = when {
            isAttachmentPickerVisible || messageHolderHelper?.isEmojiPickerPaneVisible() == true ->
                config.keyboardHeight + bottomInsetPadding
            composeBarBottomInsetLatch != ComposeBarBottomInsetLatch.NONE ->
                composeBarBottomInsetLatchPx
            else -> {
                val rootInsets = ViewCompat.getRootWindowInsets(binding.root)
                val imeBottom = rootInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                if (imeBottom > 0 && rootInsets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                    imeBottom
                } else {
                    config.keyboardHeight + bottomInsetPadding
                }
            }
        }

        if (inputStripHeight > 0) {
            return inputStripHeight + overlayHeight
        }
        return if (barContainer.height > 0) barContainer.height else inputStripHeight + overlayHeight
    }

    /** Height of the compose row(s) above the attachment/emoji picker or IME inset. */
    private fun resolveComposeInputStripHeight(barContainer: View): Int {
        val divider = binding.messageHolder.attachmentPickerDivider
        if (divider.isLaidOut && divider.top > barContainer.top) {
            return divider.top - barContainer.top
        }
        val holder = binding.messageHolder
        val candidates = listOf(
            holder.threadSendMessageActionWrapper,
            holder.threadTypeMessageWrapper,
            holder.threadAddAttachmentHolder,
            holder.threadAttachmentsRecyclerview,
            holder.scheduledMessageHolder,
        )
        var maxBottom = barContainer.top
        for (view in candidates) {
            if (view.visibility != View.VISIBLE) continue
            if (view.isLaidOut && view.bottom > maxBottom) {
                maxBottom = view.bottom
            }
        }
        return if (maxBottom > barContainer.top) maxBottom - barContainer.top else 0
    }

    /** Height from list bottom to clear the floating CAB ripple container ([R.id.lyt_action]) + its bottom margin. */
//    private fun actionModeBottomOverlayClearancePx(): Int {
//        val box = binding.root.findViewById<View>(R.id.lyt_action)
//        val lp = box.layoutParams as? ViewGroup.MarginLayoutParams
//        val marginBottom = lp?.bottomMargin?.takeIf { it > 0 }
//            ?: resources.getDimensionPixelSize(R.dimen.ripple_bottom)
//        val frameHeight = when {
//            box.height > 0 -> box.height
//            box.measuredHeight > 0 -> box.measuredHeight
//            else -> {
//                val ripple = binding.actionModeRippleToolbar
//                maxOf(ripple.height, ripple.measuredHeight, dp(48))
//            }
//        }
//        return frameHeight + marginBottom
//    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Matches [EdgeToEdgeActivity.setupEdgeToEdge] padding for IME + system bars on the compose bar.
     * Call when root insets change or when the IME hides without the EditText losing focus (toolbar taps).
     */
    private fun applyComposeBarImePaddingFromInsets() {
        val rootInsets = ViewCompat.getRootWindowInsets(binding.root)
            ?: ViewCompat.getRootWindowInsets(window.decorView)
            ?: return
        if (freezeThreadListLayoutOnResume && frozenComposeBarInsetBottom > 0) {
            binding.messageHolder.root.updatePaddingWithBase(bottom = frozenComposeBarInsetBottom)
            binding.shortCodeHolder.root.updatePaddingWithBase(bottom = frozenComposeBarInsetBottom)
            return
        }
        val imeAndSystem = rootInsets.getInsets(
            WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars()
        )
        val rawBottom = imeAndSystem.bottom
        val bottomPx = when (composeBarBottomInsetLatch) {
            ComposeBarBottomInsetLatch.KEYBOARD_TO_ATTACHMENT_PICKER ->
                composeBarBottomInsetLatchPx
            ComposeBarBottomInsetLatch.ATTACHMENT_PICKER_TO_KEYBOARD -> {
                val slop = dp(16)
                val reached = rootInsets.isVisible(WindowInsetsCompat.Type.ime()) &&
                    rawBottom >= composeBarBottomInsetLatchPx - slop
                val v = maxOf(rawBottom, composeBarBottomInsetLatchPx)
                if (reached) {
                    composeBarBottomInsetLatch = ComposeBarBottomInsetLatch.NONE
                    releaseOverlayTransitionListPaddingFreeze(recalculate = true)
                }
                v
            }
            ComposeBarBottomInsetLatch.NONE -> rawBottom
        }
        if (isRecycleBin) {
            binding.threadMessagesList.updatePaddingWithBase(bottom = bottomPx)
        } else {
            binding.messageHolder.root.updatePaddingWithBase(bottom = bottomPx)
        }
        binding.shortCodeHolder.root.updatePaddingWithBase(bottom = bottomPx)
    }

    private fun beginKeyboardToAttachmentPickerComposeInsetLatch() {
        if (isRecycleBin) return
        beginOverlayTransitionListPaddingFreeze()
        val rootInsets = ViewCompat.getRootWindowInsets(binding.root)
            ?: ViewCompat.getRootWindowInsets(window.decorView)
            ?: return
        val bottom = rootInsets.getInsets(
            WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars()
        ).bottom
        if (bottom <= 0) return
        composeBarBottomInsetLatchPx = bottom
        composeBarBottomInsetLatch = ComposeBarBottomInsetLatch.KEYBOARD_TO_ATTACHMENT_PICKER
        applyComposeBarImePaddingFromInsets()
    }

    private fun beginAttachmentPickerToKeyboardComposeInsetLatch() {
        if (isRecycleBin) return
        beginOverlayTransitionListPaddingFreeze()
        val rootInsets = ViewCompat.getRootWindowInsets(binding.root)
            ?: ViewCompat.getRootWindowInsets(window.decorView)
            ?: return
        val nav = rootInsets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        composeBarBottomInsetLatchPx = config.keyboardHeight + nav
        composeBarBottomInsetLatch = ComposeBarBottomInsetLatch.ATTACHMENT_PICKER_TO_KEYBOARD
        applyComposeBarImePaddingFromInsets()
    }

    private fun cancelPendingAttachmentPickerShow() {
        pendingAttachmentPickerAfterKeyboardHide = false
    }

    private fun clearComposeBarBottomInsetLatch() {
        cancelPendingAttachmentPickerShow()
        val hadLatch = composeBarBottomInsetLatch != ComposeBarBottomInsetLatch.NONE
        composeBarBottomInsetLatch = ComposeBarBottomInsetLatch.NONE
        if (hadLatch) {
            applyComposeBarImePaddingFromInsets()
        }
        releaseOverlayTransitionListPaddingFreeze(recalculate = true)
    }

    private fun isThreadKeyboardVisible(): Boolean {
        val rootInsets = ViewCompat.getRootWindowInsets(binding.root)
            ?: ViewCompat.getRootWindowInsets(window.decorView)
            ?: return false
        return rootInsets.isVisible(WindowInsetsCompat.Type.ime()) &&
            rootInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0
    }

    /**
     * Long-press message options while the IME is open: show the sheet without dismissing the keyboard.
     */
    fun showMessageOptionsDialog(
        title: CharSequence,
        options: List<OptionListItem>,
        blurTarget: BlurTarget?,
    ) {
        if (isDestroyed || isFinishing || options.isEmpty()) return

        val keyboardVisibleAtShow = isThreadKeyboardVisible()
        OptionListDialog(
            activity = this,
            title = title,
            options = options,
            blurTarget = blurTarget,
            cancelListener = null,
            onDialogPrepared = { dialog ->
                if (keyboardVisibleAtShow) {
                    keepKeyboardVisibleForOverlayDialog(dialog)
                }
            },
        )
    }

    private fun keepKeyboardVisibleForOverlayDialog(dialog: MDialog) {
        dialog.window?.let { window ->
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            )
            window.setWindowAnimations(0)
        }
    }

    /**
     * Focus loss is unreliable when the toolbar hides the IME (EditText often keeps focus).
     * Still apply manual padding + re-dispatch for any edge cases.
     */
    private fun syncMessageInputBarToBottomAfterFocusLoss() {
        if (isRecycleBin) return
        val bar = binding.messageHolder.root
        applyComposeBarImePaddingFromInsets()
        val content = findViewById<View>(android.R.id.content)
        fun requestInsets() {
            ViewCompat.requestApplyInsets(binding.root)
            content?.let { ViewCompat.requestApplyInsets(it) }
            ViewCompat.requestApplyInsets(bar)
        }
        binding.root.post {
            applyComposeBarImePaddingFromInsets()
            requestInsets()
        }
    }

    private fun saveDraftMessage(
        notifyConversationsAfter: Boolean = false,
        showDraftSavedToast: Boolean = false,
    ) {
        if (isRecycleBin) {
            if (notifyConversationsAfter) {
                bus?.post(Events.RefreshConversations())
            }
            return
        }
        if (messageHolderHelper?.isCountdownActive == true || SendMessageCountdownStore.isActive(threadId)) {
            return
        }
        val draftMessage = messageHolderHelper?.getMessageText() ?: ""
        val selections = messageHolderHelper?.getAttachmentSelections().orEmpty()
        val attachmentsJson = if (selections.isEmpty()) {
            null
        } else {
            val stored = selections.map {
                DraftStoredAttachment(
                    uriString = it.uri.toString(),
                    mimetype = it.mimetype,
                    filename = it.filename,
                    isPending = it.isPending,
                )
            }
            Gson().toJson(stored)
        }
        val persistCompose = draftMessage.isNotEmpty() ||
            selections.isNotEmpty() ||
            isScheduledMessage
        val scheduledMillis = if (isScheduledMessage) scheduledDateTime.millis else 0L

        ensureBackgroundThread {
            if (persistCompose) {
                saveSmsDraft(
                    body = draftMessage,
                    threadId = threadId,
                    attachmentsJson = attachmentsJson,
                    isScheduled = isScheduledMessage,
                    scheduledMillis = scheduledMillis,
                )
                if (showDraftSavedToast) {
                    runOnUiThread {
                        showDraftSavedToastMessage()
                    }
                }
                if (notifyConversationsAfter && threadId > 0L) {
                    refreshConversationRowFromTelephony(threadId)
                }
            } else {
                deleteSmsDraft(threadId)
                if (notifyConversationsAfter && threadId > 0L) {
                    refreshConversationRowFromTelephony(threadId)
                }
            }
            if (notifyConversationsAfter) {
                val localOnly = config.selectedConversationPin == 0
                // MAIN-thread subscribers; safe to post from the background save thread.
                bus?.post(Events.RefreshConversations(localListRefreshOnly = localOnly))
            }
        }
    }

    private fun showDraftSavedToastMessage() {
        Toast.makeText(applicationContext, R.string.message_saved_in_draft, Toast.LENGTH_SHORT).show()
    }

    private fun Draft.threadHasPersistedComposeContent(): Boolean =
        body.isNotBlank() ||
            !attachmentsJson.isNullOrBlank() ||
            (isScheduled && scheduledMillis > 0L)

    private fun applyThreadDraftRow(draft: Draft) {
        val helper = messageHolderHelper
        if (helper != null) {
            helper.setMessageText(draft.body)
            val json = draft.attachmentsJson
            if (!json.isNullOrBlank()) {
                try {
                    val type = object : TypeToken<List<DraftStoredAttachment>>() {}.type
                    val list: List<DraftStoredAttachment> = Gson().fromJson(json, type) ?: emptyList()
                    helper.replaceAttachmentsFromDraft(list)
                } catch (_: Exception) {
                    helper.replaceAttachmentsFromDraft(emptyList())
                }
            } else {
                helper.replaceAttachmentsFromDraft(emptyList())
            }
            binding.messageHolder.threadTypeMessage.setSelection(draft.body.length)
            binding.messageHolder.threadCharacterCounter.beVisibleIf(
                config.showCharacterCounter && draft.body.isNotEmpty()
            )
            helper.checkSendMessageAvailability()
        } else if (draft.body.isNotEmpty()) {
            binding.messageHolder.threadTypeMessage.setText(draft.body)
            binding.messageHolder.threadTypeMessage.setSelection(draft.body.length)
            binding.messageHolder.threadCharacterCounter.beVisibleIf(config.showCharacterCounter)
        }

        if (draft.isScheduled && draft.scheduledMillis > 0L) {
            scheduledDateTime = DateTime(draft.scheduledMillis)
            showScheduleMessageDialog()
        } else {
            hideScheduleSendUi()
        }
    }

    private fun applyThreadDraftAfterHelperInitialized() {
        if (isRecycleBin) return
        if (SendMessageCountdownStore.isActive(threadId)) return
        ensureBackgroundThread {
            val draftRow = getSmsDraftEntity(threadId) ?: return@ensureBackgroundThread
            if (!draftRow.threadHasPersistedComposeContent()) return@ensureBackgroundThread
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                applyComposeDraftIfStillValid(draftRow)
            }
        }
    }

    private fun applyComposeDraftIfStillValid(draft: Draft) {
        if (!draft.threadHasPersistedComposeContent()) return
        ensureBackgroundThread {
            if (isStaleSentComposeDraft(draft)) {
                deleteSmsDraft(threadId)
                refreshConversationRowFromTelephony(threadId)
                return@ensureBackgroundThread
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                applyThreadDraftRow(draft)
            }
        }
    }

    private fun isStaleSentComposeDraft(draft: Draft): Boolean {
        val draftBody = draft.body.trim()
        if (draftBody.isEmpty()) return false
        val latestOutgoing = getMessages(
            threadId = threadId,
            limit = 10,
            includeScheduledMessages = false,
            includeBlockedMessages = showBlockedMessagesInThread,
        ).asSequence()
            .filter { !it.isReceivedMessage() }
            .maxByOrNull { it.id }
            ?: return false
        return latestOutgoing.body.trim() == draftBody
    }

    private fun refreshMenuItems() {
        var isReceivedMessage = false
        if (messages.isNotEmpty()){
            for (message in messages) {
                isReceivedMessage = message.isReceivedMessage()
                if (isReceivedMessage) break
            }
        }
        val firstPhoneNumber = participants.firstOrNull()?.phoneNumbers?.firstOrNull()?.value
        val archiveAvailable = config.isArchiveAvailable
        val isGroupConversation = participants.size > 1 || conversation?.isGroupConversation == true
        val isDialVisible = !isGroupConversation && participants.size == 1 && !isSpecialNumber() && !isRecycleBin

        binding.threadToolbar.setActionMenuItemVisibility(R.id.dial_number, isDialVisible)
        binding.threadToolbar.menu.apply {
            findItem(R.id.delete)?.isVisible = threadItems.isNotEmpty()
            findItem(R.id.select_messages)?.isVisible = threadItems.isNotEmpty()
            findItem(R.id.restore)?.isVisible = threadItems.isNotEmpty() && isRecycleBin
            findItem(R.id.archive)?.isVisible =
                threadItems.isNotEmpty() && conversation?.isArchived == false && !isRecycleBin && archiveAvailable
            findItem(R.id.unarchive)?.isVisible =
                threadItems.isNotEmpty() && conversation?.isArchived == true && !isRecycleBin && archiveAvailable
            findItem(R.id.rename_conversation)?.isVisible = participants.size > 1 && conversation != null && !isRecycleBin
            findItem(R.id.conversation_details)?.isVisible = false//conversation != null && !isRecycleBin
            findItem(R.id.block_number)?.isVisible = !isRecycleBin
            findItem(R.id.mark_as_unread)?.isVisible = threadItems.isNotEmpty() && !isRecycleBin && isReceivedMessage

            // allow saving number in cases when we don't have it stored yet and it is a casual readable number
            findItem(R.id.add_number_to_contact)?.isVisible =
                participants.size == 1 && participants.first().name == firstPhoneNumber && firstPhoneNumber.any {
                    it.isDigit()
                } && !isRecycleBin
            val unblockText = if (participants.size == 1) com.goodwy.strings.R.string.unblock_number else com.goodwy.strings.R.string.unblock_numbers
            val blockText = if (participants.size == 1) com.goodwy.commons.R.string.block_number else com.goodwy.commons.R.string.block_numbers
            findItem(R.id.block_number)?.title = if (isBlockNumbers()) getString(unblockText) else getString(blockText)
        }
        binding.threadToolbar.invalidateMenu()
        // Update menu item icon colors after refreshing menu items
        updateMenuItemIconColors()
    }

    private fun updateMenuItemIconColors() {
        val topBarColor = getStartRequiredStatusBarColor()
        val contrastColor = topBarColor.getContrastColor()
        val itemColor = if (baseConfig.topAppBarColorIcon) getProperPrimaryColor() else contrastColor

        // Tint icons shown in the action bar (dial + more).
        val actionMenu = binding.threadToolbar.actionMenu
        for (i in 0 until actionMenu.size()) {
            try {
                actionMenu.getItem(i)?.icon?.setTint(itemColor)
            } catch (_: Exception) {
            }
        }
    }

    private fun setupOptionsMenu() {
        // Match MainActivity: keep action bar lean and move thread actions under "more".
        binding.threadToolbar.inflateMenu(R.menu.action_menu_thread)
        binding.threadToolbar.invalidateMenu()
        binding.threadToolbar.setOnMenuItemClickListener { menuItem ->
            if (participants.isEmpty()) {
                return@setOnMenuItemClickListener true
            }
            return@setOnMenuItemClickListener handleThreadMenuClick(menuItem)
        }
        binding.threadToolbar.setPopupForMoreItem(
            R.id.more,
            R.menu.menu_thread,
            binding.mainBlurTarget,
            object : android.view.MenuItem.OnMenuItemClickListener {
                override fun onMenuItemClick(item: android.view.MenuItem): Boolean {
                    return handleThreadMenuClick(item)
                }
            }
        )
        binding.threadToolbar.bindBlurTarget(this, binding.mainBlurTarget)
        binding.threadActionModeToolbar.bindBlurTarget(this, binding.mainBlurTarget)
    }

    private fun handleThreadMenuClick(menuItem: android.view.MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.block_number -> blockNumber()
            R.id.delete -> askConfirmDelete()
            R.id.restore -> askConfirmRestoreAll()
            R.id.archive -> archiveConversation()
            R.id.unarchive -> unarchiveConversation()
            R.id.rename_conversation -> renameConversation()
//            R.id.conversation_details -> launchConversationDetails(threadId)
            R.id.add_number_to_contact -> addNumberToContact()
            R.id.dial_number -> {
                val isGroupConversation = participants.size > 1 || conversation?.isGroupConversation == true
                if (isGroupConversation || participants.isEmpty() || isRecycleBin || isSpecialNumber()) {
                    return false
                }
                dialNumber()
            }
            R.id.mark_as_unread -> markAsUnread()
            R.id.select_messages -> getOrCreateThreadAdapter().startActMode()
            else -> return false
        }
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == MessageHolderHelper.CAPTURE_VIDEO_INTENT) {
            messageHolderHelper?.handleCaptureVideoResult(resultCode, resultData)
            return
        }
        if (requestCode == MessageHolderHelper.CAPTURE_PHOTO_INTENT) {
            messageHolderHelper?.handleCapturePhotoResult(resultCode, resultData)
            return
        }
        if (requestCode == REQUEST_EDIT_SLIDESHOW && resultCode == RESULT_OK) {
            messageHolderHelper?.handleSlideshowEditorResult(resultData)
            return
        }
        if (resultCode != RESULT_OK) return
        messageToResend = null

        // Handle speech-to-text
        if (requestCode == REQUEST_CODE_SPEECH_INPUT && resultData != null) {
            val res: ArrayList<String> =
                resultData.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) as ArrayList<String>
            val speechToText = Objects.requireNonNull(res)[0]
            val draft = messageHolderHelper?.getMessageText() ?: ""
            val draftPlusSpeech =
                if (draft.isNotEmpty()) {
                    if (draft.last().toString() != " ") "$draft $speechToText" else "$draft $speechToText"
                } else speechToText
            if (draftPlusSpeech.isNotEmpty()) {
                ensureBackgroundThread {
                    val existing = getSmsDraftEntity(threadId)
                    saveSmsDraft(
                        body = draftPlusSpeech,
                        threadId = threadId,
                        attachmentsJson = existing?.attachmentsJson,
                        isScheduled = existing?.isScheduled ?: false,
                        scheduledMillis = existing?.scheduledMillis ?: 0L,
                    )
                }
                messageHolderHelper?.setMessageText(draftPlusSpeech)
            }
            return
        }

        // Handle attachments via helper
        messageHolderHelper?.handleActivityResult(requestCode, resultCode, resultData)

        when (requestCode) {
            MessageHolderHelper.PICK_CONTACT_INTENT ->
                messageHolderHelper?.handlePickContactAttachmentResult("ThreadActivity", resultCode, resultData)
            PICK_SAVE_FILE_INTENT -> resultData?.data?.let { saveAttachments(resultData) }
            PICK_SAVE_DIR_INTENT -> resultData?.data?.let { saveAttachments(resultData) }
        }
    }

    private fun setupCachedMessages(callback: () -> Unit) {
        ensureBackgroundThread {
            messages = try {
                val recent = when {
                    isRecycleBin -> messagesDB.getRecentRecycleBinThreadMessages(threadId, MESSAGES_LIMIT)
                    config.useRecycleBin -> messagesDB.getRecentNonRecycledThreadMessages(threadId, MESSAGES_LIMIT)
                    else -> messagesDB.getRecentThreadMessages(threadId, MESSAGES_LIMIT)
                }
                ArrayList(recent.asReversed())
            } catch (_: Exception) {
                ArrayList()
            }
            allMessagesFetched = messages.size < MESSAGES_LIMIT

            clearExpiredScheduledMessages(threadId, messages)
            messages.removeAll { it.isScheduled && it.millis() < System.currentTimeMillis() }

            messages.sortWith(compareBy({ it.date }, { it.id }))

            setupParticipants()
            enrichParticipantContactNames()
            setupAdapter()

            runOnUiThread {
                if (messages.isEmpty() && !isSpecialNumber()) {
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                    binding.messageHolder.threadTypeMessage.requestFocus()
                }

                setupThreadTitle()
                setupSIMSelector()
                //updateMessageType()
                callback()
            }
        }
    }

    private fun setupThread(callback: () -> Unit) {
        if (conversation == null && isLaunchedFromShortcut) {
            if (isTaskRoot) {
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(this)
                }
            }
            finish()
            return
        }
        val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ensureBackgroundThread {
            privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)

            val cachedMessagesCode = messages.clone().hashCode()
            if (!isRecycleBin) {
                messages = getMessages(threadId, includeBlockedMessages = showBlockedMessagesInThread)
                if (config.useRecycleBin) {
                    val recycledMessages = messagesDB.getThreadMessagesFromRecycleBin(threadId)
                    messages = messages.filterNotInByKey(recycledMessages) { it.getStableId() }
                }
            }

            val hasParticipantWithoutName = participants.any { contact ->
                contact.phoneNumbers.map { it.normalizedNumber }.contains(contact.name)
            }

            try {
                if (participants.isNotEmpty() && messages.hashCode() == cachedMessagesCode && !hasParticipantWithoutName) {
                    setupAdapter()
                    runOnUiThread { callback() }
                    return@ensureBackgroundThread
                }
            } catch (_: Exception) {
            }

            setupParticipants()
            enrichParticipantContactNames()

            // check if no participant came from a privately stored contact in Simple Contacts
            if (privateContacts.isNotEmpty()) {
                val senderNumbersToReplace = HashMap<String, String>()
                participants.filter { it.doesHavePhoneNumber(it.name) }.forEach { participant ->
                    privateContacts.firstOrNull { it.doesHavePhoneNumber(participant.phoneNumbers.first().normalizedNumber) }
                        ?.apply {
                            senderNumbersToReplace[participant.phoneNumbers.first().normalizedNumber] = name
                            participant.name = name
                            participant.photoUri = photoUri
                        }
                }

                messages.forEach { message ->
                    if (senderNumbersToReplace.keys.contains(message.senderName)) {
                        message.senderName = senderNumbersToReplace[message.senderName]!!
                    }
                }
            }

            if (participants.isEmpty()) {
                val name = intent.getStringExtra(THREAD_TITLE) ?: ""
                val number = intent.getStringExtra(THREAD_NUMBER)
                if (number == null) {
                    toast(com.goodwy.commons.R.string.unknown_error_occurred)
                    finish()
                    return@ensureBackgroundThread
                }

                val phoneNumber = PhoneNumber(number, 0, "", number)
                val contact = SimpleContact(
                    rawId = 0,
                    contactId = 0,
                    name = name,
                    photoUri = "",
                    phoneNumbers = arrayListOf(phoneNumber),
                    birthdays = ArrayList(),
                    anniversaries = ArrayList()
                )
                participants.add(contact)
            }

            if (!isRecycleBin) {
                messages.chunked(30).forEach { currentMessages ->
                    messagesDB.insertMessages(*currentMessages.toTypedArray())
                }
            }

            setupAdapter()
            runOnUiThread {
                setupThreadTitle()
                setupSIMSelector()
                callback()
            }
        }
        updateContactImage()
    }

    /** Same surface / background logic as [MainActivity.mainContentBackgroundColor]. */
    private fun mainContentBackgroundColor(): Int {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        return if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
    }

    /**
     * Runs after [super.onCreate] and **before** [setContentView]: replaces the dark Material3 Dark
     * `windowBackground` so transparent system bars do not reveal it before messages load.
     */
    private fun paintThreadWindowBeforeContentView() {
        val backgroundColor = mainContentBackgroundColor()
        window.setBackgroundDrawable(ColorDrawable(backgroundColor))
        window.decorView.setBackgroundColor(backgroundColor)
    }

    /** Same as [MainActivity] onResume: surface only for dynamic theme + light mode, else proper background. */
    private fun applyThreadListBackgroundColors(): Int {
        val backgroundColor = mainContentBackgroundColor()
        window.setBackgroundDrawable(ColorDrawable(backgroundColor))
        window.decorView.setBackgroundColor(backgroundColor)
        binding.threadHolder.setBackgroundColor(backgroundColor)
        binding.threadMessagesList.setBackgroundColor(backgroundColor)
        binding.mainBlurTarget.setBackgroundColor(backgroundColor)
        binding.root.setBackgroundColor(backgroundColor)
        return backgroundColor
    }

    private fun getOrCreateThreadAdapter(): ThreadAdapter {
        applyThreadListBackgroundColors()
        var currAdapter = binding.threadMessagesList.adapter
        if (currAdapter == null) {
            currAdapter = ThreadAdapter(
                activity = this,
                recyclerView = binding.threadMessagesList,
                itemClick = { handleItemClick(it) },
                isRecycleBin = isRecycleBin,
                isGroupChat = participants.size > 1,
                retryFailedMessage = { retryFailedMessage(it) },
                deleteMessages = { messages, toRecycleBin, fromRecycleBin, isPopupMenu ->
                    deleteMessages(messages, toRecycleBin, fromRecycleBin, isPopupMenu)
                }
            )

            binding.threadMessagesList.adapter = currAdapter
        }
        return currAdapter as ThreadAdapter
    }

    private fun setupAdapter() {
        threadItems = getThreadItems()

        runOnUiThread {
            refreshMenuItems()
            getOrCreateThreadAdapter().apply {
                val layoutManager = binding.threadMessagesList.layoutManager as LinearLayoutManager
                val lastPosition = itemCount - 1
                val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()
                val shouldScrollToBottom =
                    currentList.lastOrNull() != threadItems.lastOrNull() && lastPosition - lastVisiblePosition == 1
                updateMessages(threadItems, if (shouldScrollToBottom) lastPosition else -1)
            }
            binding.threadMessagesList.post {
                applyThreadListBackgroundColors()
                applyThreadTopBarChrome()
            }
        }

    }

    private fun scrollToBottom() {
        val position = getOrCreateThreadAdapter().currentList.lastIndex
        if (position >= 0) {
            binding.threadMessagesList.smoothScrollToPosition(position)
        }
    }

    private fun setupScrollListener() {
        binding.threadMessagesList.onScroll(
            onScrolled = { dx, dy ->
                tryLoadMoreMessages()
                if (dy < 0 && wasImeVisibleForThreadListInsets) {
                    hideKeyboard()
                }
                val layoutManager = binding.threadMessagesList.layoutManager as LinearLayoutManager
                val lastVisibleItemPosition = layoutManager.findLastCompletelyVisibleItemPosition()
//                 deleted by sun unnecessary ---->
//                val isCloseToBottom =
//                    lastVisibleItemPosition >= getOrCreateThreadAdapter().itemCount - SCROLL_TO_BOTTOM_FAB_LIMIT
//                val fab = binding.scrollToBottomFab
//                if (isCloseToBottom) fab.hide() else fab.show()
//                <---------
                // Update top bar (status bar + toolbar icon) colors on scroll so they match content behind transparent bar
                if (config.changeColourTopBar) {
                    val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
                    val scrollOffset = binding.threadMessagesList.computeVerticalScrollOffset()
                    val color = if (scrollOffset == 0) {
                        if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
                    } else {
                        getColoredMaterialStatusBarColor()
                    }
                    updateTopBarColors(binding.threadToolbar, color)
                    val contrastColor = color.getContrastColor()
                    val itemColor = if (baseConfig.topAppBarColorIcon) getProperPrimaryColor() else contrastColor
                    setupThreadToolbarNavigation(color = itemColor)
                }
            },
            onScrollStateChanged = { newState ->
                if (newState == RecyclerView.SCROLL_STATE_IDLE) tryLoadMoreMessages()
            }
        )
    }

    private fun setupThreadToolbarNavigation(color: Int) {
        val toolbar = binding.threadToolbar
        toolbar.navigationIcon = resources.getColoredDrawableWithColor(
            this,
            com.android.common.R.drawable.ic_cmn_arrow_left_fill,
            color
        )
        toolbar.setNavigationContentDescription(com.goodwy.commons.R.string.back)
        toolbar.setNavigationOnClickListener {
            hideKeyboard()
            if (!onBackPressedCompat()) {
                finish()
            }
        }
    }

    private fun handleItemClick(any: Any) {
        when {
            any is Message && any.isScheduled -> showScheduledMessageInfo(any)
        }
    }

    private fun retryFailedMessage(message: Message) {
        if (message.type != Telephony.Sms.MESSAGE_TYPE_FAILED) return

        val subscriptionId = message.subscriptionId.takeIf { it != -1 }
            ?: availableSIMCards.getOrNull(currentSIMCardIndex)?.subscriptionId
            ?: SmsManager.getDefaultSmsSubscriptionId()
        val attachments = message.attachment?.attachments ?: emptyList()

        sendNormalMessage(
            text = message.body,
            subscriptionId = subscriptionId,
            attachments = attachments,
            messageId = message.id,
            clearCompose = false,
        )
    }

    private fun deleteMessages(
        messagesToRemove: List<Message>,
        toRecycleBin: Boolean,
        fromRecycleBin: Boolean,
        isPopupMenu: Boolean = false,
    ) {
        val deletePosition = threadItems.indexOf(messagesToRemove.first())
        messages.removeAll(messagesToRemove.toSet())
        threadItems = getThreadItems()

        runOnUiThread {
            if (messages.isEmpty() && !isPopupMenu) {
                finish()
            } else {
                getOrCreateThreadAdapter().apply {
                    updateMessages(threadItems, scrollPosition = deletePosition)
                    finishActMode()
                }
            }
        }

        messagesToRemove.forEach { message ->
            val messageId = message.id
            if (message.isScheduled) {
                deleteScheduledMessage(messageId)
                cancelScheduleSendPendingIntent(messageId)
            } else {
                if (toRecycleBin) {
                    moveMessageToRecycleBin(messageId)
                } else if (fromRecycleBin) {
                    restoreMessageFromRecycleBin(messageId)
                } else {
                    deleteMessage(messageId, message.isMMS)
                }
            }
        }
        updateLastConversationMessage(threadId)

        // move all scheduled messages to a temporary thread when there are no real messages left
        if (messages.isNotEmpty() && messages.all { it.isScheduled }) {
            val scheduledMessage = messages.last()
            val fakeThreadId = generateRandomId()
            createTemporaryThread(scheduledMessage, fakeThreadId, conversation)
            updateScheduledMessagesThreadId(messages, fakeThreadId)
            threadId = fakeThreadId
        }
    }

    private fun jumpToMessage(messageId: Long) {
        if (messages.any { it.id == messageId }) {
            val index = threadItems.indexOfFirst { (it as? Message)?.id == messageId }
            if (index != -1) binding.threadMessagesList.smoothScrollToPosition(index)
            return
        }

        ensureBackgroundThread {
            if (loadingOlderMessages) return@ensureBackgroundThread
            loadingOlderMessages = true
            isJumpingToMessage = true

            var cutoff = messages.firstOrNull()?.date ?: Int.MAX_VALUE
            var found = false
            var loops = 0

            // not the best solution, but this will do for now.
            while (!found && !allMessagesFetched) {
                if (fetchOlderMessages(cutoff).isEmpty() || loops >= 1000) break
                cutoff = messages.first().date
                found = messages.any { it.id == messageId }
                loops++
            }

            threadItems = getThreadItems()
            runOnUiThread {
                loadingOlderMessages = false
                val index = threadItems.indexOfFirst { (it as? Message)?.id == messageId }
                getOrCreateThreadAdapter().updateMessages(
                    newMessages = threadItems, scrollPosition = index, smoothScroll = true
                )
                isJumpingToMessage = false
            }
        }
    }

    private fun tryLoadMoreMessages() {
        if (isJumpingToMessage) return
        val layoutManager = binding.threadMessagesList.layoutManager as LinearLayoutManager
        if (layoutManager.findFirstVisibleItemPosition() <= PREFETCH_THRESHOLD) {
            loadMoreMessages()
        }
    }

    private fun loadMoreMessages() {
        if (messages.isEmpty() || allMessagesFetched || loadingOlderMessages) return
        loadingOlderMessages = true
        val cutoff = messages.first().date
        ensureBackgroundThread {
            fetchOlderMessages(cutoff)
            threadItems = getThreadItems()
            runOnUiThread {
                loadingOlderMessages = false
                getOrCreateThreadAdapter().updateMessages(threadItems)
                getOrCreateThreadAdapter().updateTitle()
            }
        }
    }

    private fun fetchOlderMessages(cutoff: Int): List<Message> {
        var older = getMessages(
            threadId,
            dateFrom = cutoff,
            includeBlockedMessages = showBlockedMessagesInThread,
        )
            .filterNotInByKey(messages) { it.getStableId() }
        if (config.useRecycleBin && !isRecycleBin) {
            val recycledMessages = messagesDB.getThreadMessagesFromRecycleBin(threadId)
            older = older.filterNotInByKey(recycledMessages) { it.getStableId() }
        }

        if (older.isEmpty()) {
            allMessagesFetched = true
            return older
        }

        messages.addAll(0, older)
        return older
    }

    private fun loadConversation() {
        handlePermission(PERMISSION_READ_PHONE_STATE) { granted ->
            if (granted) {
                setupMessageHolderHelper()
                setupButtons()
                setupConversation()
                setupCachedMessages {
                    setupThread {
                        val searchedMessageId = intent.getLongExtra(SEARCHED_MESSAGE_ID, -1L)
                        intent.removeExtra(SEARCHED_MESSAGE_ID)
                        if (searchedMessageId != -1L) {
                            jumpToMessage(searchedMessageId)
                        }
                    }
                    setupScrollListener()
                }
            } else {
                finish()
            }
        }
    }

    private fun setupConversation() {
        ensureBackgroundThread {
            conversation = conversationsDB.getConversationWithThreadId(threadId)
        }
    }

    private fun restorePendingSendCountdownIfNeeded() {
        if (!SendMessageCountdownStore.isActive(threadId)) {
            return
        }
        messageHolderHelper?.restorePendingCountdownIfAny()
    }

    private fun setupMessageHolderHelper() {
        isSpeechToTextAvailable = if (config.useSpeechToText) isSpeechToTextAvailable() else false

        messageHolderHelper = MessageHolderHelper(
            activity = this,
            binding = binding.messageHolder,
            threadId = threadId,
            onSendMessage = { text, subscriptionId, attachments ->
                sendMessageWithHelper(text, subscriptionId, attachments)
            },
            onSpeechToText = { speechToText() },
            onExpandMessage = { showExpandedMessageFragment() },
            onTextChanged = {
                messageToResend = null
            },
            onHideAttachmentPickerRequested = {
                cancelPendingAttachmentPickerShow()
                isAttachmentPickerVisible = false
                messageHolderHelper?.hideAttachmentPicker()
                binding.messageHolder.root.post {
                    // Picker→keyboard: keep inset latch + frozen list padding until IME finishes opening.
                    if (composeBarBottomInsetLatch != ComposeBarBottomInsetLatch.ATTACHMENT_PICKER_TO_KEYBOARD) {
                        clearComposeBarBottomInsetLatch()
                    }
                    binding.root.post { ViewCompat.requestApplyInsets(binding.root) }
                }
            },
            onThreadTypeMessageFocusChange = { hasFocus ->
                if (!hasFocus && !ignoreInputFocusLossInsetSync &&
                    composeBarBottomInsetLatch == ComposeBarBottomInsetLatch.ATTACHMENT_PICKER_TO_KEYBOARD
                ) {
                    clearComposeBarBottomInsetLatch()
                }
                if (!hasFocus && !isAttachmentPickerVisible && !ignoreInputFocusLossInsetSync &&
                    messageHolderHelper?.isEmojiPickerPaneVisible() != true
                ) {
                    syncMessageInputBarToBottomAfterFocusLoss()
                }
            },
            onPrepareKeyboardFromAttachmentPicker = {
                cancelPendingAttachmentPickerShow()
                beginAttachmentPickerToKeyboardComposeInsetLatch()
            },
        )

        messageHolderHelper?.setup(isSpeechToTextAvailable)
        messageHolderHelper?.bindSendMessageCountdownStore()
        restorePendingSendCountdownIfNeeded()

        binding.messageHolder.apply {
            threadTypeMessage.setText(intent.getStringExtra(THREAD_TEXT))
            threadAddAttachmentHolder.setOnClickListener {
                if (attachmentPickerHolder.isVisible()) {
                    pendingAttachmentPickerAfterKeyboardHide = false
                    beginOverlayTransitionListPaddingFreeze()
                    messageHolderHelper?.hideAttachmentPicker()
                    binding.messageHolder.root.post {
                        isAttachmentPickerVisible = false
                        clearComposeBarBottomInsetLatch()
                        threadTypeMessage.requestApplyInsets()
                        binding.root.post { ViewCompat.requestApplyInsets(binding.root) }
                    }
                } else {
                    ignoreInputFocusLossInsetSync = true
                    beginOverlayTransitionListPaddingFreeze()
                    isAttachmentPickerVisible = true
                    if (isThreadKeyboardVisible()) {
                        pendingAttachmentPickerAfterKeyboardHide = true
                        beginKeyboardToAttachmentPickerComposeInsetLatch()
                        hideKeyboard()
                        threadTypeMessage.clearFocus()
                    } else {
                        pendingAttachmentPickerAfterKeyboardHide = false
                        messageHolderHelper?.showAttachmentPicker()
                        scheduleKeyboardToAttachmentPickerTransitionComplete()
                    }
                }

            }

//            if (intent.extras?.containsKey(THREAD_ATTACHMENT_URI) == true) {
//                val uri = intent.getStringExtra(THREAD_ATTACHMENT_URI)!!.toUri()
//                messageHolderHelper?.addAttachment(uri)
//            } else if (intent.extras?.containsKey(THREAD_ATTACHMENT_URIS) == true) {
//                (intent.getSerializableExtra(THREAD_ATTACHMENT_URIS) as? ArrayList<Uri>)?.forEach {
//                    messageHolderHelper?.addAttachment(it)
//                }
//            }
        }

        attachmentIntentLauncher = messageHolderHelper?.let { AttachmentIntentLauncher(this, it) }
        messageHolderHelper?.setAttachmentIntentLauncher(attachmentIntentLauncher)
        attachmentIntentLauncher?.let { launcher ->
            messageHolderHelper?.setupAttachmentPicker(
                onChoosePhoto = { launcher.launchSelectImage() },
                onChooseVideo = { launcher.launchSelectVideo() },
                onTakePhoto = { launcher.launchCapturePhoto() },
                onRecordVideo = { launcher.launchCaptureVideo() },
                onPickAudio = { launcher.showPickAudioDialog() },
                onPickFile = { launcher.launchPickDocument() },
                onPickContact = { launcher.launchPickContact() },
                onScheduleMessage = { launchScheduleSendDialog() },
                onPickQuickText = {
                    val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
                        ?: throw IllegalStateException("mainBlurTarget not found")
                    com.android.mms.dialogs.QuickTextSelectionDialog(this, blurTarget) { selectedText ->
                        messageHolderHelper?.insertText(selectedText)
                    }
                }
            )
        }

        messageHolderHelper?.hideAttachmentPicker()
        applyThreadDraftAfterHelperInitialized()
    }

    private fun setupButtons() = binding.apply {
        updateTextColors(threadHolder)
        val textColor = getProperTextColor()
        val backgroundColor = applyThreadListBackgroundColors()

        binding.messageHolder.apply {
            threadMessagesFastscroller.updateColors(backgroundColor)
//            threadAddAttachment.applyColorFilter(textColor)
//            threadAddAttachment.background.applyColorFilter(surfaceColor)
        }

//        deleted by sun unnecessary ---->
//        scrollToBottomFab.setOnClickListener {
//            scrollToBottom()
//        }
//        scrollToBottomFab.backgroundTintList = ColorStateList.valueOf(getBottomBarColor())
//        scrollToBottomFab.applyColorFilter(textColor)
//        <--------------

        setupScheduleSendUi()
    }

    private fun sendMessageWithHelper(text: String, subscriptionId: Int?, attachments: List<Attachment>) {
        val finalSubscriptionId = SendSubscriptionHelper.resolveForSend(
            explicitSubId = subscriptionId
                ?: availableSIMCards.getOrNull(currentSIMCardIndex)?.subscriptionId,
        ) ?: return

        if (isScheduledMessage) {
            sendScheduledMessage(text, finalSubscriptionId)
        } else {
            sendNormalMessage(text, finalSubscriptionId, attachments)
        }
    }

    private fun askForExactAlarmPermissionIfNeeded(callback: () -> Unit = {}) {
        if (isSPlus()) {
            val alarmManager: AlarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            if (alarmManager.canScheduleExactAlarms()) {
                callback()
            } else {
                val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
                    ?: throw IllegalStateException("mainBlurTarget not found")
                PermissionRequiredDialog(
                    activity = this,
                    textId = com.goodwy.commons.R.string.allow_alarm_scheduled_messages,
                    blurTarget = blurTarget,
                    positiveActionCallback = {
                        openRequestExactAlarmSettings(BuildConfig.APPLICATION_ID)
                    },
                )
            }
        } else {
            callback()
        }
    }

    private fun setupParticipants() {
        if (participants.isEmpty()) {
            participants = if (messages.isEmpty()) {
                val intentNumbers = getPhoneNumbersFromIntent()
                val participants = getThreadParticipants(threadId, null)
                fixParticipantNumbers(participants, intentNumbers)
            } else {
                messages.first().participants
            }
            runOnUiThread {
                maybeDisableShortCodeReply()
            }
        }
    }

    /**
     * Blocked threads are excluded from the main conversation sync, so cached participants and
     * [Conversation.title] often keep a raw phone number. Resolve names the same way as
     * [getThreadContactNames] in the blocked-messages / conversation lists.
     */
    private fun enrichParticipantContactNames() {
        participants.filter { it.doesHavePhoneNumber(it.name) }.forEach { participant ->
            val number = participant.phoneNumbers.firstOrNull()?.normalizedNumber ?: return@forEach
            val resolved = getThreadContactNames(listOf(number), privateContacts).firstOrNull() ?: return@forEach
            if (!participant.doesHavePhoneNumber(resolved)) {
                participant.name = resolved
            }
        }
    }

    private fun resolveThreadDisplayTitle(): String {
        val storedTitle = conversation?.title
        // Do not prefer a stale Room title that is only the phone number (common for blocked threads).
        val title = storedTitle?.takeUnless { stored ->
            participants.size == 1 && participants.first().doesHavePhoneNumber(stored)
        }
        var threadTitle = if (participants.size > 1) {
            participants.getThreadTitle(this@ThreadActivity)
        } else {
            if (!title.isNullOrEmpty()) title else participants.getThreadTitle(this@ThreadActivity)
        }
        if (participants.size == 1) {
            val participant = participants.first()
            if (participant.doesHavePhoneNumber(threadTitle)) {
                intent.getStringExtra(THREAD_TITLE)?.trim()?.takeIf {
                    it.isNotEmpty() && !participant.doesHavePhoneNumber(it)
                }?.let { threadTitle = it }
            }
        }
        return threadTitle
    }

    private fun isSpecialNumber(): Boolean {
        val addresses = participants.getAddresses()
        return addresses.any { isShortCodeWithLetters(it) }
    }

    private fun maybeDisableShortCodeReply() {
        if (isSpecialNumber() && !isRecycleBin) {
            currentFocus?.clearFocus()
            hideKeyboard()
            binding.messageHolder.threadTypeMessage.text?.clear()
            binding.messageHolder.root.beGone()
            binding.shortCodeHolder.root.beVisible()

            val textColor = getProperTextColor()
            binding.shortCodeHolder.replyDisabledText.setTextColor(textColor)
            binding.shortCodeHolder.replyDisabledInfo.apply {
                applyColorFilter(textColor)
                setOnClickListener {
                    val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
                        ?: throw IllegalStateException("mainBlurTarget not found")
                    InvalidNumberDialog(
                        activity = this@ThreadActivity,
                        text = getString(R.string.invalid_short_code_desc),
                        blurTarget = blurTarget
                    )
                }
                tooltipText = getString(com.goodwy.commons.R.string.more_info)
            }
        }
    }

    /**
     * Paints toolbar / header from [THREAD_TITLE], [THREAD_NUMBER], and [THREAD_URI] so the UI matches
     * the conversation list before Room and telephony finish loading.
     */
    private fun applyInitialThreadHeaderFromIntent() {
        val titleExtra = intent.getStringExtra(THREAD_TITLE)?.trim().orEmpty()
        val rawNumberExtra = intent.getStringExtra(THREAD_NUMBER)
        if (titleExtra.isEmpty() && rawNumberExtra.isNullOrBlank()) {
            return
        }

        val numbers = getPhoneNumbersFromIntent()
        var threadTitle = titleExtra
        var threadSubtitle = ""

        when {
            numbers.size <= 1 -> {
                val phoneNumber = when {
                    numbers.isNotEmpty() -> numbers.first().trim()
                    !rawNumberExtra.isNullOrBlank() -> rawNumberExtra.trim()
                    else -> ""
                }
                if (phoneNumber.isNotEmpty()) {
                    val normalizedPhone = phoneNumber.normalizePhoneNumber()
                    val normalizedTitle = threadTitle.normalizePhoneNumber()
                    if (threadTitle.isNotEmpty() &&
                        (normalizedTitle == normalizedPhone || threadTitle == phoneNumber) &&
                        threadTitle.startsWith("+")
                    ) {
                        threadTitle = getDisplayNumberWithoutCountryCode(phoneNumber)
                    }
                    val displayPhone = getDisplayNumberWithoutCountryCode(phoneNumber)
                    val showPhoneSubtitle = config.showPhoneNumber ||
                        threadTitle.isEmpty() ||
                        normalizedTitle == normalizedPhone ||
                        threadTitle == phoneNumber
                    if (showPhoneSubtitle && displayPhone.isNotEmpty()) {
                        threadSubtitle = displayPhone
                    }
                    if (threadSubtitle.isNotEmpty() &&
                        (threadSubtitle == threadTitle || displayPhone == threadTitle)
                    ) {
                        threadSubtitle = ""
                    }
                }
            }
            else -> {
                threadSubtitle =
                    TextUtils.join(
                        ", ",
                        numbers.map { getDisplayNumberWithoutCountryCode(it) }.toTypedArray(),
                    )
            }
        }

        if (threadTitle.isEmpty() && numbers.isNotEmpty()) {
            threadTitle = getDisplayNumberWithoutCountryCode(numbers.first())
        }

        val participantCount = when {
            numbers.size > 1 -> numbers.size
            else -> 1
        }

        bindThreadHeaderUi(threadTitle, threadSubtitle, participantCount, bindInteractions = false)
        binding.root.post { updateContactImage() }
    }

    /**
     * Shared header layout for [setupThreadTitle] and [applyInitialThreadHeaderFromIntent].
     */
    private fun bindThreadHeaderUi(
        threadTitle: String,
        threadSubtitle: String,
        participantCount: Int,
        bindInteractions: Boolean,
    ) = binding.apply {
        val textColor = getProperTextColor()
        threadToolbar.title = ""
        when (config.threadTopStyle) {
            THREAD_TOP_COMPACT -> {
                topDetailsLarge.beGone()
                topDetailsCompact.root.beVisible()
                topDetailsCompact.apply {
                    senderPhoto.beVisibleIf(config.showContactThumbnails)
                    if (threadTitle.isNotEmpty()) {
                        senderName.text = threadTitle
                        senderName.setTextColor(textColor)
                    }
                    senderNumber.beGoneIf(
                        threadSubtitle.isEmpty() || threadTitle == threadSubtitle || participantCount > 1,
                    )
                    senderNumber.text = threadSubtitle
                    senderNumber.setTextColor(textColor)
                    if (bindInteractions) {
                        arrayOf(senderPhoto, senderName, senderNumber).forEach {
                            it.setOnClickListener {
                                if (conversation != null) launchConversationDetails(threadId)
                            }
                        }
                        senderName.setOnLongClickListener { copyToClipboard(senderName.value); true }
                        senderNumber.setOnLongClickListener { copyToClipboard(senderNumber.value); true }
                    }
                }
            }
            THREAD_TOP_LARGE -> {
                topDetailsCompact.root.beGone()
                topDetailsLarge.beVisible()
                topDetailsLarge.apply {
                    if (threadTitle.isNotEmpty()) {
                        senderNameLarge.text = threadTitle
                        senderNameLarge.setTextColor(textColor)
                        senderNameLarge.isSelected = true
                        senderNameLarge.post { senderNameLarge.isSelected = true }
                    }
                    senderNumberLarge.beGoneIf(
                        threadSubtitle.isEmpty() || threadTitle == threadSubtitle || participantCount > 1,
                    )
                    senderNumberLarge.text = threadSubtitle
                    senderNumberLarge.setTextColor(textColor)
                    if (bindInteractions) {
                        senderNameLarge.setOnLongClickListener { copyToClipboard(senderNameLarge.value); true }
                    }
                }
            }
        }
        if (isThreadMessageSelectionModeActive()) {
            topDetailsCompact.root.beGone()
            topDetailsLarge.beGone()
            threadToolbar.beGone()
        }
    }

    private fun setupThreadTitle() {
        // For multiple participants always show "first user's name or phone and N others" in sender_name_large/sender_name
        var threadTitle = resolveThreadDisplayTitle()
        // Hide country code prefix (e.g. +850) when displaying raw phone number not in contacts
        if (participants.size == 1) {
            val phoneNumber = participants.first().phoneNumbers.firstOrNull()?.normalizedNumber ?: ""
            val normalizedTitle = threadTitle.normalizePhoneNumber()
            val normalizedPhone = phoneNumber.normalizePhoneNumber()
            if (phoneNumber.isNotEmpty() &&
                (normalizedTitle == normalizedPhone || threadTitle == phoneNumber) &&
                threadTitle.startsWith("+")
            ) {
                threadTitle = getDisplayNumberWithoutCountryCode(phoneNumber)
            }
        }
        val threadSubtitle = participants.getThreadSubtitle(this@ThreadActivity)
        bindThreadHeaderUi(threadTitle, threadSubtitle, participants.size, bindInteractions = true)
        syncThreadTopBlurStripGeometry()
    }

    @SuppressLint("MissingPermission")
    private fun setupSIMSelector() {
        val textColor = getProperTextColor()
        val availableSIMs = subscriptionManagerCompat().activeSubscriptionInfoList ?: run {
            binding.messageHolder.threadSelectSimIconHolder.beGone()
            binding.messageHolder.syncEmojiButtonWithSimHolderVisibility()
            return
        }
        if (availableSIMs.size > 1) {
            binding.messageHolder.threadSelectSimIconHolder.beVisible()
            availableSIMCards.clear()
            availableSIMs.forEachIndexed { index, subscriptionInfo ->
                var label = subscriptionInfo.displayName?.toString() ?: ""
                if (subscriptionInfo.number?.isNotEmpty() == true) {
                    label += " (${subscriptionInfo.number})"
                }
                val SIMCard = SIMCard(index + 1, subscriptionInfo.subscriptionId, label, subscriptionInfo.mnc)
                availableSIMCards.add(SIMCard)
            }

            val numbers = ArrayList<String>()
            participants.forEach { contact ->
                contact.phoneNumbers.forEach {
                    numbers.add(it.normalizedNumber)
                }
            }

            if (numbers.isEmpty()) {
                binding.messageHolder.threadSelectSimIconHolder.beGone()
                binding.messageHolder.syncEmojiButtonWithSimHolderVisibility()
                return
            }

            binding.messageHolder.threadSelectSimIcon.beVisible()
            binding.messageHolder.threadSelectSimNumber.beVisible()

            currentSIMCardIndex = getProperSimIndex(availableSIMs, numbers)
//            binding.messageHolder.threadSelectSimIcon.background.applyColorFilter(
//                resources.getColor(com.goodwy.commons.R.color.activated_item_foreground, theme)
//            )
//            binding.messageHolder.threadSelectSimIcon.applyColorFilter(getProperTextColor())
            binding.messageHolder.threadSelectSimIcon.applyColorFilter(
                resolveSimIconTint(
                    textColor,
                    availableSIMCards.getOrNull(currentSIMCardIndex)?.subscriptionId ?: -1,
                    availableSIMCards.getOrNull(currentSIMCardIndex)?.id ?: 1
                )
            )
            binding.messageHolder.threadSelectSimIconHolder.beVisibleIf(!config.showSimSelectionDialog)
            binding.messageHolder.threadAvailableMessageCount.beVisible()
            // added by sun ------->
            // if ask every time in system setting, not show sim icon
            if (SmsManager.getDefaultSmsSubscriptionId() < 0) {
                binding.messageHolder.threadSelectSimNumber.beGone()
                binding.messageHolder.threadSelectSimIconHolder.beGone()
            }
            // <----------
            val simLabel =
                if (availableSIMCards.size > currentSIMCardIndex) availableSIMCards[currentSIMCardIndex].label else "SIM Card"
            binding.messageHolder.threadAvailableMessageCount.contentDescription = simLabel

            if (availableSIMCards.isNotEmpty()) {
                binding.messageHolder.threadSelectSimIconHolder.setOnClickListener {
                    val blurTarget = this.findViewById<BlurTarget>(R.id.mainBlurTarget)
                    SelectSIMDialog(
                        activity = this,
                        blurTarget = blurTarget,
                        anchorView = binding.messageHolder.threadSendMessageActionWrapper
                    ) { _, selectedHandleIndex ->
                        currentSIMCardIndex = selectedHandleIndex
                        val currentSIMCard = availableSIMCards[currentSIMCardIndex]
                        @SuppressLint("SetTextI18n")
                        binding.messageHolder.threadSelectSimNumber.text = currentSIMCard.id.toString()
                        // changed by sun for set color to sim type --->
                        val simColor = resolveSimIconTint(textColor, currentSIMCard.subscriptionId, currentSIMCard.id)
                        trySetSystemDefaultSmsSubscriptionId(currentSIMCard.subscriptionId)
                        // <------------

                        binding.messageHolder.threadSelectSimIcon.applyColorFilter(simColor)
                        val currentSubscriptionId = currentSIMCard.subscriptionId
                        numbers.forEach {
                            config.saveUseSIMIdAtNumber(it, currentSubscriptionId)
                        }
                        it.performHapticFeedback()
                        binding.messageHolder.threadSelectSimIconHolder.contentDescription = currentSIMCard.label
                        toast(currentSIMCard.label)
                        updateAvailableMessageCountForCurrentSim()
                    }
                }
            }

            binding.messageHolder.threadSelectSimNumber.setTextColor(textColor.getContrastColor())
            try {
                @SuppressLint("SetTextI18n")
                binding.messageHolder.threadSelectSimNumber.text = (availableSIMCards[currentSIMCardIndex].id).toString()
                // changed by sun for set color to sim type --->
//                val simColor =
//                    if (!config.colorSimIcons) textColor
//                    else {
//                        val simId = availableSIMCards[currentSIMCardIndex].id
//                        if (simId in 1..4) config.simIconsColors[simId] else config.simIconsColors[0]
//                    }
                val simColor = resolveSimIconTint(
                    textColor,
                    availableSIMCards[currentSIMCardIndex].subscriptionId,
                    availableSIMCards[currentSIMCardIndex].id
                )
                // <------------

                binding.messageHolder.threadSelectSimIcon.applyColorFilter(simColor)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
        else {
            binding.messageHolder.threadSelectSimIconHolder.beGone()
            binding.messageHolder.threadSelectSimIcon.beGone()
            binding.messageHolder.threadSelectSimNumber.beGone()
        }
        binding.messageHolder.syncEmojiButtonWithSimHolderVisibility()
        updateAvailableMessageCountForCurrentSim()
    }

    private fun updateAvailableMessageCountForCurrentSim() {
        if (!config.showSmsRemainedCount) {
            binding.messageHolder.threadAvailableMessageCount.beGone()
            return
        }
        val slotId = FeeInfoUtils.getCurrentSimSlotId(
            context = this,
            availableSIMCards = availableSIMCards,
            currentSIMCardIndex = currentSIMCardIndex
        )
        Log.d(debugTag, "updateAvailableMessageCountForCurrentSim: resolved slotId=$slotId")
        if (slotId == null) {
            Log.d(debugTag, "updateAvailableMessageCountForCurrentSim: slotId is null, hiding view")
            binding.messageHolder.threadAvailableMessageCount.beGone()
            return
        }

        ensureBackgroundThread {
            val smsCount = FeeInfoUtils.getAvailableSmsCountForSlot(this, slotId)
            Log.d(debugTag, "updateAvailableMessageCountForCurrentSim: slotId=$slotId, smsCount=$smsCount")
            runOnUiThread {
                val countView = binding.messageHolder.threadAvailableMessageCount
                if (smsCount == null) {
                    countView.beGone()
                } else {
                    countView.text = getString(R.string.available_sms_count, smsCount)
                    countView.setTextColor(getProperTextColor())
                    countView.beVisible()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getProperSimIndex(
        availableSIMs: MutableList<SubscriptionInfo>,
        numbers: List<String>,
    ): Int {
        val userPreferredSimId = config.getUseSIMIdAtNumber(numbers.first())
        val userPreferredSimIdx =
            availableSIMs.indexOfFirstOrNull { it.subscriptionId == userPreferredSimId }

        val lastMessage = messages.lastOrNull()
        val senderPreferredSimIdx = if (lastMessage?.isReceivedMessage() == true) {
            availableSIMs.indexOfFirstOrNull { it.subscriptionId == lastMessage.subscriptionId }
        } else {
            null
        }

        val defaultSmsSubscriptionId = SmsManager.getDefaultSmsSubscriptionId()
        val systemPreferredSimIdx = if (defaultSmsSubscriptionId >= 0) {
            availableSIMs.indexOfFirstOrNull { it.subscriptionId == defaultSmsSubscriptionId }
        } else {
            null
        }

        return userPreferredSimIdx ?: senderPreferredSimIdx ?: systemPreferredSimIdx ?: 0
    }

//    private fun tryBlocking() {
//        if (isOrWasThankYouInstalled()) {
//            blockNumber()
//        } else {
//            FeatureLockedDialog(this) { }
//        }
//    }

    private fun showMConfirmDialog(question: String, onConfirm: () -> Unit) {
        val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        val dialog = MConfirmDialog(this)
        dialog.bindBlurTarget(blurTarget)
        dialog.setContent(question)
        dialog.setConfirmTitle(resources.getString(com.goodwy.commons.R.string.ok))
        dialog.setCancelTitle(resources.getString(com.goodwy.commons.R.string.cancel))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCompleteListener { isConfirm ->
            if (isConfirm) {
                onConfirm()
            }
        }
        dialog.show()
        trackOpenDialog(dialog)
    }

    private fun isBlockNumbers(): Boolean {
        return participants.getAddresses().any { isNumberBlocked(it, getBlockedNumbers()) }
    }

    private fun blockNumber() {
        val numbers = participants.getAddresses()
        val numbersString = TextUtils.join(", ", numbers)
        val isBlockNumbers = isBlockNumbers()
        val baseString =
            if (isBlockNumbers) com.goodwy.strings.R.string.unblock_confirmation
            else com.goodwy.commons.R.string.block_confirmation
        val question = String.format(resources.getString(baseString), numbersString)

        showMConfirmDialog(question) {
            ensureBackgroundThread {
                numbers.forEach {
                    if (isBlockNumbers) {
                        deleteBlockedNumber(it)
                        runOnUiThread { refreshMenuItems() }
                    } else {
                        addBlockedNumber(it)
                        runOnUiThread { refreshMenuItems() }
                    }
                }
                refreshConversations()
                //finish()
            }
        }
    }

    private fun askConfirmDelete() {
        val confirmationMessage = R.string.delete_whole_conversation_confirmation
        showMConfirmDialog(getString(confirmationMessage)) {
            ensureBackgroundThread {
                if (isRecycleBin) {
                    emptyMessagesRecycleBinForConversation(threadId)
                } else {
                    deleteConversation(threadId)
                }
                runOnUiThread {
                    refreshConversations()
                    finish()
                }
            }
        }
    }

    private fun askConfirmRestoreAll() {
        showMConfirmDialog(getString(R.string.restore_confirmation)) {
            ensureBackgroundThread {
                restoreAllMessagesFromRecycleBinForConversation(threadId)
                runOnUiThread {
                    refreshConversations()
                    finish()
                }
            }
        }
    }

    private fun archiveConversation() {
        ensureBackgroundThread {
            updateConversationArchivedStatus(threadId, true)
            runOnUiThread {
                refreshConversations()
                finish()
            }
        }
    }

    private fun unarchiveConversation() {
        ensureBackgroundThread {
            updateConversationArchivedStatus(threadId, false)
            runOnUiThread {
                refreshConversations()
                finish()
            }
        }
    }

    private fun dialNumber() {
        val phoneNumber = participants.first().phoneNumbers.first().normalizedNumber
        dialNumber(phoneNumber)
    }

    private fun markAsUnread() {
        ensureBackgroundThread {
            conversationsDB.markUnread(threadId)
            // Match ConversationsAdapter: single-message unread updates Telephony reliably; bulk thread unread often does not.
            markLastMessageUnread(threadId)
            runOnUiThread {
                finish()
                bus?.post(Events.RefreshConversations())
            }
        }
    }

    private fun addNumberToContact() {
        val phoneNumber =
            participants.firstOrNull()?.phoneNumbers?.firstOrNull()?.normalizedNumber ?: return
        launchAddNumberToContactFlow(phoneNumber)
    }

    @SuppressLint("MissingPermission")
    private fun renameConversation() {
        val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        RenameConversationDialog(this, conversation!!, blurTarget) { title ->
            ensureBackgroundThread {
                conversation = renameConversation(conversation!!, newTitle = title)
                runOnUiThread {
                    setupThreadTitle()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getThreadItems(): ArrayList<ThreadItem> {
        val items = ArrayList<ThreadItem>()
        if (isFinishing) {
            return items
        }

        messages.sortWith(compareBy({ it.date }, { it.id }))

        val subscriptionIdToSimId = HashMap<Int, String>()
        subscriptionIdToSimId[-1] = "?"
        subscriptionManagerCompat().activeSubscriptionInfoList?.forEachIndexed { index, subscriptionInfo ->
            subscriptionIdToSimId[subscriptionInfo.subscriptionId] = "${index + 1}"
        }

        var prevDateDay = -1 // track calendar day (yyyyMMdd) to show date header only on date change
        var hadUnreadItems = false
        val cnt = messages.size
        for (i in 0 until cnt) {
            val message = messages.getOrNull(i) ?: continue
            // Show date header (MM.DD) only for the first message of each calendar day
            val cal = java.util.Calendar.getInstance(java.util.Locale.ENGLISH)
            cal.timeInMillis = message.date * 1000L
            val messageDateDay = cal.get(java.util.Calendar.YEAR) * 10000 +
                (cal.get(java.util.Calendar.MONTH) + 1) * 100 + cal.get(java.util.Calendar.DAY_OF_MONTH)
            if (messageDateDay != prevDateDay) {
                val simCardID = subscriptionIdToSimId[message.subscriptionId] ?: "?"
                items.add(ThreadDateTime(message.date, simCardID))
                prevDateDay = messageDateDay
            }
            items.add(message)

            if (!message.read) {
                hadUnreadItems = true
                markMessageRead(message.id, message.isMMS)
                conversationsDB.markRead(threadId)
            }

        }

        if (hadUnreadItems) {
            bus?.post(Events.RefreshConversations())
        }

        return items
    }

    private fun launchActivityForResult(
        intent: Intent,
        requestCode: Int,
        @StringRes error: Int = com.goodwy.commons.R.string.no_app_found,
    ) {
        hideKeyboard()
        try {
            startActivityForResult(intent, requestCode)
        } catch (_: ActivityNotFoundException) {
            showErrorToast(getString(error))
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun getAttachmentsDir(): File {
        return File(cacheDir, "attachments").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    private fun launchCapturePhotoIntent() {
        val imageFile = File.createTempFile("attachment_", ".jpg", getAttachmentsDir())
        val capturedImageUri = getMyFileUri(imageFile)
//        messageHolderHelper?.setCapturedImageUri(capturedImageUri)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, capturedImageUri)
        }
        launchActivityForResult(intent, MessageHolderHelper.CAPTURE_PHOTO_INTENT)
    }

    private fun launchCaptureVideoIntent() {
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        launchActivityForResult(intent, MessageHolderHelper.CAPTURE_VIDEO_INTENT)
    }

    private fun launchCaptureAudioIntent() {
        val intent = Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
        launchActivityForResult(intent, MessageHolderHelper.CAPTURE_AUDIO_INTENT)
    }

    private fun launchGetContentIntent(mimeTypes: Array<String>, requestCode: Int) {
        Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            launchActivityForResult(this, requestCode)
        }
    }

    private fun launchPickContactIntent() {
        val intent = MessageHolderHelper.createSelectContactsIntent()
        val resolved = packageManager.resolveActivity(intent, 0)
        Log.d(
            debugTag,
            "launchPickContactIntent action=${intent.action} resolved=${resolved?.activityInfo?.packageName}/" +
                "${resolved?.activityInfo?.name}",
        )
        launchActivityForResult(intent, MessageHolderHelper.PICK_CONTACT_INTENT)
    }

    private fun addContactAttachment(contactUri: Uri) {
        messageHolderHelper?.addContactAttachment(contactUri)
    }

    private fun addAttachment(uri: Uri) {
        messageHolderHelper?.addAttachment(uri)
    }

    private fun saveAttachments(resultData: Intent) {
        applicationContext.contentResolver.takePersistableUriPermission(
            resultData.data!!, FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val destinationUri = resultData.data ?: return
        ensureBackgroundThread {
            try {
                if (DocumentsContract.isTreeUri(destinationUri)) {
                    val outputDir = DocumentFile.fromTreeUri(this, destinationUri)
                        ?: return@ensureBackgroundThread
                    pendingAttachmentsToSave?.forEach { attachment ->
                        val documentFile = outputDir.createFile(
                            attachment.mimetype,
                            attachment.filename.takeIf { it.isNotBlank() }
                                ?: attachment.uriString.getFilenameFromPath()
                        ) ?: return@forEach
                        copyToUri(src = attachment.getUri(), dst = documentFile.uri)
                    }
                } else {
                    copyToUri(pendingAttachmentsToSave!!.first().getUri(), resultData.data!!)
                }

                toast(com.goodwy.commons.R.string.file_saved)
            } catch (e: Exception) {
                showErrorToast(e)
            } finally {
                pendingAttachmentsToSave = null
            }
        }
    }

    private fun checkSendMessageAvailability() {
        messageHolderHelper?.checkSendMessageAvailability()
    }

    private fun sendMessage() {
        val text = messageHolderHelper?.getMessageText() ?: ""
        if (text.isEmpty() && (messageHolderHelper?.getAttachmentSelections()?.isEmpty() != false)) {
            showErrorToast(getString(com.goodwy.commons.R.string.unknown_error_occurred))
            return
        }
        scrollToBottom()

        val processedText = removeDiacriticsIfNeeded(text)
        val subscriptionId = availableSIMCards.getOrNull(currentSIMCardIndex)?.subscriptionId
            ?: SmsManager.getDefaultSmsSubscriptionId()

        if (config.showSimSelectionDialog && availableSIMCards.size > 1) {
            val items: ArrayList<RadioItem> = arrayListOf()
            items.clear()
            availableSIMCards.forEach {
//                val simColor = if (it.id in 1..4) config.simIconsColors[it.id] else config.simIconsColors[0]
                val simColor = resolveSimIconTint(getProperTextColor(), it.subscriptionId, it.id)
                val res = when (it.id) {
                    1 -> R.drawable.ic_sim_one
                    2 -> R.drawable.ic_sim_two
                    else -> R.drawable.ic_sim_vector
                }
                val drawable = ResourcesCompat.getDrawable(resources, res, theme)?.apply {
                    applyColorFilter(simColor)
                }
                items.add(RadioItem(it.id, it.label, it, drawable = drawable))
            }
            val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            RadioGroupIconDialog(this@ThreadActivity, items, blurTarget = blurTarget) {
                val simId = (it as SIMCard).subscriptionId
                if (isScheduledMessage) {
                    sendScheduledMessage(processedText, simId)
                } else {
                    sendNormalMessage(processedText, simId)
                }
            }
        } else {
            if (isScheduledMessage) {
                sendScheduledMessage(processedText, subscriptionId)
            } else {
                sendNormalMessage(processedText, subscriptionId)
            }
        }
    }


    private fun sendScheduledMessage(text: String, subscriptionId: Int) {
        if (scheduledDateTime.millis < System.currentTimeMillis() + 1000L) {
            toast(R.string.must_pick_time_in_the_future)
            launchScheduleSendDialog()
            return
        }

        refreshedSinceSent = false
        try {
            ensureBackgroundThread {
                val messageId = scheduledMessage?.id ?: generateRandomId()
                val message = buildScheduledMessage(text, subscriptionId, messageId)
                threadId = message.threadId
                upsertConversationForScheduledMessage(message, threadId, conversation)
                scheduleMessage(message)
                insertOrUpdateMessage(message)
                refreshConversations()

                runOnUiThread {
                    clearCurrentMessage()
                    deleteComposeAttachmentCacheIfUnnecessary()
                    hideScheduleSendUi()
                    scheduledMessage = null
                }
            }
        } catch (e: Exception) {
            showErrorToast(
                e.localizedMessage ?: getString(com.goodwy.commons.R.string.unknown_error_occurred)
            )
        }
    }

    private fun sendNormalMessage(
        text: String,
        subscriptionId: Int,
        attachments: List<Attachment> = messageHolderHelper?.buildMessageAttachments() ?: emptyList(),
        messageId: Long? = messageToResend,
        clearCompose: Boolean = true,
    ) {
        val addresses = participants.getAddresses()

        try {
            refreshedSinceSent = false
            sendMessageCompat(
                text = text,
                addresses = addresses,
                subId = subscriptionId,
                attachments = attachments,
                messageId = messageId,
                showDeliveredToastOnSuccess = addresses.size == 1,
            )
            ensureBackgroundThread {
                deleteSmsDraft(threadId)
                val latestMessages = getMessages(
                    threadId,
                    limit = maxOf(1, attachments.size),
                    includeBlockedMessages = showBlockedMessagesInThread,
                )
                val messagesToInsertOrUpdate = latestMessages
                    .filterNotInByKey(messages) { it.getStableId() }
                    .toMutableList()
                messageId?.let { resentMessageId ->
                    latestMessages.firstOrNull { it.id == resentMessageId }?.let { resentMessage ->
                        if (messagesToInsertOrUpdate.none { it.getStableId() == resentMessage.getStableId() }) {
                            messagesToInsertOrUpdate.add(resentMessage)
                        }
                    }
                }
                for (message in messagesToInsertOrUpdate) {
                    insertOrUpdateMessage(message)
                }
            }
            if (clearCompose) {
                clearCurrentMessage()
                deleteComposeAttachmentCacheIfUnnecessary()
            }

        } catch (e: Exception) {
            showErrorToast(e)
        } catch (e: Error) {
            showErrorToast(
                e.localizedMessage ?: getString(com.goodwy.commons.R.string.unknown_error_occurred)
            )
        }
    }

    private fun clearCurrentMessage() {
        messageHolderHelper?.clearMessage()
    }

    private fun insertOrUpdateMessage(message: Message) {
        if (messages.map { it.id }.contains(message.id)) {
            val messageToReplace = messages.find { it.id == message.id }
            messages[messages.indexOf(messageToReplace)] = message
        } else {
            messages.add(message)
        }

        val newItems = getThreadItems()
        runOnUiThread {
            getOrCreateThreadAdapter().updateMessages(newItems, newItems.lastIndex)
            if (!refreshedSinceSent) {
                refreshMessages()
            }
        }
        messagesDB.insertOrUpdate(message)
        if (shouldUnarchive()) {
            updateConversationArchivedStatus(message.threadId, false)
            refreshConversations()
        }
    }

    private fun getPhoneNumbersFromIntent(): ArrayList<String> {
        val numberFromIntent = intent.getStringExtra(THREAD_NUMBER)
        val numbers = ArrayList<String>()

        if (numberFromIntent != null) {
            if (numberFromIntent.startsWith('[') && numberFromIntent.endsWith(']')) {
                val type = object : TypeToken<List<String>>() {}.type
                numbers.addAll(Gson().fromJson(numberFromIntent, type))
            } else {
                numbers.add(numberFromIntent)
            }
        }
        return numbers
    }

    private fun fixParticipantNumbers(
        participants: ArrayList<SimpleContact>,
        properNumbers: ArrayList<String>,
    ): ArrayList<SimpleContact> {
        for (number in properNumbers) {
            for (participant in participants) {
                participant.phoneNumbers = participant.phoneNumbers.map {
                    val numberWithoutPlus = number.replace("+", "")
                    if (numberWithoutPlus == it.normalizedNumber.trim()) {
                        if (participant.name == it.normalizedNumber) {
                            participant.name = number
                        }
                        PhoneNumber(number, 0, "", number)
                    } else {
                        PhoneNumber(it.normalizedNumber, 0, "", it.normalizedNumber)
                    }
                } as ArrayList<PhoneNumber>
            }
        }

        return participants
    }

    fun saveMMS(attachments: List<Attachment>) {
        pendingAttachmentsToSave = attachments
        if (attachments.size == 1) {
            val attachment = attachments.first()
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = attachment.mimetype
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, attachment.uriString.split("/").last())
                launchActivityForResult(
                    intent = this,
                    requestCode = PICK_SAVE_FILE_INTENT,
                    error = com.goodwy.commons.R.string.system_service_disabled
                )
            }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                launchActivityForResult(
                    intent = this,
                    requestCode = PICK_SAVE_DIR_INTENT,
                    error = com.goodwy.commons.R.string.system_service_disabled
                )
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onPendingSendCountdownCompleted(event: Events.PendingSendCountdownCompleted) {
        if (event.threadId != threadId) return
        messageHolderHelper?.clearMessage()
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun refreshMessages(@Suppress("unused") event: Events.RefreshMessages) {
        if (isRecycleBin) {
            return
        }

        refreshedSinceSent = true
        allMessagesFetched = false

        if (isActivityVisible) {
            notificationManager.cancel(threadId.hashCode())
        }

        val lastMaxId = messages.filterNot { it.isScheduled }.maxByOrNull { it.id }?.id ?: 0L
        val newThreadId = getThreadId(participants.getAddresses().toSet())
        val newMessages = getMessages(
            newThreadId,
            includeScheduledMessages = false,
            includeBlockedMessages = showBlockedMessagesInThread,
        )

        if (messages.isNotEmpty() && messages.all { it.isScheduled } && newMessages.isNotEmpty()) {
            // update scheduled messages with real thread id
            threadId = newThreadId
            if (isActivityVisible) {
                setVisibleThreadId(threadId)
            }
            updateScheduledMessagesThreadId(
                messages = messages.filter { it.threadId != threadId },
                newThreadId = threadId
            )
        }

        messages = newMessages.apply {
            val scheduledMessages = messagesDB.getScheduledThreadMessages(threadId)
                .filterNot { it.isScheduled && it.millis() < System.currentTimeMillis() }
            addAll(scheduledMessages)
            if (config.useRecycleBin) {
                val recycledMessages = messagesDB.getThreadMessagesFromRecycleBin(threadId).toSet()
                removeAll(recycledMessages)
            }
        }

        messages.filter { !it.isScheduled && !it.isReceivedMessage() && it.id > lastMaxId }
            .forEach { latestMessage ->
                messagesDB.insertOrIgnore(latestMessage)
            }

        setupAdapter()
        runOnUiThread {
            setupSIMSelector()
        }
    }

    private fun isMmsMessage(text: String): Boolean {
        val isGroupMms = participants.size > 1 && config.sendGroupMessageMMS
        val isLongMmsMessage = isLongMmsMessage(text)
        return (messageHolderHelper?.getAttachmentSelections()?.isNotEmpty() == true) || isGroupMms || isLongMmsMessage
    }

//    private fun updateMessageType() {
//        val text = binding.messageHolder.threadTypeMessage.text.toString()
//        val stringId = if (isMmsMessage(text)) {
//            R.string.mms
//        } else {
//            R.string.sms
//        }
//        //binding.messageHolder.threadSendMessage.setText(stringId)
//    }

    private fun showScheduledMessageInfo(message: Message) {
        val items = arrayListOf(
            RadioItem(TYPE_EDIT, getString(R.string.update_message)),
            RadioItem(TYPE_SEND, getString(R.string.send_now)),
            RadioItem(TYPE_DELETE, getString(com.goodwy.commons.R.string.delete))
        )
        val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        RadioGroupDialog(
            activity = this,
            items = items,
            titleId = R.string.scheduled_message,
            blurTarget = blurTarget,
            requireConfirmButton = true
        ) { any ->
            when (any as Int) {
                TYPE_DELETE -> cancelScheduledMessageAndRefresh(message.id)
                TYPE_EDIT -> editScheduledMessage(message)
                TYPE_SEND -> {
                    messages.removeAll { message.id == it.id }
                    extractAttachments(message)
                    sendNormalMessage(message.body, message.subscriptionId)
                    cancelScheduledMessageAndRefresh(message.id)
                }
            }
        }
    }

    private fun extractAttachments(message: Message) {
        val messageAttachment = message.attachment
        if (messageAttachment != null) {
            for (attachment in messageAttachment.attachments) {
                addAttachment(attachment.getUri())
            }
        }
    }

    private fun editScheduledMessage(message: Message) {
        scheduledMessage = message
        clearCurrentMessage()
        binding.messageHolder.threadTypeMessage.setText(message.body)
        extractAttachments(message)
        scheduledDateTime = DateTime(message.millis()) //TODO Persian date
        showScheduleMessageDialog()
    }

    private fun cancelScheduledMessageAndRefresh(messageId: Long) {
        ensureBackgroundThread {
            deleteScheduledMessage(messageId)
            cancelScheduleSendPendingIntent(messageId)
            refreshMessages()
        }
    }

    private fun launchScheduleSendDialog() {
        askForExactAlarmPermissionIfNeeded {
            val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            showScheduleDateTimePicker(blurTarget) { newDateTime ->
                scheduledDateTime = newDateTime
                showScheduleMessageDialog()
            }
        }
    }

//    private fun isPlayServicesAvailable(): Boolean {
//        val googleAPI = GoogleApiAvailability.getInstance()
//        val result = googleAPI.isGooglePlayServicesAvailable(applicationContext)
//        return result == ConnectionResult.SUCCESS
//    }

    private fun setupScheduleSendUi() = binding.messageHolder.apply {
        scheduledMessageButton.apply {
            setOnClickListener {
                launchScheduleSendDialog()
            }
        }
        scheduledMessagePress.setOnClickListener {
            launchScheduleSendDialog()
        }

        discardScheduledMessage.apply {
            setOnClickListener {
                hideScheduleSendUi()
                if (scheduledMessage != null) {
                    cancelScheduledMessageAndRefresh(scheduledMessage!!.id)
                    scheduledMessage = null
                }
            }
        }
    }

    private fun showScheduleMessageDialog() {
        isScheduledMessage = true
        updateSendButtonDrawable()
        binding.messageHolder.scheduledMessageHolder.beVisible()

        val dateTime = scheduledDateTime
        val millis = dateTime.millis
        binding.messageHolder.scheduledMessageButton.text =
            if (dateTime.yearOfCentury().get() > DateTime.now().yearOfCentury().get()) {
                millis.formatDate(this)
            } else {
                val flags = FORMAT_SHOW_TIME or FORMAT_SHOW_DATE or FORMAT_NO_YEAR
                DateUtils.formatDateTime(this, millis, flags)
            }
    }

    private fun hideScheduleSendUi() {
        isScheduledMessage = false
        binding.messageHolder.scheduledMessageHolder.beGone()
        updateSendButtonDrawable()
    }

    private fun updateSendButtonDrawable() {
        messageHolderHelper?.setScheduledMessage(isScheduledMessage)
        messageHolderHelper?.updateSendButtonDrawable()
    }

    private fun buildScheduledMessage(text: String, subscriptionId: Int, messageId: Long): Message {
        val threadId = resolveScheduledMessageThreadId(
            addresses = participants.getAddresses(),
            existingThreadId = threadId,
            fallbackThreadId = messageId,
        )
        return Message(
            id = messageId,
            body = text,
            type = MESSAGE_TYPE_QUEUED,
            status = STATUS_NONE,
            participants = participants,
            date = (scheduledDateTime.millis / 1000).toInt(),
            dateSent = 0,
            read = false,
            threadId = threadId,
            isMMS = isMmsMessage(text),
            attachment = MessageAttachment(messageId, text, messageHolderHelper?.buildMessageAttachments(messageId) ?: arrayListOf()),
            senderPhoneNumber = "",
            senderName = "",
            senderPhotoUri = "",
            subscriptionId = subscriptionId,
            isScheduled = true
        )
    }


    private fun showAttachmentPicker() {
        messageHolderHelper?.showAttachmentPicker()
    }

    private fun maybeSetupRecycleBinView() {
        if (isRecycleBin) {
            binding.messageHolder.root.beGone()
        }
    }

    private fun hideAttachmentPicker() {
        messageHolderHelper?.hideAttachmentPicker()
    }

    private fun getBottomBarColor() = if (isDynamicTheme()) {
        getColoredMaterialStatusBarColor() //resources.getColor(R.color.you_bottom_bar_color)
    } else {
        getColoredMaterialStatusBarColor()
    }

    fun setupMessagingEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.messageHolder.threadTypeMessage
        ) { view, insets ->
            val type = WindowInsetsCompat.Type.ime()
            val isKeyboardVisible = insets.isVisible(type)
            if (isKeyboardVisible) {
                val keyboardHeight = insets.getInsets(type).bottom
                val bottomBarHeight = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom

                // check keyboard height just to be sure, 150 seems like a good middle ground between ime and navigation bar
                config.keyboardHeight = if (keyboardHeight > 150) {
                    keyboardHeight - bottomBarHeight
                } else {
                    getDefaultKeyboardHeight()
                }
                // IME and the attachment picker must never be visible together. When + is tapped with the
                // keyboard up, [pendingAttachmentPickerAfterKeyboardHide] defers showing the picker until IME hides.
                hideAttachmentPicker()
                if (!pendingAttachmentPickerAfterKeyboardHide) {
                    isAttachmentPickerVisible = false
                }
                wasKeyboardVisible = true
                hadKeyboardLayoutWhenLeaving = true
            } else {
                wasKeyboardVisible = false
                if (!freezeThreadListLayoutOnResume) {
                    hadKeyboardLayoutWhenLeaving = false
                }
                if (pendingAttachmentPickerAfterKeyboardHide) {
                    binding.root.post { finishOpeningAttachmentPickerAfterKeyboardHide() }
                } else if (isAttachmentPickerVisible &&
                    composeBarBottomInsetLatch != ComposeBarBottomInsetLatch.ATTACHMENT_PICKER_TO_KEYBOARD
                ) {
                    showAttachmentPicker()
                }
            }

            insets
        }
    }

    companion object {
        @Volatile
        private var visibleThreadId: Long? = null

        fun isThreadCurrentlyVisible(threadId: Long): Boolean {
            return visibleThreadId != null && visibleThreadId == threadId
        }

        private fun setVisibleThreadId(threadId: Long) {
            visibleThreadId = threadId.takeIf { it > 0L }
        }

        private fun clearVisibleThreadIdIfMatches(threadId: Long) {
            if (visibleThreadId == threadId) {
                visibleThreadId = null
            }
        }

        private const val ACTION_FEE_INFO_SET = "com.chonha.total.action.ACTION_FEE_INFO_SET"
        const val TYPE_EDIT = 14
        const val TYPE_SEND = 15
        const val TYPE_DELETE = 16
        const val MIN_DATE_TIME_DIFF_SECS = 300
        //        deleted by sun unnecessary ---->
        const val SCROLL_TO_BOTTOM_FAB_LIMIT = 20
        //        <--------
        const val PREFETCH_THRESHOLD = 15
        const val PICK_SAVE_FILE_INTENT = 2012
        const val PICK_SAVE_DIR_INTENT = 2013
    }

    private fun updateContactImage() {
        val senderPhoto = when (config.threadTopStyle) {
            THREAD_TOP_COMPACT -> binding.topDetailsCompact.senderPhoto
            // THREAD_TOP_LARGE -> binding.senderPhotoLarge
            THREAD_TOP_LARGE -> null
            else -> binding.topDetailsCompact.senderPhoto
        }

        if (senderPhoto == null) return

        var threadTitle = resolveThreadDisplayTitle()
        if (threadTitle.isEmpty()) threadTitle = intent.getStringExtra(THREAD_TITLE) ?: ""

        if (conversation != null && (!isDestroyed || !isFinishing)) {
            if ((threadTitle == conversation!!.phoneNumber || conversation!!.isCompany) && conversation!!.photoUri == "") {
                val drawable =
                    if (conversation!!.isCompany) SimpleContactsHelper(this@ThreadActivity).getColoredCompanyIcon(conversation!!.title)
                    else SimpleContactsHelper(this@ThreadActivity).getColoredContactIcon(conversation!!.title)
                senderPhoto.setImageDrawable(drawable)
            } else {
                val placeholder = if (participants.size > 1) {
                    SimpleContactsHelper(this).getColoredGroupIcon(threadTitle)
                } else {
                    null
                }

                SimpleContactsHelper(this).loadContactImage(conversation!!.photoUri, senderPhoto, threadTitle, placeholder)
            }
        } else {
            if (!isDestroyed || !isFinishing) {
                val placeholder = if (participants.size > 1) {
                    SimpleContactsHelper(this).getColoredGroupIcon(threadTitle)
                } else {
                    null
                }

                val number = intent.getStringExtra(THREAD_NUMBER)
                var namePhoto: NamePhoto? = null
                if (number != null) {
                    namePhoto = getNameAndPhotoFromPhoneNumber(number)
                }
                var threadUri = intent.getStringExtra(THREAD_URI) ?: ""
                if (threadUri == "" && namePhoto != null) {
                    threadUri = namePhoto.photoUri ?: ""
                }
                if (threadTitle.isEmpty() && namePhoto != null) threadTitle = namePhoto.name
                SimpleContactsHelper(this).loadContactImage(threadUri, senderPhoto, threadTitle, placeholder)
            }
        }
    }


    private fun showExpandedMessageFragment() {
        val currentText = binding.messageHolder.threadTypeMessage.text?.toString() ?: ""
        expandedMessageFragment = com.android.mms.fragments.ExpandedMessageFragment.newInstance(currentText)

        expandedMessageFragment?.setOnMessageTextChangedListener { text ->
            binding.messageHolder.threadTypeMessage.setText(text)
        }

        expandedMessageFragment?.setOnSendMessageListener { subscriptionId ->
            val text = expandedMessageFragment?.getMessageText() ?: ""
            binding.messageHolder.threadTypeMessage.setText(text)
            hideExpandedMessageFragment()
            val attachments = messageHolderHelper?.buildMessageAttachments() ?: emptyList()
            sendMessageWithHelper(text, subscriptionId, attachments)
//            val blurTarget = this.findViewById<BlurTarget>(R.id.mainBlurTarget)
//            SelectSIMDialog(this, blurTarget, anchorView = binding.messageHolder.threadSendMessage) { _, selectedHandleIndex ->
//                this.config.currentSIMCardIndex = selectedHandleIndex
//                sendMessage()
//            }
        }

        expandedMessageFragment?.setOnMinimizeListener {
            val text = expandedMessageFragment?.getMessageText() ?: ""
            binding.messageHolder.threadTypeMessage.setText(text)
            hideExpandedMessageFragment()
        }

        expandedMessageFragment?.setupSendConfiguration(
            isSpeechToTextAvailable = isSpeechToTextAvailable,
            hasReadyAttachments = {
                val selections = messageHolderHelper?.getAttachmentSelections() ?: emptyList()
                selections.isNotEmpty() && !selections.any { it.isPending }
            },
            onSpeechToText = { speechToText() },
            resolveSubscriptionForSend = { anchorView, onSubId ->
                messageHolderHelper?.resolveSubscriptionForSend(
                    anchorView = anchorView,
                    anchorPlacement = SelectSimDialogAnchorPlacement. BOTTOM_RIGHT_OF_ANCHOR,
                    onSubId
                ) ?: SendSubscriptionHelper.resolveForSend()?.let(onSubId)
            }

        )

        // Update fragment thread title after fragment is created
        expandedMessageFragment?.let { fragment ->
            // Set up lifecycle observer BEFORE committing transaction to ensure it catches the lifecycle events
            val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                    // Update when fragment resumes (view is guaranteed to be created by then)
                    updateFragmentThreadTitle(fragment)
                    fragment.lifecycle.removeObserver(this)
                }
            }
            fragment.lifecycle.addObserver(observer)

            // Hide the main content and show the fragment container (sibling of thread_coordinator)
            findViewById<View>(R.id.thread_coordinator)?.beGone()
            findViewById<View>(R.id.fragment_container)?.beVisible()

            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack(null)
                .commit()

            // Also try immediate update if view is already available (for faster execution)
            // Use postDelayed to give the fragment time to create its view
            fragment.view?.post {
                updateFragmentThreadTitle(fragment)
            } ?: run {
                // If view is null, post with a small delay
                Handler(android.os.Looper.getMainLooper()).postDelayed({
                    fragment.view?.post {
                        updateFragmentThreadTitle(fragment)
                    }
                }, 100)
            }
        }
    }

    private fun updateFragmentThreadTitle(fragment: com.android.mms.fragments.ExpandedMessageFragment) {
        if (fragment.view == null) return

        var threadTitle = resolveThreadDisplayTitle()
        // Hide country code prefix (e.g. +850) when displaying raw phone number not in contacts
        if (participants.size == 1) {
            val phoneNumber = participants.first().phoneNumbers.firstOrNull()?.normalizedNumber ?: ""
            val normalizedTitle = threadTitle.normalizePhoneNumber()
            val normalizedPhone = phoneNumber.normalizePhoneNumber()
            if (phoneNumber.isNotEmpty() && (normalizedTitle == normalizedPhone || threadTitle == phoneNumber)) {
                threadTitle = getDisplayNumberWithoutCountryCode(phoneNumber)
            }
        }
        val threadSubtitle = participants.getThreadSubtitle(this@ThreadActivity)
        fragment.updateThreadTitle(
            threadTitle = threadTitle,
            threadSubtitle = threadSubtitle,
            threadTopStyle = config.threadTopStyle,
            showContactThumbnails = config.showContactThumbnails,
            conversationPhotoUri = conversation?.photoUri,
            conversationTitle = conversation?.title,
            conversationPhoneNumber = conversation?.phoneNumber,
            isCompany = conversation?.isCompany ?: false,
            participantsCount = participants.size,
            threadId = threadId,
        )
    }

    private fun hideExpandedMessageFragment() {
        expandedMessageFragment?.let {
            supportFragmentManager.popBackStack()
            findViewById<View>(R.id.fragment_container)?.beGone()
            findViewById<View>(R.id.thread_coordinator)?.beVisible()
            expandedMessageFragment = null
        }
    }

    override fun onBackPressedCompat(): Boolean {
        if (messageHolderHelper?.dismissEmojiPicker() == true) return true
        if (finishThreadMessageSelectionIfActive()) return true
        return if (expandedMessageFragment != null) {
            hideExpandedMessageFragment()
            true
        } else {
            false
        }
    }

    /** System back (e.g. 3-button nav) while the message list is in selection mode — same as CAB back. */
    private fun finishThreadMessageSelectionIfActive(): Boolean {
        val adapter = binding.threadMessagesList.adapter as? ThreadAdapter ?: return false
        if (!adapter.isActionModeActive()) return false
        adapter.finishActMode()
        return true
    }

    /** True while the message list is in CAB / multi-select mode ([ThreadAdapter] selection). */
    private fun isThreadMessageSelectionModeActive(): Boolean =
        (binding.threadMessagesList.adapter as? ThreadAdapter)?.isActionModeActive() == true

    override fun getActionModeToolbar(): com.goodwy.commons.views.CustomActionModeToolbar =
        binding.threadActionModeToolbar

    override fun showActionModeToolbar() {
        if (!isRecycleBin) {
            hideKeyboard()
            binding.messageHolder.threadTypeMessage.clearFocus()
            // The keyboard dismissal causes the RecyclerView to expand and items to shift.
            // Cancel any active drag selection after setDragSelectActive() has been called
            // (which happens synchronously after this method returns in viewLongClicked), so
            // that the layout change does not cause unintended range selection.
            binding.threadMessagesList.post {
                (binding.threadMessagesList.adapter as? ThreadAdapter)
                    ?.cancelDragSelection()
            }
        }
        binding.threadToolbar.beGone()
        binding.topDetailsCompact.root.beGone()
        binding.topDetailsLarge.beGone()
        binding.messageHolder.root.beGone()
        binding.threadActionModeToolbar.beVisible()
//        deleted by sun unnecessary ---->
//        binding.scrollToBottomFab.beGone()
//        <-----------
        binding.root.post {
            applyActionModeRippleToolbarForThread()
            refreshActionModeToolbarBlur()
            if (!isRecycleBin) {
                ViewCompat.requestApplyInsets(binding.root)
                syncMessageInputBarToBottomAfterFocusLoss()
                refreshThreadMessagesListPaddingForComposeBarHeight()
                binding.threadMessagesList.post {
                    refreshThreadMessagesListPaddingForComposeBarHeight()
                }
//                binding.root.findViewById<View>(R.id.lyt_action).post {
//                    refreshThreadMessagesListPaddingForComposeBarHeight()
//                }
            }
        }
    }

    override fun hideActionModeToolbar() {
        binding.threadActionModeToolbar.beGone()
        binding.actionModeRippleToolbar.visibility = View.GONE
        binding.threadToolbar.beVisible()
        if (config.threadTopStyle == THREAD_TOP_LARGE) {
            binding.topDetailsCompact.root.beGone()
            binding.topDetailsLarge.beVisible()
        } else {
            binding.topDetailsLarge.beGone()
            binding.topDetailsCompact.root.beVisible()
        }
//        deleted by sun unnecessary ---->
//        binding.scrollToBottomFab.beVisible()
//        <--------
        if (!isRecycleBin && !isSpecialNumber()) {
            binding.messageHolder.root.beVisible()
            binding.messageHolder.root.post {
                ViewCompat.requestApplyInsets(binding.root)
                refreshThreadMessagesListPaddingForComposeBarHeight()
            }
        }
    }

    override fun getBlurTargetView() = binding.mainBlurTarget

    private fun applyActionModeRippleToolbarForThread() {
        val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget) ?: return
        val adapter = binding.threadMessagesList.adapter as? ThreadAdapter ?: return
        if (!adapter.isActionModeActive()) {
            binding.actionModeRippleToolbar.visibility = View.GONE
            return
        }
        val (items, _) = adapter.buildThreadRippleToolbar()
        if (items.isEmpty()) {
            binding.actionModeRippleToolbar.visibility = View.GONE
            return
        }
        binding.actionModeRippleToolbar.setTabs(this, items, blurTarget)
        binding.actionModeRippleToolbar.setOnClickedListener { index ->
            adapter.dispatchRippleToolbarAction(index)
        }
        binding.actionModeRippleToolbar.visibility = View.VISIBLE

        for (i in 0 until items.size) {
            binding.actionModeRippleToolbar.setRippleTabEnabledWidthAlpha(i,
                adapter.isThreadRippleTabInteractionEnabled(i))
        }
    }

    fun refreshActionModeRippleToolbarIfNeeded() {
        if (isDestroyed || isFinishing) return
        applyActionModeRippleToolbarForThread()
    }

    fun getThreadList() : MyRecyclerView {
        return binding.threadMessagesList
    }
}
