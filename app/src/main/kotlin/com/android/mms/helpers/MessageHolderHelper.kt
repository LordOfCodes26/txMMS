package com.android.mms.helpers

import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionInfo
import android.util.TypedValue
import android.view.KeyEvent
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import com.goodwy.commons.activities.BaseSimpleActivity
import com.android.mms.R
import com.android.mms.adapters.AttachmentsAdapter
import com.android.mms.databinding.LayoutThreadSendMessageHolderBinding
import com.android.mms.extensions.*
import com.android.mms.messaging.sendMessageCompat
import com.android.mms.models.Attachment
import com.android.mms.models.AttachmentSelection
import com.android.mms.models.SIMCard
import com.goodwy.commons.extensions.*
import douglasspgyn.com.github.circularcountdown.CircularCountdown
import douglasspgyn.com.github.circularcountdown.listener.CircularListener

class MessageHolderHelper(
    private val activity: BaseSimpleActivity,
    private val binding: LayoutThreadSendMessageHolderBinding,
    private val onSendMessage: (text: String, subscriptionId: Int?, attachments: List<Attachment>) -> Unit,
    private val onSpeechToText: () -> Unit = {},
    private val onExpandMessage: (() -> Unit)? = null,
    private val onTextChanged: ((String) -> Unit)? = null
) {
    private var isCountdownActive = false
    private var isSpeechToTextAvailable = false
    private val availableSIMCards = ArrayList<SIMCard>()
    private var currentSIMCardIndex = 0
    private var capturedImageUri: Uri? = null
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
            threadSendMessage.applyColorFilter(properPrimaryColor.getContrastColor())
            threadAddAttachment.applyColorFilter(textColor)
            threadAddAttachment.background.applyColorFilter(surfaceColor)
            threadTypeMessageHolder.background.applyColorFilter(surfaceColor)

            threadCharacterCounter.beVisibleIf(threadTypeMessage.value.isNotEmpty() && activity.config.showCharacterCounter)
            threadCharacterCounter.backgroundTintList = activity.getProperBackgroundColor().getColorStateList()

            threadTypeMessage.setTextSize(TypedValue.COMPLEX_UNIT_PX, activity.getTextSizeMessage())

            if (isSpeechToTextAvailable) {
                threadSendMessageWrapper.setOnLongClickListener {
                    onSpeechToText()
                    true
                }
            }

            threadSendMessage.backgroundTintList = properPrimaryColor.getColorStateList()
            threadSendMessageWrapper.isClickable = false
            threadSendMessageCountdown.beGone()

            threadExpandMessage.apply {
                applyColorFilter(textColor)
                setOnClickListener {
                    onExpandMessage?.invoke()
                }
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
                            startSendMessageCountdown()
                        } else {
                            sendMessage()
                        }
                        return@setOnKeyListener true
                    }
                    false
                }
            }
        }

        checkSendMessageAvailability()
    }

    fun setupAttachmentPicker(
        onChoosePhoto: () -> Unit,
        onChooseVideo: () -> Unit,
        onTakePhoto: () -> Unit,
        onRecordVideo: () -> Unit,
        onRecordAudio: () -> Unit,
        onPickFile: () -> Unit,
        onPickContact: () -> Unit,
        onScheduleMessage: (() -> Unit)? = null
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
                com.goodwy.commons.R.color.ic_messages
            ).map { ResourcesCompat.getColor(activity.resources, it, activity.theme) }

            arrayOf(
                choosePhotoIcon,
                chooseVideoIcon,
                takePhotoIcon,
                recordVideoIcon,
                recordAudioIcon,
                pickFileIcon,
                pickContactIcon,
                scheduleMessageIcon
            ).forEachIndexed { index, icon ->
                val iconColor = buttonColors[index]
                icon.background.applyColorFilter(iconColor)
                if (index != 0 && index != 2 && index != 5) icon.applyColorFilter(iconColor.getContrastColor())
                if (index == 5) icon.applyColorFilter(ResourcesCompat.getColor(activity.resources, com.goodwy.commons.R.color.ic_messages, activity.theme))
            }

            val textColor = activity.getProperTextColor()
            arrayOf(
                choosePhotoText,
                chooseVideoText,
                takePhotoText,
                recordVideoText,
                recordAudioText,
                pickFileText,
                pickContactText,
                scheduleMessageText
            ).forEach { it.setTextColor(textColor) }

            choosePhoto.setOnClickListener { onChoosePhoto() }
            chooseVideo.setOnClickListener { onChooseVideo() }
            takePhoto.setOnClickListener { onTakePhoto() }
            recordVideo.setOnClickListener { onRecordVideo() }
            recordAudio.setOnClickListener { onRecordAudio() }
            pickFile.setOnClickListener { onPickFile() }
            pickContact.setOnClickListener { onPickContact() }
            scheduleMessage.setOnClickListener {
                onScheduleMessage?.invoke() ?: activity.toast(com.goodwy.commons.R.string.unknown_error_occurred)
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
                            startSendMessageCountdown()
                        } else {
                            sendMessage()
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
            R.drawable.ic_send_vector
        }
        ResourcesCompat.getDrawable(activity.resources, drawableResId, activity.theme)?.apply {
            applyColorFilter(activity.getProperPrimaryColor().getContrastColor())
            binding.threadSendMessage.setImageDrawable(this)
        }
    }
    
    fun setScheduledMessage(scheduled: Boolean) {
        isScheduledMessage = scheduled
        updateSendButtonDrawable()
    }

    private fun startSendMessageCountdown() {
        if (isCountdownActive) return

        val delaySeconds = activity.config.messageSendDelay
        if (delaySeconds <= 0) {
            sendMessage()
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
                            sendMessage()
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
                sendMessage()
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

    fun sendMessage() {
        val text = binding.threadTypeMessage.value.trim()
        val attachments = buildMessageAttachments()
        val subscriptionId = getSubscriptionId()
        onSendMessage(text, subscriptionId, attachments)
    }

    fun getMessageText(): String = binding.threadTypeMessage.value

    fun setMessageText(text: String) {
        binding.threadTypeMessage.setText(text)
    }

    fun clearMessage() {
        binding.threadTypeMessage.setText("")
        getAttachmentsAdapter()?.clear()
        if (isCountdownActive) {
            isCountdownActive = false
            hideCountdown()
        }
        checkSendMessageAvailability()
    }

    fun addAttachment(uri: Uri) {
        val id = uri.toString()
        if (getAttachmentSelections().any { it.id == id }) {
            activity.toast(R.string.duplicate_item_warning)
            return
        }

        val mimeType = activity.contentResolver.getType(uri)
        if (mimeType == null) {
            activity.toast(com.goodwy.commons.R.string.unknown_error_occurred)
            return
        }

        val isImage = mimeType.isImageMimeType()
        val isGif = mimeType.isGifMimeType()
        if (isGif || !isImage) {
            val fileSize = activity.getFileSizeFromUri(uri)
            val mmsFileSizeLimit = activity.config.mmsFileSizeLimit
            if (mmsFileSizeLimit != FILE_SIZE_NONE && fileSize > mmsFileSizeLimit) {
                activity.toast(R.string.attachment_sized_exceeds_max_limit, length = Toast.LENGTH_LONG)
                return
            }
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
        val attachment = AttachmentSelection(
            id = id,
            uri = uri,
            mimetype = mimeType,
            filename = activity.getFilenameFromUri(uri),
            isPending = isImage && !isGif
        )
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
                    addAttachment(data)
                }
            }
        }
    }

    fun setCapturedImageUri(uri: Uri?) {
        capturedImageUri = uri
    }

    fun showAttachmentPicker() {
        binding.attachmentPickerHolder.showWithAnimation()
        animateAttachmentButton(rotation = -135f)
    }

    fun hideAttachmentPicker() {
        binding.attachmentPickerHolder.beGone()
        animateAttachmentButton(rotation = 0f)
    }

    private fun animateAttachmentButton(rotation: Float) {
        binding.threadAddAttachment.animate()
            .rotation(rotation)
            .setDuration(500L)
            .setInterpolator(OvershootInterpolator())
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
                val SIMCard = SIMCard(index + 1, subscriptionInfo.subscriptionId, label)
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
                val SIMCard = SIMCard(index + 1, subscriptionInfo.subscriptionId, label)
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

