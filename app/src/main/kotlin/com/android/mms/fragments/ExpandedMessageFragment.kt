package com.android.mms.fragments

import android.annotation.SuppressLint
import android.media.AudioManager
import android.os.Bundle
import android.telephony.SmsMessage
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.android.mms.R
import com.android.mms.databinding.FragmentExpandedMessageBinding
import com.android.mms.extensions.config
import com.android.mms.extensions.getTextSizeMessage
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.NavigationIcon
import com.goodwy.commons.helpers.SimpleContactsHelper
import douglasspgyn.com.github.circularcountdown.CircularCountdown
import douglasspgyn.com.github.circularcountdown.listener.CircularListener

class ExpandedMessageFragment : Fragment() {
    private var _binding: FragmentExpandedMessageBinding? = null
    private val binding get() = _binding!!
    
    private var messageText: String = ""
    private var onMessageTextChanged: ((String) -> Unit)? = null
    private var onSendMessage: (() -> Unit)? = null
    private var onMinimize: (() -> Unit)? = null
    private var isCountdownActive = false

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

    private fun setupViews() {
        val activity = requireActivity()
        val textColor = activity.getProperTextColor()
        val topBarColor = activity.getColoredMaterialStatusBarColor()
        val properPrimaryColor = activity.getProperPrimaryColor()

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
        
        // Set background color for topDetailsLargeExpanded
        binding.topDetailsLargeExpanded.setBackgroundColor(topBarColor)
        
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

        // Setup character counters
        val shouldShowCounter = messageText.isNotEmpty() && activity.config.showCharacterCounter
        binding.expandedThreadCharacterCounter.apply {
            beVisibleIf(shouldShowCounter)
            backgroundTintList = activity.getProperBackgroundColor().getColorStateList()
        }
        binding.topDetailsCompactExpanded.findViewById<com.goodwy.commons.views.MyTextView>(
            com.android.mms.R.id.expandedThreadCharacterCounterCompact
        )?.apply {
            beVisibleIf(shouldShowCounter)
            backgroundTintList = activity.getProperBackgroundColor().getColorStateList()
        }
        
        // Setup text editor
        binding.expandedThreadTypeMessage.apply {
            setText(messageText)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, activity.getTextSizeMessage())
            setTextColor(textColor)
            setHintTextColor(textColor.adjustAlpha(0.6f))
            
            // Add text change listener
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    onMessageTextChanged?.invoke(s?.toString() ?: "")
                    updateSendButtonAvailability()
                    
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

        // Setup send button wrappers (for both large and compact views)
        binding.expandedThreadSendMessage.apply {
            backgroundTintList = properPrimaryColor.getColorStateList()
            applyColorFilter(properPrimaryColor.getContrastColor())
        }
        
        binding.topDetailsCompactExpanded.findViewById<android.widget.ImageView>(
            com.android.mms.R.id.expandedThreadSendMessageCompact
        )?.apply {
            backgroundTintList = properPrimaryColor.getColorStateList()
            applyColorFilter(properPrimaryColor.getContrastColor())
        }
        
        // Initialize countdown views (hidden by default)
        binding.expandedThreadSendMessageCountdown.beGone()
        binding.topDetailsCompactExpanded.findViewById<douglasspgyn.com.github.circularcountdown.CircularCountdown>(
            com.android.mms.R.id.expandedThreadSendMessageCountdownCompact
        )?.beGone()
        
        // Setup send button click handlers
        val sendClickListener = {
            if (activity.config.messageSendDelay > 0 && !isCountdownActive) {
                startSendMessageCountdown()
            } else {
                onSendMessage?.invoke()
                if (activity.config.soundOnOutGoingMessages) {
                    val audioManager = activity.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
                    audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_SPACEBAR)
                }
            }
        }
        
        binding.expandedThreadSendMessageWrapper.apply {
            isClickable = false
            setOnClickListener { sendClickListener() }
        }
        
        binding.topDetailsCompactExpanded.findViewById<android.widget.LinearLayout>(
            com.android.mms.R.id.expandedThreadSendMessageWrapperCompact
        )?.apply {
            isClickable = false
            setOnClickListener { sendClickListener() }
        }

        // Update send button availability
        updateSendButtonAvailability()
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

    fun setOnSendMessageListener(listener: () -> Unit) {
        onSendMessage = listener
    }

