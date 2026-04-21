package com.android.mms.helpers

import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.util.TypedValue
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.updateLayoutParams
import java.io.File
import androidx.core.content.res.ResourcesCompat
import com.android.mms.emoji.ChatPaneEmoji
import com.android.mms.emoji.Ch350EmojiBootstrap
import com.android.mms.emoji.RepeatListener
import com.android.common.dialogs.MDateTimePickerDialog
import com.goodwy.commons.activities.BaseSimpleActivity
import com.android.mms.R
import com.android.mms.activities.SimpleActivity
import com.android.mms.adapters.AttachmentsAdapter
import com.android.mms.databinding.LayoutThreadSendMessageHolderBinding
import com.android.mms.dialogs.SelectSIMDialog
import com.android.mms.extensions.*
import com.android.mms.models.Attachment
import com.android.mms.models.AttachmentSelection
import com.android.mms.models.DraftStoredAttachment
import com.android.mms.models.SIMCard
import com.goodwy.commons.extensions.*
import douglasspgyn.com.github.circularcountdown.CircularCountdown
import douglasspgyn.com.github.circularcountdown.listener.CircularListener
import eightbitlab.com.blurview.BlurTarget

class MessageHolderHelper(
    private val activity: BaseSimpleActivity,
    private val binding: LayoutThreadSendMessageHolderBinding,
    private val onSendMessage: (text: String, subscriptionId: Int?, attachments: List<Attachment>) -> Unit,
    private val onSpeechToText: () -> Unit = {},
    private val onExpandMessage: (() -> Unit)? = null,
    private val onTextChanged: ((String) -> Unit)? = null,
    private val onHideAttachmentPickerRequested: (() -> Unit)? = null,
    private val onThreadTypeMessageFocusChange: ((hasFocus: Boolean) -> Unit)? = null,
    /** Invoked before hiding the attachment picker when the field gains focus and the picker is open (keyboard replacing picker). */
    private val onPrepareKeyboardFromAttachmentPicker: (() -> Unit)? = null,
) {
    var isCountdownActive = false
    private var isSpeechToTextAvailable = false
    private val availableSIMCards = ArrayList<SIMCard>()
    private var currentSIMCardIndex = 0
    private var capturedImageUri: Uri? = null
    private var chatPaneEmoji: ChatPaneEmoji? = null
    private var isEmojiPickerVisible: Boolean = false
    var isScheduledMessage: Boolean = false
        private set

    companion object {
        const val CAPTURE_PHOTO_INTENT = 1001
        const val CAPTURE_VIDEO_INTENT = 1002
        const val CAPTURE_AUDIO_INTENT = 1003
        const val PICK_PHOTO_INTENT = 1004
        const val PICK_VIDEO_INTENT = 1005
        const val PICK_DOCUMENT_INTENT = 1006
        const val PICK_CONTACT_INTENT = 1007
    }

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
                threadSendMessageWrapper.setOnLongClickListener {
                    onSpeechToText()
                    true
                }
            }

