package com.android.mms.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Spannable
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.util.TypedValue
import android.view.*
import android.view.ScaleGestureDetector
import android.widget.LinearLayout
import com.goodwy.commons.views.BlurPopupMenu
import android.widget.RelativeLayout
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.core.net.toUri
import androidx.core.text.layoutDirection
import androidx.core.view.ViewCompat
import androidx.core.view.get
import androidx.core.view.size
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.DiffUtil
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.load.resource.bitmap.FitCenter
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.goodwy.commons.adapters.MyRecyclerViewListAdapter
import com.goodwy.commons.dialogs.ConfirmationDialog
import com.goodwy.commons.extensions.applyColorFilter
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.copyToClipboard
import com.goodwy.commons.extensions.formatDateOrTime
import com.goodwy.commons.extensions.getContrastColor
import com.goodwy.commons.extensions.getLetterBackgroundColors
import com.goodwy.commons.extensions.getPopupMenuTheme
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.getTextSize
import com.goodwy.commons.extensions.getTextSizeSmall
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isRTLLayout
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.launchInternetSearch
import com.goodwy.commons.extensions.launchSendSMSIntent
import com.goodwy.commons.extensions.shareTextIntent
import com.goodwy.commons.extensions.showErrorToast
import com.goodwy.commons.extensions.usableScreenSize
import com.goodwy.commons.helpers.TEXT_ALIGNMENT_ALONG_EDGES
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.views.MyRecyclerView
import com.android.mms.R
import com.android.mms.activities.NewConversationActivity
import com.android.mms.activities.SimpleActivity
import com.android.mms.activities.ThreadActivity
import com.android.mms.activities.VCardViewerActivity
import com.android.mms.databinding.ItemAttachmentDocumentBinding
import com.android.mms.databinding.ItemAttachmentImageBinding
import com.android.mms.databinding.ItemAttachmentVcardBinding
import com.android.mms.databinding.ItemMessageBinding
import com.android.mms.databinding.ItemThreadDateTimeBinding
import com.android.mms.databinding.ItemThreadErrorBinding
import com.android.mms.databinding.ItemThreadSendingBinding
import com.android.mms.databinding.ItemThreadSuccessBinding
import com.android.mms.dialogs.DeleteConfirmationDialog
import com.android.mms.dialogs.MessageDetailsDialog
import com.android.mms.dialogs.SelectTextDialog
import com.android.mms.extensions.config
import com.android.mms.extensions.getContactFromAddress
import com.android.mms.extensions.getListNumbersFromText
import com.android.mms.extensions.getTextSizeMessage
import com.android.mms.extensions.isImageMimeType
import com.android.mms.extensions.isVCardMimeType
import com.android.mms.extensions.isVideoMimeType
import com.android.mms.extensions.launchViewIntent
import com.android.mms.extensions.setPaddingBubble
import com.android.mms.extensions.startContactDetailsIntentRecommendation
import com.android.mms.extensions.subscriptionManagerCompat
import com.android.mms.helpers.ACTION_COPY_CODE
import com.android.mms.helpers.ACTION_COPY_MESSAGE
import com.android.mms.helpers.ACTION_NOTHING
import com.android.mms.helpers.ACTION_SELECT_TEXT
import com.android.mms.helpers.BUBBLE_STYLE_IOS
import com.android.mms.helpers.BUBBLE_STYLE_IOS_NEW
import com.android.mms.helpers.BUBBLE_STYLE_ROUNDED
import com.android.mms.helpers.BubbleDrawableOption
import com.android.mms.helpers.EXTRA_VCARD_URI
import com.android.mms.helpers.THREAD_DATE_TIME
import com.android.mms.helpers.THREAD_RECEIVED_MESSAGE
import com.android.mms.helpers.THREAD_SENT_MESSAGE
import com.android.mms.helpers.THREAD_SENT_MESSAGE_ERROR
import com.android.mms.helpers.THREAD_SENT_MESSAGE_SENDING
import com.android.mms.helpers.THREAD_SENT_MESSAGE_SENT
import com.android.mms.helpers.getBubbleDrawableOption
import com.android.mms.helpers.generateStableId
import com.android.mms.helpers.setupDocumentPreview
import com.android.mms.helpers.setupVCardPreview
import com.android.mms.models.Attachment
import com.android.mms.models.Message
import com.android.mms.models.ThreadItem
import com.android.mms.models.ThreadItem.ThreadDateTime
import com.android.mms.models.ThreadItem.ThreadError
import com.android.mms.models.ThreadItem.ThreadSending
import com.android.mms.models.ThreadItem.ThreadSent
import java.util.Locale
import kotlin.math.abs