    fun setOnMinimizeListener(listener: () -> Unit) {
        onMinimize = listener
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
        participantsCount: Int = 1
    ) {
        val textColor = requireActivity().getProperTextColor()
        val contactsHelper = SimpleContactsHelper(requireContext())
        
        when (threadTopStyle) {
            com.android.mms.helpers.THREAD_TOP_COMPACT -> {
                binding.topDetailsCompactExpanded.beVisible()
                binding.topDetailsLargeExpanded.beGone()
                
                // Access views within the FrameLayout using findViewById
                val senderPhotoView = binding.topDetailsCompactExpanded.findViewById<android.widget.ImageView>(R.id.sender_photo)
                val senderNameView = binding.topDetailsCompactExpanded.findViewById<com.goodwy.commons.views.MyTextView>(R.id.sender_name)
                val senderNumberView = binding.topDetailsCompactExpanded.findViewById<com.goodwy.commons.views.MyTextView>(R.id.sender_number)
                
                senderPhotoView?.beVisibleIf(showContactThumbnails)
                if (threadTitle.isNotEmpty()) {
                    senderNameView?.text = threadTitle
                    senderNameView?.setTextColor(textColor)
                }
                senderNumberView?.beGoneIf(threadTitle == threadSubtitle || participantsCount > 1 || threadSubtitle.isEmpty())
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
            com.android.mms.helpers.THREAD_TOP_LARGE -> {
                binding.topDetailsCompactExpanded.beGone()
                binding.topDetailsLargeExpanded.beVisible()
                
                val senderPhotoView = binding.topDetailsLargeExpanded.findViewById<android.widget.ImageView>(R.id.sender_photo_large_expanded)
                val senderNameView = binding.topDetailsLargeExpanded.findViewById<com.goodwy.commons.views.MyTextView>(R.id.sender_name_large_expanded)
                val senderNumberView = binding.topDetailsLargeExpanded.findViewById<com.goodwy.commons.views.MyTextView>(R.id.sender_number_large_expanded)
                
                senderPhotoView?.beVisibleIf(showContactThumbnails)
                if (threadTitle.isNotEmpty()) {
                    senderNameView?.text = threadTitle
                    senderNameView?.setTextColor(textColor)
                }
                senderNumberView?.beGoneIf(threadTitle == threadSubtitle || participantsCount > 1 || threadSubtitle.isEmpty())
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
            else -> {
                binding.topDetailsCompactExpanded.beGone()
                binding.topDetailsLargeExpanded.beGone()
            }
        }
    }
    
    private fun loadContactImage(
        contactsHelper: SimpleContactsHelper,
        photoUri: String?,
        imageView: android.widget.ImageView,
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

    private fun updateSendButtonAvailability() {
        val hasText = binding.expandedThreadTypeMessage.text?.isNotEmpty() == true
        binding.expandedThreadSendMessageWrapper.apply {
            isEnabled = hasText
            isClickable = hasText
            alpha = if (hasText) 1f else 0.4f
        }
        binding.topDetailsCompactExpanded.findViewById<android.widget.LinearLayout>(
            com.android.mms.R.id.expandedThreadSendMessageWrapperCompact
        )?.apply {
            isEnabled = hasText
            isClickable = hasText
            alpha = if (hasText) 1f else 0.4f
        }
    }
    
    private fun startSendMessageCountdown() {
        if (isCountdownActive) return
        
        val activity = requireActivity()
        val delaySeconds = activity.config.messageSendDelay
        if (delaySeconds <= 0) {
            onSendMessage?.invoke()
            return
        }

        isCountdownActive = true
        
        // Handle countdown for large view
        binding.apply {
            expandedThreadSendMessage.beGone()
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
                            onSendMessage?.invoke()
                            if (activity.config.soundOnOutGoingMessages) {
                                val audioManager = activity.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
                                audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_SPACEBAR)
                            }
                        }
                    })
                    .start()
            } catch (e: Exception) {
                isCountdownActive = false
                hideCountdown()
                onSendMessage?.invoke()
            }
        }
        
        // Handle countdown for compact view
        val compactSendButton = binding.topDetailsCompactExpanded.findViewById<android.widget.ImageView>(
            com.android.mms.R.id.expandedThreadSendMessageCompact
        )
        val compactCountdown = binding.topDetailsCompactExpanded.findViewById<douglasspgyn.com.github.circularcountdown.CircularCountdown>(
            com.android.mms.R.id.expandedThreadSendMessageCountdownCompact
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
                create(0, delaySeconds, CircularCountdown.TYPE_SECOND).start()
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
        }
        
        val compactCountdown = binding.topDetailsCompactExpanded.findViewById<douglasspgyn.com.github.circularcountdown.CircularCountdown>(
            com.android.mms.R.id.expandedThreadSendMessageCountdownCompact
        )
        val compactSendButton = binding.topDetailsCompactExpanded.findViewById<android.widget.ImageView>(
            com.android.mms.R.id.expandedThreadSendMessageCompact
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

