package com.android.mms.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionInfo
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.RequiresPermission
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max
import androidx.fragment.app.Fragment
import com.android.mms.R
import com.android.mms.databinding.FragmentExpandedMessageBinding
import com.android.mms.emoji.RepeatListener
import com.android.mms.extensions.config
import com.android.mms.extensions.getTextSizeMessage
import com.android.mms.extensions.indexOfFirstOrNull
import com.android.mms.extensions.subscriptionManagerCompat
import com.android.mms.helpers.FeeInfoUtils
import com.android.mms.helpers.THREAD_TOP_COMPACT
import com.android.mms.helpers.THREAD_TOP_LARGE
import com.android.mms.helpers.bindConversationListAvatar
import com.android.mms.helpers.syncEmojiButtonWithSimHolderVisibility
import com.android.mms.models.SIMCard
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.views.ContactAvatarView
import com.goodwy.commons.views.MyTextView
import douglasspgyn.com.github.circularcountdown.CircularCountdown
import douglasspgyn.com.github.circularcountdown.listener.CircularListener

class ExpandedMessageFragment : Fragment() {
    private var _binding: FragmentExpandedMessageBinding? = null
    private val binding get() = _binding!!
    
    private var messageText: String = ""
    private var onMessageTextChanged: ((String) -> Unit)? = null
    private var onSendMessage: ((subscriptionId: Int) -> Unit)? = null
    private var onMinimize: (() -> Unit)? = null
    private var onSpeechToText: (() -> Unit)? = null
    private var hasAddressForSend: (() -> Boolean)? = null
    private var hasReadyAttachments: (() -> Boolean)? = null
    private var resolveSubscriptionForSend: ((anchorView: View, onSubId: (Int) -> Unit) -> Unit)? = null
    private var isCountdownActive = false
    private var isSpeechToTextAvailable = false

    private var currentSIMCardIndex = 0
    private val availableSIMCards = ArrayList<SIMCard>()

    private enum class ComposeSendMode { SEND, SPEECH, DISABLED }

    private var appliedComposeSendMode: ComposeSendMode? = null

    companion object {
        private const val ARG_MESSAGE_TEXT = "message_text"

        fun newInstance(messageText: String): ExpandedMessageFragment {
            return ExpandedMessageFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MESSAGE_TEXT, messageText)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.getString(ARG_MESSAGE_TEXT)?.let {
            messageText = it
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExpandedMessageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
    }

    @SuppressLint("MissingPermission")
    private fun setupViews() {
        val activity = requireActivity()
        val textColor = activity.getProperTextColor()
        // Match ThreadActivity / NewConversationActivity toolbar: [EdgeToEdgeActivity.getStartRequiredStatusBarColor]
        // (scroll-offset-aware surface or background), not the always-material scrolled bar color.
        val topBarColor = (activity as BaseSimpleActivity).getStartRequiredStatusBarColor()
        val sendIconTint = activity.getProperTextColor()

        // Setup minimize buttons (for both large and compact views)
        binding.expandedMinimizeButton.apply {
            applyColorFilter(topBarColor.getContrastColor())
            setOnClickListener {
                onMinimize?.invoke()
            }
        }
        
        binding.topDetailsCompactExpanded.findViewById<android.widget.ImageView>(
            com.android.mms.R.id.expandedMinimizeButtonCompact
        )?.apply {
            applyColorFilter(topBarColor.getContrastColor())
            setOnClickListener {
                onMinimize?.invoke()
            }
        }

        // Thread title will be set by the activity through updateThreadTitle()
        binding.topDetailsCompactExpanded.beGone()
        binding.topDetailsLargeExpanded.beGone()
        
        // Same top bar fill as main thread / new conversation (large + compact headers).
        binding.topDetailsLargeExpanded.setBackgroundColor(topBarColor)
        binding.topDetailsCompactExpanded.setBackgroundColor(topBarColor)
        
        // Handle system window insets to avoid status bar overlap
        ViewCompat.setOnApplyWindowInsetsListener(binding.topDetailsLargeExpanded) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                systemBars.top,
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.topDetailsCompactExpanded) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                systemBars.top,
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }

