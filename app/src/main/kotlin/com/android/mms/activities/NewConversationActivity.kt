package com.android.mms.activities

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Intent
import android.graphics.Color
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.Telephony.Sms.MESSAGE_TYPE_QUEUED
import android.provider.Telephony.Sms.STATUS_NONE
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.text.format.DateUtils
import android.text.format.DateUtils.FORMAT_NO_YEAR
import android.text.format.DateUtils.FORMAT_SHOW_DATE
import android.text.format.DateUtils.FORMAT_SHOW_TIME
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.TextView
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.goodwy.commons.dialogs.PermissionRequiredDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.PhoneNumber
import com.goodwy.commons.models.SimpleContact
import com.android.mms.R
import com.android.mms.adapters.ContactsAdapter
import com.android.mms.adapters.ContactPhonePair
import com.android.mms.databinding.ActivityNewConversationBinding
import com.android.mms.databinding.ItemSuggestedContactBinding
import com.android.mms.dialogs.showScheduleDateTimePicker
import com.android.mms.extensions.*
import com.android.mms.helpers.*
import com.android.mms.messaging.isLongMmsMessage
import com.android.mms.messaging.scheduleMessage
import com.android.mms.messaging.sendMessageCompat
import com.android.mms.models.Attachment
import com.android.mms.models.Message
import com.android.mms.models.MessageAttachment
import com.android.mms.models.Events
import com.android.mms.models.SIMCard
import com.android.mms.BuildConfig
import com.android.mms.helpers.MessageHolderHelper
import com.android.mms.models.Draft
import com.android.mms.models.DraftStoredAttachment
import org.greenrobot.eventbus.EventBus
import org.joda.time.DateTime
import java.net.URLDecoder
import java.util.Objects

class NewConversationActivity : SimpleActivity() {
    private val debugTag = "NewConversationFee"
    private var allContacts = ArrayList<SimpleContact>()
    private var privateContacts = ArrayList<SimpleContact>()
    private var isSpeechToTextAvailable = false
    private var isAttachmentPickerVisible = false
    /** When true, [threadTypeMessage] focus loss is from opening the attachment picker; skip extra inset sync. */
    private var ignoreInputFocusLossInsetSync = false
    /** Same latch model as [ThreadActivity] for IME ↔ attachment picker transitions. */
    private enum class ComposeBarBottomInsetLatch {
        NONE,
        KEYBOARD_TO_ATTACHMENT_PICKER,
        ATTACHMENT_PICKER_TO_KEYBOARD,
    }

    private var composeBarBottomInsetLatch = ComposeBarBottomInsetLatch.NONE
    private var composeBarBottomInsetLatchPx = 0
    private var wasKeyboardVisible = false
    private var messageHolderHelper: MessageHolderHelper? = null
    private var expandedMessageFragment: com.android.mms.fragments.ExpandedMessageFragment? = null
    // Map to store chip display text -> phone number mapping
    private val chipDisplayToPhoneNumber = mutableMapOf<String, String>()
    // Flag to prevent recursive calls when updating chips
    private var isUpdatingChips = false
    private val availableSIMCards = ArrayList<SIMCard>()
    private var currentSIMCardIndex = 0
    private var scheduledDateTime = DateTime.now().plusMinutes(5)
    private var isScheduledMessage = false
    private val binding by viewBinding(ActivityNewConversationBinding::inflate)
    private var draftResumeApplied = false
    /** Thread id of the draft opened from the main list; cleared when recipients no longer match that thread. */
    private var resumedDraftThreadId: Long = 0L

    private val recipientSearchHandler = Handler(Looper.getMainLooper())
    private var recipientSearchThrottleRunnable: Runnable? = null
    /** ElapsedRealtime when [runRecipientSearchForChipFilter] last started; used to cap search frequency. */
    private var lastRecipientSearchRunElapsed = 0L

    companion object {
        private const val PICK_SAVE_FILE_INTENT = 1008
        private const val PICK_SAVE_DIR_INTENT = 1009
        private const val REQUEST_CODE_CONTACT_PICKER = 1010
        /** Cap recipient search suggestions so filtering stays responsive with large address books. */
        private const val MAX_RECIPIENT_SEARCH_SUGGESTIONS = 6
        /** Min interval between contact-provider searches while the user is typing (throttle). */
        private const val RECIPIENT_SEARCH_THROTTLE_MS = 250L
        /**
         * When [title start] + [full title width] reaches this fraction of the new-conversation app bar width,
         * the toolbar shows a shortened first name with an ellipsis before "and N other(s)".
         */
        private const val NEW_CONVERSATION_TITLE_APPBAR_FILL_RATIO = 0.9f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        // While app bar insets/height and the chip row are still settling, hide scroll content to avoid
        // an overlapped first frame; [revealNewConversationScrollContentAfterAppBar] runs after the lock.
        binding.nestScroll.beInvisible()
        updateTextColors(binding.newConversationHolder)
        initTheme()
        applyNewConversationWindowBackgroundsAndTopChrome()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setupEdgeToEdge(
            padBottomImeAndSystem = listOf(binding.messageHolder.root),
            animateIme = false,
        )
        setupNewConversationComposeWindowInsets()
        binding.nestScroll.post {
            scrollingView = binding.nestScroll
            if (config.changeColourTopBar) {
                val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
                setupSearchMenuScrollListener(
                    binding.nestScroll,
                    binding.newConversationAppbar,
                    useSurfaceColor,
                )
            }
        }

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        binding.newConversationAddress.hint = getString(R.string.recipients_hint)
        binding.newConversationAddress.getAddressBookButton().setColorFilter(com.goodwy.commons.R.color.bw_000)

        setupMessagingEdgeToEdge()

        // [collapseAndLockCollapsing] height uses status-bar padding; that is only set after [WindowCompat] +
        // edge-to-edge insets. Running the title/collapse before that produced a too-short app bar and overlap
        // with the chips row until a chip add triggered [updateNewConversationTitle] again.
        ViewCompat.requestApplyInsets(binding.root)
        binding.newConversationAppbar.post {
            updateNewConversationTitle()
            revealNewConversationScrollContentAfterAppBar()
        }

        // READ_CONTACTS permission is not mandatory, but without it we won't be able to show any suggestions during typing
        handlePermission(PERMISSION_READ_CONTACTS) {
            initContacts()
        }

    }

    override fun onResume() {
        super.onResume()
        val getProperPrimaryColor = getProperPrimaryColor()

        // Match ContactPickerActivity: transparent status/nav bars, same background logic
        @Suppress("DEPRECATION")
        if (isSystemInDarkMode()) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            )
        }
        applyNewConversationWindowBackgroundsAndTopChrome()
        updateNewConversationTitle()
        binding.newConversationAppbar.requireCustomToolbar().apply {
            val textColor = getProperTextColor()
            setTitleTextColor(textColor)
            navigationIcon = resources.getColoredDrawableWithColor(
                this@NewConversationActivity,
                com.android.common.R.drawable.ic_cmn_arrow_left_fill,
                textColor
            )
            setNavigationContentDescription(com.goodwy.commons.R.string.back)
            setNavigationOnClickListener {
                hideKeyboard()
                finish()
            }
        }
//        binding.newConversationHolder.setBackgroundColor(backgroundColor)
//        binding.newConversationAddress.setBackgroundColor(backgroundColor)
//        binding.suggestionsOverlay.setBackgroundColor(backgroundColor)

        binding.noContactsPlaceholder2.setTextColor(getProperPrimaryColor)
        binding.noContactsPlaceholder2.underlineText()
//        binding.suggestionsLabel.setTextColor(getProperPrimaryColor)

        binding.contactsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
