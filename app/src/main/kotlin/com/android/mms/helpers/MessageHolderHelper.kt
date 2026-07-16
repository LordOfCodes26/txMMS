package com.android.mms.helpers

import android.annotation.SuppressLint
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.media.RingtoneManager
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import androidx.core.view.updateLayoutParams
import com.android.common.dialogs.MDateTimePickerDialog
import com.android.mms.R
import com.goodwy.commons.extensions.trackOpenDialog
import com.android.mms.activities.NewConversationActivity
import com.android.mms.activities.SimpleActivity
import com.android.mms.adapters.AttachmentsAdapter
import com.android.mms.databinding.LayoutThreadSendMessageHolderBinding
import com.android.mms.dialogs.SelectSIMDialog
import com.android.mms.dialogs.SelectSimDialogAnchorPlacement
import com.android.mms.emoji.Ch350EmojiBootstrap
import com.android.mms.emoji.ChatPaneEmoji
import com.android.mms.emoji.Ch350EmojiText.getCh350EncodedText
import com.android.mms.emoji.Ch350EmojiText.setCh350ComposeText
import com.android.mms.emoji.RepeatListener
import com.android.mms.extensions.*
import com.android.mms.activities.ManageSlideshowActivity
import com.android.mms.activities.PlaySlideshowActivity
import com.android.mms.activities.ViewMmsActivity
import com.android.mms.models.Attachment
import com.android.mms.models.AttachmentSelection
import com.android.mms.models.DraftStoredAttachment
import com.android.mms.models.MmsSlide
import com.android.mms.models.MmsSlideshow
import com.android.mms.models.SIMCard
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.dialogs.RadioGroupDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.RadioItem
import com.goodwy.commons.models.contacts.Contact
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import douglasspgyn.com.github.circularcountdown.CircularCountdown
import douglasspgyn.com.github.circularcountdown.listener.CircularListener
import eightbitlab.com.blurview.BlurTarget
import java.io.File