class ThreadAdapter(
    activity: SimpleActivity,
    recyclerView: MyRecyclerView,
    itemClick: (Any) -> Unit,
    val isRecycleBin: Boolean,
    val isGroupChat: Boolean,
    val deleteMessages: (messages: List<Message>, toRecycleBin: Boolean, fromRecycleBin: Boolean, isPopupMenu: Boolean) -> Unit
) : MyRecyclerViewListAdapter<ThreadItem>(activity, recyclerView, ThreadItemDiffCallback(), itemClick) {
    private var fontSize = activity.getTextSize()
    private var fontSizeSmall = activity.getTextSizeSmall()
    private var fontSizeMessage = activity.getTextSizeMessage()
    private var fontSizeMessageMultiplier = activity.config.fontSizeMessageMultiplier
    private val minFontSizeMultiplier = 0.5f
    private val maxFontSizeMultiplier = 3.0f

    @SuppressLint("MissingPermission")
    private val hasMultipleSIMCards = (activity.subscriptionManagerCompat().activeSubscriptionInfoList?.size ?: 0) > 1
    private val maxChatBubbleWidth = (activity.usableScreenSize.x * 0.8f).toInt()
    
    // Shared scale gesture detector for pinch-to-zoom
    private val scaleGestureDetector = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val newMultiplier = (fontSizeMessageMultiplier * scaleFactor).coerceIn(minFontSizeMultiplier, maxFontSizeMultiplier)
            
            if (kotlin.math.abs(newMultiplier - fontSizeMessageMultiplier) > 0.01f) {
                fontSizeMessageMultiplier = newMultiplier
                activity.config.fontSizeMessageMultiplier = fontSizeMessageMultiplier
                
                // Update all visible message bubbles
                notifyItemRangeChanged(0, itemCount)
            }
            return true
        }
    })

    companion object {
        private const val MAX_MEDIA_HEIGHT_RATIO = 3
        private const val SIM_BITS = 21
        private const val SIM_MASK = (1L shl SIM_BITS) - 1
    }

    @DrawableRes
    private fun getCompactBubbleRes(@DrawableRes baseRes: Int): Int {
        val baseName = runCatching { resources.getResourceEntryName(baseRes) }.getOrNull() ?: return baseRes
        val compactName = when {
            baseName.startsWith("bubble_incoming_") -> baseName.replaceFirst("bubble_incoming_", "bubble_incoming_compact_")
            baseName.startsWith("bubble_outgoing_") -> baseName.replaceFirst("bubble_outgoing_", "bubble_outgoing_compact_")
            else -> return baseRes
        }
        val compactRes = resources.getIdentifier(compactName, "drawable", activity.packageName)
        return if (compactRes != 0) compactRes else baseRes
    }

    init {
        setupDragListener(true)
        setHasStableIds(true)
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    override fun getActionMenuId() = R.menu.cab_thread

    override fun prepareActionMode(menu: Menu) {
        val isOneItemSelected = isOneItemSelected()
        val selectedItem = getSelectedItems().firstOrNull() as? Message
        val hasText = selectedItem?.body != null && selectedItem.body != ""
        val selectedMessages = getSelectedItems().filterIsInstance<Message>()
        val hasAnyText = selectedMessages.any { it.body.isNotBlank() }
        val showSaveAs = getSelectedItems().all {
            it is Message && (it.attachment?.attachments?.size ?: 0) > 0
        } && getSelectedAttachments().isNotEmpty()

        menu.apply {
            findItem(R.id.cab_copy_to_clipboard).isVisible = isOneItemSelected && hasText
            findItem(R.id.cab_save_as).isVisible = showSaveAs
            findItem(R.id.cab_share).isVisible = isOneItemSelected && hasText
            findItem(R.id.cab_forward_message).isVisible = selectedKeys.isNotEmpty() && hasAnyText
            findItem(R.id.cab_select_text).isVisible = isOneItemSelected && hasText
            findItem(R.id.cab_properties).isVisible = isOneItemSelected
            findItem(R.id.cab_restore).isVisible = isRecycleBin
        }
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_copy_to_clipboard -> copyToClipboard()
            R.id.cab_save_as -> saveAs()
            R.id.cab_share -> shareText()
            R.id.cab_forward_message -> forwardMessage()
            R.id.cab_select_text -> selectText()
            R.id.cab_delete -> askConfirmDelete()
            R.id.cab_restore -> askConfirmRestore()
            R.id.cab_select_all -> selectAll()
            R.id.cab_properties -> showMessageDetails()
        }
    }

    override fun getSelectableItemCount() = currentList.filterIsInstance<Message>().size

    override fun getIsItemSelectable(position: Int) = !isThreadDateTime(position)

    override fun getItemSelectionKey(position: Int): Int? {
        return (currentList.getOrNull(position) as? Message)?.getSelectionKey()
    }

    override fun getItemKeyPosition(key: Int): Int {
        return currentList.indexOfFirst { (it as? Message)?.getSelectionKey() == key }
    }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = when (viewType) {
            THREAD_DATE_TIME -> ItemThreadDateTimeBinding.inflate(layoutInflater, parent, false)
            THREAD_SENT_MESSAGE_ERROR -> ItemThreadErrorBinding.inflate(layoutInflater, parent, false)
            THREAD_SENT_MESSAGE_SENT -> ItemThreadSuccessBinding.inflate(layoutInflater, parent, false)
            THREAD_SENT_MESSAGE_SENDING -> ItemThreadSendingBinding.inflate(layoutInflater, parent, false)
            else -> ItemMessageBinding.inflate(layoutInflater, parent, false)
        }

        return ThreadViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val isClickable = item is ThreadError || item is Message
        val isLongClickable = item is Message
        holder.bindView(item, isClickable, isLongClickable) { itemView, _ ->
            when (item) {
                is ThreadDateTime -> setupDateTime(itemView, item)
                is ThreadError -> setupThreadError(itemView)
                is ThreadSent -> setupThreadSuccess(itemView, item.delivered)
                is ThreadSending -> setupThreadSending(itemView)
                is Message -> setupView(holder, itemView, item)
            }
        }
        bindViewHolder(holder)
    }

    override fun getItemId(position: Int): Long {
        return when (val item = getItem(position)) {
            is Message -> item.getStableId()
            is ThreadDateTime -> {
                val sim = (item.simID.hashCode().toLong() and SIM_MASK)
                val key = (item.date.toLong() shl SIM_BITS) or sim
                generateStableId(THREAD_DATE_TIME, key)
            }
            is ThreadError -> generateStableId(THREAD_SENT_MESSAGE_ERROR, item.messageId)
            is ThreadSending -> generateStableId(THREAD_SENT_MESSAGE_SENDING, item.messageId)
            is ThreadSent -> generateStableId(THREAD_SENT_MESSAGE_SENT, item.messageId)
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (val item = getItem(position)) {
            is ThreadDateTime -> THREAD_DATE_TIME
            is ThreadError -> THREAD_SENT_MESSAGE_ERROR
            is ThreadSent -> THREAD_SENT_MESSAGE_SENT
            is ThreadSending -> THREAD_SENT_MESSAGE_SENDING
            is Message -> if (item.isReceivedMessage()) THREAD_RECEIVED_MESSAGE else THREAD_SENT_MESSAGE
        }
    }

    private fun copyToClipboard() {
        val firstItem = getSelectedItems().firstOrNull() as? Message ?: return
        activity.copyToClipboard(firstItem.body)
    }

    private fun getSelectedAttachments(): List<Attachment> {
        val selectedMessages = getSelectedItems().filterIsInstance<Message>()
        return selectedMessages.flatMap { it.attachment?.attachments.orEmpty() }
    }

    private fun saveAs() {
        val attachments = getSelectedAttachments()
        if (attachments.isNotEmpty()) {
            (activity as ThreadActivity).saveMMS(attachments)
        }
    }

    private fun shareText() {
        val firstItem = getSelectedItems().firstOrNull() as? Message ?: return
        activity.shareTextIntent(firstItem.body)
    }

    private fun selectText() {
        val firstItem = getSelectedItems().firstOrNull() as? Message ?: return
        if (firstItem.body.trim().isNotEmpty()) {
            val blurTarget = activity.findViewById<eightbitlab.com.blurview.BlurTarget>(com.android.mms.R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            SelectTextDialog(activity, firstItem.body, blurTarget)
        }
    }

    private fun showMessageDetails() {
        val message = getSelectedItems().firstOrNull() as? Message ?: return
        val blurTarget = activity.findViewById<eightbitlab.com.blurview.BlurTarget>(com.android.mms.R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        MessageDetailsDialog(activity, message, blurTarget)
    }

    private fun askConfirmDelete(message: Message? = null) {
        val itemsCnt = if (message != null) 1 else selectedKeys.size

        // not sure how we can get UnknownFormatConversionException here, so show the error and hope that someone reports it
        val items = try {
            resources.getQuantityString(R.plurals.delete_messages, itemsCnt, itemsCnt)
        } catch (e: Exception) {
            activity.showErrorToast(e)
            return
        }

        val baseString = if (activity.config.useRecycleBin && !isRecycleBin) {
            com.goodwy.commons.R.string.move_to_recycle_bin_confirmation
        } else {
            com.goodwy.commons.R.string.deletion_confirmation
        }
        val question = String.format(resources.getString(baseString), items)

        val blurTarget = activity.findViewById<eightbitlab.com.blurview.BlurTarget>(com.android.mms.R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        DeleteConfirmationDialog(activity, question, activity.config.useRecycleBin && !isRecycleBin, blurTarget) { skipRecycleBin ->
            ensureBackgroundThread {
                val messagesToRemove = if (message != null) arrayListOf(message) else getSelectedItems()
                if (messagesToRemove.isNotEmpty()) {
                    val toRecycleBin = !skipRecycleBin && activity.config.useRecycleBin && !isRecycleBin
                    deleteMessages(messagesToRemove.filterIsInstance<Message>(), toRecycleBin, false, message != null)
                }
            }
        }
    }

    private fun askConfirmRestore() {
        val itemsCnt = selectedKeys.size

        // not sure how we can get UnknownFormatConversionException here, so show the error and hope that someone reports it
        val items = try {
            resources.getQuantityString(R.plurals.delete_messages, itemsCnt, itemsCnt)
        } catch (e: Exception) {
            activity.showErrorToast(e)
            return
        }

        val baseString = R.string.restore_confirmation
        val question = String.format(resources.getString(baseString), items)

        val blurTarget = activity.findViewById<eightbitlab.com.blurview.BlurTarget>(com.android.mms.R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        ConfirmationDialog(activity, question, blurTarget = blurTarget) {
            ensureBackgroundThread {
                val messagesToRestore = getSelectedItems()
                if (messagesToRestore.isNotEmpty()) {
                    deleteMessages(messagesToRestore.filterIsInstance<Message>(), false, true, false)
                }
            }
        }
    }

    private fun forwardMessage() {
        val selectedMessages = getSelectedItems().filterIsInstance<Message>()
        if (selectedMessages.isEmpty()) return

        val mergedBody = selectedMessages
            .mapNotNull { it.body.takeIf(String::isNotBlank) }
            .joinToString(separator = "\n")

        val allAttachments = selectedMessages.flatMap { it.attachment?.attachments.orEmpty() }
        
        Intent(activity, NewConversationActivity::class.java).apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, mergedBody)

            // Handle single attachment
            if (allAttachments.size == 1) {
                putExtra(Intent.EXTRA_STREAM, allAttachments.first().getUri())
            } else if (allAttachments.size > 1) {
                // Handle multiple attachments
                action = Intent.ACTION_SEND_MULTIPLE
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(allAttachments.map { it.getUri() }))
            }

            activity.startActivity(this)
        }
        finishActMode()
    }

    private fun getSelectedItems(): ArrayList<ThreadItem> {
        return currentList.filter {
            selectedKeys.contains((it as? Message)?.getSelectionKey() ?: 0)
        } as ArrayList<ThreadItem>
    }

    private fun isThreadDateTime(position: Int) = currentList.getOrNull(position) is ThreadDateTime

    fun updateMessages(
        newMessages: ArrayList<ThreadItem>,
        scrollPosition: Int = -1,
        smoothScroll: Boolean = false
    ) {
        // Refresh font size multiplier from config in case it was changed
        fontSizeMessageMultiplier = activity.config.fontSizeMessageMultiplier
        val latestMessages = newMessages.toMutableList()
        submitList(latestMessages) {
            if (scrollPosition != -1) {
                if (smoothScroll) {
                    recyclerView.smoothScrollToPosition(scrollPosition)
                } else {
                    recyclerView.scrollToPosition(scrollPosition)
                }
            }
        }
    }

    private fun getActionTitleAndIcon(url: String): Pair<Int, Int> {
        return when {
            url.startsWith("tel") -> Pair(com.goodwy.commons.R.string.call, com.goodwy.commons.R.drawable.ic_phone_vector)
            url.startsWith("mailto") -> Pair(com.goodwy.commons.R.string.send_email, com.goodwy.commons.R.drawable.ic_mail_vector)
            url.startsWith("geo") -> Pair(com.goodwy.strings.R.string.open_in_maps, R.drawable.ic_place_vector)
            else -> Pair(com.goodwy.strings.R.string.open, R.drawable.ic_launch)
        }
    }

    private fun showLinkPopupMenu(context: Context, url: String, view: View) {
        val popupMenu = BlurPopupMenu(activity, view, Gravity.START)
        val text = url.toUri().schemeSpecificPart
        val (title, icon) = getActionTitleAndIcon(url)

        // Use only 24dp icons
        popupMenu.menu.add(1, 0, 0, text).setIcon(R.drawable.ic_empty)
        popupMenu.menu.add(1, 1, 1, title).setIcon(icon)
        if (title == com.goodwy.commons.R.string.call) popupMenu.menu.add(1, 2, 2, com.goodwy.strings.R.string.message).setIcon(R.drawable.ic_comment)
        popupMenu.menu.add(1, 3, 3, com.goodwy.strings.R.string.search_the_web).setIcon(R.drawable.ic_internet)
        popupMenu.menu.add(1, 4, 4, com.goodwy.commons.R.string.share).setIcon(com.goodwy.commons.R.drawable.ic_ios_share)
        popupMenu.menu.add(1, 5, 5, com.goodwy.commons.R.string.copy).setIcon(com.goodwy.commons.R.drawable.ic_copy_vector)

        popupMenu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> activity.copyToClipboard(text)

                2 -> activity.launchSendSMSIntent(text)

                3 -> activity.launchInternetSearch(text)

                4 -> activity.shareTextIntent(text)

                5 -> activity.copyToClipboard(text)

                else -> {
                    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                    context.startActivity(intent)
                }
            }
            true
        }
        popupMenu.show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupView(holder: ViewHolder, view: View, message: Message) {
        ItemMessageBinding.bind(view).apply {
            threadMessageHolder.isSelected = selectedKeys.contains(message.getSelectionKey())
            threadMessageBodyWrapper.beVisibleIf(message.body.isNotEmpty())
            threadMessageBody.apply {
                val spannable = SpannableString(message.body)
                Linkify.addLinks(spannable, Linkify.ALL)
                text = spannable
                val alignment =
                    if (context.config.textAlignment == TEXT_ALIGNMENT_ALONG_EDGES) View.TEXT_ALIGNMENT_VIEW_END else View.TEXT_ALIGNMENT_INHERIT
                textAlignment = alignment
                movementMethod = LinkMovementMethod.getInstance()

                setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizeMessage * fontSizeMessageMultiplier)
                val lineSpacingExtra = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    2f,
                    resources.displayMetrics
                )
                setLineSpacing(lineSpacingExtra, 1.1f)
                setOnLongClickListener {
                    holder.viewLongClicked()
                    true
                }

                setOnClickListener {
                    // If in selection mode, only handle selection/unselection
                    if (actModeCallback.isSelectable) {
                        holder.viewClicked(message)
                        return@setOnClickListener
                    }
                    
                    if (message.isScheduled) {
                        holder.viewClicked(message)
                    } else {
                        when (context.config.actionOnMessageClickSetting) {
                            ACTION_COPY_CODE -> {
                                val numbersList = message.body.getListNumbersFromText()
                                if (numbersList.isNotEmpty()) {
                                    if (numbersList.size == 1) {
                                        activity.copyToClipboard(numbersList.first())
                                    } else {
                                        showPopupMenuCopyNumbers(numbersList, this)
                                    }
                                }
                            }
                            ACTION_COPY_MESSAGE -> activity.copyToClipboard(message.body)
                            ACTION_SELECT_TEXT -> {
                                val blurTarget = activity.findViewById<eightbitlab.com.blurview.BlurTarget>(com.android.mms.R.id.mainBlurTarget)
                                    ?: throw IllegalStateException("mainBlurTarget not found")
                                SelectTextDialog(activity, message.body, blurTarget)
                            }
                            ACTION_NOTHING -> showPopupMenu(message, this)
                            else -> return@setOnClickListener
                        }
                    }
                }

                setOnTouchListener { v, event ->
                    val action = event.action
                    if (action == MotionEvent.ACTION_UP) {
                        val x = event.x
                        val y = event.y

                        val offset = this.getOffsetForPosition(x, y)
                        if (offset != -1) {
                            val links = spannable.getSpans(offset, offset, URLSpan::class.java)
                            if (links.isNotEmpty()) {
                                val url = links[0].url
                                showLinkPopupMenu(v.context, url, v)
                                return@setOnTouchListener true
                            }
                        }
                    }
                    false
                }
            }

            if (message.isReceivedMessage()) {
                setupReceivedMessageView(messageBinding = this, message = message)
            } else {
                setupSentMessageView(messageBinding = this, message = message)
            }

            if (message.attachment?.attachments?.isNotEmpty() == true) {
                threadMessageAttachmentsHolder.beVisible()
                threadMessageAttachmentsHolder.removeAllViews()
                for (attachment in message.attachment.attachments) {
                    val mimetype = attachment.mimetype
                    when {
                        mimetype.isImageMimeType() || mimetype.isVideoMimeType() -> setupImageView(holder, binding = this, message, attachment)
                        mimetype.isVCardMimeType() -> setupVCardView(holder, threadMessageAttachmentsHolder, message, attachment)
                        else -> setupFileView(holder, threadMessageAttachmentsHolder, message, attachment)
                    }

                    threadMessagePlayOutline.beVisibleIf(mimetype.startsWith("video/"))
                }
            } else {
                threadMessageAttachmentsHolder.beGone()
                threadMessagePlayOutline.beGone()
            }
        }
    }

    private fun showPopupMenu(message: Message, view: View) {
        val popupMenu = BlurPopupMenu(activity, view, Gravity.END)
        val text = message.body
        val numbersList = text.getListNumbersFromText()
        val colorRed = resources.getColor(R.color.red_call, activity.theme)
        
        val deleteItem = popupMenu.menu.add(1, 1, 1, com.goodwy.commons.R.string.delete).setIcon(com.goodwy.commons.R.drawable.ic_delete_outline)
        // Set red color for delete item title
        val coloredText = SpannableString(deleteItem.title).apply {
            setSpan(ForegroundColorSpan(colorRed), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        deleteItem.title = coloredText
        
        popupMenu.menu.add(1, 2, 2, com.goodwy.strings.R.string.search_the_web).setIcon(R.drawable.ic_internet)
        popupMenu.menu.add(1, 3, 3, com.goodwy.commons.R.string.share).setIcon(com.goodwy.commons.R.drawable.ic_ios_share)
        popupMenu.menu.add(1, 4, 4, com.goodwy.commons.R.string.properties).setIcon(com.goodwy.commons.R.drawable.ic_info_vector)
        popupMenu.menu.add(1, 5, 5, R.string.forward_message).setIcon(R.drawable.ic_redo_vector)
        popupMenu.menu.add(1, 6, 6, com.goodwy.commons.R.string.select_text).setIcon(R.drawable.ic_text_select)
        popupMenu.menu.add(1, 7, 7, com.goodwy.commons.R.string.copy).setIcon(com.goodwy.commons.R.drawable.ic_copy_vector)
        val staticItem = 8
        if (numbersList.isNotEmpty()) {
            numbersList.apply {
                val size = numbersList.size
                val range = if (size > 7) 0..7 else 0 until size
                for (index in range) {
                    val item = this[index]
                    val menuName = activity.getString(com.goodwy.commons.R.string.copy) + " \"${item}\""
                    popupMenu.menu.add(1, staticItem + index, staticItem + index, menuName).setIcon(com.goodwy.commons.R.drawable.ic_copy_vector)
                }
            }
        }
        popupMenu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> askConfirmDelete(message)

                2 -> activity.launchInternetSearch(text)

                3 -> activity.shareTextIntent(text)

                4 -> {
                    val blurTarget = activity.findViewById<eightbitlab.com.blurview.BlurTarget>(com.android.mms.R.id.mainBlurTarget)
                        ?: throw IllegalStateException("mainBlurTarget not found")
                    MessageDetailsDialog(activity, message, blurTarget)
                }

                5 -> {
                    val attachments = message.attachment?.attachments.orEmpty()
                    Intent(activity, NewConversationActivity::class.java).apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, message.body)

                        if (attachments.size == 1) {
                            putExtra(Intent.EXTRA_STREAM, attachments.first().getUri())
                        } else if (attachments.size > 1) {
                            action = Intent.ACTION_SEND_MULTIPLE
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(attachments.map { it.getUri() }))
                        }

                        activity.startActivity(this)
                    }
                }

                6 -> {
                    val blurTarget = activity.findViewById<eightbitlab.com.blurview.BlurTarget>(com.android.mms.R.id.mainBlurTarget)
                        ?: throw IllegalStateException("mainBlurTarget not found")
                    SelectTextDialog(activity, text, blurTarget)
                }

                7 -> activity.copyToClipboard(text)

                else -> {
                    if (numbersList.isNotEmpty()) activity.copyToClipboard(numbersList[item.itemId - staticItem])
                }
            }
            true
        }
        popupMenu.show()
    }

    private fun showPopupMenuCopyNumbers(numbersList: List<String>, view: View) {
        val popupMenu = BlurPopupMenu(activity, view, Gravity.END)
        if (numbersList.isNotEmpty()) {
            numbersList.apply {
                val size = numbersList.size
                for (index in 0 until size) {
                    val item = this[index]
                    val menuName = activity.getString(com.goodwy.commons.R.string.copy) + " \"${item}\""
                    popupMenu.menu.add(1, index, index, menuName).setIcon(com.goodwy.commons.R.drawable.ic_copy_vector)
                }
            }
        }
        popupMenu.setOnMenuItemClickListener { item ->
            if (numbersList.isNotEmpty()) activity.copyToClipboard(numbersList[item.itemId])
            true
        }
        popupMenu.show()
    }

    private fun View.applyDrawablePadding(drawable: Drawable?) {
        if (drawable == null) return
        val padding = Rect()
        if (drawable.getPadding(padding)) {
            setPadding(padding.left, padding.top, padding.right, padding.bottom)
        }
    }

    private fun View.applyBubbleMinimumHeight(@Suppress("UNUSED_PARAMETER") selectedBubbleOption: BubbleDrawableOption?) {
        // Let the bubble wrap its content height; fixed minimums make short one-line messages look too tall.
        minimumHeight = 0
    }

    private fun setupReceivedMessageView(messageBinding: ItemMessageBinding, message: Message) {
        messageBinding.apply {
            with(ConstraintSet()) {
                clone(threadMessageHolder)
                clear(threadMessageWrapper.id, ConstraintSet.END)
                connect(threadMessageWrapper.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                applyTo(threadMessageHolder)
            }

//            threadMessageSenderPhoto.beVisible()
//            threadMessageSenderPhoto.setOnClickListener {
//                val contact = message.getSender()!!
//                activity.getContactFromAddress(contact.phoneNumbers.first().normalizedNumber) {
//                    if (it != null) {
//                        activity.startContactDetailsIntent(it)
//                    }
//                }
//            }


            val letterBackgroundColors = activity.getLetterBackgroundColors()
            val primaryOrSenderColor =
                if (activity.config.bubbleInContactColor) letterBackgroundColors[abs(message.senderName.hashCode()) % letterBackgroundColors.size].toInt()
                else activity.getProperPrimaryColor()
            val useSurfaceColor = activity.isDynamicTheme() && !activity.isSystemInDarkMode()
            val surfaceColor = if (useSurfaceColor) activity.getProperBackgroundColor() else activity.getSurfaceColor()
            val backgroundReceived = if (activity.config.bubbleInvertColor) primaryOrSenderColor else surfaceColor
            val selectedBubbleOption = getBubbleDrawableOption(activity.config.bubbleDrawableSet)
            val contrastColorReceived = if (selectedBubbleOption != null) activity.getProperTextColor() else backgroundReceived.getContrastColor()

            threadMessageBodyWrapper.apply {
                // Setup pinch-to-zoom gesture detector for font size adjustment
                // Only process multi-touch events (pinch gestures) to avoid interfering with clicks
                setOnTouchListener { v, event ->
                    // Only handle multi-touch events (pinch gesture with 2+ pointers)
                    if (event.pointerCount >= 2) {
                        scaleGestureDetector.onTouchEvent(event)
                        if (scaleGestureDetector.isInProgress) {
                            return@setOnTouchListener true
                        }
                    }
                    false // Let other touch events (like clicks) still work
                }

                val isRtl = activity.isRTLLayout
                val bubbleStyle = activity.config.bubbleStyle

                val bubbleReceived = if (selectedBubbleOption != null) {
                    if (isRtl) getCompactBubbleRes(selectedBubbleOption.outgoingRes) else getCompactBubbleRes(selectedBubbleOption.incomingRes)
                } else {
                    when (bubbleStyle) {
                        BUBBLE_STYLE_IOS_NEW -> if (isRtl) R.drawable.item_sent_ios_new_background else R.drawable.item_received_ios_new_background
                        BUBBLE_STYLE_IOS -> if (isRtl) R.drawable.item_sent_ios_background else R.drawable.item_received_ios_background
                        BUBBLE_STYLE_ROUNDED -> if (isRtl) R.drawable.item_sent_rounded_background else R.drawable.item_received_rounded_background
                        else -> if (isRtl) R.drawable.item_sent_background else R.drawable.item_received_background
                    }
                }
                val bubbleDrawable = ResourcesCompat.getDrawable(resources, bubbleReceived, activity.theme)
                background = bubbleDrawable
                applyBubbleMinimumHeight(selectedBubbleOption)
                if (selectedBubbleOption == null) {
                    setPaddingBubble(activity, bubbleStyle)
                    background.applyColorFilter(backgroundReceived)
                } else {
                    // Respect 9-patch content padding when using custom bubble drawables.
                    applyDrawablePadding(bubbleDrawable)
                }
            }

            val alignment =
                if (activity.config.textAlignment == TEXT_ALIGNMENT_ALONG_EDGES) View.TEXT_ALIGNMENT_VIEW_START else View.TEXT_ALIGNMENT_INHERIT
            threadMessageBody.apply {
                textAlignment = alignment
                setTextColor(contrastColorReceived)
                setLinkTextColor(contrastColorReceived)
            }

            if (isGroupChat && message.body.isNotEmpty() && message.isReceivedMessage()) {
                threadMessageSenderName.apply {
                    beVisible()
                    textAlignment = alignment
                    text = message.senderName
                    setTextColor(letterBackgroundColors[abs(message.senderName.hashCode()) % letterBackgroundColors.size].toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizeMessage * 0.9f * fontSizeMessageMultiplier)
                    setOnClickListener {
                        val contact = message.getSender()!!
                        activity.getContactFromAddress(contact.phoneNumbers.first().normalizedNumber) {
                            if (it != null) {
                                activity.startContactDetailsIntentRecommendation(it)
                            }
                        }
                    }
                }
            }

//            if (!activity.isFinishing && !activity.isDestroyed) {
//                SimpleContactsHelper(activity).loadContactImage(message.senderPhotoUri, threadMessageSenderPhoto, message.senderName)
//            }
//            if (!activity.isFinishing && !activity.isDestroyed) {
//                val contactLetterIcon = SimpleContactsHelper(activity).getContactLetterIcon(message.senderName)
//                val placeholder = contactLetterIcon.toDrawable(activity.resources)

//                val options = RequestOptions()
//                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
//                    .error(placeholder)
//                    .centerCrop()
//
//                Glide.with(activity)
//                    .load(message.senderPhotoUri)
//                    .placeholder(placeholder)
//                    .apply(options)
//                    .apply(RequestOptions.circleCropTransform())
//                    .into(threadMessageSenderPhoto)
//            }
        }
    }

    private fun setupSentMessageView(messageBinding: ItemMessageBinding, message: Message) {

        val letterBackgroundColors = activity.getLetterBackgroundColors()
        val primaryOrSenderColor = if (activity.config.bubbleInContactColor) letterBackgroundColors[abs(
            message.senderName.hashCode().hashCode()
        ) % letterBackgroundColors.size].toInt()
        else activity.getProperPrimaryColor()
        val useSurfaceColor = activity.isDynamicTheme() && !activity.isSystemInDarkMode()
        val surfaceColor = if (useSurfaceColor) activity.getProperBackgroundColor() else activity.getSurfaceColor()
        val backgroundReceived = if (activity.config.bubbleInvertColor) surfaceColor else primaryOrSenderColor
        val selectedBubbleOption = getBubbleDrawableOption(activity.config.bubbleDrawableSet)
        // Custom bubble drawables can be light in light mode, so avoid forcing white text.
        val contrastColorReceived = if (selectedBubbleOption != null) activity.getProperTextColor() else backgroundReceived.getContrastColor()

        messageBinding.apply {
            with(ConstraintSet()) {
                clone(threadMessageHolder)
                clear(threadMessageWrapper.id, ConstraintSet.START)
                connect(threadMessageWrapper.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                applyTo(threadMessageHolder)
            }

            threadMessageBodyWrapper.apply {
                // Setup pinch-to-zoom gesture detector for font size adjustment
                // Only process multi-touch events (pinch gestures) to avoid interfering with clicks
                setOnTouchListener { v, event ->
                    // Only handle multi-touch events (pinch gesture with 2+ pointers)
                    if (event.pointerCount >= 2) {
                        scaleGestureDetector.onTouchEvent(event)
                        if (scaleGestureDetector.isInProgress) {
                            return@setOnTouchListener true
                        }
                    }
                    false // Let other touch events (like clicks) still work
                }
                
                updateLayoutParams<RelativeLayout.LayoutParams> {
                    removeRule(RelativeLayout.END_OF)
                    addRule(RelativeLayout.ALIGN_PARENT_END)
                }

                val isRtl = Locale.getDefault().layoutDirection == ViewCompat.LAYOUT_DIRECTION_RTL
                val bubbleStyle = activity.config.bubbleStyle

                val bubbleReceived = if (selectedBubbleOption != null) {
                    if (isRtl) getCompactBubbleRes(selectedBubbleOption.incomingRes) else getCompactBubbleRes(selectedBubbleOption.outgoingRes)
                } else {
                    when (bubbleStyle) {
                        BUBBLE_STYLE_IOS_NEW -> if (isRtl) R.drawable.item_received_ios_new_background else R.drawable.item_sent_ios_new_background
                        BUBBLE_STYLE_IOS -> if (isRtl) R.drawable.item_received_ios_background else R.drawable.item_sent_ios_background
                        BUBBLE_STYLE_ROUNDED -> if (isRtl) R.drawable.item_received_rounded_background else R.drawable.item_sent_rounded_background
                        else -> if (isRtl) R.drawable.item_received_background else R.drawable.item_sent_background
                    }
                }
                val bubbleDrawable = AppCompatResources.getDrawable(activity, bubbleReceived)
                background = bubbleDrawable
                applyBubbleMinimumHeight(selectedBubbleOption)
                if (selectedBubbleOption == null) {
                    setPaddingBubble(activity, bubbleStyle, false)
                    background.applyColorFilter(backgroundReceived)
                } else {
                    // Respect 9-patch content padding when using custom bubble drawables.
                    applyDrawablePadding(bubbleDrawable)
                }
            }
            threadMessageBody.apply {
                setTextColor(contrastColorReceived)
                setLinkTextColor(contrastColorReceived)

                if (message.isScheduled) {
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                    val scheduledDrawable = AppCompatResources.getDrawable(activity, com.goodwy.commons.R.drawable.ic_clock_vector)?.apply {
                        applyColorFilter(contrastColorReceived)
                        val size = lineHeight
                        //val paddingIconBottom = context.resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.smaller_margin)
                        setBounds(0, 0, size, size)
                    }

                    setCompoundDrawables(null, null, scheduledDrawable, null)
                } else {
                    typeface = Typeface.DEFAULT
                    setCompoundDrawables(null, null, null, null)
                }
            }
        }
    }

    private fun setupImageView(holder: ViewHolder, binding: ItemMessageBinding, message: Message, attachment: Attachment) = binding.apply {
        val mimetype = attachment.mimetype
        val uri = attachment.getUri()

        val imageView = ItemAttachmentImageBinding.inflate(layoutInflater)
        threadMessageAttachmentsHolder.addView(imageView.root)

        val placeholderDrawable = Color.TRANSPARENT.toDrawable()
        val options = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .placeholder(placeholderDrawable)
            .transform(FitCenter())

        Glide.with(root.context)
            .load(uri)
            .apply(options)
            .dontAnimate()
            .override(maxChatBubbleWidth, maxChatBubbleWidth * MAX_MEDIA_HEIGHT_RATIO)
            .downsample(DownsampleStrategy.AT_MOST)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                    threadMessagePlayOutline.beGone()
                    threadMessageAttachmentsHolder.removeView(imageView.root)
                    return false
                }

                override fun onResourceReady(dr: Drawable, a: Any, t: Target<Drawable>, d: DataSource, i: Boolean) = false
            })
            .into(imageView.attachmentImage)

        imageView.attachmentImage.updateLayoutParams<ViewGroup.LayoutParams> {
            width = maxChatBubbleWidth
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }

        imageView.attachmentImage.setOnClickListener {
            if (actModeCallback.isSelectable) {
                holder.viewClicked(message)
            } else {
                activity.launchViewIntent(uri, mimetype, attachment.filename)
            }
        }
        imageView.root.setOnLongClickListener {
            holder.viewLongClicked()
            true
        }
    }

    private fun setupVCardView(holder: ViewHolder, parent: LinearLayout, message: Message, attachment: Attachment) {
        val uri = attachment.getUri()
        val vCardView = ItemAttachmentVcardBinding.inflate(layoutInflater).apply {
            setupVCardPreview(
                activity = activity,
                uri = uri,
                onClick = {
                    if (actModeCallback.isSelectable) {
                        holder.viewClicked(message)
                    } else {
                        val intent = Intent(activity, VCardViewerActivity::class.java).also {
                            it.putExtra(EXTRA_VCARD_URI, uri)
                        }
                        activity.startActivity(intent)
                    }
                },
                onLongClick = { holder.viewLongClicked() }
            )
        }.root

        parent.addView(vCardView)
    }

    private fun setupFileView(holder: ViewHolder, parent: LinearLayout, message: Message, attachment: Attachment) {
        val mimetype = attachment.mimetype
        val uri = attachment.getUri()
        val attachmentView = ItemAttachmentDocumentBinding.inflate(layoutInflater).apply {
            setupDocumentPreview(
                uri = uri,
                title = attachment.filename,
                mimeType = attachment.mimetype,
                onClick = {
                    if (actModeCallback.isSelectable) {
                        holder.viewClicked(message)
                    } else {
                        activity.launchViewIntent(uri, mimetype, attachment.filename)
                    }
                },
                onLongClick = { holder.viewLongClicked() }
            )
        }.root

        parent.addView(attachmentView)
    }

    private fun setupDateTime(view: View, dateTime: ThreadDateTime) {
        ItemThreadDateTimeBinding.bind(view).apply {
            threadDateTime.apply {
                text = (dateTime.date * 1000L).formatDateOrTime(
                    context = context,
                    hideTimeOnOtherDays = false,
                    showCurrentYear = false
                )
                setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizeSmall)
            }
            threadDateTime.setTextColor(textColor)

            threadSimIcon.beVisibleIf(hasMultipleSIMCards)
            threadSimNumber.beVisibleIf(hasMultipleSIMCards)
            if (hasMultipleSIMCards) {
                val simColor = if (!activity.config.colorSimIcons) textColor
                else {
                    when (dateTime.simID) {
                        "1" -> activity.config.simIconsColors[1]
                        "2" -> activity.config.simIconsColors[2]
                        "3" -> activity.config.simIconsColors[3]
                        "4" -> activity.config.simIconsColors[4]
                        else -> activity.config.simIconsColors[0]
                    }
                }

                threadSimNumber.text = dateTime.simID
                threadSimNumber.setTextColor(simColor.getContrastColor())
                threadSimIcon.applyColorFilter(simColor)
            }
        }
    }

    private fun setupThreadSuccess(view: View, isDelivered: Boolean) {
        ItemThreadSuccessBinding.bind(view).apply {
            threadSuccess.setImageResource(if (isDelivered) R.drawable.ic_check_double_vector else com.goodwy.commons.R.drawable.ic_check_vector)
            threadSuccess.applyColorFilter(textColor)
        }
    }

    private fun setupThreadError(view: View) {
        val binding = ItemThreadErrorBinding.bind(view)
        binding.threadError.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize - 4)
    }

    private fun setupThreadSending(view: View) {
        ItemThreadSendingBinding.bind(view).threadSending.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
            setTextColor(textColor)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isDestroyed && !activity.isFinishing) {
            val binding = (holder as ThreadViewHolder).binding
            if (binding is ItemMessageBinding) {
                Glide.with(activity).clear(binding.threadMessageSenderPhoto)
            }
        }
    }

    inner class ThreadViewHolder(val binding: ViewBinding) : ViewHolder(binding.root)
}