//                hideKeyboard()
            }
        })

        //  refreshNewConversationInsetsAndToolbarGeometry()
        
        setupMessageHolder()
        handlePermission(PERMISSION_READ_PHONE_STATE) {
            if (it) {
                setupSIMSelector()
            }
        }

        updateSuggestionsOverlayVisibility()
    }

    override fun onDestroy() {
        recipientSearchThrottleRunnable?.let { recipientSearchHandler.removeCallbacks(it) }
        recipientSearchThrottleRunnable = null
        super.onDestroy()
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun applyNewConversationWindowBackgroundsAndTopChrome() {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        binding.root.setBackgroundColor(backgroundColor)
        binding.mainBlurTarget.setBackgroundColor(backgroundColor)
        binding.conversationScrollBlur.setBackgroundColor(backgroundColor)
        scrollingView = binding.nestScroll
        binding.newConversationAppbar.updateColors(
            getStartRequiredStatusBarColor(),
            scrollingView?.computeVerticalScrollOffset() ?: 0,
        )
        binding.newConversationAppbar.setBackgroundColor(Color.TRANSPARENT)
        binding.newConversationAppbar.binding.searchBarContainer.setBackgroundColor(Color.TRANSPARENT)
    }

    /** Same idea as [SettingsActivity.refreshSideFrameBlurAndInsets]: blur + toolbar geometry after resume. */
    private fun refreshNewConversationInsetsAndToolbarGeometry() {
        binding.root.post {
            ViewCompat.requestApplyInsets(binding.root)
            binding.newConversationAppbar.requireCustomToolbar().bindBlurTarget(
                this@NewConversationActivity,
                binding.conversationScrollBlur,
            )
            binding.conversationScrollBlur.invalidate()
            postSyncMySearchMenuToolbarGeometry(
                binding.root,
                binding.newConversationAppbar,
                binding.conversationScrollBlur,
                topSideFrame = null,
                binding.newConversationHolder,
            )
        }
    }

    /**
     * Same pattern as [ThreadActivity.makeSystemBarsToTransparent]: root insets re-apply compose IME padding
     * (latch-aware) after [setupEdgeToEdge].
     */
    private fun setupNewConversationComposeWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            if (composeBarBottomInsetLatch != ComposeBarBottomInsetLatch.NONE) {
                applyComposeBarImePaddingFromInsets()
            }
            binding.root.post {
                applyComposeBarImePaddingFromInsets()
            }
            insets
        }

    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Same as [ThreadActivity.applyComposeBarImePaddingFromInsets] for the compose strip (non-recycle-bin). */
    private fun applyComposeBarImePaddingFromInsets() {
        val rootInsets = ViewCompat.getRootWindowInsets(binding.root)
            ?: ViewCompat.getRootWindowInsets(window.decorView)
            ?: return
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
                }
                v
            }
            ComposeBarBottomInsetLatch.NONE -> rawBottom
        }
        binding.messageHolder.root.updatePaddingWithBase(bottom = bottomPx)
    }

    private fun beginKeyboardToAttachmentPickerComposeInsetLatch() {
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
        val rootInsets = ViewCompat.getRootWindowInsets(binding.root)
            ?: ViewCompat.getRootWindowInsets(window.decorView)
            ?: return
        val nav = rootInsets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        composeBarBottomInsetLatchPx = config.keyboardHeight + nav
        composeBarBottomInsetLatch = ComposeBarBottomInsetLatch.ATTACHMENT_PICKER_TO_KEYBOARD
        applyComposeBarImePaddingFromInsets()
    }

    private fun clearComposeBarBottomInsetLatch() {
        if (composeBarBottomInsetLatch == ComposeBarBottomInsetLatch.NONE) return
        composeBarBottomInsetLatch = ComposeBarBottomInsetLatch.NONE
        applyComposeBarImePaddingFromInsets()
    }

    private fun setupMessageHolder() {
        isSpeechToTextAvailable = isSpeechToTextAvailable()
        
        messageHolderHelper = MessageHolderHelper(
            activity = this,
            binding = binding.messageHolder,
            onSendMessage = { text, subscriptionId, attachments ->
                sendMessageAndNavigate(text, subscriptionId, attachments)
            },
            onSpeechToText = { speechToText() },
            onExpandMessage = { showExpandedMessageFragment() },
            onHideAttachmentPickerRequested = {
                isAttachmentPickerVisible = false
                messageHolderHelper?.hideAttachmentPicker()
                binding.messageHolder.messageHolder.postDelayed({
                    binding.root.post { ViewCompat.requestApplyInsets(binding.root) }
                }, 350)
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
                    applyComposeBarImePaddingFromInsets()
                    binding.root.post { ViewCompat.requestApplyInsets(binding.root) }
                }
            },
            onPrepareKeyboardFromAttachmentPicker = {
                beginAttachmentPickerToKeyboardComposeInsetLatch()
            },
        )
        
        messageHolderHelper?.setup(isSpeechToTextAvailable)
        
        binding.messageHolder.apply {
            threadAddAttachmentHolder.setOnClickListener {
                if (attachmentPickerHolder.isVisible()) {
                    clearComposeBarBottomInsetLatch()
                    isAttachmentPickerVisible = false
                    messageHolderHelper?.hideAttachmentPicker()
                } else {
                    ignoreInputFocusLossInsetSync = true
                    beginKeyboardToAttachmentPickerComposeInsetLatch()
                    hideKeyboard()
                    threadTypeMessage.clearFocus()
                    binding.messageHolderWrapper.postDelayed({
                        isAttachmentPickerVisible = true
                        messageHolderHelper?.showAttachmentPicker()
                        if (composeBarBottomInsetLatch == ComposeBarBottomInsetLatch.KEYBOARD_TO_ATTACHMENT_PICKER) {
                            composeBarBottomInsetLatch = ComposeBarBottomInsetLatch.NONE
                            applyComposeBarImePaddingFromInsets()
                        }
                        threadTypeMessage.requestApplyInsets()
                        binding.root.post { ViewCompat.requestApplyInsets(binding.root) }
                        ignoreInputFocusLossInsetSync = false
                    }, 250)
                }
            }
            
            messageHolderHelper?.setupAttachmentPicker(
                onChoosePhoto = { launchGetContentIntent(arrayOf("image/*"), MessageHolderHelper.PICK_PHOTO_INTENT) },
                onChooseVideo = { launchGetContentIntent(arrayOf("video/*"), MessageHolderHelper.PICK_VIDEO_INTENT) },
                onTakePhoto = { launchCapturePhotoIntent() },
                onRecordVideo = { launchCaptureVideoIntent() },
                onRecordAudio = { launchCaptureAudioIntent() },
                onPickFile = { launchGetContentIntent(arrayOf("*/*"), MessageHolderHelper.PICK_DOCUMENT_INTENT) },
                onPickContact = { launchPickContactIntent() },
                onScheduleMessage = { launchScheduleSendDialog() },
                onPickQuickText = {
                    val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
                        ?: throw IllegalStateException("mainBlurTarget not found")
                    com.android.mms.dialogs.QuickTextSelectionDialog(this@NewConversationActivity, blurTarget) { selectedText ->
                        messageHolderHelper?.insertText(selectedText)
                    }
                }
            )
            
            messageHolderHelper?.hideAttachmentPicker()
        }
        setupScheduleSendUi()

        // Handle forwarded messages from Intent.ACTION_SEND or Intent.ACTION_SEND_MULTIPLE
        handleForwardedMessage()
    }

    /** Same as [ThreadActivity.setupMessagingEdgeToEdge]: keep [config.keyboardHeight] accurate for picker↔IME latch. */
    private fun setupMessagingEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.messageHolder.threadTypeMessage) { _, insets ->
            val type = WindowInsetsCompat.Type.ime()
            val isKeyboardVisible = insets.isVisible(type)
            if (isKeyboardVisible) {
                val keyboardInsetBottom = insets.getInsets(type).bottom
                val bottomBarHeight = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                config.keyboardHeight = if (keyboardInsetBottom > 150) {
                    keyboardInsetBottom - bottomBarHeight
                } else {
                    getDefaultKeyboardHeight()
                }
                if (!wasKeyboardVisible) {
                    isAttachmentPickerVisible = false
                    messageHolderHelper?.hideAttachmentPicker()
                }
                wasKeyboardVisible = true
            } else {
                wasKeyboardVisible = false
                // Avoid briefly re-showing the picker while IME is animating in (picker → keyboard); that
                // caused a visible flash on [threadTypeMessage]. Thread re-shows only when not in latch.
                if (isAttachmentPickerVisible &&
                    composeBarBottomInsetLatch != ComposeBarBottomInsetLatch.ATTACHMENT_PICKER_TO_KEYBOARD
                ) {
                    messageHolderHelper?.showAttachmentPicker()
                }
            }
            insets
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (resultCode != RESULT_OK) return
        
        if (requestCode == REQUEST_CODE_SPEECH_INPUT && resultData != null) {
            val res: java.util.ArrayList<String> =
                resultData.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) as java.util.ArrayList<String>
            val speechToText = Objects.requireNonNull(res)[0]
            if (speechToText.isNotEmpty()) {
                messageHolderHelper?.setMessageText(speechToText)
            }
        } else if (requestCode == REQUEST_CODE_CONTACT_PICKER && resultData != null) {
            val displayTexts = ContactPickerActivity.getSelectedDisplayTexts(resultData)
            val normalizedNumbers = ContactPickerActivity.getSelectedPhoneNumbers(resultData)
            if (displayTexts.size == normalizedNumbers.size) {
                isUpdatingChips = true
                for (i in displayTexts.indices) {
                    val displayText = displayTexts[i]
                    val normalizedNumber = normalizedNumbers[i]
                    if (displayText.isNotEmpty() && normalizedNumber.isNotEmpty() && !chipDisplayToPhoneNumber.containsKey(displayText)) {
                        chipDisplayToPhoneNumber[displayText] = normalizedNumber
                        binding.newConversationAddress.addChip(displayText)
                    }
                }
                isUpdatingChips = false
            }
        } else {
            messageHolderHelper?.handleActivityResult(requestCode, resultCode, resultData)
            
            if (requestCode == MessageHolderHelper.PICK_CONTACT_INTENT && resultData?.data != null) {
                addContactAttachment(resultData.data!!)
            }
        }
    }
    /** Same spacing as [R.string.thread_title_multiple_format] between first name and the "and N other(s)" suffix in split titles. */
    private fun spacedOthersSuffix(othersPhrase: String) = " $othersPhrase"

    /** Toolbar / activity title from recipient chips: none → "New conversation", one → chip text, many → first + "and N other(s)". */
    private fun getNewConversationDisplayTitle(): String {
        val chips = binding.newConversationAddress.allChips.filter { it.isNotEmpty() }
        return when (chips.size) {
            0 -> getString(R.string.new_conversation)
            1 -> chips[0]
            else -> {
                val othersCount = chips.size - 1
                val othersPhrase = resources.getQuantityString(R.plurals.and_other_contacts, othersCount, othersCount)
                getString(R.string.thread_title_multiple_format, chips[0], othersPhrase)
            }
        }
    }

    private fun updateNewConversationTitle() {
        val chips = binding.newConversationAddress.allChips.filter { it.isNotEmpty() }
        val fullCombinedTitle = getNewConversationDisplayTitle()
        binding.newConversationAppbar.apply {
            binding.collapsingTitle.text = ""
            requireCustomToolbar().apply {
                when {
                    chips.isEmpty() -> {
                        title = fullCombinedTitle
                    }
                    chips.size == 1 -> {
                        title = chips[0]
                    }
                    else -> {
                        // Always set a correct baseline title first. The crowding-adjust path below is
                        // posted and may early-return if layout isn't ready yet; without a baseline the
                        // toolbar can get stuck showing only the first recipient after draft restore.
                        title = fullCombinedTitle
                        adjustNewConversationToolbarTitleIfCrowded(
                            chips = chips,
                            fullCombinedTitle = fullCombinedTitle,
                        )
                    }
                }
                setTitleTextColor(getProperTextColor())
            }
            setCollapsingTitleVisible(false)
            collapseAndLockCollapsing()
        }
    }

    /**
     * For two recipients, the toolbar shows one line ([title] only). When that line would pass about
     * [NEW_CONVERSATION_TITLE_APPBAR_FILL_RATIO] of the app bar width, it is shortened with an ellipsis.
     * For three or more recipients, [title] is the first name and [title2] is "and N other(s)"; only [title]
     * is ellipsized so [title2] stays fully visible.
     */
    private fun adjustNewConversationToolbarTitleIfCrowded(
        chips: List<String>,
        fullCombinedTitle: String,
    ) {
        if (chips.size < 2) return
        val appBar = binding.newConversationAppbar
        val toolbar = appBar.requireCustomToolbar()
        // title1 and title2 are laid out flush; no extra margin between them (see custom_toolbar).
        appBar.post {
            val titleView = toolbar.findViewById<TextView>(com.goodwy.commons.R.id.titleTextView) ?: return@post
            val appBarW = appBar.width
            if (appBarW <= 0) return@post
            val paint = TextPaint(titleView.paint)
            val appBarScreenX = IntArray(2)
            val titleScreenX = IntArray(2)
            appBar.getLocationOnScreen(appBarScreenX)
            titleView.getLocationOnScreen(titleScreenX)
            val titleStart = titleScreenX[0] - appBarScreenX[0]
            val thresholdPx = NEW_CONVERSATION_TITLE_APPBAR_FILL_RATIO * appBarW
            val fullW = paint.measureText(fullCombinedTitle)
            if (titleStart + fullW < thresholdPx) {
                return@post
            }

            val othersCount = chips.size - 1
            val othersPhrase = resources.getQuantityString(R.plurals.and_other_contacts, othersCount, othersCount)
            val maxTextWidth = (thresholdPx - titleStart).coerceAtLeast(0f)
            val adjusted = buildEllipsizedMultipleRecipientTitle(
                paint = paint,
                firstRecipient = chips[0],
                othersPhrase = othersPhrase,
                maxWidth = maxTextWidth,
            )
            toolbar.title = adjusted
            return@post
        }
    }

    private fun buildEllipsizedFirstNameOnly(
        paint: TextPaint,
        firstRecipient: String,
        maxWidth: Float,
    ): String {
        val ellipsis = "…"
        if (paint.measureText(firstRecipient) <= maxWidth) {
            return firstRecipient
        }
        if (firstRecipient.length == 1) {
            val single = firstRecipient + ellipsis
            if (paint.measureText(single) <= maxWidth) {
                return single
            }
            return if (paint.measureText(ellipsis) <= maxWidth) ellipsis else firstRecipient
        }
        for (len in firstRecipient.length - 1 downTo 1) {
            val prefix = firstRecipient.take(len).trimEnd()
            if (prefix.isEmpty()) continue
            val candidate = prefix + ellipsis
            if (paint.measureText(candidate) <= maxWidth) {
                return candidate
            }
        }
        return if (paint.measureText(ellipsis) <= maxWidth) ellipsis else firstRecipient
    }

    private fun buildEllipsizedMultipleRecipientTitle(
        paint: TextPaint,
        firstRecipient: String,
        othersPhrase: String,
        maxWidth: Float,
    ): String {
        val ellipsis = "…"
        val fullFormatted = getString(R.string.thread_title_multiple_format, firstRecipient, othersPhrase)
        if (paint.measureText(fullFormatted) <= maxWidth) {
            return fullFormatted
        }
        if (firstRecipient.length == 1) {
            val single = getString(R.string.thread_title_multiple_format, firstRecipient + ellipsis, othersPhrase)
            if (paint.measureText(single) <= maxWidth) {
                return single
            }
        } else {
            for (len in firstRecipient.length - 1 downTo 1) {
                val prefix = firstRecipient.take(len).trimEnd()
                if (prefix.isEmpty()) continue
                val candidate = getString(R.string.thread_title_multiple_format, prefix + ellipsis, othersPhrase)
                if (paint.measureText(candidate) <= maxWidth) {
                    return candidate
                }
            }
        }
        val minimal = getString(R.string.thread_title_multiple_format, ellipsis, othersPhrase).trim()
        if (paint.measureText(minimal) <= maxWidth) {
            return minimal
        }
        return othersPhrase
    }

    /** After [updateNewConversationTitle] locks the app bar, show the nested scroll and focus the chip field. */
    private fun revealNewConversationScrollContentAfterAppBar() {
        binding.nestScroll.post {
            binding.nestScroll.beVisible()
            binding.newConversationAddress.requestEditTextFocus()
        }
    }

    @SuppressLint("MissingPermission")
    private fun initContacts() {
        if (isThirdPartyIntent()) {
            return
        }

        fetchContacts()

        isSpeechToTextAvailable = isSpeechToTextAvailable()

        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val surfaceColor = if (useSurfaceColor) getProperBackgroundColor() else getSurfaceColor()
        val properTextColor = getProperTextColor()
        val properAccentColor = getProperAccentColor()
        
        binding.newConversationAddress.setColors(properTextColor, properAccentColor, surfaceColor)
//        binding.newConversationAddress.getEditText().setBackgroundResource(com.goodwy.commons.R.drawable.search_bg)
//        binding.newConversationAddress.getEditText().backgroundTintList = ColorStateList.valueOf(surfaceColor)

        // Listen for chip addition to validate newly added chips
        binding.newConversationAddress.setOnChipAddedListener { chipText ->
            if (chipDisplayToPhoneNumber.containsKey(chipText)) {
                return@setOnChipAddedListener true
            }
            // Validate the newly added chip
            if (!isValidForChip(chipText)) {
                isUpdatingChips = true
                binding.newConversationAddress.removeChip(chipText)
                isUpdatingChips = false
                toast(R.string.invalid_contact_or_number, length = Toast.LENGTH_SHORT)
                return@setOnChipAddedListener false
            }
            return@setOnChipAddedListener true
        }
        
        // Listen for chip changes to handle typed phone numbers and contact names
        binding.newConversationAddress.setOnChipsChangedListener { chips ->
            if (!isUpdatingChips) {
                // Keep mapping in sync with visible chips so deleted chips can be re-added later.
                val activeChips = chips.toSet()
                val staleKeys = chipDisplayToPhoneNumber.keys.filter { key -> !activeChips.contains(key) }
                staleKeys.forEach { staleKey ->
                    chipDisplayToPhoneNumber.remove(staleKey)
                }

                chips.forEach { chipText ->
                    // If this chip is not in our mapping, check if it's a phone number or contact name
                    if (!chipDisplayToPhoneNumber.containsKey(chipText)) {
                        // First check if it's a contact name (prioritize contact names over phone numbers)
                        val contact = findContactByName(chipText)
                        if (contact != null) {
                            // Found a contact with this name, handle phone number selection
                            handleContactNameChip(chipText, contact)
                        } else {
                            // Not a contact name, check if it's a phone number
                            val normalizedNumber = chipText.normalizePhoneNumber()
                            // Check if normalization produced a meaningful result (has digits and reasonable length)
                            if (normalizedNumber.isNotEmpty() && normalizedNumber.length >= 3 && normalizedNumber.all { it.isDigit() }) {
                                // Look up contact and phone number object for this phone number
                                val contactAndPhoneNumber = findContactAndPhoneNumberByNormalizedNumber(normalizedNumber)
                                if (contactAndPhoneNumber != null) {
                                    val (contact, phoneNumber) = contactAndPhoneNumber
                                    // Contact found, update the chip with name and type if needed
                                    val displayText = getDisplayTextForPhoneNumberWithType(phoneNumber, contact)
                                    isUpdatingChips = true
                                    binding.newConversationAddress.removeChip(chipText)
                                    binding.newConversationAddress.addChip(displayText)
                                    chipDisplayToPhoneNumber[displayText] = normalizedNumber
                                    isUpdatingChips = false
                                } else {
                                    // No contact found, store the mapping (chipText -> normalizedNumber)
                                    // This handles both cases: chipText might be the original or already normalized
                                    chipDisplayToPhoneNumber[chipText] = normalizedNumber
                                }
                            }
                        }
                    }
                }

                // Update SIM selector when chips change
                handlePermission(PERMISSION_READ_PHONE_STATE) {
                    if (it) {
                        setupSIMSelector()
                    }
                }
            }
            updateNewConversationTitle()
        }

        binding.newConversationAddress.setOnTextChangedListener { searchString ->
            updateSuggestionsOverlayVisibility(searchString)

            if (searchString.isEmpty()) {
                recipientSearchThrottleRunnable?.let { recipientSearchHandler.removeCallbacks(it) }
                recipientSearchThrottleRunnable = null
                lastRecipientSearchRunElapsed = 0L
                return@setOnTextChangedListener
            }

            recipientSearchThrottleRunnable?.let { recipientSearchHandler.removeCallbacks(it) }
            val now = SystemClock.elapsedRealtime()
            val sinceLastRun = if (lastRecipientSearchRunElapsed == 0L) {
                RECIPIENT_SEARCH_THROTTLE_MS
            } else {
                now - lastRecipientSearchRunElapsed
            }
            val delayMs = if (sinceLastRun >= RECIPIENT_SEARCH_THROTTLE_MS) {
                0L
            } else {
                RECIPIENT_SEARCH_THROTTLE_MS - sinceLastRun
            }
            val runnable = Runnable { runRecipientSearchForChipFilter() }
            recipientSearchThrottleRunnable = runnable
            recipientSearchHandler.postDelayed(runnable, delayMs)
        }

        binding.newConversationAddress.setSpeechToTextButtonVisible(false)
        binding.newConversationAddress.setSpeechToTextButtonClickListener { speechToText() }
        binding.newConversationAddress.setAddressBookButtonClickListener { launchContactPicker() }

        binding.noContactsPlaceholder2.setOnClickListener {
            handlePermission(PERMISSION_READ_CONTACTS) {
                if (it) {
                    fetchContacts()
                }
            }
        }

//        binding.contactsLetterFastscroller.textColor = properTextColor.getColorStateList()
//        binding.contactsLetterFastscroller.pressedTextColor = properAccentColor
//        binding.contactsLetterFastscrollerThumb.setupWithFastScroller(binding.contactsLetterFastscroller)
//        binding.contactsLetterFastscrollerThumb.fontSize = getTextSize()
//        binding.contactsLetterFastscrollerThumb.textColor = properAccentColor.getContrastColor()
//        binding.contactsLetterFastscrollerThumb.thumbColor = properAccentColor.getColorStateList()
    }

    /**
     * Runs throttled recipient search using the current chip-field text (see [RECIPIENT_SEARCH_THROTTLE_MS]).
     */
    private fun runRecipientSearchForChipFilter() {
        recipientSearchThrottleRunnable = null
        val query = binding.newConversationAddress.currentText
        if (query.isEmpty()) {
            lastRecipientSearchRunElapsed = 0L
            setupAdapter(ArrayList(), showSearchResults = false)
            return
        }
        lastRecipientSearchRunElapsed = SystemClock.elapsedRealtime()
        ensureBackgroundThread {
            val helper = SimpleContactsHelper(this@NewConversationActivity)
            var systemMatches = helper.getAvailableContactsMatchingSearchSync(
                favoritesOnly = false,
                searchText = query,
                limit = MAX_RECIPIENT_SEARCH_SUGGESTIONS,
            )
            if (systemMatches.isEmpty() && allContacts.isNotEmpty()) {
                systemMatches = filterAllContactsForRecipientSearch(query)
            }
            val privateMatches = ArrayList<SimpleContact>()
            for (contact in privateContacts) {
                if (privateMatches.size >= MAX_RECIPIENT_SEARCH_SUGGESTIONS) {
                    break
                }
                val nameMatches = contact.name.contains(query, true) ||
                    contact.name.contains(query.normalizeString(), true) ||
                    contact.name.normalizeString().contains(query, true)
                val phoneMatches = contact.phoneNumbers.any {
                    it.normalizedNumber.contains(query, true) ||
                        it.value.contains(query, true)
                }
                if ((nameMatches || phoneMatches) && contact.phoneNumbers.isNotEmpty()) {
                    privateMatches.add(contact)
                }
            }

            val merged = ArrayList<SimpleContact>(systemMatches)
            val systemRawIds = systemMatches.mapTo(HashSet()) { it.rawId }
            for (c in privateMatches) {
                if (!systemRawIds.contains(c.rawId)) {
                    merged.add(c)
                }
            }
            merged.sortWith(compareBy { !it.name.startsWith(query, true) })
            val contactsForAdapter =
                if (merged.size > MAX_RECIPIENT_SEARCH_SUGGESTIONS) {
                    ArrayList(merged.subList(0, MAX_RECIPIENT_SEARCH_SUGGESTIONS))
                } else {
                    merged
                }

            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (binding.newConversationAddress.currentText != query) return@runOnUiThread
                setupAdapter(contactsForAdapter, showSearchResults = true)
            }
        }
    }

    /** In-memory search over [allContacts]; used when the contacts-provider query returns nothing. */
    private fun filterAllContactsForRecipientSearch(searchString: String): ArrayList<SimpleContact> {
        val filtered = ArrayList<SimpleContact>()
        for (contact in allContacts) {
            val nameMatches = contact.name.contains(searchString, true) ||
                contact.name.contains(searchString.normalizeString(), true) ||
                contact.name.normalizeString().contains(searchString, true)
            val phoneMatches = contact.phoneNumbers.any {
                it.normalizedNumber.contains(searchString, true) ||
                    it.value.contains(searchString, true)
            }
            if (nameMatches || phoneMatches) {
                filtered.add(contact)
            }
        }
        filtered.sortWith(compareBy { !it.name.startsWith(searchString, true) })
        return if (filtered.size > MAX_RECIPIENT_SEARCH_SUGGESTIONS) {
            ArrayList(filtered.subList(0, MAX_RECIPIENT_SEARCH_SUGGESTIONS))
        } else {
            filtered
        }
    }

    private fun isThirdPartyIntent(): Boolean {
        val result = SmsIntentParser.parse(intent)
        if (result != null) {
            val (body, recipients) = result
            launchThreadActivity(
                phoneNumber = URLDecoder.decode(recipients.replace("+", "%2b").trim()),
                name = "",
                body = body
            )
            finish()
            return true
        }
        return false
    }

    private fun parseRecipientNumbersFromIntent(extra: String?): ArrayList<String> {
        val numbers = ArrayList<String>()
        if (extra.isNullOrEmpty()) return numbers
        if (extra.startsWith('[') && extra.endsWith(']')) {
            val type = object : TypeToken<List<String>>() {}.type
            numbers.addAll(Gson().fromJson(extra, type))
        } else {
            numbers.add(extra)
        }
        return numbers
    }

    /**
     * Restores chips and draft when opened from the main list for a thread that has a local draft
     * but no telephony messages yet ([com.android.mms.extensions.getThreadTelephonyMessageCount] == 0).
     *
     * Draft body is read on a background thread because Room does not allow main-thread queries
     * by default ([getSmsDraft] uses [draftsDB]).
     */
    private fun maybeApplyDraftResumeFromIntent() {
        if (draftResumeApplied || !intent.getBooleanExtra(NEW_CONVERSATION_RESUME_DRAFT, false)) {
            return
        }
        val threadId = intent.getLongExtra(THREAD_ID, 0L)
        if (threadId <= 0L) return
        val numbers = parseRecipientNumbersFromIntent(intent.getStringExtra(THREAD_NUMBER))
        if (numbers.isEmpty()) return

        draftResumeApplied = true
        intent.removeExtra(NEW_CONVERSATION_RESUME_DRAFT)
        resumedDraftThreadId = threadId

        ensureBackgroundThread {
            val draftEntity = getSmsDraftEntity(threadId)
            val meaningfulDraft = draftEntity != null && (
                draftEntity.body.isNotBlank() ||
                    !draftEntity.attachmentsJson.isNullOrBlank() ||
                    (draftEntity.isScheduled && draftEntity.scheduledMillis > 0L)
                )
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                isUpdatingChips = true
                numbers.forEach { rawNumber ->
                    val normalized = rawNumber.normalizePhoneNumber().ifEmpty { rawNumber }
                    val contact = findContactByPhoneNumber(normalized)
                    val phoneObj = contact?.phoneNumbers?.firstOrNull { it.normalizedNumber == normalized }
                        ?: PhoneNumber(normalized, 0, "", rawNumber)
                    val displayText = getDisplayTextForPhoneNumberWithType(phoneObj, contact)
                    chipDisplayToPhoneNumber[displayText] = normalized
                    binding.newConversationAddress.addChip(displayText)
                }
                isUpdatingChips = false
                binding.newConversationAddress.clearText()
                updateNewConversationTitle()
                if (meaningfulDraft && draftEntity != null) {
                    applyNewConversationDraftRow(draftEntity)
                }
            }
        }
    }

    private fun applyNewConversationDraftRow(draft: Draft) {
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
            helper.checkSendMessageAvailability()
        } else if (draft.body.isNotEmpty()) {
            binding.messageHolder.threadTypeMessage.setText(draft.body)
            binding.messageHolder.threadTypeMessage.setSelection(draft.body.length)
        }

        if (draft.isScheduled && draft.scheduledMillis > 0L) {
            scheduledDateTime = DateTime(draft.scheduledMillis)
            showScheduleMessageDialog()
        } else {
            hideScheduleSendUi()
        }
    }

    private fun fetchContacts() {
        fillSuggestedContacts {
//            SimpleContactsHelper(this).getAvailableContacts(false) {
                allContacts = it

                if (privateContacts.isNotEmpty()) {
                    // Deduplicate contacts before adding privateContacts
                    // Contacts from different sources may have different rawId but same phone numbers
                    val existingPhoneNumbers = HashSet<String>()
                    val existingContactNamesWithoutPhone = HashSet<String>()
                    allContacts.forEach { contact ->
                        contact.phoneNumbers.forEach { phoneNumber ->
                            existingPhoneNumbers.add(phoneNumber.normalizedNumber)
                        }
                        // Track names only for contacts without phone numbers (for fallback deduplication)
                        if (contact.name.isNotEmpty() && contact.phoneNumbers.isEmpty()) {
                            existingContactNamesWithoutPhone.add(contact.name.lowercase().trim())
                        }
                    }
                    
                    // Only add private contacts that don't already exist (by phone number, or by name if no phone)
                    privateContacts.forEach { privateContact ->
                        val hasMatchingPhoneNumber = privateContact.phoneNumbers.isNotEmpty() && 
                            privateContact.phoneNumbers.any { phoneNumber ->
                                existingPhoneNumbers.contains(phoneNumber.normalizedNumber)
                            }
                        // Only check name if contact has no phone numbers (to avoid false positives)
                        val hasMatchingName = privateContact.phoneNumbers.isEmpty() && 
                            privateContact.name.isNotEmpty() && 
                            existingContactNamesWithoutPhone.contains(privateContact.name.lowercase().trim())
                        
                        // Skip if duplicate by phone number or (if no phone) by name
                        if (!hasMatchingPhoneNumber && !hasMatchingName) {
                            allContacts.add(privateContact)
                            // Add its phone numbers and name to the sets for future checks
                            privateContact.phoneNumbers.forEach { phoneNumber ->
                                existingPhoneNumbers.add(phoneNumber.normalizedNumber)
                            }
                            if (privateContact.name.isNotEmpty() && privateContact.phoneNumbers.isEmpty()) {
                                existingContactNamesWithoutPhone.add(privateContact.name.lowercase().trim())
                            }
                        }
                    }
                    allContacts.sort()
                }

                runOnUiThread {
//                    setupAdapter(allContacts)
                    maybeApplyDraftResumeFromIntent()
                }
//            }
        }
    }

    /**
     * @param showSearchResults When false, the recipient search list and its placeholders stay hidden
     * (no typing text in the chip field). When true, the list is shown only if there are matches.
     */
    private fun setupAdapter(contacts: ArrayList<SimpleContact>, showSearchResults: Boolean) {
        // Expand contacts with multiple phone numbers into separate entries
        val contactPhonePairs = ArrayList<ContactPhonePair>()
        contacts.forEach { contact ->
            if (contact.phoneNumbers.isEmpty()) {
                // Contact with no phone numbers - skip it
                return@forEach
            } else if (contact.phoneNumbers.size == 1) {
                // Single phone number - add as single entry
                contactPhonePairs.add(ContactPhonePair(contact, contact.phoneNumbers.first()))
            } else {
                // Multiple phone numbers - add each as separate entry
                contact.phoneNumbers.forEach { phoneNumber ->
                    contactPhonePairs.add(ContactPhonePair(contact, phoneNumber))
                }
            }
        }

        if (showSearchResults && contactPhonePairs.size > MAX_RECIPIENT_SEARCH_SUGGESTIONS) {
            val keep = MAX_RECIPIENT_SEARCH_SUGGESTIONS
            contactPhonePairs.subList(keep, contactPhonePairs.size).clear()
        }

        val hasContacts = contactPhonePairs.isNotEmpty()
//        binding.contactsList.beVisibleIf(hasContacts)
        binding.contactsListWrapper.beVisibleIf(hasContacts && showSearchResults)
        binding.noContactsPlaceholder.beVisibleIf(!hasContacts && showSearchResults)
        binding.noContactsPlaceholder2.beVisibleIf(
            showSearchResults && !hasContacts && !hasPermission(
                PERMISSION_READ_CONTACTS
            )
        )
//        binding.contactsLetterFastscroller.beVisibleIf(hasContacts)
//        binding.contactsLetterFastscrollerThumb.beVisibleIf(hasContacts)

        if (!hasContacts) {
            val placeholderText = if (hasPermission(PERMISSION_READ_CONTACTS)) {
                com.goodwy.commons.R.string.no_contacts_found
            } else {
                com.goodwy.commons.R.string.no_access_to_contacts
            }

            binding.noContactsPlaceholder.text = getString(placeholderText)
        }

        val currAdapter = binding.contactsList.adapter
        if (currAdapter == null) {
            ContactsAdapter(this, contactPhonePairs, binding.contactsList) {
                hideKeyboard()
                val contactPhonePair = it as ContactPhonePair
                val contact = contactPhonePair.contact
                val phoneNumber = contactPhonePair.phoneNumber
                // Directly add the phone number since each entry is already specific
                val displayText = getDisplayTextForPhoneNumberWithType(phoneNumber, contact)
                // Set mapping BEFORE adding chip to prevent listener from re-triggering
                chipDisplayToPhoneNumber[displayText] = phoneNumber.normalizedNumber
                isUpdatingChips = true
                binding.newConversationAddress.addChip(displayText)
                isUpdatingChips = false
                binding.newConversationAddress.clearText()
            }.apply {
                binding.contactsList.adapter = this
            }

            if (areSystemAnimationsEnabled) {
                binding.contactsList.scheduleLayoutAnimation()
            }
        } else {
            (currAdapter as ContactsAdapter).updateContacts(contactPhonePairs)
        }

        setupLetterFastscroller(contactPhonePairs)
    }

    /**
     * Shows the suggested-contacts strip when the recipient field filter is empty and
     * [binding.suggestionsHolder] has been populated; hides while the user is typing.
     * [ChipsInputView] does not invoke the text listener for the initial empty field, so this is
     * also called after [fillSuggestedContacts] runs and from [onResume].
     */
    private fun updateSuggestionsOverlayVisibility(recipientFilterText: String = binding.newConversationAddress.currentText) {
        if (recipientFilterText.isNotEmpty()) {
            binding.suggestionsScrollview.beGone()
            binding.suggestionsOverlay.beGone()
            return
        }
        setupAdapter(ArrayList(), showSearchResults = false)
        if (binding.suggestionsHolder.childCount > 0) {
            binding.suggestionsOverlay.beVisible()
            binding.suggestionsScrollview.beVisible()
        } else {
            binding.suggestionsScrollview.beGone()
            binding.suggestionsOverlay.beGone()
        }
    }

    private fun fillSuggestedContacts(callback: (ArrayList<SimpleContact>) -> Unit) {
        val privateCursor = getMyContactsCursor(false, true)
        ensureBackgroundThread {
            SimpleContactsHelper(this).getAvailableContacts(false) {
                privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)
                val contacts =  ArrayList(it + privateContacts)
                val suggestions = getSuggestedContacts(contacts)
                runOnUiThread {
                    binding.suggestionsHolder.removeAllViews()
                    if (suggestions.isNotEmpty()) {
                        suggestions.forEach { contact ->
                            ItemSuggestedContactBinding.inflate(layoutInflater).apply {
                                suggestedContactName.text = contact.name
                                suggestedContactName.setTextColor(getProperTextColor())
                                
                                // Display phone numbers
                                val phoneNumbersText = if (contact.phoneNumbers.isNotEmpty()) {
                                    TextUtils.join(", ", contact.phoneNumbers.map { it.value })
                                } else {
                                    ""
                                }
                                suggestedContactNumber.text = phoneNumbersText
                                suggestedContactNumber.setTextColor(getProperTextColor())
                                suggestedContactNumber.beVisibleIf(phoneNumbersText.isNotEmpty())

                                if (!isDestroyed) {
                                    if (contact.isABusinessContact() && contact.photoUri == "") {
                                        val drawable =
                                            SimpleContactsHelper(this@NewConversationActivity).getColoredCompanyIcon(contact.name)
                                        suggestedContactImage.setImageDrawable(drawable)
                                    } else {
                                        SimpleContactsHelper(this@NewConversationActivity).loadContactImage(
                                            contact.photoUri,
                                            suggestedContactImage,
                                            contact.name
                                        )
                                    }
                                    binding.suggestionsHolder.addView(root)
                                    root.setOnClickListener {
                                        hideKeyboard()
                                        // Handle multiple phone numbers by showing picker dialog
                                        maybeShowNumberPickerDialog(contact.phoneNumbers) { number ->
                                            // Directly add the phone number since each entry is already specific
                                            val displayText = getDisplayTextForPhoneNumberWithType(number)
                                            // Set mapping BEFORE adding chip to prevent listener from re-triggering
                                            chipDisplayToPhoneNumber[displayText] = number.normalizedNumber
                                            isUpdatingChips = true
                                            binding.newConversationAddress.addChip(displayText)
                                            isUpdatingChips = false
                                            binding.newConversationAddress.clearText()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    callback(it)
                    updateSuggestionsOverlayVisibility()
                }
            }
        }
    }

    private fun setupLetterFastscroller(contactPhonePairs: ArrayList<ContactPhonePair>) {
//        binding.contactsLetterFastscroller.setupWithRecyclerView(
//            binding.contactsList,
//            { position ->
//                try {
//                    FastScrollItemIndicator.Text(
//                        CommonContact(id = 0, firstName = contactPhonePairs[position].contact.name, contactId = 0).getFirstLetter()
//                    )
//                } catch (_: Exception) {
//                    FastScrollItemIndicator.Text("")
//                }
//            },
//            useDefaultScroller = true
//        )
//
//        try {
//            //Decrease the font size based on the number of letters in the letter scroller
//            val allNotEmpty = contactPhonePairs.filter { it.contact.name.isNotEmpty() }
//            val all = allNotEmpty.map { CommonContact(id = 0, firstName = it.contact.name, contactId = 0).getFirstLetter() }
//            val unique: Set<String> = HashSet(all)
//            val sizeUnique = unique.size
//            if (isHighScreenSize()) {
//                if (sizeUnique > 39) binding.contactsLetterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTooTiny
//                else if (sizeUnique > 32) binding.contactsLetterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTiny
//                else binding.contactsLetterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleSmall
//            } else {
//                if (sizeUnique > 49) binding.contactsLetterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTooTiny
//                else if (sizeUnique > 37) binding.contactsLetterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTiny
//                else binding.contactsLetterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleSmall
//            }
//        } catch (_: Exception) { }
    }

    private fun isHighScreenSize(): Boolean {
        return when (resources.configuration.screenLayout
            and Configuration.SCREENLAYOUT_LONG_MASK) {
            Configuration.SCREENLAYOUT_LONG_NO -> false
            else -> true
        }
    }

    /**
     * Gets the display text for a phone number.
     * Returns contact name if found in contacts, otherwise returns the phone number.
     */
    private fun getDisplayTextForPhoneNumber(phoneNumber: String): String {
        if (!hasPermission(PERMISSION_READ_CONTACTS)) {
            return phoneNumber
        }

        // First check in allContacts
        allContacts.forEach { contact ->
            if (contact.phoneNumbers.any { it.normalizedNumber == phoneNumber }) {
                return contact.name.ifEmpty { phoneNumber }
            }
        }

        // Then check in privateContacts
        privateContacts.forEach { contact ->
            if (contact.phoneNumbers.any { it.normalizedNumber == phoneNumber }) {
                return contact.name.ifEmpty { phoneNumber }
            }
        }

        // If not found in loaded contacts, try using SimpleContactsHelper
        val contactName = SimpleContactsHelper(this).getNameFromPhoneNumber(phoneNumber)
        return if (contactName != phoneNumber) contactName else phoneNumber
    }

    /**
     * Gets the display text for a phone number with type information.
     * This allows multiple phone numbers from the same contact to be added as separate chips.
     * Format: "Contact Name (Phone Type)" if contact has multiple numbers, otherwise just "Contact Name".
     * If multiple numbers have the same type, includes the phone number value to make them unique.
     */
    private fun getDisplayTextForPhoneNumberWithType(phoneNumber: PhoneNumber, contact: SimpleContact? = null): String {
        val contactToUse = contact ?: findContactByPhoneNumber(phoneNumber.normalizedNumber)
        
        if (contactToUse == null) {
            // No contact found, return the phone number
            return phoneNumber.normalizedNumber
        }

        val contactName = contactToUse.name.ifEmpty { phoneNumber.normalizedNumber }
        
        // If contact has multiple phone numbers, include the type to make chips unique
        if (contactToUse.phoneNumbers.size > 1) {
            return "$contactName (${phoneNumber.value})"
        }
        
        // Single phone number, just return the contact name
        return contactName
    }

    /**
     * Finds a contact by phone number.
     * Returns the contact if found, or null otherwise.
     */
    private fun findContactByPhoneNumber(phoneNumber: String): SimpleContact? {
        if (!hasPermission(PERMISSION_READ_CONTACTS)) {
            return null
        }

        // First check in allContacts
        allContacts.forEach { contact ->
            if (contact.phoneNumbers.any { it.normalizedNumber == phoneNumber }) {
                return contact
            }
        }

        // Then check in privateContacts
        privateContacts.forEach { contact ->
            if (contact.phoneNumbers.any { it.normalizedNumber == phoneNumber }) {
                return contact
            }
        }

        return null
    }

    /**
     * Finds a contact and the specific PhoneNumber object by normalized phone number.
     * Returns a Pair of (SimpleContact, PhoneNumber) if found, or null otherwise.
     */
    private fun findContactAndPhoneNumberByNormalizedNumber(normalizedNumber: String): Pair<SimpleContact, PhoneNumber>? {
        if (!hasPermission(PERMISSION_READ_CONTACTS)) {
            return null
        }

        // First check in allContacts
        allContacts.forEach { contact ->
            contact.phoneNumbers.forEach { phoneNumber ->
                if (phoneNumber.normalizedNumber == normalizedNumber) {
                    return Pair(contact, phoneNumber)
                }
            }
        }

        // Then check in privateContacts
        privateContacts.forEach { contact ->
            contact.phoneNumbers.forEach { phoneNumber ->
                if (phoneNumber.normalizedNumber == normalizedNumber) {
                    return Pair(contact, phoneNumber)
                }
            }
        }

        return null
    }

    /**
     * Finds a contact by name (case-insensitive, prioritizes exact matches).
     * Returns the first matching contact, or null if not found.
     */
    private fun findContactByName(name: String): SimpleContact? {
        if (!hasPermission(PERMISSION_READ_CONTACTS)) {
            return null
        }

        val searchName = name.trim().lowercase()
        if (searchName.isEmpty()) {
            return null
        }

        // First try exact match in allContacts
        allContacts.forEach { contact ->
            val contactName = contact.name.lowercase()
            if (contactName == searchName) {
                return contact
            }
        }

        // Then try exact match in privateContacts
        privateContacts.forEach { contact ->
            val contactName = contact.name.lowercase()
            if (contactName == searchName) {
                return contact
            }
        }

        // If no exact match, try partial matches (contact name contains search text)
        allContacts.forEach { contact ->
            val contactName = contact.name.lowercase()
            if (contactName.contains(searchName)) {
                return contact
            }
        }

        // Try partial matches in privateContacts
        privateContacts.forEach { contact ->
            val contactName = contact.name.lowercase()
            if (contactName.contains(searchName)) {
                return contact
            }
        }

        return null
    }

    /**
     * Validates if text can be added as a chip.
     * Returns true if:
     * - Text contains only numbers and phone formatting characters (+, -, spaces, parentheses), OR
     * - Text contains non-numbers but matches a contact
     * Returns false if:
     * - Text contains non-numbers AND doesn't match any contact
     */
    private fun isValidForChip(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return false
        }

        // Check if the original text contains only digits and common phone formatting characters
        // Phone formatting characters: +, -, spaces, parentheses, #, *
        val phoneFormatChars = setOf('+', '-', ' ', '(', ')', '#', '*')
        val containsOnlyNumbersAndFormatting = trimmed.all { it.isDigit() || phoneFormatChars.contains(it) }
        
        // If it contains only numbers and formatting, it's valid (will be normalized to digits)
        if (containsOnlyNumbersAndFormatting) {
            return true
        }

        // If it contains non-numeric characters (letters, etc.), check if it matches a contact
        // This prevents invalid text like "abc123" from being added unless it's a contact name
        val contact = findContactByName(trimmed)
        return contact != null
    }

    /**
     * Handles adding a chip when a contact name is typed.
     * Shows number picker dialog if contact has multiple phone numbers.
     */
    private fun handleContactNameChip(chipText: String, contact: SimpleContact) {
        if (contact.phoneNumbers.isEmpty()) {
            // Contact has no phone numbers, remove the chip
            isUpdatingChips = true
            binding.newConversationAddress.removeChip(chipText)
            isUpdatingChips = false
            toast(com.goodwy.commons.R.string.no_phone_number_found, length = Toast.LENGTH_SHORT)
            return
        }

        if (contact.phoneNumbers.size == 1) {
            // Single phone number, add chip directly
            val phoneNumber = contact.phoneNumbers.first()
            val displayText = getDisplayTextForPhoneNumberWithType(phoneNumber, contact)
            isUpdatingChips = true
            chipDisplayToPhoneNumber[displayText] = phoneNumber.normalizedNumber
            binding.newConversationAddress.removeChip(chipText)
            binding.newConversationAddress.addChip(displayText)
            isUpdatingChips = false
        } else {
            // Multiple phone numbers, show picker dialog
            isUpdatingChips = true
            binding.newConversationAddress.removeChip(chipText)
            isUpdatingChips = false
            
            maybeShowNumberPickerDialog(contact.phoneNumbers) { number ->
                val displayText = getDisplayTextForPhoneNumberWithType(number, contact)
                isUpdatingChips = true
                chipDisplayToPhoneNumber[displayText] = number.normalizedNumber
                binding.newConversationAddress.addChip(displayText)
                isUpdatingChips = false
            }
        }
    }

    
    private fun sendMessageAndNavigate(text: String, subscriptionId: Int?, attachments: List<Attachment>) {
        hideKeyboard()
        val chips = binding.newConversationAddress.allChips
        val allNumbers = mutableListOf<String>()
        chips.forEach { chip ->
            if (chip.isNotEmpty()) {
                // Get phone number from mapping
                val phoneNumber = chipDisplayToPhoneNumber[chip]
                if (phoneNumber != null) {
                    // Mapping exists, use the stored phone number
                    if (phoneNumber.isNotEmpty() && !allNumbers.contains(phoneNumber)) {
                        allNumbers.add(phoneNumber)
                    }
                } else {
                    // No mapping found - this shouldn't happen for properly added chips,
                    // but handle it as fallback: try to normalize the chip text
                    val normalizedNumber = chip.normalizePhoneNumber()
                    if (normalizedNumber.isNotEmpty() && normalizedNumber.length >= 3 && normalizedNumber.all { it.isDigit() }) {
                        if (!allNumbers.contains(normalizedNumber)) {
                            allNumbers.add(normalizedNumber)
                        }
                    } else {
                        // Not a valid phone number, try to look up contact name
                        val contact = findContactByName(chip)
                        if (contact != null && contact.phoneNumbers.isNotEmpty()) {
                            // Found contact, use first phone number
                            val contactPhoneNumber = contact.phoneNumbers.first().normalizedNumber
                            if (!allNumbers.contains(contactPhoneNumber)) {
                                allNumbers.add(contactPhoneNumber)
                            }
                        }
                    }
                }
            }
        }
        
        // Also check for text input that hasn't been added as a chip
        val currentText = binding.newConversationAddress.currentText.trim()
        if (currentText.isNotEmpty()) {
            // Split by comma or semicolon to handle multiple numbers
            val textNumbers = currentText.split(",", ";")
            // Validate each part before processing
            textNumbers.forEach { numberText ->
                val trimmedNumber = numberText.trim()
                if (trimmedNumber.isNotEmpty()) {
                    // Validate using isValidForChip before processing
                    if (!isValidForChip(trimmedNumber)) {
                        toast(R.string.invalid_contact_or_number, length = Toast.LENGTH_SHORT)
                        return
                    }
                }
            }
            
            // If validation passed, process the numbers
            textNumbers.forEach { numberText ->
                val trimmedNumber = numberText.trim()
                if (trimmedNumber.isNotEmpty()) {
                    // Normalize the phone number
                    val normalizedNumber = trimmedNumber.normalizePhoneNumber()
                    // Add if not already in the list (avoid duplicates)
                    if (normalizedNumber.isNotEmpty() && !allNumbers.contains(normalizedNumber)) {
                        allNumbers.add(normalizedNumber)
                    } else if (normalizedNumber.isEmpty() && !allNumbers.contains(trimmedNumber)) {
                        // If normalization failed, use the original trimmed number
                        allNumbers.add(trimmedNumber)
                    }
                }
            }
        }
        
        if (allNumbers.isEmpty()) {
            toast(R.string.empty_destination_address, length = Toast.LENGTH_SHORT)
            return
        }

        if (isScheduledMessage) {
            val finalSubscriptionId = subscriptionId
                ?: availableSIMCards.getOrNull(currentSIMCardIndex)?.subscriptionId
                ?: messageHolderHelper?.getSubscriptionIdForNumbers(allNumbers)
                ?: SmsManager.getDefaultSmsSubscriptionId()
            sendScheduledMessage(text, finalSubscriptionId, allNumbers)
            return
        }
        
        // Get subscription ID for the numbers if not provided
        val finalSubscriptionId = subscriptionId 
            ?: availableSIMCards.getOrNull(currentSIMCardIndex)?.subscriptionId
            ?: messageHolderHelper?.getSubscriptionIdForNumbers(allNumbers)
            ?: SmsManager.getDefaultSmsSubscriptionId()
        
        // Process message text (remove diacritics if needed)
        val processedText = removeDiacriticsIfNeeded(text)
        
        // Get thread ID before sending to delete draft
        val numbersSet = allNumbers.toSet()
        val threadId = getThreadId(numbersSet)
        
        // Send message
        try {
            sendMessageCompat(
                text = processedText,
                addresses = allNumbers,
                subId = finalSubscriptionId,
                attachments = attachments,
                messageId = null
            )
            
            // Play sound if enabled
            if (config.soundOnOutGoingMessages) {
                val audioManager = getSystemService(AudioManager::class.java)
                audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_SPACEBAR)
            }
            
            // Clear message and attachments
            messageHolderHelper?.clearMessage()
            deleteComposeAttachmentCacheIfUnnecessary()

            val staleResumeId = resumedDraftThreadId
            // Delete any draft for this thread to prevent it from showing in ThreadActivity;
            // also drop the list draft for the pre-edit recipient set if the user changed chips.
            ensureBackgroundThread {
                if (staleResumeId > 0L && staleResumeId != threadId) {
                    deleteSmsDraft(staleResumeId)
                }
                deleteSmsDraft(threadId)
                runOnUiThread { resumedDraftThreadId = 0L }
            }
            
            // Navigate to ThreadActivity after sending (don't pass body to avoid showing sent message)
            val numbersString = allNumbers.joinToString(";")
            val displayName = if (allNumbers.size == 1) allNumbers[0] else "${allNumbers.size} recipients"
            launchThreadActivity(numbersString, displayName, body = "")
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    
    private fun addContactAttachment(contactUri: Uri) {
        // Contact attachment functionality can be added later if needed
        toast(com.goodwy.commons.R.string.unknown_error_occurred)
    }
    
    private fun launchCapturePhotoIntent() {
        val imageFile = java.io.File.createTempFile("attachment_", ".jpg", getAttachmentsDir())
        val capturedImageUri = getMyFileUri(imageFile)
        messageHolderHelper?.setCapturedImageUri(capturedImageUri)
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
        Intent(Intent.ACTION_PICK).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            launchActivityForResult(this, MessageHolderHelper.PICK_CONTACT_INTENT)
        }
    }

    private fun launchContactPicker() {
        handlePermission(PERMISSION_READ_CONTACTS) { granted ->
            if (granted) {
                hideKeyboard()
                startActivityForResult(Intent(this, ContactPickerActivity::class.java), REQUEST_CODE_CONTACT_PICKER)
            } else {
                toast(com.goodwy.commons.R.string.no_contacts_permission, length = Toast.LENGTH_LONG)
            }
        }
    }
    
    private fun launchActivityForResult(intent: Intent, requestCode: Int) {
        hideKeyboard()
        try {
            startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }
    
    private fun getAttachmentsDir(): java.io.File {
        return java.io.File(cacheDir, "attachments").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    private fun askForExactAlarmPermissionIfNeeded(callback: () -> Unit = {}) {
        if (isSPlus()) {
            val alarmManager: AlarmManager = getSystemService(AlarmManager::class.java)
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

    private fun setupScheduleSendUi() = binding.messageHolder.apply {
        val textColor = getProperTextColor()
        scheduledMessageHolder.background.applyColorFilter(getProperPrimaryColor().darkenColor())
        scheduledMessageIcon.applyColorFilter(textColor)
        scheduledMessageButton.setTextColor(textColor)
        scheduledMessagePress.setOnClickListener {
            launchScheduleSendDialog()
        }
        discardScheduledMessage.apply {
            applyColorFilter(textColor)
            setOnClickListener {
                hideScheduleSendUi()
            }
        }
    }

    private fun showScheduleMessageDialog() {
        isScheduledMessage = true
        messageHolderHelper?.setScheduledMessage(true)
        messageHolderHelper?.updateSendButtonDrawable()
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
        messageHolderHelper?.setScheduledMessage(false)
        messageHolderHelper?.updateSendButtonDrawable()
        binding.messageHolder.scheduledMessageHolder.beGone()
    }

    @SuppressLint("MissingPermission")
    private fun sendScheduledMessage(text: String, subscriptionId: Int, allNumbers: List<String>) {
        val processedText = removeDiacriticsIfNeeded(text)
        if (scheduledDateTime.millis < System.currentTimeMillis() + 1000L) {
            toast(R.string.must_pick_time_in_the_future)
            launchScheduleSendDialog()
            return
        }
        try {
            val staleResumeId = resumedDraftThreadId
            ensureBackgroundThread {
                val canonicalThreadId = getThreadId(allNumbers.toSet())
                if (staleResumeId > 0L && canonicalThreadId > 0L && staleResumeId != canonicalThreadId) {
                    deleteSmsDraft(staleResumeId)
                    runOnUiThread { resumedDraftThreadId = 0L }
                }
                val messageId = generateRandomId()
                val participants = getParticipantsFromNumbers(allNumbers)
                val message = buildScheduledMessage(processedText, subscriptionId, messageId, participants)
                scheduleMessage(message)
                messagesDB.insertOrUpdate(message)
                createTemporaryThread(message, message.threadId, null) {
                    runOnUiThread {
                        resumedDraftThreadId = 0L
                        messageHolderHelper?.clearMessage()
                        deleteComposeAttachmentCacheIfUnnecessary()
                        hideScheduleSendUi()
                        val numbersString = allNumbers.joinToString(";")
                        val displayName = if (allNumbers.size == 1) allNumbers[0] else "${allNumbers.size} recipients"
                        launchThreadActivity(numbersString, displayName, body = "", threadId = message.threadId)
                    }
                }
            }
        } catch (e: Exception) {
            showErrorToast(e.localizedMessage ?: getString(com.goodwy.commons.R.string.unknown_error_occurred))
        }
    }

    private fun getParticipantsFromNumbers(numbers: List<String>): ArrayList<SimpleContact> {
        val participants = ArrayList<SimpleContact>()
        numbers.forEach { number ->
            val name = getDisplayTextForPhoneNumber(number)
            val phoneNumber = PhoneNumber(number, 0, "", number)
            participants.add(
                SimpleContact(
                    rawId = 0,
                    contactId = 0,
                    name = name,
                    photoUri = "",
                    phoneNumbers = arrayListOf(phoneNumber),
                    birthdays = ArrayList(),
                    anniversaries = ArrayList()
                )
            )
        }
        return participants
    }

    private fun buildScheduledMessage(
        text: String,
        subscriptionId: Int,
        messageId: Long,
        participants: ArrayList<SimpleContact>
    ): Message {
        val threadId = messageId
        val isGroupMms = participants.size > 1 && config.sendGroupMessageMMS
        val isLongMms = isLongMmsMessage(text)
        val hasAttachments = messageHolderHelper?.getAttachmentSelections()?.isNotEmpty() == true
        val isMMS = hasAttachments || isGroupMms || isLongMms
        val attachments = messageHolderHelper?.buildMessageAttachments(messageId) ?: ArrayList()
        return Message(
            id = messageId,
            body = text,
            type = MESSAGE_TYPE_QUEUED,
            status = STATUS_NONE,
            participants = participants,
            date = (scheduledDateTime.millis / 1000).toInt(),
            read = false,
            threadId = threadId,
            isMMS = isMMS,
            attachment = MessageAttachment(messageId, text, attachments),
            senderPhoneNumber = "",
            senderName = "",
            senderPhotoUri = "",
            subscriptionId = subscriptionId,
            isScheduled = true
        )
    }

    private fun handleForwardedMessage() {
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            // Handle text
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank()) {
                messageHolderHelper?.setMessageText(text)
                binding.messageHolder.threadTypeMessage.requestFocus()
            }
            
            // Handle single attachment
            if (intent.action == Intent.ACTION_SEND && intent.extras?.containsKey(Intent.EXTRA_STREAM) == true) {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                uri?.let {
                    messageHolderHelper?.addAttachment(it)
                }
            }
            
            // Handle multiple attachments
            if (intent.action == Intent.ACTION_SEND_MULTIPLE && intent.extras?.containsKey(Intent.EXTRA_STREAM) == true) {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                uris?.forEach { uri ->
                    messageHolderHelper?.addAttachment(uri)
                }
            }
        }
    }
    
    private fun launchThreadActivity(phoneNumber: String, name: String, body: String = "", photoUri: String = "", threadId: Long? = null) {
        hideKeyboard()
//        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra("sms_body") ?: ""
        val numbers = phoneNumber.split(";").toSet()
        val number = if (numbers.size == 1) phoneNumber else Gson().toJson(numbers)
        Intent(this, ThreadActivity::class.java).apply {
            putExtra(THREAD_ID, threadId ?: getThreadId(numbers))
            putExtra(THREAD_TITLE, name)
            putExtra(THREAD_TEXT, body.ifEmpty { intent.getStringExtra(Intent.EXTRA_TEXT) })
            putExtra(THREAD_NUMBER, number)
            putExtra(THREAD_URI, photoUri)

            if (intent.action == Intent.ACTION_SEND && intent.extras?.containsKey(Intent.EXTRA_STREAM) == true) {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                putExtra(THREAD_ATTACHMENT_URI, uri?.toString())
            } else if (intent.action == Intent.ACTION_SEND_MULTIPLE && intent.extras?.containsKey(
                    Intent.EXTRA_STREAM
                ) == true
            ) {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                putExtra(THREAD_ATTACHMENT_URIS, uris)
            }

            startActivity(this)
            finish()
        }
    }
    
    private fun showExpandedMessageFragment() {
        val currentText = binding.messageHolder.threadTypeMessage.text?.toString() ?: ""
        expandedMessageFragment = com.android.mms.fragments.ExpandedMessageFragment.newInstance(currentText)
        
        expandedMessageFragment?.setOnMessageTextChangedListener { text ->
            binding.messageHolder.threadTypeMessage.setText(text)
        }
        
        expandedMessageFragment?.setOnSendMessageListener {
            val text = expandedMessageFragment?.getMessageText() ?: ""
            binding.messageHolder.threadTypeMessage.setText(text)
            hideExpandedMessageFragment()
            // Get the message text and attachments, then send
            val messageText = binding.messageHolder.threadTypeMessage.text?.toString() ?: ""
            val attachments = messageHolderHelper?.buildMessageAttachments() ?: emptyList()
            val subscriptionId = messageHolderHelper?.getSubscriptionIdForNumbers(emptyList())
            sendMessageAndNavigate(messageText, subscriptionId, attachments)
        }
        
        expandedMessageFragment?.setOnMinimizeListener {
            val text = expandedMessageFragment?.getMessageText() ?: ""
            binding.messageHolder.threadTypeMessage.setText(text)
            hideExpandedMessageFragment()
        }
        
        expandedMessageFragment?.let { fragment ->
            // Same visibility pattern as [ThreadActivity.showExpandedMessageFragment]: fragment host is a
            // sibling of the main coordinator (see activity_new_conversation.xml), and the compose strip
            // is hidden so the expanded editor is the only message UI (matches thread full-screen expand).
            findViewById<View>(R.id.new_conversation_coordinator)?.beGone()
            findViewById<View>(R.id.message_holder_wrapper)?.beGone()
            findViewById<View>(R.id.fragment_container)?.beVisible()

            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack(null)
                .commit()
            
            // Update fragment title for new conversation
            fragment.view?.post {
                updateFragmentTitle(fragment)
            } ?: run {
                // If view is null, post with a small delay
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    fragment.view?.post {
                        updateFragmentTitle(fragment)
                    }
                }, 100)
            }
        }
    }
    
    private fun updateFragmentTitle(fragment: com.android.mms.fragments.ExpandedMessageFragment) {
        if (fragment.view == null) return
        
        val chips = binding.newConversationAddress.allChips
        val recipientNames = chips.filter { it.isNotEmpty() }
        val threadTitle = getNewConversationDisplayTitle()
        val expandedThreadId = when {
            resumedDraftThreadId > 0L -> resumedDraftThreadId
            recipientNames.isNotEmpty() -> {
                val numbers = recipientNames.mapNotNull { chipDisplayToPhoneNumber[it] }
                    .filter { it.isNotEmpty() }
                    .toSet()
                if (numbers.isNotEmpty()) getThreadId(numbers) else 0L
            }
            else -> 0L
        }
        val singleRecipient = recipientNames.singleOrNull()
        val singlePhone = singleRecipient?.let { chipDisplayToPhoneNumber[it] }

        fragment.updateThreadTitle(
            threadTitle = threadTitle,
            threadSubtitle = "",
            threadTopStyle = config.threadTopStyle,
            showContactThumbnails = config.showContactThumbnails,
            conversationPhotoUri = "",
            conversationTitle = singleRecipient,
            conversationPhoneNumber = singlePhone,
            isCompany = false,
            participantsCount = recipientNames.size,
            threadId = expandedThreadId,
        )
    }
    
    private fun hideExpandedMessageFragment() {
        expandedMessageFragment?.let {
            supportFragmentManager.popBackStack()
            findViewById<View>(R.id.fragment_container)?.beGone()
            findViewById<View>(R.id.new_conversation_coordinator)?.beVisible()
            findViewById<View>(R.id.message_holder_wrapper)?.beVisible()
            expandedMessageFragment = null
        }
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    private fun setupSIMSelector() {
        val textColor = getProperTextColor()
        val availableSIMs = subscriptionManagerCompat().activeSubscriptionInfoList ?: run {
            binding.messageHolder.syncEmojiButtonWithSimHolderVisibility()
            return
        }
        if (availableSIMs.size > 1) {
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
            // Get phone numbers from chips
            binding.newConversationAddress.allChips.forEach { chip ->
                if (chip.isNotEmpty()) {
                    val phoneNumber = chipDisplayToPhoneNumber[chip]
                    if (phoneNumber != null && phoneNumber.isNotEmpty() && !numbers.contains(phoneNumber)) {
                        numbers.add(phoneNumber)
                    } else {
                        // Try to normalize the chip text as fallback
                        val normalizedNumber = chip.normalizePhoneNumber()
                        if (normalizedNumber.isNotEmpty() && normalizedNumber.length >= 3 && normalizedNumber.all { it.isDigit() }) {
                            if (!numbers.contains(normalizedNumber)) {
                                numbers.add(normalizedNumber)
                            }
                        }
                    }
                }
            }

            // Determine SIM index - use numbers if available, otherwise use default
            currentSIMCardIndex = if (numbers.isNotEmpty()) {
                getProperSimIndex(availableSIMs, numbers)
            } else {
                // No numbers yet, use default SMS subscription or first SIM
                val defaultSmsSubscriptionId = SmsManager.getDefaultSmsSubscriptionId()
                val systemPreferredSimIdx = if (defaultSmsSubscriptionId >= 0) {
                    availableSIMs.indexOfFirstOrNull { it.subscriptionId == defaultSmsSubscriptionId }
                } else {
                    null
                }
                systemPreferredSimIdx ?: 0
            }

            binding.messageHolder.threadSelectSimIcon.beVisible()
            binding.messageHolder.threadSelectSimNumber.beVisible()
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
            binding.messageHolder.threadSelectSimIconHolder.beVisibleIf(true)
            // added by sun ------->
            // if ask every time in system setting, not show sim icon
            if (SmsManager.getDefaultSmsSubscriptionId() < 0) {
                binding.messageHolder.threadSelectSimNumber.beGone()
                binding.messageHolder.threadSelectSimIconHolder.beVisibleIf(false)
            }
            // <----------
            val simLabel =
                if (availableSIMCards.size > currentSIMCardIndex) availableSIMCards[currentSIMCardIndex].label else "SIM Card"
            binding.messageHolder.threadSelectSimIconHolder.contentDescription = simLabel

            if (availableSIMCards.isNotEmpty()) {
                binding.messageHolder.threadSelectSimIconHolder.setOnClickListener {
//                    binding.messageHolder.simPopupPicker.beVisible()
                    currentSIMCardIndex = (currentSIMCardIndex + 1) % availableSIMCards.size
                    val currentSIMCard = availableSIMCards[currentSIMCardIndex]
                    @SuppressLint("SetTextI18n")
                    binding.messageHolder.threadSelectSimNumber.text = currentSIMCard.id.toString()
                    // changed by sun for set color to sim type --->
//                    val simColor = if (!config.colorSimIcons) textColor
//                    else {
//                        val simId = currentSIMCard.id
//                        if (simId in 1..4) config.simIconsColors[simId] else config.simIconsColors[0]
//                    }
                    val simColor = resolveSimIconTint(textColor, currentSIMCard.subscriptionId, currentSIMCard.id)
                    trySetSystemDefaultSmsSubscriptionId(currentSIMCard.subscriptionId)
                    // <------------
                    binding.messageHolder.threadSelectSimIcon.applyColorFilter(simColor)
                    val currentSubscriptionId = currentSIMCard.subscriptionId
                    // Only save preference if we have phone numbers
                    if (numbers.isNotEmpty()) {
                        numbers.forEach {
                            config.saveUseSIMIdAtNumber(it, currentSubscriptionId)
                        }
                    }
                    it.performHapticFeedback()
                    binding.messageHolder.threadSelectSimIconHolder.contentDescription = currentSIMCard.label
                    toast(currentSIMCard.label)
                    updateAvailableMessageCountForCurrentSim()
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
                // <-------------

                binding.messageHolder.threadSelectSimIcon.applyColorFilter(simColor)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        } else {
            binding.messageHolder.threadSelectSimIconHolder.beGone()
//            binding.messageHolder.simPopupPicker.beGone()
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
                countView.setTextColor(getProperTextColor())
                if (smsCount == null) {
                   countView.beGone()
                } else {
                    countView.text = getString(R.string.available_sms_count, smsCount)
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

        val defaultSmsSubscriptionId = SmsManager.getDefaultSmsSubscriptionId()
        val systemPreferredSimIdx = if (defaultSmsSubscriptionId >= 0) {
            availableSIMs.indexOfFirstOrNull { it.subscriptionId == defaultSmsSubscriptionId }
        } else {
            null
        }

        return userPreferredSimIdx ?: systemPreferredSimIdx ?: 0
    }

    override fun onPause() {
        super.onPause()
        composeBarBottomInsetLatch = ComposeBarBottomInsetLatch.NONE
        saveNewConversationDraft()
    }

    override fun onStop() {
        super.onStop()
        saveNewConversationDraft()
    }

    /**
     * Adds recipients typed in the chips field but not yet committed as chips (comma/semicolon separated).
     * Invalid segments are skipped so a draft can still be saved from valid chips + valid pending input.
     */
    private fun mergeRecipientNumbersFromRecipientField(allNumbers: MutableList<String>) {
        val currentText = binding.newConversationAddress.currentText.trim()
        if (currentText.isEmpty()) return
        val textNumbers = currentText.split(",", ";")
        for (numberText in textNumbers) {
            val trimmedNumber = numberText.trim()
            if (trimmedNumber.isEmpty() || !isValidForChip(trimmedNumber)) continue
            val normalizedNumber = trimmedNumber.normalizePhoneNumber()
            when {
                normalizedNumber.isNotEmpty() && !allNumbers.contains(normalizedNumber) ->
                    allNumbers.add(normalizedNumber)
                normalizedNumber.isEmpty() && !allNumbers.contains(trimmedNumber) ->
                    allNumbers.add(trimmedNumber)
            }
        }
    }

    private fun saveNewConversationDraft() {
        val chips = binding.newConversationAddress.allChips
        val messageText = binding.messageHolder.threadTypeMessage.text?.toString()?.trim() ?: ""
        val hasAttachments = messageHolderHelper?.getAttachmentSelections()?.isNotEmpty() == true
        val hasMessage =
            messageText.isNotEmpty() || hasAttachments || isScheduledMessage
        val staleResumeId = resumedDraftThreadId
        val allNumbers = mutableListOf<String>()
        chips.forEach { chip ->
            if (chip.isNotEmpty()) {
                val phoneNumber = chipDisplayToPhoneNumber[chip]
                    ?: chip.normalizePhoneNumber().takeIf { it.length >= 3 && it.all { c -> c.isDigit() } }
                if (phoneNumber != null && phoneNumber.isNotEmpty() && !allNumbers.contains(phoneNumber)) {
                    allNumbers.add(phoneNumber)
                }
            }
        }

        mergeRecipientNumbersFromRecipientField(allNumbers)

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
        val scheduledMillis = if (isScheduledMessage) scheduledDateTime.millis else 0L

        val shouldPersist = hasMessage && allNumbers.isNotEmpty()
        val shouldClearDraftForEmptyMessage = !hasMessage && allNumbers.isNotEmpty()
        if (staleResumeId <= 0L && !shouldPersist && !shouldClearDraftForEmptyMessage) return

        ensureBackgroundThread {
            var didMutateDrafts = false
            if (staleResumeId > 0L) {
                val currentTid = if (allNumbers.isNotEmpty()) getThreadId(allNumbers.toSet()) else 0L
                val recipientsChanged =
                    allNumbers.isEmpty() || (currentTid > 0L && currentTid != staleResumeId)
                if (recipientsChanged) {
                    deleteSmsDraft(staleResumeId)
                    refreshConversationRowFromTelephony(staleResumeId)
                    runOnUiThread { resumedDraftThreadId = 0L }
                    didMutateDrafts = true
                }
            }

            if (shouldClearDraftForEmptyMessage) {
                val tid = getThreadId(allNumbers.toSet())
                if (tid > 0L) {
                    deleteSmsDraft(tid)
                    refreshConversationRowFromTelephony(tid)
                    didMutateDrafts = true
                }
                if (staleResumeId > 0L && staleResumeId == tid) {
                    runOnUiThread { resumedDraftThreadId = 0L }
                }
            }

            if (shouldPersist) {
                val threadId = getThreadId(allNumbers.toSet())
                if (threadId > 0) {
                    saveSmsDraft(
                        body = messageText,
                        threadId = threadId,
                        attachmentsJson = attachmentsJson,
                        isScheduled = isScheduledMessage,
                        scheduledMillis = scheduledMillis,
                    )
                    didMutateDrafts = true
                    refreshConversationRowFromTelephony(threadId)
                }
            }

            if (didMutateDrafts) {
                val localOnly = config.selectedConversationPin == 0
                runOnUiThread {
                    EventBus.getDefault().post(Events.RefreshConversations(localListRefreshOnly = localOnly))
                }
            }
        }
    }

    override fun onBackPressedCompat(): Boolean {
        if (messageHolderHelper?.dismissEmojiPicker() == true) return true
        return if (expandedMessageFragment != null) {
            hideExpandedMessageFragment()
            true
        } else {
            false
        }
    }
}
