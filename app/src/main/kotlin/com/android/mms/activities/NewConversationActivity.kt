package com.android.mms.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionInfo
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.reddit.indicatorfastscroll.FastScrollItemIndicator
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.SimpleContact
import com.android.mms.R
import com.android.mms.adapters.ContactsAdapter
import com.android.mms.databinding.ActivityNewConversationBinding
import com.android.mms.databinding.ItemSuggestedContactBinding
import com.android.mms.extensions.*
import com.android.mms.helpers.*
import com.android.mms.messaging.isShortCodeWithLetters
import com.android.mms.messaging.sendMessageCompat
import com.android.mms.models.Attachment
import com.android.mms.models.SIMCard
import com.android.mms.helpers.MessageHolderHelper
import java.net.URLDecoder
import java.util.Locale
import java.util.Objects

class NewConversationActivity : SimpleActivity() {
    private var allContacts = ArrayList<SimpleContact>()
    private var privateContacts = ArrayList<SimpleContact>()
    private var isSpeechToTextAvailable = false
    private var isAttachmentPickerVisible = false
    private var messageHolderHelper: MessageHolderHelper? = null

    private val binding by viewBinding(ActivityNewConversationBinding::inflate)
    
    companion object {
        private const val PICK_SAVE_FILE_INTENT = 1008
        private const val PICK_SAVE_DIR_INTENT = 1009
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        title = getString(R.string.new_conversation)
        updateTextColors(binding.newConversationHolder)

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.contactsList, binding.messageHolder.root))
//        setupMaterialScrollListener(
//            scrollingView = binding.contactsList,
//            topAppBar = binding.newConversationAppbar
//        )

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        binding.newConversationAddress.requestEditTextFocus()
        binding.newConversationAddress.hint = getString(R.string.add_contact_or_number)

        // READ_CONTACTS permission is not mandatory, but without it we won't be able to show any suggestions during typing
        handlePermission(PERMISSION_READ_CONTACTS) {
            initContacts()
        }
    }

    override fun onResume() {
        super.onResume()
        val getProperPrimaryColor = getProperPrimaryColor()

        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        setupTopAppBar(binding.newConversationAppbar, NavigationIcon.Arrow, topBarColor = backgroundColor)
        binding.newConversationHolder.setBackgroundColor(backgroundColor)

        binding.noContactsPlaceholder2.setTextColor(getProperPrimaryColor)
        binding.noContactsPlaceholder2.underlineText()
        binding.suggestionsLabel.setTextColor(getProperPrimaryColor)

        binding.contactsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                hideKeyboard()
            }
        })
        
        setupMessageHolder()
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
            onExpandMessage = null
        )
        
        messageHolderHelper?.setup(isSpeechToTextAvailable)
        
        binding.messageHolder.apply {
            threadAddAttachmentHolder.setOnClickListener {
                if (attachmentPickerHolder.isVisible()) {
                    isAttachmentPickerVisible = false
                    messageHolderHelper?.hideAttachmentPicker()
                } else {
                    isAttachmentPickerVisible = true
                    messageHolderHelper?.showAttachmentPicker()
                }
            }
            
            messageHolderHelper?.setupAttachmentPicker(
                onChoosePhoto = { launchGetContentIntent(arrayOf("image/*", "video/*"), MessageHolderHelper.PICK_PHOTO_INTENT) },
                onChooseVideo = { launchGetContentIntent(arrayOf("video/*"), MessageHolderHelper.PICK_VIDEO_INTENT) },
                onTakePhoto = { launchCapturePhotoIntent() },
                onRecordVideo = { launchCaptureVideoIntent() },
                onRecordAudio = { launchCaptureAudioIntent() },
                onPickFile = { launchGetContentIntent(arrayOf("*/*"), MessageHolderHelper.PICK_DOCUMENT_INTENT) },
                onPickContact = { launchPickContactIntent() },
                onScheduleMessage = null
            )
            
            messageHolderHelper?.hideAttachmentPicker()
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
        } else {
            messageHolderHelper?.handleActivityResult(requestCode, resultCode, resultData)
            
            if (requestCode == MessageHolderHelper.PICK_CONTACT_INTENT && resultData?.data != null) {
                addContactAttachment(resultData.data!!)
            }
        }
    }

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
        binding.newConversationAddress.getEditText().setBackgroundResource(com.goodwy.commons.R.drawable.search_bg)
        binding.newConversationAddress.getEditText().backgroundTintList = ColorStateList.valueOf(surfaceColor)

        binding.newConversationAddress.setOnTextChangedListener { searchString ->
            val filteredContacts = ArrayList<SimpleContact>()
            allContacts.forEach { contact ->
                if (contact.phoneNumbers.any { it.normalizedNumber.contains(searchString, true) } ||
                    contact.name.contains(searchString, true) ||
                    contact.name.contains(searchString.normalizeString(), true) ||
                    contact.name.normalizeString().contains(searchString, true)) {
                    filteredContacts.add(contact)
                }
            }

            filteredContacts.sortWith(compareBy { !it.name.startsWith(searchString, true) })
            setupAdapter(filteredContacts)
        }

        binding.newConversationAddress.setSpeechToTextButtonVisible(isSpeechToTextAvailable)
        binding.newConversationAddress.setSpeechToTextButtonClickListener { speechToText() }
        
        binding.newConversationAddress.setOnConfirmListener {
            val chips = binding.newConversationAddress.allChips
            val currentText = binding.newConversationAddress.currentText.trim()
            
            val allNumbers = mutableListOf<String>()
            
            // Add chips (these are already validated when added)
            chips.forEach { chip ->
                if (chip.isNotEmpty()) {
                    allNumbers.add(chip)
                }
            }
            
            // If currentText is not empty and no chips, treat it as a number
            if (currentText.isNotEmpty() && chips.isEmpty()) {
                // Validate short codes with letters
                if (isShortCodeWithLetters(currentText)) {
                    toast(R.string.invalid_short_code, length = Toast.LENGTH_LONG)
                    return@setOnConfirmListener
                }
                // Treat as number if it looks like one (contains digits)
                if (currentText.any { it.isDigit() }) {
                    allNumbers.add(currentText)
                    binding.newConversationAddress.addChip(currentText)
                    binding.newConversationAddress.clearText()
                }
            }
            
            // Focus on message input if recipients are selected
            if (allNumbers.isNotEmpty()) {
                binding.messageHolder.threadTypeMessage.requestFocus()
                showKeyboard(binding.messageHolder.threadTypeMessage)
            }
        }

        binding.noContactsPlaceholder2.setOnClickListener {
            handlePermission(PERMISSION_READ_CONTACTS) {
                if (it) {
                    fetchContacts()
                }
            }
        }

        binding.contactsLetterFastscroller.textColor = properTextColor.getColorStateList()
        binding.contactsLetterFastscroller.pressedTextColor = properAccentColor
        binding.contactsLetterFastscrollerThumb.setupWithFastScroller(binding.contactsLetterFastscroller)
        binding.contactsLetterFastscrollerThumb.textColor = properAccentColor.getContrastColor()
        binding.contactsLetterFastscrollerThumb.thumbColor = properAccentColor.getColorStateList()
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

    private fun fetchContacts() {
        fillSuggestedContacts {
//            SimpleContactsHelper(this).getAvailableContacts(false) {
                allContacts = it

                if (privateContacts.isNotEmpty()) {
                    allContacts.addAll(privateContacts)
                    allContacts.sort()
                }

                runOnUiThread {
                    setupAdapter(allContacts)
                }
//            }
        }
    }

    private fun setupAdapter(contacts: ArrayList<SimpleContact>) {
        val hasContacts = contacts.isNotEmpty()
        binding.contactsList.beVisibleIf(hasContacts)
        binding.noContactsPlaceholder.beVisibleIf(!hasContacts)
        binding.noContactsPlaceholder2.beVisibleIf(
            !hasContacts && !hasPermission(
                PERMISSION_READ_CONTACTS
            )
        )

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
            ContactsAdapter(this, contacts, binding.contactsList) {
                hideKeyboard()
                val contact = it as SimpleContact
                maybeShowNumberPickerDialog(contact.phoneNumbers) { number ->
                    // Add as chip instead of launching immediately
                    binding.newConversationAddress.addChip(number.normalizedNumber)
                    binding.newConversationAddress.clearText()
                }
            }.apply {
                binding.contactsList.adapter = this
            }

            if (areSystemAnimationsEnabled) {
                binding.contactsList.scheduleLayoutAnimation()
            }
        } else {
            (currAdapter as ContactsAdapter).updateContacts(contacts)
        }

        setupLetterFastscroller(contacts)
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
                    if (suggestions.isEmpty()) {
                        binding.suggestionsLabel.beGone()
                        binding.suggestionsScrollview.beGone()
                    } else {
                        //binding.suggestionsLabel.beVisible()
                        binding.suggestionsScrollview.beVisible()
                        suggestions.forEach { contact ->
                            ItemSuggestedContactBinding.inflate(layoutInflater).apply {
                                suggestedContactName.text = contact.name
                                suggestedContactName.setTextColor(getProperTextColor())

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
                                        // Add as chip instead of launching immediately
                                        binding.newConversationAddress.addChip(contact.phoneNumbers.first().normalizedNumber)
                                        binding.newConversationAddress.clearText()
                                    }
                                }
                            }
                        }
                    }
                    callback(it)
                }
            }
        }
    }

    private fun setupLetterFastscroller(contacts: ArrayList<SimpleContact>) {
        try {
            //Decrease the font size based on the number of letters in the letter scroller
            val allNotEmpty = contacts.filter { it.name.isNotEmpty() }
            val all = allNotEmpty.map { it.name.substring(0, 1) }
            val unique: Set<String> = HashSet(all)
            val sizeUnique = unique.size
            if (isHighScreenSize()) {
                if (sizeUnique > 48) binding.contactsLetterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTooTiny
                else if (sizeUnique > 37) binding.contactsLetterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTiny
                else binding.contactsLetterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleSmall
            } else {
                if (sizeUnique > 36) binding.contactsLetterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTooTiny
                else if (sizeUnique > 30) binding.contactsLetterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTiny
                else binding.contactsLetterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleSmall
            }
        } catch (_: Exception) { }

        binding.contactsLetterFastscroller.setupWithRecyclerView(binding.contactsList, { position ->
            try {
                val name = contacts[position].name
                val emoji = name.take(2)
                val character = if (emoji.isEmoji()) emoji else if (name.isNotEmpty()) name.substring(0, 1) else ""
                FastScrollItemIndicator.Text(
                    character.uppercase(Locale.getDefault()).normalizeString()
                )
            } catch (_: Exception) {
                FastScrollItemIndicator.Text("")
            }
        })
    }

    private fun isHighScreenSize(): Boolean {
        return when (resources.configuration.screenLayout
            and Configuration.SCREENLAYOUT_LONG_MASK) {
            Configuration.SCREENLAYOUT_LONG_NO -> false
            else -> true
        }
    }

    
    private fun sendMessageAndNavigate(text: String, subscriptionId: Int?, attachments: List<Attachment>) {
        hideKeyboard()
        
        val chips = binding.newConversationAddress.allChips
        val allNumbers = mutableListOf<String>()
        chips.forEach { chip ->
            if (chip.isNotEmpty()) {
                allNumbers.add(chip)
            }
        }
        
        if (allNumbers.isEmpty()) {
            toast(R.string.empty_destination_address, length = Toast.LENGTH_SHORT)
            return
        }
        
        // Get subscription ID for the numbers if not provided
        val finalSubscriptionId = subscriptionId ?: messageHolderHelper?.getSubscriptionIdForNumbers(allNumbers)
        
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
            
            // Clear message and attachments
            messageHolderHelper?.clearMessage()
            
            // Delete any draft for this thread to prevent it from showing in ThreadActivity
            ensureBackgroundThread {
                deleteSmsDraft(threadId)
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
            launchActivityForResult(this, requestCode)
        }
    }
    
    private fun launchPickContactIntent() {
        Intent(Intent.ACTION_PICK).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            launchActivityForResult(this, MessageHolderHelper.PICK_CONTACT_INTENT)
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

    private fun launchThreadActivity(phoneNumber: String, name: String, body: String = "", photoUri: String = "") {
        hideKeyboard()
//        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra("sms_body") ?: ""
        val numbers = phoneNumber.split(";").toSet()
        val number = if (numbers.size == 1) phoneNumber else Gson().toJson(numbers)
        Intent(this, ThreadActivity::class.java).apply {
            putExtra(THREAD_ID, getThreadId(numbers))
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
}