//            (ResourcesCompat.getDrawable(activity.resources, R.drawable.thread_send_message_circle_border, activity.theme)?.mutate() as? GradientDrawable)?.let { drawable ->
//                drawable.setStroke((activity.resources.displayMetrics.density).toInt(), textColor)
//                threadSendMessage.background = drawable
//            }
            threadSendMessageWrapper.isClickable = false
            threadSendMessageCountdown.beGone()

            threadExpandMessage.apply {
                applyColorFilter(textColor)
                setOnClickListener {
                    onExpandMessage?.invoke()
                }
            }

            threadTypeMessage.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    if (binding.attachmentPickerHolder.isVisible()) {
                        onPrepareKeyboardFromAttachmentPicker?.invoke()
                    }
                    onHideAttachmentPickerRequested?.invoke()
                    hideAttachmentPicker()
                    hideEmojiPicker(resumeKeyboard = false)
                    activity.showKeyboard(threadTypeMessage)
                }
                onThreadTypeMessageFocusChange?.invoke(hasFocus)
            }

            threadTypeMessage.onTextChangeListener {
                onTextChanged?.invoke(it)
                checkSendMessageAvailability()
                val messageString = if (activity.config.useSimpleCharacters) {
                    it.normalizeString()
                } else {
                    it
                }
                val messageLength = SmsMessage.calculateLength(messageString, false)
                @SuppressLint("SetTextI18n")
                threadCharacterCounter.text = "${messageLength[2]}/${messageLength[0]}"
                threadCharacterCounter.beVisibleIf(threadTypeMessage.value.isNotEmpty() && activity.config.showCharacterCounter)
                updateExpandIconVisibility()
            }

            threadTypeMessage.onGlobalLayout {
                updateExpandIconVisibility()
            }

            if (activity.config.sendOnEnter) {
                threadTypeMessage.inputType = EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES
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

    private fun toggleEmojiPicker() {
        if (isEmojiPickerVisible) {
            hideEmojiPicker(resumeKeyboard = true)
        } else {
            showEmojiPicker()
        }
    }

    private fun showEmojiPicker() {
        ensureCh350EmojiPane()
        if (chatPaneEmoji == null) return
        hideAttachmentPicker()
        onHideAttachmentPickerRequested?.invoke()
        isEmojiPickerVisible = true
        activity.hideKeyboard()
        binding.threadTypeMessage.clearFocus()
        binding.messageEmojiPickerHolder.updateLayoutParams<ViewGroup.LayoutParams> {
            height = activity.config.keyboardHeight
        }
        binding.messageEmojiPickerHolder.beVisible()
        chatPaneEmoji?.showFirstTab()
        binding.imvEmoticBtn.setBackgroundResource(R.drawable.ic_sms_keyboard)
        binding.imvEmoticBtn.contentDescription = activity.getString(com.goodwy.commons.R.string.keyboard_short)
        binding.root.post { binding.root.requestLayout() }
    }

    private fun hideEmojiPicker(resumeKeyboard: Boolean) {
        if (!isEmojiPickerVisible) return
        isEmojiPickerVisible = false
        binding.messageEmojiPickerHolder.beGone()
        binding.imvEmoticBtn.setBackgroundResource(R.drawable.ic_sms_emotic)
        binding.imvEmoticBtn.contentDescription = activity.getString(com.goodwy.commons.R.string.choose_emoji)
        if (resumeKeyboard) {
            binding.threadTypeMessage.requestFocus()
            activity.showKeyboard(binding.threadTypeMessage)
        }
    }

    fun setupAttachmentPicker(
        onChoosePhoto: () -> Unit,
        onChooseVideo: () -> Unit,
        onTakePhoto: () -> Unit,
        onRecordVideo: () -> Unit,
        onRecordAudio: () -> Unit,
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
                chooseTitleText
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
                    onHideAttachmentPickerRequested?.invoke() ?: hideAttachmentPicker()
                    onScheduleMessage.invoke()
                } else {
                    val blurTarget = activity.findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
                        ?: throw IllegalStateException("mainBlurTarget not found")
                    val mDateTimePickerDialog = MDateTimePickerDialog(activity)
                    mDateTimePickerDialog.bindBlurTarget(blurTarget)
                    mDateTimePickerDialog.show()
                    mDateTimePickerDialog.setOnDateSelectListener { datetime ->
                        insertText(datetime.toLocaleString())
                    }
                }
            }
            chooseImage.setOnClickListener { onChoosePhoto() }
            chooseEmoji.setOnClickListener { showEmojiPicker() }
            chooseText.setOnClickListener { onPickQuickText() }
            chooseCamera.setOnClickListener { onTakePhoto() }
            chooseVoice.setOnClickListener { onRecordAudio() }