private class ThreadItemDiffCallback : DiffUtil.ItemCallback<ThreadItem>() {

    override fun areItemsTheSame(oldItem: ThreadItem, newItem: ThreadItem): Boolean {
        if (oldItem::class.java != newItem::class.java) return false
        return when (oldItem) {
            is ThreadError -> oldItem.messageId == (newItem as ThreadError).messageId
            is ThreadSent -> oldItem.messageId == (newItem as ThreadSent).messageId
            is ThreadSending -> oldItem.messageId == (newItem as ThreadSending).messageId
            is Message -> Message.areItemsTheSame(oldItem, newItem as Message)
            is ThreadDateTime -> {
                val new = newItem as ThreadDateTime
                oldItem.date == new.date && oldItem.simID == new.simID
            }
        }
    }

    override fun areContentsTheSame(oldItem: ThreadItem, newItem: ThreadItem): Boolean {
        if (oldItem::class.java != newItem::class.java) return false
        return when (oldItem) {
            is ThreadSending -> true
            is ThreadDateTime -> oldItem.simID == (newItem as ThreadDateTime).simID
            is ThreadError -> oldItem.messageText == (newItem as ThreadError).messageText
            is ThreadSent -> oldItem.delivered == (newItem as ThreadSent).delivered
            is Message -> Message.areContentsTheSame(oldItem, newItem as Message)
        }
    }
}