        // Edge-to-edge hosts (ThreadActivity / NewConversationActivity) do not resize for IME; pad the
        // editor container so long multi-line text stays above the keyboard and navigation bar.
        val messageContent = binding.expandedMessageContent
        val basePaddingStart = messageContent.paddingStart
        val basePaddingTop = messageContent.paddingTop
        val basePaddingEnd = messageContent.paddingEnd
        val basePaddingBottom = messageContent.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(messageContent) { v, windowInsets ->
            val imeBottom = windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBottom = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val bottomInset = max(imeBottom, navBottom)
            v.setPaddingRelative(
                basePaddingStart,
                basePaddingTop,
                basePaddingEnd,
                basePaddingBottom + bottomInset
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(messageContent)

        // Setup character counters
        val shouldShowCounter = messageText.isNotEmpty() && activity.config.showCharacterCounter
        
        // Calculate initial character count
        val initialMessageString = if (activity.config.useSimpleCharacters) {
            messageText.normalizeString()
        } else {
            messageText
        }
        val initialMessageLength = SmsMessage.calculateLength(initialMessageString, false)
        @SuppressLint("SetTextI18n")
        val initialCounterText = "${initialMessageLength[2]}/${initialMessageLength[0]}"
        
        binding.expandedThreadCharacterCounter.apply {
            text = initialCounterText
            beVisibleIf(shouldShowCounter)
            backgroundTintList = activity.getProperBackgroundColor().getColorStateList()
        }
        binding.topDetailsCompactExpanded.findViewById<com.goodwy.commons.views.MyTextView>(
            com.android.mms.R.id.expandedThreadCharacterCounterCompact
        )?.apply {
            text = initialCounterText
            beVisibleIf(shouldShowCounter)
            backgroundTintList = activity.getProperBackgroundColor().getColorStateList()
        }
        
        // Setup text editor
        binding.expandedThreadTypeMessage.apply {
            setText(messageText)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, activity.getTextSizeMessage())
            setTextColor(textColor)
            setHintTextColor(textColor.adjustAlpha(0.6f))

            val properTextColor = context.getProperTextColor()
            val properAccentColor = context.getProperAccentColor()

            binding.expandedThreadTypeMessage.setColors(properTextColor, properAccentColor, context.getProperTextCursorColor())
            
            // Add text change listener
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    onMessageTextChanged?.invoke(s?.toString() ?: "")
                    checkSendMessageAvailability()
                    
                    // Update character counter
                    val messageString = if (activity.config.useSimpleCharacters) {
                        s?.toString()?.normalizeString() ?: ""
                    } else {
                        s?.toString() ?: ""
                    }
                    val messageLength = SmsMessage.calculateLength(messageString, false)
                    @SuppressLint("SetTextI18n")
                    val counterText = "${messageLength[2]}/${messageLength[0]}"
                    val shouldShow = s?.isNotEmpty() == true && activity.config.showCharacterCounter
                    
                    binding.expandedThreadCharacterCounter.text = counterText
                    binding.expandedThreadCharacterCounter.beVisibleIf(shouldShow)
                    
                    binding.topDetailsCompactExpanded.findViewById<com.goodwy.commons.views.MyTextView>(
                        com.android.mms.R.id.expandedThreadCharacterCounterCompact
                    )?.apply {
                        text = counterText
                        beVisibleIf(shouldShow)
                    }
                }
            })
            
            // Request focus and show keyboard
            requestFocus()
            activity.showKeyboard(this)
        }

        // Send control: same drawable + tint as [layout_thread_send_message_holder] / [MessageHolderHelper.setup]
        binding.expandedThreadSendMessage.applyColorFilter(sendIconTint)
        binding.topDetailsCompactExpanded.findViewById<android.widget.ImageView>(
            com.android.mms.R.id.expandedThreadSendMessageCompact
        )?.applyColorFilter(sendIconTint)
        
        // Initialize countdown views (hidden by default)
        binding.expandedThreadSendMessageCountdown.beGone()
        binding.topDetailsCompactExpanded.findViewById<douglasspgyn.com.github.circularcountdown.CircularCountdown>(
            com.android.mms.R.id.expandedThreadSendMessageCountdownCompact
        )?.beGone()
        
        // Update send button availability
        applySendConfigurationToView()
        checkSendMessageAvailability()
        getCurrentSIMCardIndex()
        updateAvailableMessageCountForCurrentSim()
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    private fun getCurrentSIMCardIndex() {
        val availableSIMs = activity?.subscriptionManagerCompat()?.activeSubscriptionInfoList ?: run {
            return
        }
        if (availableSIMs.size >= 1) {
            availableSIMCards.clear()
            availableSIMs.forEachIndexed { index, subscriptionInfo ->
                var label = subscriptionInfo.displayName?.toString() ?: ""
                if (subscriptionInfo.number?.isNotEmpty() == true) {
                    label += " (${subscriptionInfo.number})"
                }
                val SIMCard = SIMCard(index + 1, subscriptionInfo.subscriptionId, label, subscriptionInfo.mnc)
                availableSIMCards.add(SIMCard)
            }

        }
        currentSIMCardIndex =  getProperSimIndex(availableSIMs)
    }
    @SuppressLint("MissingPermission")
    private fun getProperSimIndex(
        availableSIMs: MutableList<SubscriptionInfo>,
    ): Int {

        val defaultSmsSubscriptionId = SmsManager.getDefaultSmsSubscriptionId()
        val systemPreferredSimIdx = if (defaultSmsSubscriptionId >= 0) {
            availableSIMs.indexOfFirstOrNull { it.subscriptionId == defaultSmsSubscriptionId }
        } else {
            null
        }

        return systemPreferredSimIdx ?: 0
    }

    fun updateAvailableMessageCountForCurrentSim() {
        activity?.config?.showSmsRemainedCount?.let {
            if (!it) {
                binding.threadAvailableMessageCount.beGone()
                return
            }
        }
        val slotId = context?.let {
            FeeInfoUtils.getCurrentSimSlotId(
                context = it,
                availableSIMCards = availableSIMCards,
                currentSIMCardIndex = currentSIMCardIndex
            )
        }
        Log.d("SUN_DEBUG", "updateAvailableMessageCountForCurrentSim: resolved slotId=$slotId")
        if (slotId == null) {
            Log.d("SUN_DEBUG", "updateAvailableMessageCountForCurrentSim: slotId is null, hiding view")
            binding.threadAvailableMessageCount.beGone()
            return
        }

        ensureBackgroundThread {
            val smsCount = activity?.let { FeeInfoUtils.getAvailableSmsCountForSlot(it, slotId) }
            Log.d("SUN_DEBUG", "updateAvailableMessageCountForCurrentSim: slotId=$slotId, smsCount=$smsCount")
            activity?.runOnUiThread {
                val countView = binding.threadAvailableMessageCount
                if (smsCount == null) {
                    countView.beGone()
                } else {
                    countView.text = getString(R.string.available_sms_count, smsCount)
                    activity?.getProperTextColor()?.let { countView.setTextColor(it) }
                    countView.beVisible()
                }
            }
        }
    }

    fun setMessageText(text: String) {
        messageText = text
        binding.expandedThreadTypeMessage.setText(text)
    }

    fun getMessageText(): String {
        return binding.expandedThreadTypeMessage.text?.toString() ?: ""
    }

    fun setOnMessageTextChangedListener(listener: (String) -> Unit) {
        onMessageTextChanged = listener
    }

    fun setOnSendMessageListener(listener: (subscriptionId: Int) -> Unit) {
        onSendMessage = listener
    }

