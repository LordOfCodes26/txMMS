package com.android.mms.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import com.android.mms.R
import com.android.mms.databinding.FragmentExpandedMessageBinding
import com.android.mms.extensions.getTextSizeMessage
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.NavigationIcon
import com.goodwy.commons.helpers.SimpleContactsHelper

class ExpandedMessageFragment : Fragment() {
    private var _binding: FragmentExpandedMessageBinding? = null
    private val binding get() = _binding!!
    
    private var messageText: String = ""
    private var onMessageTextChanged: ((String) -> Unit)? = null
    private var onSendMessage: (() -> Unit)? = null
    private var onMinimize: (() -> Unit)? = null

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

        // Setup toolbar manually since setupTopAppBar is an activity method
        val contrastColor = topBarColor.getContrastColor()
        val navigationIconDrawable = ResourcesCompat.getDrawable(
            resources,
            com.goodwy.commons.R.drawable.ic_chevron_left_vector,
            activity.theme
        )?.apply {
            applyColorFilter(contrastColor)
        }
        binding.expandedMessageToolbar.navigationIcon = navigationIconDrawable
        binding.expandedMessageToolbar.setNavigationContentDescription(com.goodwy.commons.R.string.back)
        
        // Update toolbar colors
        binding.expandedMessageToolbar.setBackgroundColor(topBarColor)
        binding.expandedMessageToolbar.setTitleTextColor(contrastColor)
        binding.expandedMessageToolbar.setSubtitleTextColor(contrastColor)

        // Setup minimize icon (navigation icon)
        binding.expandedMessageToolbar.setNavigationOnClickListener {
            onMinimize?.invoke()
        }

        // Update navigation icon to minimize icon (chevron down)
        val minimizeIcon = ResourcesCompat.getDrawable(
            resources,
            com.goodwy.commons.R.drawable.ic_chevron_down_vector,
            activity.theme
        )?.apply {
            applyColorFilter(topBarColor.getContrastColor())
        }
        binding.expandedMessageToolbar.navigationIcon = minimizeIcon
        binding.expandedMessageToolbar.setNavigationContentDescription(getString(R.string.minimize_message))

        // Setup thread title (same as ThreadActivity)
        setupThreadTitle()

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
                }
            })
            
            // Request focus and show keyboard
            requestFocus()
            activity.showKeyboard(this)
        }

        // Setup send button in toolbar header
        binding.expandedThreadSendMessage.apply {
            backgroundTintList = properPrimaryColor.getColorStateList()
            applyColorFilter(properPrimaryColor.getContrastColor())
            setOnClickListener {
                onSendMessage?.invoke()
            }
        }

        // Update send button availability
        updateSendButtonAvailability()
    }

    private fun setupThreadTitle() {
        // Thread title will be set by the activity through interface
        // For now, just hide both views - activity will update them
        binding.topDetailsCompactExpanded.root.beGone()
        binding.topDetailsLargeExpanded.beGone()
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
                binding.topDetailsCompactExpanded.root.beVisible()
                binding.topDetailsLargeExpanded.beGone()
                binding.topDetailsCompactExpanded.apply {
                    senderPhoto.beVisibleIf(showContactThumbnails)
                    if (threadTitle.isNotEmpty()) {
                        senderName.text = threadTitle
                        senderName.setTextColor(textColor)
                    }
                    senderNumber.beGoneIf(threadTitle == threadSubtitle || participantsCount > 1 || threadSubtitle.isEmpty())
                    senderNumber.text = threadSubtitle
                    senderNumber.setTextColor(textColor)
                    
                    // Load contact image
                    if (showContactThumbnails) {
                        loadContactImage(
                            contactsHelper = contactsHelper,
                            photoUri = conversationPhotoUri,
                            imageView = senderPhoto,
                            threadTitle = threadTitle,
                            conversationTitle = conversationTitle,
                            conversationPhoneNumber = conversationPhoneNumber,
                            isCompany = isCompany,
                            participantsCount = participantsCount
                        )
                    }
                }
            }
            com.android.mms.helpers.THREAD_TOP_LARGE -> {
                binding.topDetailsCompactExpanded.root.beGone()
                binding.topDetailsLargeExpanded.beVisible()
                // Use findViewById since binding might not have these properties yet
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
                binding.topDetailsCompactExpanded.root.beGone()
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
        binding.expandedThreadSendMessage.apply {
            isEnabled = hasText
            isClickable = hasText
            alpha = if (hasText) 1f else 0.4f
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