//            pickFile.setOnClickListener { onPickFile() }
            chooseContact.setOnClickListener { onPickContact() }
            chooseTitle.setOnClickListener {
                onPickQuickText()
            }

        }
    }

    fun checkSendMessageAvailability() {
        updateSendButtonDrawable()
        binding.apply {
            if (threadTypeMessage.text!!.isNotEmpty() || (getAttachmentSelections().isNotEmpty() && !getAttachmentSelections().any { it.isPending })) {
                threadSendMessageWrapper.apply {
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
            } else if (isSpeechToTextAvailable) {
                threadSendMessageWrapper.apply {
                    isEnabled = true
                    isClickable = true
                    alpha = 1f
                    contentDescription = activity.getString(com.goodwy.strings.R.string.voice_input)
                    setOnClickListener {
                        onSpeechToText()
                    }
                }
            } else {
                threadSendMessageWrapper.apply {
                    isEnabled = false
                    isClickable = false
                    alpha = 0.4f
                }
            }
        }
    }

    fun updateSendButtonDrawable() {
        val drawableResId = if (isScheduledMessage) {
            R.drawable.ic_schedule_send_vector
        } else if (binding.threadTypeMessage.text!!.isNotEmpty() ||
            (getAttachmentSelections().isNotEmpty() && !getAttachmentSelections().any { it.isPending })) {
            R.drawable.ic_send_vector
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

    fun startSendMessageCountdown(subscriptionId: Int) {
        if (isCountdownActive) return

        val delaySeconds = activity.config.messageSendDelay
        if (delaySeconds <= 0) {
            sendMessage(subscriptionId)
            if (activity.config.soundOnOutGoingMessages) {
                val audioManager = activity.getSystemService(AudioManager::class.java)
                audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_SPACEBAR)
            }
            return
        }

        isCountdownActive = true
        binding.apply {
            threadSendMessage.beGone()
            threadSendMessageCountdown.beVisible()

            threadSendMessageCountdown.setOnClickListener {
                if (isCountdownActive) {
                    isCountdownActive = false
                    hideCountdown()
                    activity.toast(R.string.sending_cancelled)
                }
            }

            try {
                threadSendMessageCountdown.create(0, delaySeconds, CircularCountdown.TYPE_SECOND)
                    .listener(object : CircularListener {
                        override fun onTick(progress: Int) {}
                        override fun onFinish(newCycle: Boolean, cycleCount: Int) {
                            isCountdownActive = false
                            hideCountdown()
                            sendMessage(subscriptionId)
                            if (activity.config.soundOnOutGoingMessages) {
                                val audioManager = activity.getSystemService(AudioManager::class.java)
                                audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_SPACEBAR)
                            }
                        }
                    })
                    .start()
            } catch (e: Exception) {
                isCountdownActive = false
                hideCountdown()
                sendMessage(subscriptionId)
                if (activity.config.soundOnOutGoingMessages) {
                    val audioManager = activity.getSystemService(AudioManager::class.java)
                    audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_SPACEBAR)
                }
            }
        }
    }

    private fun hideCountdown() {
        binding.apply {
            try {
                threadSendMessageCountdown.stop()
            } catch (e: Exception) {}
            threadSendMessageCountdown.setOnClickListener(null)
            threadSendMessageCountdown.beGone()
            threadSendMessage.beVisible()
        }
    }
    // added by sun
    // when use multi sim, show dialog sim select
    // ----->
    @SuppressLint("MissingPermission")
    private fun resolveSubscriptionThen(onSubId: (Int) -> Unit) {
        val subs = activity.subscriptionManagerCompat().activeSubscriptionInfoList
        val defaultSmsSubscriptionId = SmsManager.getDefaultSmsSubscriptionId()
        if (!subs.isNullOrEmpty() && subs.size > 1 && defaultSmsSubscriptionId < 0) {       // when muti sim and always check sms in system setting
            val blurTarget = activity.findViewById<BlurTarget>(R.id.mainBlurTarget)
            SelectSIMDialog(activity as SimpleActivity, blurTarget) { simCard, _ ->
                onSubId(simCard.subscriptionId)
            }
        }
        else {
            val subId = when {
                subs?.size == 1 -> subs[0].subscriptionId
                subs?.size == 2 -> defaultSmsSubscriptionId
                else ->getSubscriptionId()
            }
            subId?.let { onSubId(it) }
        }
    }
    private fun pickSimAndSendOrSendDirect() {
        resolveSubscriptionThen { sendMessage(it) }
    }
    // <-----------

    @SuppressLint("SuspiciousIndentation")
    fun sendMessage(subscriptionId: Int) {
        val text = binding.threadTypeMessage.value.trim()
        val attachments = buildMessageAttachments()

        onSendMessage(text, subscriptionId, attachments)
    }

    fun getMessageText(): String = binding.threadTypeMessage.value

    fun setMessageText(text: String) {
        binding.threadTypeMessage.setText(text)
    }

    fun insertText(text: String) {
        val editText = binding.threadTypeMessage
        val start = editText.selectionStart
        val end = editText.selectionEnd
        val currentText = editText.text.toString()
        val newText = currentText.substring(0, start) + text + currentText.substring(end)
        editText.setText(newText)
        editText.setSelection(start + text.length)
        checkSendMessageAvailability()
    }

    fun clearMessage() {
        binding.threadTypeMessage.setText("")
        clearAttachments()
        if (isCountdownActive) {
            isCountdownActive = false
            hideCountdown()
        }
        checkSendMessageAvailability()
    }

    fun clearAttachments() {
        getAttachmentsAdapter()?.clear()
        checkSendMessageAvailability()
    }

    fun addAttachment(uri: Uri) {
        val mimeType = activity.contentResolver.getType(uri)
        if (mimeType == null) {
            activity.toast(com.goodwy.commons.R.string.unknown_error_occurred)
            return
        }
        addAttachmentWithKnownMime(uri, mimeType, activity.getFilenameFromUri(uri))
    }

    /**
     * Restores compose attachments from draft in one adapter update so list diffing cannot apply an
     * intermediate empty list after a clear (see [AttachmentsAdapter.submitAttachments]).
     */
    fun replaceAttachmentsFromDraft(stored: List<DraftStoredAttachment>) {
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
        if (selections.isEmpty()) {
            getAttachmentsAdapter()?.submitAttachments(emptyList())
            binding.threadAttachmentsRecyclerview.beGone()
            checkSendMessageAvailability()
            return
        }
        var adapter = getAttachmentsAdapter()
        if (adapter == null) {
            adapter = AttachmentsAdapter(
                activity = activity,
                recyclerView = binding.threadAttachmentsRecyclerview,
                onAttachmentsRemoved = {
                    binding.threadAttachmentsRecyclerview.beGone()
                    checkSendMessageAvailability()
                },
                onReady = { checkSendMessageAvailability() },
            )
            binding.threadAttachmentsRecyclerview.adapter = adapter
        }
        binding.threadAttachmentsRecyclerview.beVisible()
        adapter.submitAttachments(selections)
        binding.threadAttachmentsRecyclerview.post {
            binding.threadAttachmentsRecyclerview.requestLayout()
        }
        checkSendMessageAvailability()
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

    private fun addAttachmentWithKnownMime(
        uri: Uri,
        mimeType: String,
        filename: String,
        isPendingOverride: Boolean? = null,
    ) {
        val id = uri.toString()
        if (getAttachmentSelections().any { it.id == id }) {
            activity.toast(R.string.duplicate_item_warning)
            return
        }

        if (!passesNonImageAttachmentSizeCap(uri, mimeType)) {
            activity.toast(R.string.attachment_sized_exceeds_max_limit, length = Toast.LENGTH_LONG)
            return
        }

        var adapter = getAttachmentsAdapter()
        if (adapter == null) {
            adapter = AttachmentsAdapter(
                activity = activity,
                recyclerView = binding.threadAttachmentsRecyclerview,
                onAttachmentsRemoved = {
                    binding.threadAttachmentsRecyclerview.beGone()
                    checkSendMessageAvailability()
                },
                onReady = { checkSendMessageAvailability() }
            )
            binding.threadAttachmentsRecyclerview.adapter = adapter
        }

        binding.threadAttachmentsRecyclerview.beVisible()
        val attachment = buildAttachmentSelection(uri, mimeType, filename, isPendingOverride)
        adapter.addAttachment(attachment)
        checkSendMessageAvailability()
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK) return
        val data = resultData?.data

        when (requestCode) {
            CAPTURE_PHOTO_INTENT -> {
                if (capturedImageUri != null) {
                    addAttachment(capturedImageUri!!)
                }
            }
            CAPTURE_VIDEO_INTENT,
            PICK_DOCUMENT_INTENT,
            CAPTURE_AUDIO_INTENT,
            PICK_PHOTO_INTENT,
            PICK_VIDEO_INTENT -> {
                if (data != null) {
                    val stableUri = stabilizePickerResultUri(resultData, data)
                    addAttachment(stableUri)
                }
            }
        }
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
            return copyPickedUriToAttachmentCache(uri) ?: uri
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
        return copyPickedUriToAttachmentCache(uri) ?: uri
    }

    private fun copyPickedUriToAttachmentCache(uri: Uri): Uri? {
        return try {
            val name = activity.getFilenameFromUri(uri).trim()
            val base = if (name.isNotEmpty()) name else "attachment"
            val safeBase = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dir = File(activity.cacheDir, "attachments").apply { mkdirs() }
            val dest = File(dir, "${System.currentTimeMillis()}_$safeBase")
            val destUri = activity.getMyFileUri(dest)
            activity.copyToUri(uri, destUri)
            if (dest.length() > 0L) destUri else null
        } catch (_: Exception) {
            null
        }
    }

    fun setCapturedImageUri(uri: Uri?) {
        capturedImageUri = uri
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
        binding.threadExpandMessage.beVisibleIf(lineCount > 2)
    }

    private fun getAttachmentsAdapter(): AttachmentsAdapter? {
        val adapter = binding.threadAttachmentsRecyclerview.adapter
        return adapter as? AttachmentsAdapter
    }

    fun getAttachmentSelections() = getAttachmentsAdapter()?.attachments ?: emptyList()

    fun buildMessageAttachments(messageId: Long = -1L): ArrayList<Attachment> = getAttachmentSelections()
        .map { Attachment(null, messageId, it.uri.toString(), it.mimetype, 0, 0, it.filename) }
        .toCollection(ArrayList())

    @SuppressLint("MissingPermission")
    private fun getSubscriptionId(): Int? {
        val availableSIMs = activity.subscriptionManagerCompat().activeSubscriptionInfoList
        if (availableSIMs == null || availableSIMs.isEmpty()) {
            return SmsManager.getDefaultSmsSubscriptionId().takeIf { it >= 0 }
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
            SmsManager.getDefaultSmsSubscriptionId().takeIf { it >= 0 }
        }
    }

    @SuppressLint("MissingPermission")
    fun getSubscriptionIdForNumbers(numbers: List<String>): Int? {
        val availableSIMs = activity.subscriptionManagerCompat().activeSubscriptionInfoList
        if (availableSIMs == null || availableSIMs.isEmpty()) {
            return SmsManager.getDefaultSmsSubscriptionId().takeIf { it >= 0 }
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
            SmsManager.getDefaultSmsSubscriptionId().takeIf { it >= 0 }
        }
    }
}