//    fun setOnSendMessageListener(listener: () -> Unit) {
//        onSendMessage = listener
//    }

    fun setOnMinimizeListener(listener: () -> Unit) {
        onMinimize = listener
    }

    fun setupSendConfiguration(
        isSpeechToTextAvailable: Boolean = false,
        hasAddressForSend: (() -> Boolean)? = null,
        hasReadyAttachments: (() -> Boolean)? = null,
        onSpeechToText: (() -> Unit)? = null,
        resolveSubscriptionForSend: ((anchorView: View, onSubId: (Int) -> Unit) -> Unit)? = null
    ) {
        this.isSpeechToTextAvailable = isSpeechToTextAvailable
        this.hasAddressForSend = hasAddressForSend
        this.hasReadyAttachments = hasReadyAttachments
        this.onSpeechToText = onSpeechToText
        this.resolveSubscriptionForSend = resolveSubscriptionForSend
        applySendConfigurationToView()
        checkSendMessageAvailability()
    }
    private fun applySendConfigurationToView() {
        val binding = _binding ?: return

        val speechLongClick = if (isSpeechToTextAvailable) {
            View.OnLongClickListener {
                onSpeechToText?.invoke()
                true
            }
        } else {
            null
        }
        binding.expandedThreadSendMessage.setOnLongClickListener(speechLongClick)
        binding.topDetailsCompactExpanded.findViewById<ImageView>(
            R.id.expandedThreadSendMessageCompact
        )?.setOnLongClickListener(speechLongClick)

//        checkSendMessageAvailability()
    }

    fun checkSendMessageAvailability() {
        if (_binding == null) return

        val hasReadyAttachments = hasReadyAttachments?.invoke() == true
        val hasText = binding.expandedThreadTypeMessage.text?.isNotEmpty() == true
        val hasContent = hasText || hasReadyAttachments

        val requiresAddress = hasAddressForSend != null
        val hasAddress = hasAddressForSend?.invoke() ?: true
        val canSend = hasContent && hasAddress

        val newMode = when {
            canSend -> ComposeSendMode.SEND
            isSpeechToTextAvailable && (!requiresAddress || hasAddress) -> ComposeSendMode.SPEECH
            else -> ComposeSendMode.DISABLED
        }

        if (newMode != appliedComposeSendMode) {
            appliedComposeSendMode = newMode
            applyComposeSendMode(newMode)
        }

        updateSendButtonDrawable()
    }

    fun updateThreadTitle(
        threadTitle: String,
        threadSubtitle: String,
        threadTopStyle: Int,
        showContactThumbnails: Boolean,
        conversationPhotoUri: String? = null,
        conversationTitle: String? = null,
        conversationPhoneNumber: String? = null,
        isCompany: Boolean = false,
        participantsCount: Int = 1,
        /** Same semantics as [com.android.mms.models.Conversation.threadId] for list avatar rules ([BaseConversationsAdapter.bindContactAvatar]). */
        threadId: Long = 0L,
    ) {
        val textColor = requireActivity().getProperTextColor()
        val contactsHelper = SimpleContactsHelper(requireContext())
        
        when (threadTopStyle) {
            THREAD_TOP_COMPACT -> {
                binding.topDetailsCompactExpanded.beVisible()
                binding.topDetailsLargeExpanded.beGone()
                
                // Access views within the FrameLayout using findViewById
                val senderPhotoView = binding.topDetailsCompactExpanded.findViewById<ImageView>(R.id.sender_photo)
                val senderNameView = binding.topDetailsCompactExpanded.findViewById<MyTextView>(R.id.sender_name)
                val senderNumberView = binding.topDetailsCompactExpanded.findViewById<MyTextView>(R.id.sender_number)
                
                // Follow ThreadActivity setupThreadTitle() THREAD_TOP_COMPACT logic (sender_name, sender_number)
                senderPhotoView?.beVisibleIf(showContactThumbnails)
                if (threadTitle.isNotEmpty()) {
                    senderNameView?.text = threadTitle
                    senderNameView?.setTextColor(textColor)
                }
                senderNumberView?.beGoneIf(threadSubtitle.isEmpty() || threadTitle == threadSubtitle || participantsCount > 1)
                senderNumberView?.text = threadSubtitle
                senderNumberView?.setTextColor(textColor)
                
                // Load contact image
                if (showContactThumbnails && senderPhotoView != null) {
                    loadContactImage(
                        contactsHelper = contactsHelper,
                        photoUri = conversationPhotoUri,
                        imageView = senderPhotoView,
                        threadTitle = threadTitle,
                        conversationTitle = conversationTitle,
                        conversationPhoneNumber = conversationPhoneNumber,
                        isCompany = isCompany,
                        participantsCount = participantsCount
                    )
                }
            }
            THREAD_TOP_LARGE -> {
                binding.topDetailsCompactExpanded.beGone()
                binding.topDetailsLargeExpanded.beVisible()
                
                val senderPhotoView =
                    binding.topDetailsLargeExpanded.findViewById<ContactAvatarView>(R.id.sender_photo_large_expanded)
                val senderNameView = binding.topDetailsLargeExpanded.findViewById<MyTextView>(R.id.sender_name_large_expanded)
                val senderNumberView = binding.topDetailsLargeExpanded.findViewById<MyTextView>(R.id.sender_number_large_expanded)
                
                // Follow ThreadActivity setupThreadTitle() THREAD_TOP_LARGE logic (sender_name_large, sender_number_large)
//                senderPhotoView?.beVisibleIf(showContactThumbnails)
                senderPhotoView?.beGone()
                if (threadTitle.isNotEmpty()) {
                    senderNameView?.text = threadTitle
                    senderNameView?.setTextColor(textColor)
                }
                senderNumberView?.beGoneIf(threadSubtitle.isEmpty() || threadTitle == threadSubtitle || participantsCount > 1)
                senderNumberView?.text = threadSubtitle
                senderNumberView?.setTextColor(textColor)
                
                // Large avatar: same rules as main conversation list ([BaseConversationsAdapter.bindContactAvatar]).
                if (showContactThumbnails && senderPhotoView != null) {
                    val activity = requireActivity() as BaseSimpleActivity
                    senderPhotoView.bindConversationListAvatar(
                        activity = activity,
                        threadId = threadId,
                        title = conversationTitle ?: threadTitle,
                        phoneNumber = conversationPhoneNumber.orEmpty(),
                        photoUri = conversationPhotoUri.orEmpty(),
                        isGroupConversation = participantsCount > 1,
                    )
                }
            }
            else -> {
                binding.topDetailsCompactExpanded.beGone()
                binding.topDetailsLargeExpanded.beGone()
            }
        }
    }
    
    private fun loadContactImage(
        contactsHelper: SimpleContactsHelper,
        photoUri: String?,
        imageView: ImageView,
        threadTitle: String,
        conversationTitle: String?,
        conversationPhoneNumber: String?,
        isCompany: Boolean,
        participantsCount: Int
    ) {
        val title = conversationTitle ?: threadTitle
        val phoneNumber = conversationPhoneNumber ?: ""
        
        if ((title == phoneNumber || isCompany) && (photoUri.isNullOrEmpty())) {
            val drawable = if (isCompany) {
                contactsHelper.getColoredCompanyIcon(title)
            } else {
                contactsHelper.getColoredContactIcon(title)
            }
            imageView.setImageDrawable(drawable)
        } else {
            val placeholder = if (participantsCount > 1) {
                contactsHelper.getColoredGroupIcon(threadTitle)
            } else {
                null
            }
            contactsHelper.loadContactImage(photoUri ?: "", imageView, threadTitle, placeholder)
        }
    }

    private fun applyComposeSendMode(mode: ComposeSendMode) {
        val largeSendButton = binding.expandedThreadSendMessage
        val compactSendButton = binding.topDetailsCompactExpanded.findViewById<ImageView>(R.id.expandedThreadSendMessageCompact)

        largeSendButton.apply {
            when (mode) {
                ComposeSendMode.SEND -> {
                    isEnabled = true
                    isClickable = true
                    alpha = 1f
                    contentDescription = getString(R.string.sending)
                    setOnClickListener { onSendButtonClicked(this) }
                }
                ComposeSendMode.SPEECH -> {
                    isEnabled = true
                    isClickable = true
                    alpha = 1f
                    contentDescription = getString(com.goodwy.strings.R.string.voice_input)
                    setOnClickListener { onSpeechToText?.invoke() }
                }
                ComposeSendMode.DISABLED -> {
                    isEnabled = false
                    isClickable = false
                    alpha = 0.4f
                    setOnClickListener(null)
                }
            }
        }

        compactSendButton.apply {
            when (mode) {
                ComposeSendMode.SEND -> {
                    isEnabled = true
                    isClickable = true
                    alpha = 1f
                    contentDescription = getString(R.string.sending)
                    setOnClickListener { onSendButtonClicked(this) }
                }
                ComposeSendMode.SPEECH -> {
                    isEnabled = true
                    isClickable = true
                    alpha = 1f
                    contentDescription = getString(com.goodwy.strings.R.string.voice_input)
                    setOnClickListener { onSpeechToText?.invoke() }
                }
                ComposeSendMode.DISABLED -> {
                    isEnabled = false
                    isClickable = false
                    alpha = 0.4f
                    setOnClickListener(null)
                }
            }
        }
        binding.expandedThreadSendMessageWrapper.apply {
            isClickable = false
            setOnClickListener(null)
        }
        binding.topDetailsCompactExpanded.findViewById<View>(R.id.expandedThreadSendMessageWrapperCompact).apply {
            isClickable = false
            setOnClickListener(null)
        }
    }

    private fun onSendButtonClicked(anchorView: View) {
        val activity = activity ?: return
        val proceedWithSubscription = { subscriptionId: Int ->
            if (activity.config.messageSendDelay > 0 && !isCountdownActive){
                startSendMessageCountdown(subscriptionId)
            } else {
                onSendMessage?.invoke(subscriptionId)
                if (activity.config.soundOnOutGoingMessages) {
                    val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_SPACEBAR)
                }
            }
        }
        val resolver = resolveSubscriptionForSend
        if (resolver != null) {
            resolver(anchorView, proceedWithSubscription)
        } else {
            proceedWithSubscription(SmsManager.getDefaultSmsSubscriptionId())
        }
    }

    private fun updateSendButtonDrawable() {
        val activity = activity ?: return
        val sendIconTint = activity.getProperTextColor()
        binding.expandedThreadSendMessage.applyColorFilter(sendIconTint)
        binding.topDetailsCompactExpanded.findViewById<ImageView>(
            R.id.expandedThreadSendMessageCompact
        )?.applyColorFilter(sendIconTint)
    }
    
    private fun startSendMessageCountdown(subscriptionId: Int) {
        if (isCountdownActive) return
        
        val activity = requireActivity()
        val delaySeconds = activity.config.messageSendDelay
        if (delaySeconds <= 0) {
            onSendMessage?.invoke(subscriptionId)
            return
        }

        isCountdownActive = true
        
        // Handle countdown for large view
        binding.apply {
            expandedThreadSendMessage.beGone()
            threadAvailableMessageCount.beGone()

            expandedThreadSendMessageCountdown.beVisible()
            
            expandedThreadSendMessageCountdown.setOnClickListener {
                if (isCountdownActive) {
                    isCountdownActive = false
                    hideCountdown()
                    activity.toast(R.string.sending_cancelled)
                }
            }
            
            try {
                expandedThreadSendMessageCountdown.create(0, delaySeconds, CircularCountdown.TYPE_SECOND)
                    .listener(object : CircularListener {
                        override fun onTick(progress: Int) {}
                        
                        override fun onFinish(newCycle: Boolean, cycleCount: Int) {
                            isCountdownActive = false
                            hideCountdown()
                            onSendMessage?.invoke(subscriptionId)
                            if (activity.config.soundOnOutGoingMessages) {
                                val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_SPACEBAR)
                            }
                        }
                    })
                    .start()
            } catch (e: Exception) {
                isCountdownActive = false
                hideCountdown()
                onSendMessage?.invoke(subscriptionId)
            }
        }
        
        // Handle countdown for compact view
        val compactSendButton = binding.topDetailsCompactExpanded.findViewById<ImageView>(
            R.id.expandedThreadSendMessageCompact
        )
        val compactCountdown = binding.topDetailsCompactExpanded.findViewById<CircularCountdown>(
            R.id.expandedThreadSendMessageCountdownCompact
        )
        
        compactSendButton?.beGone()
        compactCountdown?.apply {
            beVisible()
            setOnClickListener {
                if (isCountdownActive) {
                    isCountdownActive = false
                    hideCountdown()
                    activity.toast(R.string.sending_cancelled)
                }
            }
            try {
                create(0, delaySeconds, CircularCountdown.TYPE_SECOND)
                    .listener(object : CircularListener {
                        override fun onTick(progress: Int) {}

                        override fun onFinish(newCycle: Boolean, cycleCount: Int) {
                            if (!isCountdownActive) return
                            isCountdownActive = false
                            hideCountdown()
                            onSendMessage?.invoke(subscriptionId)
                            if (activity.config.soundOnOutGoingMessages) {
                                val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_SPACEBAR)
                            }
                        }
                    })
                    .start()
            } catch (e: Exception) {}
        }
    }

    private fun hideCountdown() {
        binding.apply {
            try {
                expandedThreadSendMessageCountdown.stop()
            } catch (e: Exception) {}
            expandedThreadSendMessageCountdown.setOnClickListener(null)
            expandedThreadSendMessageCountdown.beGone()
            expandedThreadSendMessage.beVisible()
            threadAvailableMessageCount.beVisible()
            checkSendMessageAvailability()
        }
        
        val compactCountdown = binding.topDetailsCompactExpanded.findViewById<CircularCountdown>(
            R.id.expandedThreadSendMessageCountdownCompact
        )
        val compactSendButton = binding.topDetailsCompactExpanded.findViewById<ImageView>(
            R.id.expandedThreadSendMessageCompact
        )
        
        compactCountdown?.apply {
            try {
                stop()
            } catch (e: Exception) {}
            setOnClickListener(null)
            beGone()
        }
        compactSendButton?.beVisible()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clean up countdown if active
        if (isCountdownActive) {
            isCountdownActive = false
            try {
                _binding?.expandedThreadSendMessageCountdown?.stop()
            } catch (e: Exception) {
                // Ignore
            }
        }
        _binding = null
    }
}