class MessageHolderHelper(
    private val activity: BaseSimpleActivity,
    private val binding: LayoutThreadSendMessageHolderBinding,
    private val threadId: Long = 0L,
    private val onSendMessage: (text: String, subscriptionId: Int?, attachments: List<Attachment>) -> Unit,
    private val onSpeechToText: () -> Unit = {},
    private val onExpandMessage: (() -> Unit)? = null,
    private val onTextChanged: ((String) -> Unit)? = null,
    private val onHideAttachmentPickerRequested: (() -> Unit)? = null,
    private val onThreadTypeMessageFocusChange: ((hasFocus: Boolean) -> Unit)? = null,
    /**
     * Invoked before hiding the attachment or emoji panel when the field gains focus
     * (keyboard replacing the in-layout bottom panel).
     */
    private val onPrepareKeyboardFromBottomPanel: (() -> Unit)? = null,
    /**
     * When set, opening the emoji pane is orchestrated by the activity (same deferred IME hide as the
     * attachment picker). The activity must call [showEmojiPicker] when ready.
     */
    private val onOpenEmojiPickerRequested: (() -> Unit)? = null,
    private val hasAddressForSend: (() -> Boolean)? = null,
    private val countdownRecipientNumbers: (() -> List<String>)? = null,
    private val resumeCountdownInNewConversation: Boolean = false,
    /** Resolves the store key at send/pause time (e.g. after recipients are added in new compose). */
    private val countdownThreadIdProvider: () -> Long = { threadId },
) {
    var isCountdownActive = false
        private set

    private fun storeThreadId(): Long = countdownThreadIdProvider().takeIf { it > 0L } ?: threadId

    private fun resolveCountdownThreadId(override: Long = 0L): Long =
        override.takeIf { it > 0L } ?: storeThreadId()
    private var countdownView: CircularCountdown? = null
    private var countdownCompleting = false
    private var isSpeechToTextAvailable = false
    private val availableSIMCards = ArrayList<SIMCard>()
    private var currentSIMCardIndex = 0
    private var capturedVideoUri: Uri? = null
    private var attachmentIntentLauncher: AttachmentIntentLauncher? = null
    private var pendingReplaceAttachmentId: String? = null
    private var mmsSlideshow: MmsSlideshow? = null
    private var chatPaneEmoji: ChatPaneEmoji? = null
    private var isEmojiPickerVisible: Boolean = false
    var isScheduledMessage: Boolean = false
        private set

    private val composeUiHandler = Handler(Looper.getMainLooper())
    private val debouncedRefreshCharacterCounter = object : Runnable {
        override fun run() {
            refreshCharacterCounterNow()
        }
    }

    /** Last applied send-row mode so we do not reset [View.setOnClickListener] on every keystroke. */
    private enum class ComposeSendMode { SEND, SPEECH, DISABLED }

    private var appliedComposeSendMode: ComposeSendMode? = null

    /** Avoid [SmsMessage.calculateLength] / normalization on every key when the counter is debounced. */
    private var lastExpandIconVisible: Boolean? = null

    companion object {
        const val CAPTURE_PHOTO_INTENT = 2001
        const val CAPTURE_VIDEO_INTENT = 2002
        const val CAPTURE_AUDIO_INTENT = 2003
        const val PICK_PHOTO_INTENT = 2004
        const val PICK_VIDEO_INTENT = 2005
        const val PICK_DOCUMENT_INTENT = 2006
        const val PICK_CONTACT_INTENT = 2007
        const val PICK_RINGTONE_INTENT = 2008
        const val PICK_SOUND_INTENT = 2009
        const val SELECT_CONTACTS_ACTION = "com.android.contacts.action.SELECT_CONTACTS"
        const val SELECT_CONTACTS_RESULT_ADDED_IDS = "added_contact_ids"
        const val SELECT_CONTACTS_RESULT_ALL_IDS = "all_selected_contact_ids"
	private const val LOG_TAG = "PickContactAttachment"

        private const val CHARACTER_COUNTER_DEBOUNCE_MS = 48L
        private const val CAPTURE_VIDEO_FINALIZE_MAX_ATTEMPTS = 50
        private const val CAPTURE_VIDEO_FINALIZE_RETRY_MS = 50L
        private const val CAPTURE_PHOTO_FINALIZE_MAX_ATTEMPTS = 50
        private const val CAPTURE_PHOTO_FINALIZE_RETRY_MS = 50L
        fun createSelectContactsIntent(): Intent = Intent(SELECT_CONTACTS_ACTION)

        fun logSelectContactsResult(callerTag: String, resultCode: Int, resultData: Intent?) {
            val tag = "$LOG_TAG:$callerTag"
            if (resultData == null) {
                Log.w(tag, "onActivityResult requestCode=$PICK_CONTACT_INTENT resultCode=$resultCode resultData=null")
                return
            }
            val extrasSummary = resultData.extras?.let { bundle ->
                bundle.keySet().joinToString(prefix = "{", postfix = "}") { key ->
                    val value = bundle.get(key)
                    val rendered = when (value) {
                        is LongArray -> value.contentToString()
                        is IntArray -> value.contentToString()
                        is Array<*> -> value.contentToString()
                        else -> value.toString()
                    }
                    "$key=$rendered"
                }
            } ?: "{}"
            Log.d(
                tag,
                "onActivityResult requestCode=$PICK_CONTACT_INTENT resultCode=$resultCode " +
                    "data=${resultData.data} clipData=${resultData.clipData} extras=$extrasSummary",
            )
        }

        fun getSelectedRawContactIds(resultData: Intent?): List<Int> {
            if (resultData == null) return emptyList()
            val fromAll = resultData.getLongArrayExtra(SELECT_CONTACTS_RESULT_ALL_IDS)
            val fromAdded = resultData.getLongArrayExtra(SELECT_CONTACTS_RESULT_ADDED_IDS)
            val ids = fromAll?.takeIf { it.isNotEmpty() } ?: fromAdded
            return ids?.map { it.toInt() } ?: emptyList()
        }
    }

    private var captureVideoFinalizeAttempt = 0
    private var capturePhotoFinalizeAttempt = 0

    fun setup(isSpeechToTextAvailable: Boolean = false) {
        this.isSpeechToTextAvailable = isSpeechToTextAvailable
        val textColor = activity.getProperTextColor()
        val properPrimaryColor = activity.getProperPrimaryColor()
        val useSurfaceColor = activity.isDynamicTheme() && !activity.isSystemInDarkMode()
        val surfaceColor = if (useSurfaceColor) activity.getProperBackgroundColor() else activity.getSurfaceColor()

        binding.apply {
            threadSendMessage.applyColorFilter(textColor)
//            threadAddAttachment.applyColorFilter(textColor)
//            threadAddAttachment.background.applyColorFilter(surfaceColor)
            // threadTypeMessageHolder.background.applyColorFilter(surfaceColor)

            threadCharacterCounter.beVisibleIf(threadTypeMessage.value.isNotEmpty() && activity.config.showCharacterCounter)
            threadCharacterCounter.backgroundTintList = activity.getProperBackgroundColor().getColorStateList()

            threadTypeMessage.setTextSize(TypedValue.COMPLEX_UNIT_PX, activity.getTextSizeMessage())

            if (isSpeechToTextAvailable) {
                threadSendMessage.setOnLongClickListener {
                    onSpeechToText()
                    true
                }
            }

//            (ResourcesCompat.getDrawable(activity.resources, R.drawable.thread_send_message_circle_border, activity.theme)?.mutate() as? GradientDrawable)?.let { drawable ->
//                drawable.setStroke((activity.resources.displayMetrics.density).toInt(), textColor)
//                threadSendMessage.background = drawable
//            }
            threadSendMessage.isClickable = false
            hideCountdown()

            threadExpandMessage.apply {
                setOnClickListener {
                    onExpandMessage?.invoke()
                }
            }

            threadTypeMessage.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    if (binding.attachmentPickerHolder.isVisible() || isEmojiPickerVisible) {
                        onPrepareKeyboardFromBottomPanel?.invoke()
                    }
                    onHideAttachmentPickerRequested?.invoke()
                    hideAttachmentPicker()
                    hideEmojiPicker(resumeKeyboard = false)
                    // Focus alone does not guarantee the IME (e.g. after toolbar popup dismiss).
                    showComposeKeyboard()
                }
                onThreadTypeMessageFocusChange?.invoke(hasFocus)
            }

            threadTypeMessage.onTextChangeListener {
                onTextChanged?.invoke(it)
                checkSendMessageAvailability()
                if (activity.config.showCharacterCounter) {
                    if (it.isEmpty()) {
                        composeUiHandler.removeCallbacks(debouncedRefreshCharacterCounter)
                        threadCharacterCounter.beGone()
                    } else {
                        composeUiHandler.removeCallbacks(debouncedRefreshCharacterCounter)
                        composeUiHandler.postDelayed(
                            debouncedRefreshCharacterCounter,
                            CHARACTER_COUNTER_DEBOUNCE_MS,
                        )
                    }
                } else {
                    composeUiHandler.removeCallbacks(debouncedRefreshCharacterCounter)
                }
                updateExpandIconVisibility()
            }

            threadTypeMessage.onGlobalLayout {
                updateExpandIconVisibility()
            }

            if (activity.config.sendOnEnter) {
                // Keep TYPE_CLASS_TEXT and MULTI_LINE — setting only CAP_SENTENCES breaks CJK IMEs.
                threadTypeMessage.inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
                threadTypeMessage.imeOptions = EditorInfo.IME_ACTION_SEND
                threadTypeMessage.setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_SEND) {
                        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                        return@setOnEditorActionListener true
                    }
                    false
                }

                threadTypeMessage.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
                        if (activity.config.messageSendDelay > 0 && !isCountdownActive) {
                           resolveSubscriptionThen { startSendMessageCountdown(it) }
                        } else {
                            pickSimAndSendOrSendDirect()
                        }
                        return@setOnKeyListener true
                    }
                    false
                }
            }
        }

        setupEmojiToggle()

        checkSendMessageAvailability()
        binding.syncEmojiButtonWithSimHolderVisibility()
        updateExpandIconVisibility()
    }

    private fun setupEmojiToggle() {
        binding.imvEmoticBtn.setOnClickListener {
            toggleEmojiPicker()
        }
    }

    private fun ensureCh350EmojiPane() {
        if (chatPaneEmoji != null) return
        if (!Ch350EmojiBootstrap.ensureInitialized(activity)) {
            activity.toast(R.string.ch350_emoji_pack_missing)
            return
        }
        val pane = ChatPaneEmoji(activity, binding.threadTypeMessage)
        val repeatListener = RepeatListener(400, 30) {
            chatPaneEmoji?.deleteAtCaret()
        }
        pane.setBackspaceRepeatListener(repeatListener)
        binding.messageEmojiPickerHolder.addView(
            pane,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        chatPaneEmoji = pane
    }

    /** @return true if the emoji pane was visible and is now closed. */
    fun dismissEmojiPicker(): Boolean {
        if (!isEmojiPickerVisible) return false
        hideEmojiPicker(resumeKeyboard = false)
        return true
    }

    fun isEmojiPickerPaneVisible(): Boolean = isEmojiPickerVisible

    /** Ensures the emoji pack is loaded; returns false if it cannot be shown. */
    fun prepareEmojiPicker(): Boolean {
        ensureCh350EmojiPane()
        return chatPaneEmoji != null
    }

    private fun toggleEmojiPicker() {
        if (isEmojiPickerVisible) {
            onPrepareKeyboardFromBottomPanel?.invoke()
            hideEmojiPicker(resumeKeyboard = true)
        } else {
            val openRequested = onOpenEmojiPickerRequested
            if (openRequested != null) {
                openRequested.invoke()
            } else {
                showEmojiPicker()
            }
        }
    }

    /**
     * Shows the emoji pane at [config.keyboardHeight]. Does not hide the IME — callers that open from a
     * visible keyboard must wait until IME insets report hidden (same as [showAttachmentPicker]).
     * @return false if the emoji pack could not be initialized.
     */
    fun showEmojiPicker(): Boolean {
        ensureCh350EmojiPane()
        if (chatPaneEmoji == null) return false
        hideAttachmentPicker()
        isEmojiPickerVisible = true
        binding.messageEmojiPickerHolder.updateLayoutParams<ViewGroup.LayoutParams> {
            height = activity.config.keyboardHeight
        }
        binding.messageEmojiPickerHolder.beVisible()
        chatPaneEmoji?.showFirstTab()
        binding.imvEmoticBtn.setBackgroundResource(R.drawable.ic_sms_keyboard)
        binding.imvEmoticBtn.contentDescription = activity.getString(com.goodwy.commons.R.string.keyboard_short)
        binding.root.post { binding.root.requestLayout() }
        return true
    }

    private fun hideEmojiPicker(resumeKeyboard: Boolean) {
        if (!isEmojiPickerVisible) return
        isEmojiPickerVisible = false
        binding.messageEmojiPickerHolder.beGone()
        binding.imvEmoticBtn.setBackgroundResource(R.drawable.ic_emotic)
        binding.imvEmoticBtn.contentDescription = activity.getString(com.goodwy.commons.R.string.choose_emoji)
        if (resumeKeyboard) {
            showComposeKeyboard()
        }
    }

    /**
     * Ensure the IME is shown while [binding.threadTypeMessage] is focused.
     * Matches NewConversation recipients: soft-input mode + posted [showKeyboard], because focus
     * alone does not always bring the keyboard back (toolbar/popup dismiss).
     */
    fun showComposeKeyboard() {
        val messageInput = binding.threadTypeMessage
        if (!messageInput.hasFocus()) {
            messageInput.requestFocus()
        }
        val softInputMode = activity.window.attributes.softInputMode
        val adjust = softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        activity.window.setSoftInputMode(
            adjust or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        messageInput.post { activity.showKeyboard(messageInput) }
    }

    fun setupAttachmentPicker(
        onChoosePhoto: () -> Unit,
        onChooseVideo: () -> Unit,
        onTakePhoto: () -> Unit,
        onRecordVideo: () -> Unit,
        onPickAudio: () -> Unit,
        onPickFile: () -> Unit,
        onPickContact: () -> Unit,
        onScheduleMessage: (() -> Unit)? = null,
        onPickQuickText: () -> Unit
    ) {
        binding.attachmentPicker.apply {
            val buttonColors = arrayOf(
                if (activity.isDynamicTheme()) com.goodwy.commons.R.color.you_neutral_text_color
                else if (activity.isLightTheme() || activity.isGrayTheme()) com.goodwy.commons.R.color.theme_dark_background_color
                else com.goodwy.commons.R.color.white,
                com.goodwy.commons.R.color.md_purple_500,
                com.goodwy.commons.R.color.md_blue_500,
                com.goodwy.commons.R.color.red_missed,
                com.goodwy.commons.R.color.ic_dialer,
                if (activity.isDynamicTheme()) com.goodwy.commons.R.color.you_neutral_text_color
                else if (activity.isLightTheme() || activity.isGrayTheme()) com.goodwy.commons.R.color.theme_dark_background_color
                else com.goodwy.commons.R.color.white,
                com.goodwy.commons.R.color.ic_contacts,
                com.goodwy.commons.R.color.ic_messages,
                if (activity.isDynamicTheme()) com.goodwy.commons.R.color.you_neutral_text_color
                else if (activity.isLightTheme() || activity.isGrayTheme()) com.goodwy.commons.R.color.theme_dark_background_color
                else com.goodwy.commons.R.color.white
            ).map { ResourcesCompat.getColor(activity.resources, it, activity.theme) }

//            arrayOf(
//                chooseClockIcon,
//                chooseEmojiIcon,
//                chooseTextIcon,
//                chooseContactIcon,
//                chooseImageIcon,
//                chooseVoiceIcon,
//                chooseCameraIcon,
//                chooseTitleIcon
//            ).forEachIndexed { index, icon ->
//                val iconColor = buttonColors[index]
//                icon.background.applyColorFilter(iconColor)
//                if (index != 0 && index != 2 && index != 5 && index != 8) icon.applyColorFilter(iconColor.getContrastColor())
//                if (index == 5 || index == 8) icon.applyColorFilter(ResourcesCompat.getColor(activity.resources, com.goodwy.commons.R.color.ic_messages, activity.theme))
//            }

            val textColor = activity.getProperTextColor()
            arrayOf(
                chooseClockText,
                chooseEmojiText,
                chooseTextText,
                chooseContactText,
                chooseImageText,
                chooseVoiceText,
                chooseCameraText,
                chooseRecordVideoText,
            ).forEach { it.setTextColor(textColor) }
//            chooseClock
//            chooseEmoji
//            chooseText
//            chooseContact
//            chooseImage
//            chooseVoice
//            chooseCamera
//            chooseTitle
            chooseClock.setOnClickListener {
                if (onScheduleMessage != null) {
                    // Keep attachment picker open so the schedule dialog overlays it.
                    onScheduleMessage.invoke()
                } else {
                    val blurTarget = activity.findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
                        ?: throw IllegalStateException("mainBlurTarget not found")
                    val mDateTimePickerDialog = MDateTimePickerDialog(activity)
                    mDateTimePickerDialog.bindBlurTarget(blurTarget)
                    mDateTimePickerDialog.show()
                    activity.trackOpenDialog(mDateTimePickerDialog)
                    mDateTimePickerDialog.setOnDateSelectListener { datetime ->
                        insertText(datetime.toLocaleString())
                    }
                }
            }
            val hidePickerThen: (() -> Unit) -> Unit = { action ->
                onHideAttachmentPickerRequested?.invoke() ?: hideAttachmentPicker()
                action()
            }
            chooseImage.setOnClickListener { hidePickerThen(onChoosePhoto) }
            chooseEmoji.setOnClickListener {
                onOpenEmojiPickerRequested?.invoke() ?: showEmojiPicker()
            }
            // Keep attachment picker open so the quick texts dialog overlays it.
            chooseText.setOnClickListener { onPickQuickText() }
            chooseCamera.setOnClickListener { hidePickerThen(onTakePhoto) }
            chooseVoice.setOnClickListener { hidePickerThen(onPickAudio) }
            chooseContact.setOnClickListener { hidePickerThen(onPickContact) }
            chooseRecordVideo.setOnClickListener { hidePickerThen(onRecordVideo) }
        }
    }

    // changed by sun
    // added params
    // why in newConversationActivity send message button has to disable if newConversationAddress and message are empty.
    // and in ThreadActivity only message is empty
    fun checkSendMessageAvailability() {
        val selections = getAttachmentSelections()
        val hasReadyAttachments = selections.isNotEmpty() && !selections.any { it.isPending }
        val hasText = binding.threadTypeMessage.text?.isNotEmpty() == true
        val hasContent = hasText || hasReadyAttachments

        val requiresAddress = hasAddressForSend != null
        val hasAddress = hasAddressForSend?.invoke() ?: true
        val inAirplaneMode = activity.isAirplaneModeOn()
        if (inAirplaneMode && isCountdownActive) {
            cancelSendMessageCountdown(showCancelledToast = false)
        }
        val canSend = hasContent && hasAddress && !inAirplaneMode

        val newMode = when {
            canSend -> ComposeSendMode.SEND
            // Ready to send but radio is off: keep disabled (do not switch to mic).
            hasContent && hasAddress && inAirplaneMode -> ComposeSendMode.DISABLED
            isSpeechToTextAvailable && (!requiresAddress || hasAddress) -> ComposeSendMode.SPEECH
            else -> ComposeSendMode.DISABLED
        }

        if (newMode != appliedComposeSendMode) {
            appliedComposeSendMode = newMode
            applyComposeSendMode(newMode)
        }

        updateSendButtonDrawable(selections)
    }

    private fun applyComposeSendMode(mode: ComposeSendMode) {
        binding.apply {
            when (mode) {
                ComposeSendMode.SEND -> {
                    threadSendMessage.apply {
                        isEnabled = true
                        isClickable = true
                        alpha = 1f
                        contentDescription = activity.getString(R.string.sending)
                        setOnClickListener {
                            if (activity.config.messageSendDelay > 0 && !isCountdownActive) {
                                resolveSubscriptionThen { startSendMessageCountdown(it) }
                            } else {
                                pickSimAndSendOrSendDirect()
                                if (activity.config.soundOnOutGoingMessages) {
                                    val audioManager = activity.getSystemService(AudioManager::class.java)
                                    audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_SPACEBAR)
                                }
                            }
                        }
                    }
                }
                ComposeSendMode.SPEECH -> {
                    threadSendMessage.apply {
                        isEnabled = true
                        isClickable = true
                        alpha = 1f
                        contentDescription = activity.getString(com.goodwy.strings.R.string.voice_input)
                        setOnClickListener {
                            onSpeechToText()
                        }
                    }
                }
                ComposeSendMode.DISABLED -> {
                    threadSendMessage.apply {
                        isEnabled = false
                        isClickable = false
                        alpha = 0.4f
                    }
                }
            }
        }
    }

    private fun refreshCharacterCounterNow() {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!activity.config.showCharacterCounter) {
            binding.threadCharacterCounter.beGone()
            return
        }
        val text = binding.threadTypeMessage.text?.toString() ?: ""
        if (text.isEmpty()) {
            binding.threadCharacterCounter.beGone()
            return
        }
        val messageString = if (activity.config.useSimpleCharacters) {
            text.normalizeString()
        } else {
            text
        }
        val messageLength = SmsMessage.calculateLength(messageString, false)
        @SuppressLint("SetTextI18n")
        binding.threadCharacterCounter.text = "${messageLength[2]}/${messageLength[0]}"
        binding.threadCharacterCounter.beVisible()
    }

    fun updateSendButtonDrawable(selectionsOverride: List<AttachmentSelection>? = null) {
        val selections = selectionsOverride ?: getAttachmentSelections()
        val hasReadyAttachments = selections.isNotEmpty() && !selections.any { it.isPending }
        val hasText = binding.threadTypeMessage.text?.isNotEmpty() == true
        val hasContent = hasText || hasReadyAttachments
        val hasAddress = hasAddressForSend?.invoke() ?: true
        val drawableResId = if (isScheduledMessage) {
            com.android.common.R.drawable.ic_cmn_alarm
        } else if (hasContent || hasAddress) {
            com.android.common.R.drawable.ic_cmn_sms_send
        } else if (isSpeechToTextAvailable) {
            com.goodwy.commons.R.drawable.ic_microphone_vector
        } else {
            com.android.common.R.drawable.ic_cmn_sms_send
        }
        binding.threadSendMessage.applyColorFilter(activity.getProperTextColor())
//        ResourcesCompat.getDrawable(activity.resources, drawableResId, activity.theme)?.apply {
//            applyColorFilter(activity.getProperPrimaryColor().getContrastColor())
//            binding.threadSendMessage.setImageDrawable(this)
//        }
    }
    
    fun setScheduledMessage(scheduled: Boolean) {
        isScheduledMessage = scheduled
        updateSendButtonDrawable()
    }

    fun bindSendMessageCountdownStore(countdownThreadId: Long = 0L) {
        val storeId = resolveCountdownThreadId(countdownThreadId)
        if (storeId <= 0L) return
        SendMessageCountdownStore.setFinishListener(storeId) { pending ->
            if (activity.isFinishing || activity.isDestroyed) {
                PendingSendCountdownFinisher.finish(activity.applicationContext, pending)
                return@setFinishListener
            }
            if (countdownCompleting) return@setFinishListener
            completeCountdownFromStore(pending)
        }
    }

    fun releaseSendMessageCountdownStore() {
        val storeId = storeThreadId()
        if (storeId <= 0L) return
        SendMessageCountdownStore.removeFinishListener(storeId)
    }

    fun completeCountdownFromStore(pending: PendingSendCountdown) {
        completeCountdownAndSend(pending)
    }

    /** Stops the visible timer when the activity pauses without cancelling the persisted countdown. */
    fun pauseCountdownUi(countdownThreadId: Long = 0L) {
        val storeId = resolveCountdownThreadId(countdownThreadId)
        if (!SendMessageCountdownStore.isActive(storeId)) {
            return
        }
        countdownView?.let { view ->
            try {
                view.stop()
            } catch (_: Exception) {
            }
            view.setOnClickListener(null)
            view.beGone()
        }
    }

    fun restorePendingCountdownIfAny(countdownThreadId: Long = 0L): Boolean {
        val storeId = resolveCountdownThreadId(countdownThreadId)
        if (storeId <= 0L) return false
        val pending = SendMessageCountdownStore.get(storeId) ?: return false
        val remainingSeconds = SendMessageCountdownStore.getRemainingSeconds(pending)
        if (remainingSeconds <= 0) {
            completeCountdownAndSend(pending)
            return true
        }
        restoreComposeFromPending(pending)
        pauseCountdownUi(storeId)
        showSendMessageCountdown(
            subscriptionId = pending.subscriptionId,
            totalDelaySeconds = pending.totalDelaySeconds,
            elapsedSeconds = SendMessageCountdownStore.getElapsedSeconds(pending),
            persistToStore = false,
        )
        SendMessageCountdownStore.refreshScheduledFinish(storeId)
        return true
    }

    fun startSendMessageCountdown(subscriptionId: Int) {
        if (isCountdownActive) return
        if (activity.isAirplaneModeOn()) {
            activity.toast(R.string.cannot_send_in_airplane_mode)
            checkSendMessageAvailability()
            return
        }

        val delaySeconds = activity.config.messageSendDelay
        if (delaySeconds <= 0) {
            sendMessage(subscriptionId)
            if (activity.config.soundOnOutGoingMessages) {
                val audioManager = activity.getSystemService(AudioManager::class.java)
                audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_SPACEBAR)
            }
            return
        }

        showSendMessageCountdown(
            subscriptionId = subscriptionId,
            totalDelaySeconds = delaySeconds,
            elapsedSeconds = 0,
            persistToStore = true,
        )
    }

    private fun showSendMessageCountdown(
        subscriptionId: Int,
        totalDelaySeconds: Int,
        elapsedSeconds: Int,
        persistToStore: Boolean,
    ) {
        val storeId = storeThreadId()
        if (persistToStore && storeId > 0L) {
            bindSendMessageCountdownStore(storeId)
            SendMessageCountdownStore.start(buildPendingSendEntry(subscriptionId, totalDelaySeconds))
        }

        isCountdownActive = true
        countdownCompleting = false
        binding.apply {
            threadSendMessage.beGone()
            threadAvailableMessageCount.beGone()
        }

        val countdown = ensureCountdownView()
        try {
            countdown.stop()
        } catch (_: Exception) {
        }
        countdown.beVisible()
        countdown.setOnClickListener {
            if (isCountdownActive) {
                cancelSendMessageCountdown(showCancelledToast = true)
            }
        }

        try {
            countdown.create(elapsedSeconds, totalDelaySeconds, CircularCountdown.TYPE_SECOND)
                .listener(object : CircularListener {
                    override fun onTick(progress: Int) {}
                    override fun onFinish(newCycle: Boolean, cycleCount: Int) {
                        if (countdownCompleting) {
                            hideCountdown()
                            return
                        }
                        // Persisted countdowns are owned by [SendMessageCountdownStore]. The view can
                        // auto-repeat when a cycle ends; always stop the visual timer here.
                        try {
                            countdown.stop()
                        } catch (_: Exception) {
                        }
                        val activeStoreId = storeThreadId()
                        if (activeStoreId > 0L) {
                            val pending = SendMessageCountdownStore.get(activeStoreId)
                            if (pending != null) {
                                completeCountdownAndSend(pending)
                            } else {
                                isCountdownActive = false
                                hideCountdown()
                            }
                            return
                        }
                        finishCountdownAndSend(subscriptionId)
                    }
                })
                .start()
        } catch (e: Exception) {
            val storeId = storeThreadId()
            if (storeId > 0L) {
                SendMessageCountdownStore.cancel(storeId, notifyListener = false)
            }
            finishCountdownAndSend(subscriptionId)
        }
    }

    private fun cancelSendMessageCountdown(showCancelledToast: Boolean) {
        val storeId = storeThreadId()
        if (storeId > 0L) {
            SendMessageCountdownStore.cancel(storeId, notifyListener = false)
        }
        isCountdownActive = false
        hideCountdown()
        if (showCancelledToast) {
            activity.toast(R.string.sending_cancelled)
        }
    }

    private fun completeCountdownAndSend(pending: PendingSendCountdown) {
        if (countdownCompleting) return
        if (activity.isAirplaneModeOn()) {
            cancelSendMessageCountdown(showCancelledToast = false)
            activity.toast(R.string.cannot_send_in_airplane_mode)
            checkSendMessageAvailability()
            return
        }
        countdownCompleting = true
        val storeId = pending.threadId.takeIf { it > 0L } ?: storeThreadId()
        if (storeId > 0L) {
            SendMessageCountdownStore.cancel(storeId, notifyListener = false)
        }
        isCountdownActive = false
        hideCountdown()
        onSendMessage(
            pending.messageText,
            pending.subscriptionId,
            parsePendingAttachments(pending),
        )
        PendingSendCountdownFinisher.clearComposeDraftAfterSend(activity.applicationContext, pending)
    }

    private fun finishCountdownAndSend(subscriptionId: Int) {
        if (countdownCompleting) return
        isCountdownActive = false
        hideCountdown()
        sendMessage(subscriptionId)
        if (activity.config.soundOnOutGoingMessages) {
            val audioManager = activity.getSystemService(AudioManager::class.java)
            audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_SPACEBAR)
        }
    }

    private fun buildPendingSendEntry(
        subscriptionId: Int,
        totalDelaySeconds: Int,
    ): PendingSendCountdown {
        val attachmentsJson = getAttachmentSelections().takeIf { it.isNotEmpty() }?.let { selections ->
            Gson().toJson(
                selections.map {
                    DraftStoredAttachment(
                        uriString = it.uri.toString(),
                        mimetype = it.mimetype,
                        filename = it.filename,
                        isPending = it.isPending,
                    )
                },
            )
        }
        return PendingSendCountdown(
            threadId = storeThreadId(),
            messageText = binding.threadTypeMessage.getCh350EncodedText(),
            subscriptionId = subscriptionId,
            attachmentsJson = attachmentsJson,
            startedAtMs = System.currentTimeMillis(),
            totalDelaySeconds = totalDelaySeconds,
            recipientNumbersJson = countdownRecipientNumbers?.invoke()
                ?.takeIf { it.isNotEmpty() }
                ?.let { Gson().toJson(it) },
            resumeInNewConversation = resumeCountdownInNewConversation,
        )
    }

    private fun restoreComposeFromPending(pending: PendingSendCountdown) {
        if (binding.threadTypeMessage.getCh350EncodedText() != pending.messageText) {
            binding.threadTypeMessage.setCh350ComposeText(pending.messageText)
        }
        replaceAttachmentsFromPending(pending)
    }

    private fun parsePendingAttachments(pending: PendingSendCountdown): List<Attachment> {
        val json = pending.attachmentsJson ?: return emptyList()
        return try {
            val type = object : TypeToken<List<DraftStoredAttachment>>() {}.type
            val list: List<DraftStoredAttachment> = Gson().fromJson(json, type) ?: emptyList()
            list.mapNotNull { entry ->
                if (entry.uriString.isBlank()) return@mapNotNull null
                val uri = entry.uriString.toUri()
                val mimeType = entry.mimetype.ifBlank {
                    activity.contentResolver.getType(uri).orEmpty()
                }.ifBlank { "application/octet-stream" }
                val ext = mimeType.substringAfter("/").substringBefore(";").trim().ifBlank { "dat" }
                Attachment(
                    id = null,
                    messageId = -1L,
                    uriString = uri.toString(),
                    mimetype = mimeType,
                    width = 0,
                    height = 0,
                    filename = entry.filename
                        .ifBlank { activity.getFilenameFromUri(uri) }
                        .ifBlank { "attachment_${System.currentTimeMillis()}.$ext" },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun replaceAttachmentsFromPending(pending: PendingSendCountdown) {
        val json = pending.attachmentsJson
        if (!json.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<DraftStoredAttachment>>() {}.type
                val list: List<DraftStoredAttachment> = Gson().fromJson(json, type) ?: emptyList()
                replaceAttachmentsFromDraft(list)
            } catch (_: Exception) {
                replaceAttachmentsFromDraft(emptyList())
            }
        }
    }

    private fun ensureCountdownView(): CircularCountdown {
        countdownView?.let { return it }

        val parent = binding.threadSendMessage.parent as ViewGroup
        val sendButtonIndex = parent.indexOfChild(binding.threadSendMessage)
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.layout_thread_send_countdown, parent, false) as CircularCountdown
        parent.addView(view, sendButtonIndex + 1)
        countdownView = view
        return view
    }

    private fun hideCountdown() {
        countdownView?.let { view ->
            try {
                view.stop()
            } catch (_: Exception) {
            }
            view.setOnClickListener(null)
            view.beGone()
        }
        binding.threadSendMessage.beVisible()
    }
    // added by sun
    // when use multi sim, show dialog sim select
    // ----->
    @SuppressLint("MissingPermission")
    fun resolveSubscriptionForSend(
        anchorView: View,
        anchorPlacement: SelectSimDialogAnchorPlacement = SelectSimDialogAnchorPlacement.TOP_RIGHT_OF_ANCHOR,
        onSubId: (Int) -> Unit
    ) {
        resolveSubscriptionThen(anchorView, anchorPlacement, onSubId)
    }
    private fun findSimDialogBlurTarget(): BlurTarget? {
        val target = activity.findViewById<BlurTarget>(R.id.mainBlurTarget)
        return target?.takeIf { it.isShown }
    }
    @SuppressLint("MissingPermission")
    private fun resolveSubscriptionThen(
        anchorView: View = binding.threadSendMessageActionWrapper,
        anchorPlacement: SelectSimDialogAnchorPlacement = SelectSimDialogAnchorPlacement.TOP_RIGHT_OF_ANCHOR,
        onSubId: (Int) -> Unit
    ) {
        val subs = activity.subscriptionManagerCompat().activeSubscriptionInfoList
        val defaultSmsSubscriptionId = SmsManager.getDefaultSmsSubscriptionId()
        if (!subs.isNullOrEmpty() && subs.size > 1 && defaultSmsSubscriptionId < 0) {       // when muti sim and always check sms in system setting
            SelectSIMDialog(
                activity = activity as SimpleActivity,
                blurTarget = findSimDialogBlurTarget(),
                anchorView = anchorView,
                anchorPlacement = anchorPlacement
            ) { simCard, _ ->
                onSubId(simCard.subscriptionId)
            }

        }
        else {
            val subId = when {
                subs?.size == 1 -> subs[0].subscriptionId
                subs?.size == 2 -> defaultSmsSubscriptionId
                else ->getSubscriptionId()
            }
            val resolvedSubId = SendSubscriptionHelper.firstResolvedOrTestFallback(
                subId,
                getSubscriptionId(),
                defaultSmsSubscriptionId,
            )
            if (resolvedSubId != null) {
                onSubId(resolvedSubId)
            }
        }
    }
    private fun pickSimAndSendOrSendDirect() {
        resolveSubscriptionThen { sendMessage(it) }
    }
    // <-----------

    @SuppressLint("SuspiciousIndentation")
    fun sendMessage(subscriptionId: Int) {
        if (activity.isAirplaneModeOn()) {
            activity.toast(R.string.cannot_send_in_airplane_mode)
            checkSendMessageAvailability()
            return
        }
        mergeAllSlidesIntoModel()
        val text = binding.threadTypeMessage.getCh350EncodedText()
        val attachments = buildMessageAttachments()
        onSendMessage(text, subscriptionId, attachments)
    }

    fun getMessageText(): String = binding.threadTypeMessage.getCh350EncodedText()

    fun setMessageText(text: String) {
        binding.threadTypeMessage.setCh350ComposeText(text)
    }

    fun insertText(text: String) {
        val editText = binding.threadTypeMessage
        val start = editText.selectionStart
        val end = editText.selectionEnd
        val currentText = editText.text.toString()
        val newText = currentText.substring(0, start) + text + currentText.substring(end)
        editText.setText(newText)
        editText.setSelection(start + text.length)
    }

    fun clearMessage() {
        binding.threadTypeMessage.setText("")
        clearAttachments()
        if (isCountdownActive) {
            cancelSendMessageCountdown(showCancelledToast = false)
        }
    }

    fun clearAttachments() {
        clearSlideshowState()
        getAttachmentsAdapter()?.clear()
        checkSendMessageAvailability()
    }

    private fun clearSlideshowState() {
        mmsSlideshow = null
        ComposeSlideshowBridge.clear()
    }

    fun setAttachmentIntentLauncher(launcher: AttachmentIntentLauncher?) {
        attachmentIntentLauncher = launcher
    }

 /**
     * Handles [PICK_CONTACT_INTENT] from [SELECT_CONTACTS_ACTION].
     * That picker returns raw contact ids in extras ([SELECT_CONTACTS_RESULT_ADDED_IDS] /
     * [SELECT_CONTACTS_RESULT_ALL_IDS]), not a [Uri] in [Intent.getData].
     */
    fun handlePickContactAttachmentResult(callerTag: String, resultCode: Int, resultData: Intent?) {
        logSelectContactsResult(callerTag, resultCode, resultData)
        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) return

        val dataUri = resultData.data
        if (dataUri != null) {
            Log.d("$LOG_TAG:$callerTag", "Using resultData.data uri=$dataUri")
            addContactAttachment(dataUri)
            return
        }

        val rawContactIds = getSelectedRawContactIds(resultData)
        if (rawContactIds.isEmpty()) {
            Log.w(
                "$LOG_TAG:$callerTag",
                "No contact result: data Uri is null and no " +
                    "$SELECT_CONTACTS_RESULT_ADDED_IDS / $SELECT_CONTACTS_RESULT_ALL_IDS extras",
            )
            activity.toast(com.goodwy.commons.R.string.unknown_error_occurred)
            return
        }

        Log.d("$LOG_TAG:$callerTag", "Using raw contact id extras ids=$rawContactIds")
        addContactAttachmentByRawContactId(rawContactIds.first())
    }
    fun setPendingReplaceAttachmentId(attachmentId: String) {
        pendingReplaceAttachmentId = attachmentId
    }

    fun clearPendingReplaceAttachmentId() {
        pendingReplaceAttachmentId = null
    }

    fun addAttachment(uri: Uri) {
        val mimeType = activity.getMimeTypeFromUri(uri)
        if (mimeType.isBlank()) {
            activity.toast(com.goodwy.commons.R.string.unknown_error_occurred)
            return
        }
        finishAttachmentFromPicker(uri, mimeType, activity.getFilenameFromUri(uri))
    }

    fun addContactAttachment(contactUri: Uri) {
        resolveContactForAttachment(contactUri) { contact ->
            insertContactAsText(contact)
        }
    }

    fun addContactAttachmentByRawContactId(rawContactId: Int) {
        ensureBackgroundThread {
            val contact = ContactsHelper(activity).getContactWithId(rawContactId)
            activity.runOnUiThread { insertContactAsText(contact) }
        }
    }

    private fun resolveContactForAttachment(
        contactUri: Uri,
        onResolved: (com.goodwy.commons.models.contacts.Contact?) -> Unit,
    ) {
        val privateCursor = activity.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ContactsHelper(activity).getContacts(showOnlyContactsWithNumbers = false) { contacts ->
            val contact = if (contactUri.pathSegments.last().startsWith("local_")) {
                val contactId = contactUri.path!!.substringAfter("local_").toInt()
                try {
                    val privateContacts = MyContactsContentProvider.getContacts(activity, privateCursor)
                    privateContacts.firstOrNull { it.id == contactId }
                } catch (_: Exception) {
                    null
                }
            } else {
                val contactId = activity.getContactUriRawId(contactUri)
                contacts.firstOrNull { it.id == contactId }
                    ?: ContactsHelper(activity).getContactWithId(contactId)
            }
            activity.runOnUiThread { onResolved(contact) }
        }
    }

    private fun insertContactAsText(contact: com.goodwy.commons.models.contacts.Contact?) {
        if (contact == null) {
            activity.toast(com.goodwy.commons.R.string.unknown_error_occurred)
            return
        }
        val current = binding.threadTypeMessage.value
        binding.threadTypeMessage.setText(current + contact.getContactToText(activity))
    }

    /**
     * Restores compose attachments from draft in one adapter update so list diffing cannot apply an
     * intermediate empty list after a clear (see [AttachmentsAdapter.submitAttachments]).
     */
    fun replaceAttachmentsFromDraft(stored: List<DraftStoredAttachment>) {
        val slideshowEntry = stored.firstOrNull { !it.slideshowJson.isNullOrBlank() }
        val attachmentOnlyStored = stored.filter { entry ->
            entry.slideshowJson.isNullOrBlank() && entry.uriString != SLIDESHOW_DRAFT_MARKER_URI
        }
        if (slideshowEntry != null) {
            val restored = SlideshowHelper.fromJson(slideshowEntry.slideshowJson)
            if (restored != null && restored.slides.any { it.uriString.isNotEmpty() || it.text.isNotEmpty() }) {
                val slideshowUris = restored.slides.map { it.uriString }.filter { it.isNotEmpty() }.toSet()
                restoreSlideshowDraft(
                    slideshow = restored,
                    nonSlideshowStored = attachmentOnlyStored.filter { it.uriString !in slideshowUris },
                )
                return
            }
        }
        val selections = buildDraftAttachmentSelections(attachmentOnlyStored)
        if (selections.isEmpty()) {
            clearSlideshowState()
            getAttachmentsAdapter()?.submitAttachments(emptyList())
            binding.threadAttachmentsRecyclerview.beGone()
            checkSendMessageAvailability()
            return
        }
        var adapter = getAttachmentsAdapter()
        if (adapter == null) {
            adapter = createAttachmentsAdapter()
            binding.threadAttachmentsRecyclerview.adapter = adapter
        }
        val media = SlideshowHelper.mediaSelections(selections)
        if (media.isNotEmpty()) {
            mmsSlideshow = MmsSlideshow.fromMediaSelections(media)
            refreshSlideshowComposeUi(adapter)
        } else {
            mmsSlideshow = null
            binding.threadAttachmentsRecyclerview.beVisible()
            adapter.submitAttachments(selections)
            binding.threadAttachmentsRecyclerview.post {
                binding.threadAttachmentsRecyclerview.requestLayout()
            }
            checkSendMessageAvailability()
        }
    }

    /**
     * Serializes compose attachments for [Draft.attachmentsJson], including full slideshow slide text
     * and order when the user edited slides in [ManageSlideshowActivity] / [EditSlideActivity].
     */
    fun getDraftStoredAttachments(): List<DraftStoredAttachment> {
        mergeAllSlidesIntoModel()
        val slideshow = mmsSlideshow?.takeIf { ss ->
            ss.slides.any { it.uriString.isNotEmpty() || it.text.isNotEmpty() }
        }
        val selections = getAttachmentSelections()
        val slideshowUris = slideshow?.slides?.map { it.uriString }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        val nonSlideshowSelections = if (slideshowUris.isEmpty()) {
            selections
        } else {
            selections.filter { it.uri.toString() !in slideshowUris }
        }
        val stored = nonSlideshowSelections.map { selection ->
            DraftStoredAttachment(
                uriString = selection.uri.toString(),
                mimetype = selection.mimetype,
                filename = selection.filename,
                isPending = selection.isPending,
            )
        }.toMutableList()
        if (slideshow != null) {
            stored.add(
                DraftStoredAttachment(
                    uriString = SLIDESHOW_DRAFT_MARKER_URI,
                    mimetype = "application/vnd.wap.mms-slideshow",
                    filename = "",
                    isPending = false,
                    slideshowJson = SlideshowHelper.toJson(slideshow),
                ),
            )
        }
        return stored
    }

    private fun restoreSlideshowDraft(
        slideshow: MmsSlideshow,
        nonSlideshowStored: List<DraftStoredAttachment>,
    ) {
        mmsSlideshow = slideshow
        ComposeSlideshowBridge.slideshow = slideshow
        var adapter = getAttachmentsAdapter()
        if (adapter == null) {
            adapter = createAttachmentsAdapter()
            binding.threadAttachmentsRecyclerview.adapter = adapter
        }
        val nonMedia = buildDraftAttachmentSelections(nonSlideshowStored).filter {
            !SlideshowHelper.isMediaSelection(it) && it.viewType != ATTACHMENT_AUDIO
        }
        // Image-only drafts have no non-media rows. submitAttachments(empty) fires onAttachmentsRemoved,
        // which clears mmsSlideshow before refreshSlideshowComposeUi can bind the preview.
        if (nonMedia.isNotEmpty()) {
            adapter.submitAttachments(nonMedia)
        }
        refreshSlideshowComposeUi(adapter)
    }

    private fun buildDraftAttachmentSelections(stored: List<DraftStoredAttachment>): List<AttachmentSelection> {
        val seenIds = HashSet<String>()
        val selections = ArrayList<AttachmentSelection>(stored.size)
        for (s in stored) {
            try {
                val uri = s.uriString.toUri()
                if (!seenIds.add(uri.toString())) continue
                if (!passesNonImageAttachmentSizeCap(uri, s.mimetype)) continue
                val pending = resolveDraftAttachmentPending(uri, s.mimetype)
                selections.add(buildAttachmentSelection(uri, s.mimetype, s.filename, pending))
            } catch (_: Exception) {
            }
        }
        return selections
    }

    /**
     * Draft JSON stores [AttachmentSelection.isPending]. After leaving the screen, restored rows should
     * match [AttachmentsAdapter]/[ImageCompressor]: only stay "pending" when the file still exceeds the
     * MMS limit. Otherwise load the thumbnail immediately (same as a fresh gallery pick after inline check).
     */
    private fun resolveDraftAttachmentPending(uri: Uri, mimetype: String): Boolean {
        if (!mimetype.isImageMimeType() || mimetype.isGifMimeType()) return false
        val limit = activity.config.mmsFileSizeLimit
        if (limit == FILE_SIZE_NONE) return false
        val fileSize = activity.getFileSizeFromUri(uri)
        if (fileSize < 0L || fileSize == FILE_SIZE_NONE) return false
        return fileSize > limit
    }

    private fun passesNonImageAttachmentSizeCap(uri: Uri, mimeType: String): Boolean {
        val isImage = mimeType.isImageMimeType()
        val isGif = mimeType.isGifMimeType()
        if (isGif || !isImage) {
            val fileSize = activity.getFileSizeFromUri(uri)
            val limit = activity.config.mmsFileSizeLimit
            if (limit != FILE_SIZE_NONE && fileSize > limit) {
                return false
            }
        }
        return true
    }

    private fun buildAttachmentSelection(
        uri: Uri,
        mimeType: String,
        filename: String,
        isPendingOverride: Boolean?,
    ): AttachmentSelection {
        val id = uri.toString()
        val isImage = mimeType.isImageMimeType()
        val isGif = mimeType.isGifMimeType()
        val pending = isPendingOverride ?: (isImage && !isGif)
        return AttachmentSelection(id, uri, mimeType, filename, pending)
    }

    private fun createAttachmentsAdapter(): AttachmentsAdapter {
        return AttachmentsAdapter(
            activity = activity,
            recyclerView = binding.threadAttachmentsRecyclerview,
            onAttachmentsRemoved = {
                clearSlideshowState()
                binding.threadAttachmentsRecyclerview.beGone()
                setSlideshowComposeModeActive(false)
                checkSendMessageAvailability()
            },
            onReady = { checkSendMessageAvailability() },
            onReplaceAttachment = { attachment ->
                when {
                    attachment.mimetype.isVCardMimeType() ->
                        attachmentIntentLauncher?.launchPickContactForReplace(attachment.id)
                    attachment.mimetype.isVideoMimeType() ->
                        attachmentIntentLauncher?.showReplaceVideoDialog(attachment.id)
                    attachment.mimetype.isImageMimeType() || attachment.mimetype.isGifMimeType() ->
                        attachmentIntentLauncher?.showReplaceImageDialog(attachment.id)
                }
            },
            getSlideshow = { mmsSlideshow },
            onEditSlideshow = { launchSlideshowEditor() },
            onPlaySlideshow = { playSlideshowPreview() },
            onRemoveSlideshow = { removeSlideshow() },
            onSendSlideshow = { pickSimAndSendOrSendDirect() },
            onCompressedMediaOrphaned = { oldUri, newUri -> onCompressedMediaOrphaned(oldUri, newUri) },
            onMediaAttachmentRemoved = { attachment -> onMediaAttachmentRemoved(attachment) },
        )
    }

    private fun onMediaAttachmentRemoved(attachment: AttachmentSelection) {
        val slideshow = mmsSlideshow ?: return
        val uriString = attachment.uri.toString()
        if (!slideshow.slides.any { it.uriString == uriString }) {
            return
        }
        val updatedSlides = slideshow.slides.filter { it.uriString != uriString }
        if (updatedSlides.isEmpty()) {
            clearSlideshowState()
        } else {
            mmsSlideshow = MmsSlideshow(updatedSlides)
            ComposeSlideshowBridge.slideshow = mmsSlideshow
        }
    }

    private fun finishAttachmentFromPicker(
        uri: Uri,
        mimeType: String,
        filename: String,
        isPendingOverride: Boolean? = null,
    ) {
        val replaceId = pendingReplaceAttachmentId
        pendingReplaceAttachmentId = null
        if (replaceId != null) {
            replaceAttachmentWithKnownMime(replaceId, uri, mimeType, filename, isPendingOverride)
        } else {
            addAttachmentWithKnownMime(uri, mimeType, filename, isPendingOverride)
        }
    }

    private fun addAttachmentWithKnownMime(
        uri: Uri,
        mimeType: String,
        filename: String,
        isPendingOverride: Boolean? = null,
    ) {
        if (!passesNonImageAttachmentSizeCap(uri, mimeType)) {
            activity.toast(R.string.attachment_sized_exceeds_max_limit, length = Toast.LENGTH_LONG)
            return
        }

        // Alps WorkingMessage.setAttachment: image, video, AND audio all go into the SlideshowModel
        // as slides.  Route all three through addMediaAttachmentToCompose so the compose UI always
        // shows a single unified slideshow row (image or slideshow) with at most one EDIT button.
        if (mimeType.isImageMimeType() || mimeType.isVideoMimeType() || mimeType.isAudioMimeType()) {
            val stableUri = SlideshowHelper.stabilizeAttachmentUri(activity, uri, mimeType)
            if (isDuplicateMediaUri(stableUri)) {
                activity.toast(R.string.duplicate_item_warning)
                return
            }
            addMediaAttachmentToCompose(stableUri, mimeType, filename, isPendingOverride)
            return
        }

        val id = uri.toString()
        if (getAttachmentSelections().any { it.id == id }) {
            activity.toast(R.string.duplicate_item_warning)
            return
        }

        var adapter = getAttachmentsAdapter()
        if (adapter == null) {
            adapter = createAttachmentsAdapter()
            binding.threadAttachmentsRecyclerview.adapter = adapter
        }

        binding.threadAttachmentsRecyclerview.beVisible()
        val attachment = buildAttachmentSelection(uri, mimeType, filename, isPendingOverride)
        adapter.addAttachment(attachment)
        binding.threadAttachmentsRecyclerview.post {
            binding.threadAttachmentsRecyclerview.requestLayout()
        }
        checkSendMessageAvailability()
    }

    private fun isDuplicateMediaUri(uri: Uri): Boolean {
        val uriString = uri.toString()
        return mmsSlideshow?.slides?.any { it.uriString == uriString } == true
    }

    /**
     * Alps [WorkingMessage.setAttachment] + [appendMedia]: all media lives in [mmsSlideshow] from the
     * first image/video. One slide shows the single-media preview; two+ slides show slideshow preview.
     */
    private fun addMediaAttachmentToCompose(
        uri: Uri,
        mimeType: String,
        filename: String,
        isPendingOverride: Boolean?,
    ) {
        if (!hasReadableAttachmentContent(uri)) {
            activity.toast(com.goodwy.commons.R.string.unknown_error_occurred)
            return
        }

        var adapter = getAttachmentsAdapter()
        if (adapter == null) {
            adapter = createAttachmentsAdapter()
            binding.threadAttachmentsRecyclerview.adapter = adapter
        }

        mergeAllSlidesIntoModel(adapter)

        val ext = mimeType.substringAfter("/").substringBefore(";").trim()
        val stableFilename = filename
            .ifBlank { activity.getFilenameFromUri(uri) }
            .ifBlank { "attachment_${System.currentTimeMillis()}.$ext" }
        val durationMs = if (mimeType.isVideoMimeType() || mimeType.isAudioMimeType()) {
            readMediaDurationMs(uri) ?: MmsSlide.DEFAULT_DURATION_MS
        } else {
            MmsSlide.DEFAULT_DURATION_MS
        }
        val newSlide = MmsSlide(
            uriString = uri.toString(),
            mimetype = mimeType,
            filename = stableFilename,
            durationMs = durationMs,
        )

        var slideshow = mmsSlideshow
        if (slideshow == null) {
            val existingMedia = SlideshowHelper.mediaSelections(adapter.attachments)
            if (existingMedia.isNotEmpty()) {
                slideshow = MmsSlideshow.fromMediaSelections(existingMedia)
            }
        }
        mmsSlideshow = when {
            slideshow == null -> MmsSlideshow(listOf(newSlide))
            slideshow.slides.any { it.uriString == newSlide.uriString } -> {
                activity.toast(R.string.duplicate_item_warning)
                return
            }
            else -> slideshow.addSlide(newSlide) ?: run {
                activity.toast(R.string.cannot_add_slide_anymore)
                return
            }
        }
        ComposeSlideshowBridge.slideshow = mmsSlideshow
        refreshSlideshowComposeUi(adapter, isPendingOverride)
    }

    private fun composeAdapterHasMediaAttachments(adapter: AttachmentsAdapter?): Boolean {
        val rows = adapter?.attachments.orEmpty()
        if (rows.isEmpty()) {
            return false
        }
        return rows.any { SlideshowHelper.isMediaSelection(it) } ||
            rows.any { it.id == SLIDESHOW_ATTACHMENT_ID || it.viewType == ATTACHMENT_SLIDESHOW } ||
            rows.any { it.viewType == ATTACHMENT_AUDIO }
    }

    /** Merge every known slide source so appendMedia never drops a prior image (Alps [WorkingMessage]). */
    private fun mergeAllSlidesIntoModel(adapter: AttachmentsAdapter? = getAttachmentsAdapter()) {
        val adapterHasMedia = composeAdapterHasMediaAttachments(adapter)
        val authoritative = ComposeSlideshowBridge.slideshow ?: mmsSlideshow
        if (authoritative != null && authoritative.slides.any { it.uriString.isNotEmpty() || it.text.isNotEmpty() }) {
            if (!adapterHasMedia) {
                clearSlideshowState()
                return
            }
            val slides = authoritative.slides.toMutableList()
            val knownUris = slides.map { it.uriString }.filter { it.isNotEmpty() }.toMutableSet()
            adapter?.attachments?.let { rows ->
                SlideshowHelper.mediaSelections(rows).forEach { selection ->
                    val uriString = selection.uri.toString()
                    if (uriString !in knownUris) {
                        slides.add(MmsSlide.fromSelection(selection))
                        knownUris.add(uriString)
                    }
                }
                rows.filter { it.viewType == ATTACHMENT_AUDIO }.forEach { selection ->
                    val uriString = selection.uri.toString()
                    if (uriString !in knownUris) {
                        slides.add(MmsSlide.fromSelection(selection))
                        knownUris.add(uriString)
                    }
                }
            }
            mmsSlideshow = MmsSlideshow(slides)
            ComposeSlideshowBridge.slideshow = mmsSlideshow
            return
        }

        val merged = LinkedHashMap<String, MmsSlide>()
        if (!adapterHasMedia) {
            clearSlideshowState()
            return
        }
        listOf(mmsSlideshow, ComposeSlideshowBridge.slideshow).forEach { source ->
            source?.slides?.forEach { slide ->
                if (slide.uriString.isNotEmpty()) {
                    merged[slide.uriString] = slide
                }
            }
        }
        adapter?.attachments?.let { rows ->
            // image/video selections
            SlideshowHelper.mediaSelections(rows).forEach { selection ->
                val uriString = selection.uri.toString()
                merged.putIfAbsent(uriString, MmsSlide.fromSelection(selection))
            }
            // audio selections (draft-restored standalone audio rows, if any)
            rows.filter { it.viewType == ATTACHMENT_AUDIO }.forEach { selection ->
                val uriString = selection.uri.toString()
                merged.putIfAbsent(uriString, MmsSlide.fromSelection(selection))
            }
        }
        if (merged.isNotEmpty()) {
            mmsSlideshow = MmsSlideshow(merged.values.toList())
            ComposeSlideshowBridge.slideshow = mmsSlideshow
        } else {
            clearSlideshowState()
        }
    }

    private fun onCompressedMediaOrphaned(oldUri: Uri, newUri: Uri?) {
        val slideshow = mmsSlideshow ?: return
        val index = slideshow.slides.indexOfFirst { it.uriString == oldUri.toString() }
        if (index < 0) {
            return
        }
        if (newUri == null) {
            activity.toast(R.string.compress_error)
            // Alps keeps all slides; do not drop a slide from a multi-slide slideshow on compress failure.
            if (slideshow.slides.size <= 1) {
                mmsSlideshow = slideshow.removeSlideAt(index)
            }
        } else {
            val slide = slideshow.slides[index]
            mmsSlideshow = slideshow.replaceSlideAt(
                index,
                slide.copy(uriString = newUri.toString()),
            )
        }
        ComposeSlideshowBridge.slideshow = mmsSlideshow
        syncSlideshowAfterMutation()
    }

    private fun syncSlideshowAfterMutation() {
        val adapter = getAttachmentsAdapter() ?: return
        val slideshow = mmsSlideshow
        if (slideshow == null || slideshow.slides.isEmpty()) {
            removeSlideshow()
            return
        }
        refreshSlideshowComposeUi(adapter)
    }

    private fun refreshSlideshowComposeUi(
        adapter: AttachmentsAdapter,
        isPendingOverride: Boolean? = null,
    ) {
        val slideshow = mmsSlideshow ?: return
        // Exclude standalone audio rows whose URI is already a slide in the slideshow: audio is now
        // part of the slideshow model (Alps WorkingMessage) and must not appear as a second row.
        val slideshowUriSet = slideshow.slides.mapTo(HashSet()) { it.uriString }
        val nonMedia = adapter.attachments.filter {
            !SlideshowHelper.isMediaSelection(it) && it.id != SLIDESHOW_ATTACHMENT_ID
                && it.id !in slideshowUriSet
        }

        val isRealSlideshow = slideshow.isRealSlideshow()
        if (!isRealSlideshow) {
            val slide = slideshow.slides.firstOrNull { it.uriString.isNotEmpty() } ?: run {
                adapter.submitAttachments(nonMedia)
                binding.threadAttachmentsRecyclerview.beVisibleIf(nonMedia.isNotEmpty())
                setSlideshowComposeModeActive(false)
                checkSendMessageAvailability()
                return
            }
            val pending = isPendingOverride
                ?: resolveDraftAttachmentPending(slide.uri, slide.mimetype)
            val restored = buildAttachmentSelection(
                slide.uri,
                slide.mimetype,
                slide.filename,
                pending,
            )
            adapter.submitAttachments(nonMedia + restored)
        } else {
            adapter.submitAttachments(listOf(buildSlideshowAttachmentSelection(slideshow)) + nonMedia)
        }

        // When 2+ slides: hide message input + normal send button; Send lives in the preview.
        setSlideshowComposeModeActive(isRealSlideshow)

        binding.threadAttachmentsRecyclerview.beVisible()
        binding.threadAttachmentsRecyclerview.post {
            binding.threadAttachmentsRecyclerview.requestLayout()
        }
        checkSendMessageAvailability()
    }

    private fun buildSlideshowAttachmentSelection(slideshow: MmsSlideshow): AttachmentSelection {
        val first = slideshow.slides.firstOrNull { it.uriString.isNotEmpty() } ?: slideshow.slides.first()
        return AttachmentSelection(
            id = SLIDESHOW_ATTACHMENT_ID,
            uri = first.uri,
            mimetype = "application/vnd.wap.mms-slideshow",
            filename = activity.getString(R.string.attachment),
            isPending = false,
            viewType = ATTACHMENT_SLIDESHOW,
        )
    }

    fun launchSlideshowEditor() {
        mergeAllSlidesIntoModel()
        val slideshow = mmsSlideshow ?: return
        ComposeSlideshowBridge.slideshow = slideshow
        ComposeSlideshowBridge.editingSlideIndex = 0
        val intent = Intent(activity, ManageSlideshowActivity::class.java)
        activity.startActivityForResult(intent, REQUEST_EDIT_SLIDESHOW)
    }

    fun handleSlideshowEditorResult(resultData: Intent?) {
        val raw = ComposeSlideshowBridge.slideshow
            ?: SlideshowHelper.fromJson(resultData?.getStringExtra(EXTRA_SLIDESHOW_JSON))
        val slides = raw?.slides?.filter { it.uriString.isNotEmpty() || it.text.isNotEmpty() }.orEmpty()
        if (slides.isEmpty()) {
            ComposeSlideshowBridge.clear()
            removeSlideshow()
            return
        }
        mmsSlideshow = MmsSlideshow(slides)
        ComposeSlideshowBridge.slideshow = mmsSlideshow
        val adapter = getAttachmentsAdapter() ?: return
        refreshSlideshowComposeUi(adapter)
    }

    private fun playSlideshowPreview() {
        // ComposeSlideshowBridge is the authoritative in-memory slideshow state; it is set
        // whenever an attachment is added/edited and is never overwritten by draft restores.
        // Fall back to mmsSlideshow for cases where the bridge hasn't been populated yet.
        val slideshow = ComposeSlideshowBridge.slideshow ?: mmsSlideshow ?: return
        val slides = slideshow.slides.filter { it.uriString.isNotEmpty() || it.text.isNotBlank() }
        if (slides.isEmpty()) return
        // Alps MessageUtils.viewMmsMessageAttachment: always open MmsPlayerActivity (list view).
        // ViewMmsActivity is our equivalent; "Play as slideshow" inside it opens PlaySlideshowActivity.
        val intent = Intent(activity, ViewMmsActivity::class.java).apply {
            putExtra(EXTRA_SLIDESHOW_JSON, SlideshowHelper.toJson(MmsSlideshow(slides)))
        }
        activity.startActivity(intent)
    }

    private fun removeSlideshow() {
        clearSlideshowState()
        setSlideshowComposeModeActive(false)
        val adapter = getAttachmentsAdapter() ?: return
        val remaining = adapter.attachments.filter {
            it.id != SLIDESHOW_ATTACHMENT_ID && !SlideshowHelper.isMediaSelection(it)
        }
        if (remaining.isEmpty()) {
            adapter.clear()
        } else {
            adapter.submitAttachments(remaining)
        }
        checkSendMessageAvailability()
    }

    /**
     * When [active] is true (2+ slide MMS): hide the text input and the normal send button so the
     * user sends via the "Send" button that appears in the slideshow attachment preview row.
     * When [active] is false: restore both views to their normal visible state.
     */
    private fun setSlideshowComposeModeActive(active: Boolean) {
        binding.threadTypeMessageWrapper.beVisibleIf(!active)
        binding.threadSendMessageActionWrapper.beVisibleIf(!active)
    }

    private fun replaceAttachmentWithKnownMime(
        oldId: String,
        uri: Uri,
        mimeType: String,
        filename: String,
        isPendingOverride: Boolean? = null,
    ) {
        if (!passesNonImageAttachmentSizeCap(uri, mimeType)) {
            activity.toast(R.string.attachment_sized_exceeds_max_limit, length = Toast.LENGTH_LONG)
            return
        }

        val adapter = getAttachmentsAdapter() ?: return
        val attachment = buildAttachmentSelection(uri, mimeType, filename, isPendingOverride)
        if (!adapter.replaceAttachment(oldId, attachment)) {
            return
        }
        val slideshow = mmsSlideshow
        if (slideshow != null && !slideshow.isRealSlideshow() && slideshow.slides.size == 1) {
            val slide = slideshow.slides.first()
            mmsSlideshow = slideshow.replaceSlideAt(
                0,
                slide.copy(
                    uriString = uri.toString(),
                    mimetype = mimeType,
                    filename = filename,
                ),
            )
        }
        checkSendMessageAvailability()
    }

    /**
     * Camera apps may return before the [CAPTURE_PHOTO_INTENT] output file is flushed, or only return a
     * thumbnail [Uri]/[Bitmap] in the result instead of writing [MediaStore.EXTRA_OUTPUT].
     */
    fun handleCapturePhotoResult(resultCode: Int, resultData: Intent?) {
        capturePhotoFinalizeAttempt = 0
        composeUiHandler.removeCallbacks(capturePhotoFinalizeRunnable)
        if (resultCode != android.app.Activity.RESULT_OK) {
            clearPendingReplaceAttachmentId()
            return
        }
        composeUiHandler.post(capturePhotoFinalizeRunnable)
    }

    private val capturePhotoFinalizeRunnable = object : Runnable {
        override fun run() {
            if (activity.isFinishing || activity.isDestroyed) {
                return
            }

            val uri = resolveCapturedImageUri()
            if (uri != null) {
                attachCapturedImage(uri)
                return
            }

            if (capturePhotoFinalizeAttempt < CAPTURE_PHOTO_FINALIZE_MAX_ATTEMPTS) {
                capturePhotoFinalizeAttempt++
                composeUiHandler.postDelayed(this, CAPTURE_PHOTO_FINALIZE_RETRY_MS)
                return
            }

            clearPendingReplaceAttachmentId()
        }
    }

    private fun attachCapturedImage(uri: Uri) {
        hideAttachmentPicker()
        finishAttachmentFromPicker(
            uri,
            "image/jpeg",
            activity.getFilenameFromUri(uri),
        )
    }

    /**
     * Alps MMS always finalizes the scrap `.temp.3gp` file after [CAPTURE_VIDEO_INTENT], even when the
     * camcorder returns before the file is fully flushed. Retries briefly before giving up.
     */
    fun handleCaptureVideoResult(resultCode: Int, resultData: Intent?) {
        captureVideoFinalizeAttempt = 0
        composeUiHandler.removeCallbacks(captureVideoFinalizeRunnable)
        captureVideoFinalizeRunnable.resultCode = resultCode
        captureVideoFinalizeRunnable.resultData = resultData
        composeUiHandler.post(captureVideoFinalizeRunnable)
    }

    private val captureVideoFinalizeRunnable = object : Runnable {
        var resultCode: Int = android.app.Activity.RESULT_CANCELED
        var resultData: Intent? = null

        override fun run() {
            if (activity.isFinishing || activity.isDestroyed) {
                capturedVideoUri = null
                return
            }

            val uri = resolveCapturedVideoUri(resultData)
            if (uri != null) {
                attachCapturedVideo(uri)
                return
            }

            if (captureVideoFinalizeAttempt < CAPTURE_VIDEO_FINALIZE_MAX_ATTEMPTS) {
                captureVideoFinalizeAttempt++
                composeUiHandler.postDelayed(this, CAPTURE_VIDEO_FINALIZE_RETRY_MS)
                return
            }

            capturedVideoUri = null
            clearPendingReplaceAttachmentId()
            if (resultCode == android.app.Activity.RESULT_OK) {
                activity.toast(com.goodwy.commons.R.string.unknown_error_occurred)
            }
        }
    }

    private fun attachCapturedVideo(uri: Uri) {
        hideAttachmentPicker()
        val mimeType = activity.contentResolver.getType(uri) ?: inferVideoMimeType(uri)
        finishAttachmentFromPicker(
            uri,
            mimeType,
            activity.getFilenameFromUri(uri),
        )
        capturedVideoUri = null
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (requestCode == CAPTURE_VIDEO_INTENT) {
            handleCaptureVideoResult(resultCode, resultData)
            return
        }
        if (requestCode == CAPTURE_PHOTO_INTENT) {
            handleCapturePhotoResult(resultCode, resultData)
            return
        }
        if (resultCode != android.app.Activity.RESULT_OK) {
            clearPendingReplaceAttachmentId()
            return
        }
        val data = resultData?.data

        when (requestCode) {
            PICK_DOCUMENT_INTENT,
            PICK_PHOTO_INTENT,
            PICK_VIDEO_INTENT,
            PICK_SOUND_INTENT -> {
                if (data != null) {
                    val stableUri = stabilizePickerResultUri(resultData, data)
                    val mimeType = resolvePickerMimeType(stableUri, resultData, requestCode)
                    if (mimeType == null) {
                        activity.toast(com.goodwy.commons.R.string.unknown_error_occurred)
                        clearPendingReplaceAttachmentId()
                        return
                    }
                    finishAttachmentFromPicker(
                        stableUri,
                        mimeType,
                        activity.getFilenameFromUri(stableUri),
                    )
                }
            }
            PICK_RINGTONE_INTENT -> {
                @Suppress("DEPRECATION")
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    resultData?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
                } else {
                    resultData?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                }
                if (uri != null && uri != Settings.System.getUriFor(Settings.System.RINGTONE)) {
                    val stableUri = stabilizePickerResultUri(resultData, uri)
                    val mimeType = activity.contentResolver.getType(stableUri) ?: "audio/*"
                    finishAttachmentFromPicker(
                        stableUri,
                        mimeType,
                        activity.getFilenameFromUri(stableUri),
                    )
                } else {
                    clearPendingReplaceAttachmentId()
                }
            }
        }
    }

    /**
     * Alps [ComposeMessageActivity] REQUEST_CODE_TAKE_PICTURE: read scrap `.temp.jpg`, copy to a unique file.
     */
    private fun resolveCapturedImageUri(): Uri? {
        MmsCaptureTempFiles.finalizeScrapPhoto(activity)?.let { return it }
        val path = MmsCaptureTempFiles.getScrapPhotoPath(activity) ?: return null
        val file = File(path)
        if (file.length() > 0L) {
            return activity.getMyFileUri(file)
        }
        return null
    }

    private fun resolveCapturedVideoUri(resultData: Intent?): Uri? {
        MmsCaptureTempFiles.finalizeScrapVideo(activity)?.let { return it }

        if (MmsCaptureTempFiles.scrapVideoLength(activity) > 0L) {
            return null
        }

        capturedVideoUri?.let { uri ->
            if (hasReadableAttachmentContent(uri)) {
                return uri
            }
        }

        val candidates = linkedSetOf<Uri>()
        resultData?.data?.let { candidates.add(it) }
        @Suppress("DEPRECATION")
        val outputUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resultData?.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
        } else {
            resultData?.getParcelableExtra(MediaStore.EXTRA_OUTPUT)
        }
        outputUri?.let { candidates.add(it) }
        @Suppress("DEPRECATION")
        val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resultData?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            resultData?.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        streamUri?.let { candidates.add(it) }
        resultData?.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                candidates.add(clip.getItemAt(index).uri)
            }
        }

        for (candidate in candidates) {
            val stabilized = stabilizePickerResultUri(resultData, candidate)
            if (hasReadableAttachmentContent(stabilized)) {
                return stabilized
            }
        }
        return null
    }

    private fun readMediaDurationMs(uri: Uri): Long? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(activity, uri)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
            retriever.release()
            ms
        } catch (_: Exception) {
            null
        }
    }

    private fun hasReadableAttachmentContent(uri: Uri): Boolean {
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            val file = uri.path?.let(::File) ?: return false
            return file.isFile && file.length() > 0L
        }
        val size = activity.getFileSizeFromUri(uri)
        if (size > 0L) {
            return true
        }
        return try {
            activity.contentResolver.openInputStream(uri)?.use { stream ->
                stream.read() != -1
            } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun inferVideoMimeType(uri: Uri): String {
        val name = activity.getFilenameFromUri(uri).lowercase()
        return when {
            name.endsWith(".mp4") -> "video/mp4"
            name.endsWith(".3gp") -> "video/3gpp"
            name.endsWith(".webm") -> "video/webm"
            else -> "video/*"
        }
    }

    private fun resolvePickerMimeType(uri: Uri, resultIntent: Intent?, requestCode: Int): String? {
        resultIntent?.type?.takeIf { it.isNotBlank() }?.let { return it }
        activity.contentResolver.getType(uri)?.let { return it }
        val filename = activity.getFilenameFromUri(uri).lowercase()
        inferMimeTypeFromFilename(filename)?.let { return it }
        return when (requestCode) {
            PICK_PHOTO_INTENT -> "image/jpeg"
            PICK_VIDEO_INTENT -> inferVideoMimeType(uri)
            PICK_SOUND_INTENT -> "audio/*"
            else -> null
        }
    }

    private fun inferMimeTypeFromFilename(filename: String): String? = when {
        filename.endsWith(".jpg") || filename.endsWith(".jpeg") -> "image/jpeg"
        filename.endsWith(".png") -> "image/png"
        filename.endsWith(".gif") -> "image/gif"
        filename.endsWith(".webp") -> "image/webp"
        filename.endsWith(".heic") -> "image/heic"
        filename.endsWith(".mp4") -> "video/mp4"
        filename.endsWith(".3gp") -> "video/3gpp"
        filename.endsWith(".webm") -> "video/webm"
        filename.endsWith(".mkv") -> "video/x-matroska"
        filename.endsWith(".mp3") -> "audio/mpeg"
        filename.endsWith(".wav") -> "audio/wav"
        filename.endsWith(".ogg") -> "audio/ogg"
        else -> null
    }

    /**
     * Draft save/restore stores [Uri] strings. Temporary [content://] access from pickers expires when the
     * activity is destroyed, so Glide fails after leave / re-enter unless we persist permission or copy locally.
     */
    private fun stabilizePickerResultUri(resultIntent: Intent?, uri: Uri): Uri {
        if (!"content".equals(uri.scheme, ignoreCase = true)) {
            return uri
        }
        if (resultIntent == null) {
            val mimeType = activity.contentResolver.getType(uri) ?: "application/octet-stream"
            return copyMediaUriToAttachmentCache(uri, mimeType) ?: uri
        }
        val hasPersistable = resultIntent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        if (hasPersistable) {
            val takeFlags = resultIntent.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (takeFlags != 0) {
                try {
                    activity.applicationContext.contentResolver.takePersistableUriPermission(uri, takeFlags)
                    return uri
                } catch (_: SecurityException) {
                }
            }
        }
        val mimeType = activity.contentResolver.getType(uri)
        return copyMediaUriToAttachmentCache(uri, mimeType ?: "application/octet-stream") ?: uri
    }

    private fun copyMediaUriToAttachmentCache(uri: Uri, mimeType: String): Uri? {
        return try {
            val name = activity.getFilenameFromUri(uri).trim()
            val base = if (name.isNotEmpty()) name else "attachment_${System.currentTimeMillis()}"
            val safeBase = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dir = File(activity.cacheDir, "attachments").apply { mkdirs() }
            val dest = if (safeBase.contains('.')) {
                File(dir, "${System.currentTimeMillis()}_$safeBase")
            } else {
                File(dir, "${System.currentTimeMillis()}_$safeBase${extensionForMimeType(mimeType)}")
            }
            val destUri = activity.getMyFileUri(dest)
            activity.copyToUri(uri, destUri)
            if (dest.length() > 0L) destUri else null
        } catch (_: Exception) {
            null
        }
    }

    private fun extensionForMimeType(mimeType: String): String = when {
        mimeType.contains("jpeg", ignoreCase = true) -> ".jpg"
        mimeType.contains("png", ignoreCase = true) -> ".png"
        mimeType.contains("gif", ignoreCase = true) -> ".gif"
        mimeType.contains("webp", ignoreCase = true) -> ".webp"
        mimeType.contains("heic", ignoreCase = true) -> ".heic"
        mimeType.contains("3gp", ignoreCase = true) -> ".3gp"
        mimeType.contains("webm", ignoreCase = true) -> ".webm"
        mimeType.isVideoMimeType() -> ".mp4"
        mimeType.isImageMimeType() -> ".jpg"
        else -> ""
    }

    fun setCapturedVideoUri(uri: Uri?) {
        capturedVideoUri = uri
    }

    fun showAttachmentPicker() {
        hideEmojiPicker(resumeKeyboard = false)
        val keyboardHeight = activity.config.keyboardHeight
        binding.attachmentPickerHolder.updateLayoutParams<ViewGroup.LayoutParams> {
            height = keyboardHeight
        }
        // Show without slide/alpha animation so it appears smoothly (avoids strange motion when opening from keyboard)
        binding.attachmentPickerHolder.beVisible()
        animateAttachmentButton(rotation = -135f)
    }

    fun hideAttachmentPicker() {
        binding.attachmentPickerHolder.beGone()
        animateAttachmentButton(rotation = 0f)
    }

    private fun animateAttachmentButton(rotation: Float) {
        binding.threadAddAttachment.animate()
            .rotation(rotation)
            .setDuration(200L)
            .start()
    }

    private fun updateExpandIconVisibility() {
        val lineCount = binding.threadTypeMessage.lineCount
        val visible = lineCount > 2
        if (lastExpandIconVisible == visible) return
        lastExpandIconVisible = visible
        binding.threadExpandMessage.beVisibleIf(visible)
    }

    fun stopAttachmentAudio() {
        getAttachmentsAdapter()?.stopAudio()
    }

    private fun getAttachmentsAdapter(): AttachmentsAdapter? {
        val adapter = binding.threadAttachmentsRecyclerview.adapter
        return adapter as? AttachmentsAdapter
    }

    /** True when compose already has attachment rows that must not be cleared by a stale draft reload. */
    fun hasComposeAttachments(): Boolean = getAttachmentSelections().isNotEmpty()

    fun getAttachmentSelections(): List<AttachmentSelection> {
        mergeAllSlidesIntoModel()
        val adapter = getAttachmentsAdapter() ?: return emptyList()
        val adapterRows = adapter.attachments.filter {
            it.id != SLIDESHOW_ATTACHMENT_ID &&
                it.viewType != ATTACHMENT_SLIDESHOW &&
                it.mimetype != "application/vnd.wap.mms-slideshow"
        }
        val slideshow = mmsSlideshow
        if (slideshow == null) {
            return adapterRows
        }

        val nonMedia = adapterRows.filter {
            !SlideshowHelper.isMediaSelection(it) && it.viewType != ATTACHMENT_AUDIO
        }
        val slideSelections = slideshow.slides
            .filter { it.uriString.isNotEmpty() }
            .map { slide ->
                AttachmentSelection(
                    id = slide.uriString,
                    uri = slide.uri,
                    mimetype = slide.mimetype,
                    filename = slide.filename,
                    isPending = false,
                )
            }

        // Prefer in-memory slides; if the model is empty but the picker still shows media, fall back
        // to adapter rows so send never drops visible attachments.
        val sendableMedia = LinkedHashMap<String, AttachmentSelection>()
        slideSelections.forEach { sendableMedia[it.uri.toString()] = it }
        if (sendableMedia.isEmpty()) {
            adapterRows.filter {
                SlideshowHelper.isMediaSelection(it) || it.viewType == ATTACHMENT_AUDIO
            }.forEach { sendableMedia[it.uri.toString()] = it.copy(isPending = false) }
        }
        return nonMedia + sendableMedia.values
    }

    fun buildMessageAttachments(messageId: Long = -1L): ArrayList<Attachment> {
        mergeAllSlidesIntoModel()
        return getAttachmentSelections()
            .map { selection ->
                val mimeType = selection.mimetype.ifBlank {
                    activity.contentResolver.getType(selection.uri).orEmpty()
                }.ifBlank { "application/octet-stream" }
                val stableUri = if (
                    mimeType.isImageMimeType() ||
                    mimeType.isVideoMimeType() ||
                    mimeType.isAudioMimeType()
                ) {
                    SlideshowHelper.stabilizeAttachmentUri(activity, selection.uri, mimeType)
                } else {
                    selection.uri
                }
                val ext = mimeType.substringAfter("/").substringBefore(";").trim().ifBlank { "dat" }
                val filename = selection.filename
                    .ifBlank { activity.getFilenameFromUri(stableUri) }
                    .ifBlank { "attachment_${System.currentTimeMillis()}.$ext" }
                Attachment(
                    id = null,
                    messageId = messageId,
                    uriString = stableUri.toString(),
                    mimetype = mimeType,
                    width = 0,
                    height = 0,
                    filename = filename,
                )
            }
            .toCollection(ArrayList())
    }

    @SuppressLint("MissingPermission")
    private fun getSubscriptionId(): Int? {
        val availableSIMs = activity.subscriptionManagerCompat().activeSubscriptionInfoList
        if (availableSIMs == null || availableSIMs.isEmpty()) {
            return SendSubscriptionHelper.firstResolvedOrTestFallback(
                SmsManager.getDefaultSmsSubscriptionId(),
            )
        }

        if (availableSIMCards.isEmpty()) {
            availableSIMs.forEachIndexed { index, subscriptionInfo ->
                var label = subscriptionInfo.displayName?.toString() ?: ""
                if (subscriptionInfo.number?.isNotEmpty() == true) {
                    label += " (${subscriptionInfo.number})"
                }
                val SIMCard = SIMCard(index + 1, subscriptionInfo.subscriptionId, label, subscriptionInfo.mnc)
                availableSIMCards.add(SIMCard)
            }
        }

        val defaultSmsSubscriptionId = SmsManager.getDefaultSmsSubscriptionId()
        val systemPreferredSimIdx = if (defaultSmsSubscriptionId >= 0) {
            availableSIMs.indexOfFirstOrNull { it.subscriptionId == defaultSmsSubscriptionId }
        } else {
            null
        }

        val selectedIndex = systemPreferredSimIdx ?: 0
        return if (selectedIndex < availableSIMCards.size) {
            availableSIMCards[selectedIndex].subscriptionId
        } else {
            SendSubscriptionHelper.firstResolvedOrTestFallback(
                SmsManager.getDefaultSmsSubscriptionId(),
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun getSubscriptionIdForNumbers(numbers: List<String>): Int? {
        val availableSIMs = activity.subscriptionManagerCompat().activeSubscriptionInfoList
        if (availableSIMs == null || availableSIMs.isEmpty()) {
            return SendSubscriptionHelper.firstResolvedOrTestFallback(
                SmsManager.getDefaultSmsSubscriptionId(),
            )
        }

        if (availableSIMCards.isEmpty()) {
            availableSIMs.forEachIndexed { index, subscriptionInfo ->
                var label = subscriptionInfo.displayName?.toString() ?: ""
                if (subscriptionInfo.number?.isNotEmpty() == true) {
                    label += " (${subscriptionInfo.number})"
                }
                val SIMCard = SIMCard(index + 1, subscriptionInfo.subscriptionId, label, subscriptionInfo.mnc)
                availableSIMCards.add(SIMCard)
            }
        }

        val userPreferredSimId = activity.config.getUseSIMIdAtNumber(numbers.firstOrNull() ?: "")
        val userPreferredSimIdx = availableSIMs.indexOfFirstOrNull { it.subscriptionId == userPreferredSimId }

        val defaultSmsSubscriptionId = SmsManager.getDefaultSmsSubscriptionId()
        val systemPreferredSimIdx = if (defaultSmsSubscriptionId >= 0) {
            availableSIMs.indexOfFirstOrNull { it.subscriptionId == defaultSmsSubscriptionId }
        } else {
            null
        }

        val selectedIndex = userPreferredSimIdx ?: systemPreferredSimIdx ?: 0
        return if (selectedIndex < availableSIMCards.size) {
            availableSIMCards[selectedIndex].subscriptionId
        } else {
            SendSubscriptionHelper.firstResolvedOrTestFallback(
                SmsManager.getDefaultSmsSubscriptionId(),
            )
        }
    }
}

fun LayoutThreadSendMessageHolderBinding.syncEmojiButtonWithSimHolderVisibility(){
    imvEmoticBtn.beVisibleIf(!threadSelectSimIconHolder.isVisible())
}

