package com.android.mms.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.*
import android.view.MenuItem
import android.view.ScaleGestureDetector
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.appcompat.view.menu.MenuBuilder
import com.goodwy.commons.dialogs.OptionListDialog
import com.goodwy.commons.dialogs.OptionListItem
import com.goodwy.commons.views.showMPopupMenu
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.core.net.toUri
import androidx.core.text.layoutDirection
import androidx.core.view.ViewCompat
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
import com.goodwy.commons.models.RecyclerSelectionPayload
import com.goodwy.commons.extensions.applyColorFilter
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.copyToClipboard
import com.goodwy.commons.extensions.formatDateOrTime
import com.goodwy.commons.extensions.formatTime
import com.goodwy.commons.extensions.getLetterBackgroundColors
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.getTextSizeSmall
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isRTLLayout
import com.goodwy.commons.extensions.isSystemInDarkMode
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
import com.android.mms.activities.ViewMmsActivity
import com.android.mms.databinding.ItemAttachmentDocumentBinding
import com.android.mms.databinding.ItemAttachmentImageBinding
import com.android.mms.databinding.ItemAttachmentVcardBinding
import com.android.mms.databinding.ItemMessageBinding
import com.android.mms.databinding.ItemThreadDateTimeBinding
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
import com.android.mms.extensions.openContactDetailsFromVCardUri
import com.android.mms.extensions.setPaddingBubble
import com.android.mms.extensions.applyCustomBubbleBackground
import com.android.mms.extensions.startContactDetailsIntentRecommendation
import com.android.mms.extensions.startSendingStatusAnimation
import com.android.mms.extensions.stopSendingStatusAnimation
import com.android.mms.extensions.subscriptionManagerCompat
import com.android.mms.helpers.ACTION_COPY_CODE
import com.android.mms.helpers.ACTION_COPY_MESSAGE
import com.android.mms.helpers.ACTION_SELECT_TEXT
import com.android.mms.helpers.BUBBLE_STYLE_IOS
import com.android.mms.helpers.BUBBLE_STYLE_IOS_NEW
import com.android.mms.helpers.BUBBLE_STYLE_ROUNDED
import com.android.mms.helpers.BubbleDrawableOption
import com.android.mms.helpers.EXTRA_MMS_MESSAGE_ID
import com.android.mms.helpers.EXTRA_VCARD_URI
import com.android.mms.helpers.THREAD_DATE_TIME
import com.android.mms.helpers.THREAD_RECEIVED_MESSAGE
import com.android.mms.helpers.THREAD_SENT_MESSAGE
import com.android.mms.helpers.getBubbleDrawableOption
import com.android.mms.helpers.generateStableId
import com.android.mms.helpers.setupDocumentPreview
import com.android.mms.helpers.setupVCardPreview
import com.android.mms.models.Attachment
import com.android.mms.models.Message
import com.android.mms.models.ThreadItem
import com.android.mms.models.ThreadItem.ThreadDateTime
import com.android.common.helper.IconItem
import android.widget.PopupMenu
import com.android.common.dialogs.MConfirmDialog
import com.android.mms.BuildConfig
import com.android.mms.dialogs.SelectSIMDialog
import com.android.mms.emoji.Ch350EmojiText.bindCh350MessageBody
import com.android.mms.helpers.SimMessageCopyHelper
import com.chutils.emo.views.EmoTextView
import com.android.mms.helpers.getLocaleDateFormatPatternMonthDay
import com.android.mms.helpers.resolveSimIconTint
import com.goodwy.commons.extensions.dismissTrackedMDialogs
import com.goodwy.commons.extensions.launchCallIntent
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.helpers.DARK_GREY
import com.goodwy.commons.views.enableItemDividers
import eightbitlab.com.blurview.BlurTarget
import java.util.Locale
import kotlin.math.abs

class ThreadAdapter(
    activity: SimpleActivity,
    recyclerView: MyRecyclerView,
    itemClick: (Any) -> Unit,
    val isRecycleBin: Boolean,
    val isGroupChat: Boolean,
    val retryFailedMessage: (Message) -> Unit,
    val deleteMessages: (messages: List<Message>, toRecycleBin: Boolean, fromRecycleBin: Boolean, isPopupMenu: Boolean) -> Unit
) : MyRecyclerViewListAdapter<ThreadItem>(activity, recyclerView, ThreadItemDiffCallback(), itemClick) {
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

    /** Touch listener for pinch-to-zoom font size on message bubbles. Attach to wrapper, spacer, time holder and body so pinches anywhere on the bubble work. */
    @SuppressLint("ClickableViewAccessibility")
    val pinchToZoomTouchListener = View.OnTouchListener { view, event ->
        if (event.pointerCount >= 2) {
            view.cancelLongPress()
            var v: View? = view
            while (v != null) {
                if (v.id == R.id.thread_message_holder) {
                    v.cancelLongPress()
                    break
                }
                v = v.parent as? View
            }
            recyclerView.requestDisallowInterceptTouchEvent(true)
            scaleGestureDetector.onTouchEvent(event)
            true
        } else {
            false
        }
    }

    companion object {
        private const val MAX_MEDIA_HEIGHT_RATIO = 3
        private const val SIM_BITS = 21
        private const val SIM_MASK = (1L shl SIM_BITS) - 1

        private val THREAD_RIPPLE_TOOLBAR_IDS = listOf(
            R.id.cab_ripple_delete,
            R.id.cab_ripple_message_conversion,
            R.id.cab_ripple_copy,
            R.id.cab_properties
        )
    }

    init {
        setupDragListener(true)
        setHasStableIds(true)
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    override fun getActionMenuId() = R.menu.cab_action_menu_select
    override fun getMorePopupMenuId() = R.menu.cab_thread
    override fun getMoreItemId() = 0
    override fun onMorePopupMenuItemClick(item: MenuItem) = actionItemPressed(item.itemId).let { true }

    fun isActionModeActive(): Boolean = actModeCallback.isSelectable

    fun cancelDragSelection() = recyclerView.cancelDragSelection()

    /** Visibility for thread CAB items (overflow was [cab_thread]; now used by [buildThreadRippleToolbar]). */
    private fun configureThreadActionMenuItems(menu: Menu) {
        val isOneItemSelected = isOneItemSelected()
        val selectedItem = getSelectedItems().firstOrNull() as? Message
        val hasText = selectedItem?.body != null && selectedItem.body != ""
        val selectedMessages = getSelectedItems().filterIsInstance<Message>()
        val hasAnyText = selectedMessages.any { it.body.isNotBlank() }
        val showSaveAs = getSelectedItems().all {
            it is Message && (it.attachment?.attachments?.size ?: 0) > 0
        } && getSelectedAttachments().isNotEmpty()

        menu.apply {
            findItem(R.id.cab_copy_to_clipboard)?.isVisible = isOneItemSelected && hasText
            findItem(R.id.cab_save_as)?.isVisible = showSaveAs
            findItem(R.id.cab_share)?.isVisible = isOneItemSelected && hasText
            findItem(R.id.cab_forward_message)?.isVisible = selectedKeys.isNotEmpty() && hasAnyText
            findItem(R.id.cab_select_text)?.isVisible = isOneItemSelected && hasText
            findItem(R.id.cab_properties)?.isVisible = isOneItemSelected
            findItem(R.id.cab_restore)?.isVisible = isRecycleBin && selectedKeys.isNotEmpty()
            findItem(R.id.cab_delete)?.isVisible = selectedKeys.isNotEmpty() && !isRecycleBin
            findItem(R.id.cab_ripple_delete)?.isVisible = true
            findItem(R.id.cab_ripple_message_conversion)?.isVisible = true
            findItem(R.id.cab_ripple_copy)?.isVisible = isOneItemSelected && hasText
        }
    }

    override fun prepareActionMode(menu: Menu) {
        configureThreadActionMenuItems(menu)
    }

    override fun updateSelectAllButtonIconIfAvailable(selectableItemCount: Int, selectedCount: Int) {
        super.updateSelectAllButtonIconIfAvailable(selectableItemCount, selectedCount)
        (activity as? ThreadActivity)?.refreshActionModeRippleToolbarIfNeeded()
    }
    fun isThreadRippleTabInteractionEnabled(tabIndex: Int): Boolean {
        val id = THREAD_RIPPLE_TOOLBAR_IDS.getOrNull(tabIndex) ?: return false
        if (selectedKeys.isEmpty()) return false
        val isOneItemSelected = isOneItemSelected()
        val selectedItem = getSelectedItems().firstOrNull() as? Message
        val hasText = selectedItem?.body?.isNotBlank() == true
        val selectedMessages = getSelectedItems().filterIsInstance<Message>()
        val hasAnyText = selectedMessages.any { it.body.isNotBlank() }
        return when (id) {
            R.id.cab_ripple_delete -> true
            R.id.cab_ripple_message_conversion -> hasAnyText
            R.id.cab_ripple_copy -> isOneItemSelected && hasText
            R.id.cab_properties -> isOneItemSelected
            else -> false
        }
    }
    /**
     * Bottom [com.android.common.view.MRippleToolBar] for thread selection (same pattern as [com.android.mms.activities.MainActivity]).
     */
    fun buildThreadRippleToolbar(): Pair<ArrayList<IconItem>, ArrayList<Int>> {
        val items = ArrayList<IconItem>()
        val ids = ArrayList<Int>()
        val pm = PopupMenu(activity, recyclerView)
        activity.menuInflater.inflate(R.menu.cab_thread, pm.menu)
        activity.menuInflater.inflate(R.menu.cab_action_menu, pm.menu)
        configureThreadActionMenuItems(pm.menu)
        pm.menu.findItem(R.id.cab_select_all)?.isVisible = false
        val m = pm.menu

        fun add(icon: Int, title: CharSequence, id: Int) {
            items.add(
                IconItem().apply {
                    this.icon = icon
                    this.title = title.toString()
                },
            )
            ids.add(id)
        }

        val iconForId = { itemId: Int ->
            when (itemId) {
                R.id.cab_ripple_copy -> R.drawable.ic_sms_ripple_copy
                R.id.cab_ripple_message_conversion -> com.android.common.R.drawable.ic_cmn_chat_fill
                R.id.cab_ripple_delete -> com.android.common.R.drawable.ic_cmn_delete_fill
                R.id.cab_properties -> com.android.common.R.drawable.ic_cmn_info_fill
                else -> 0
            }
        }
        val titleForId = { itemId: Int ->
            val fromMenu = m.findItem(itemId)?.title?.toString().orEmpty()
            fromMenu.ifEmpty {
                when(itemId) {
                    R.id.cab_ripple_delete -> activity.getString(com.goodwy.commons.R.string.delete)
                    R.id.cab_ripple_message_conversion -> activity.getString(R.string.message_conversion)
                    R.id.cab_ripple_copy -> activity.getString(com.goodwy.commons.R.string.copy)
                    R.id.cab_properties -> activity.getString(com.goodwy.commons.R.string.properties)
                    else -> ""
                }
            }
        }
        for (itemId in THREAD_RIPPLE_TOOLBAR_IDS) {
            add(iconForId(itemId), titleForId(itemId), itemId)
        }
        return items to ids
    }

    fun dispatchRippleToolbarAction(index: Int) {
        if (selectedKeys.isEmpty()) return
        val (_, actionIds) = buildThreadRippleToolbar()
        val id = actionIds.getOrNull(index) ?: return
        actionItemPressed(id)
    }

    override fun selectAll() {
        for (i in 0 until itemCount) {
            toggleItemSelection(true, i, false)
        }
        updateTitle()
    }

    override fun actionItemPressed(id: Int) {
        if (id == R.id.cab_select_all) {
            if (getSelectableItemCount() == selectedKeys.size) {
                (selectedKeys.clone() as HashSet<Int>).forEach { key ->
                    val position = getItemKeyPosition(key)
                    if (position != -1) {
                        toggleItemSelection(false, position, false)
                    }
                }
                updateTitle()
            } else {
                selectAll()
            }
            return
        }

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

            //ripple
            R.id.cab_ripple_delete -> askConfirmDelete()
            R.id.cab_ripple_message_conversion -> forwardMessage()
            R.id.cab_ripple_copy -> copyToClipboard()
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

    @SuppressLint("NotifyDataSetChanged")
    override fun onActionModeCreated() {
        // Keep select mode visuals consistent with MainActivity action mode.
        val useSurfaceColor = activity.isDynamicTheme() && !activity.isSystemInDarkMode()
        val cabBackgroundColor = if (useSurfaceColor) {
            activity.getSurfaceColor()
        } else {
            activity.getProperBackgroundColor()
        }

        val actModeBar = actMode?.customView?.parent as? View
        actModeBar?.setBackgroundColor(cabBackgroundColor)

        // When using host toolbar (ThreadActivity), actMode is null so get toolbar from actBarToolbar.
        val toolbar = (actMode?.customView as? com.goodwy.commons.views.CustomActionModeToolbar) ?: actBarToolbar
        toolbar?.updateTextColorForBackground(cabBackgroundColor)
        toolbar?.updateColorsForBackground(cabBackgroundColor)

        // Match [ConversationsAdapter]: show per-row checkboxes when entering selection mode.
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onActionModeDestroyed() {
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = when (viewType) {
            THREAD_DATE_TIME -> ItemThreadDateTimeBinding.inflate(layoutInflater, parent, false)
            else -> ItemMessageBinding.inflate(layoutInflater, parent, false)
        }

        return ThreadViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val isClickable = item is Message
        val isLongClickable = item is Message
        holder.bindView(item, isClickable, isLongClickable) { itemView, _ ->
            when (item) {
                is ThreadDateTime -> setupDateTime(itemView, item)
                is Message -> setupView(holder, itemView, item)
                else -> { /* ThreadError, ThreadSent, ThreadSending now shown in message bubble */ }
            }
        }
        if (item is Message) {
            holder.itemView.setOnLongClickListener {
                // if select mode return
                if (actModeCallback.isSelectable) return@setOnLongClickListener false
                showPopupMenu(item, it)
                true
            }
        }
        bindViewHolder(holder)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        val payload = payloads.firstOrNull()
        if (payload is RecyclerSelectionPayload && getItem(position) is Message) {
            // Do not use row [isSelected] (foreground selector); mirror [BaseConversationsAdapter] checkbox-only updates.
            holder.itemView.isSelected = false
            val message = getItem(position) as Message
            ItemMessageBinding.bind(holder.itemView).apply {
                val isInActionMode = actModeCallback.isSelectable
                val isRowSelected = selectedKeys.contains(message.getSelectionKey())
                threadMessageCheckbox.apply {
                    beVisibleIf(isInActionMode)
                    isChecked = isRowSelected
                    setOnClickListener {
                        if (isInActionMode) holder.itemView.performClick()
                    }
                }
            }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemId(position: Int): Long {
        return when (val item = getItem(position)) {
            is Message -> item.getStableId()
            is ThreadDateTime -> {
                val sim = (item.simID.hashCode().toLong() and SIM_MASK)
                val key = (item.date.toLong() shl SIM_BITS) or sim
                generateStableId(THREAD_DATE_TIME, key)
            }
            else -> 0L
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (val item = getItem(position)) {
            is ThreadDateTime -> THREAD_DATE_TIME
            is Message -> if (item.isReceivedMessage()) THREAD_RECEIVED_MESSAGE else THREAD_SENT_MESSAGE
            else -> THREAD_SENT_MESSAGE
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

    private fun showMConfirmDialog(question: String, onConfirm: () -> Unit) {
        val blurTarget = activity.findViewById<BlurTarget>(com.android.mms.R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        val dialog = MConfirmDialog(activity)
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

        showMConfirmDialog(question) {
            ensureBackgroundThread {
                val messagesToRemove = if (message != null) arrayListOf(message) else getSelectedItems()
                if (messagesToRemove.isNotEmpty()) {
                    val toRecycleBin = activity.config.useRecycleBin && !isRecycleBin
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

        showMConfirmDialog(question) {
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

    fun getSelectedItems(): ArrayList<ThreadItem> {
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

    private fun getActionTitleAndIcon(url: String): Int {
        return when {
            url.startsWith("tel") -> com.goodwy.commons.R.string.call
            url.startsWith("mailto") -> com.goodwy.commons.R.string.send_email
            url.startsWith("geo") -> com.goodwy.strings.R.string.open_in_maps
            else -> com.goodwy.strings.R.string.open
        }
    }

    @SuppressLint("RestrictedApi")
    private fun showLinkPopupMenu(context: Context, url: String, view: View) {
        if (actModeCallback.isSelectable) return
        var dividerGroupId = 0
        val menu = MenuBuilder(context).apply { enableItemDividers() }
        val text = url.toUri().schemeSpecificPart
        val title = getActionTitleAndIcon(url)

        // Use only 24dp icons
        menu.add(dividerGroupId++, 0, 0, text)
        menu.add(dividerGroupId++, 1, 1, title)
        if (title == com.goodwy.commons.R.string.call) menu.add(dividerGroupId++, 2, 2, com.goodwy.strings.R.string.message)
        menu.add(dividerGroupId++, 4, 4, com.goodwy.commons.R.string.share)
        menu.add(dividerGroupId, 5, 5, com.goodwy.commons.R.string.copy)

        val blurTarget = activity.findViewById<eightbitlab.com.blurview.BlurTarget>(com.android.mms.R.id.mainBlurTarget)
        showMPopupMenu(
            context = activity,
            anchor = view,
            menu = menu,
            gravity = Gravity.START,
            blurTarget = blurTarget,
            showGroupDividers = true,
            listener = MenuItem.OnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> activity.copyToClipboard(text)
                    1 -> activity.launchCallIntent(text, key = BuildConfig.RIGHT_APP_KEY)
                    2 -> activity.launchSendSMSIntent(text)
                    4 -> activity.shareTextIntent(text)
                    5 -> activity.copyToClipboard(text)
                    else -> {
                        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                        context.startActivity(intent)
                    }
                }
                true
            },
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupView(holder: ViewHolder, view: View, message: Message) {
        ItemMessageBinding.bind(view).apply {
            threadMessageHolder.isSelected = false
            val isInActionMode = actModeCallback.isSelectable
            val isRowSelected = selectedKeys.contains(message.getSelectionKey())
            threadMessageCheckbox.apply {
                beVisibleIf(isInActionMode)
                isChecked = isRowSelected
                setOnClickListener {
                    if (isInActionMode) holder.itemView.performClick()
                }
            }
            // Show body wrapper when we have body or attachments (time+SIM is always shown in wrapper)
            threadMessageBodyWrapper.beVisibleIf(message.body.isNotEmpty() || message.attachment?.attachments?.isNotEmpty() == true)
            threadMessageBody.apply {
                bindCh350MessageBody(message.body)
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
                    if (actModeCallback.isSelectable) return@setOnLongClickListener false
                    showPopupMenu(message, this)
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
//                            ACTION_NOTHING -> showPopupMenu(message, this)
                            else -> return@setOnClickListener
                        }
                    }
                }

                setOnTouchListener { v, event ->
                    if (event.pointerCount >= 2) {
                        v.cancelLongPress()
                        var itemView: View? = v
                        while (itemView != null) {
                            if (itemView.id == R.id.thread_message_holder) {
                                itemView.cancelLongPress()
                                break
                            }
                            itemView = itemView.parent as? View
                        }
                        recyclerView.requestDisallowInterceptTouchEvent(true)
                        scaleGestureDetector.onTouchEvent(event)
                        return@setOnTouchListener true
                    }
                    val action = event.action
                    if (action == MotionEvent.ACTION_UP) {
                        val x = event.x
                        val y = event.y

                        val offset = this.getOffsetForPosition(x, y)
                        if (offset != -1) {
                            val spannable = text
                            if (spannable is Spannable) {
                                val links = spannable.getSpans(offset, offset, URLSpan::class.java)
                                if (links.isNotEmpty()) {
                                    val url = links[0].url
                                    showLinkPopupMenu(v.context, url, v)
                                    return@setOnTouchListener true
                                }
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

    private fun copyToSimMessage(message: Message) {
        if (message.isMMS) {
            activity.toast(R.string.sim_copy_to_sim_mms_not_supported)
            return
        }
        val address = SimMessageCopyHelper.resolveCopyAddress(message)
        if (address.isNullOrEmpty()) {
            activity.toast(R.string.sim_copy_to_sim_failed)
            return
        }
        if (hasMultipleSIMCards) {
            val blurTarget = activity.findViewById<BlurTarget>(R.id.mainBlurTarget)
            SelectSIMDialog(
                activity = activity as SimpleActivity,
                blurTarget = blurTarget,
                showNetName = true
            ) { simCard, _ ->
                proceedCopyToSim(message, address, simCard.subscriptionId)
                activity.dismissTrackedMDialogs()
            }
        } else {
            val subscriptionId = SimMessageCopyHelper.resolveSubscriptionId(message)
            proceedCopyToSim(message, address, subscriptionId)
            activity.dismissTrackedMDialogs()
        }
    }

    private fun proceedCopyToSim(message: Message, address: String, subscriptionId: Int) {

        if (subscriptionId < 0) {
            activity.toast(R.string.sim_card_not_available)
            return
        }
        ensureBackgroundThread {
            val storageInfo = SimMessageCopyHelper.getStorageInfo(activity, subscriptionId)
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                if (storageInfo?.isFull == true) {
                    showMConfirmDialog(activity.getString(R.string.sim_storage_full_confirm)){
                        performCopyToSim(message, address, subscriptionId, storageInfo, overrideIfFull = true)
                    }
                } else {
                    performCopyToSim(message, address, subscriptionId, storageInfo, overrideIfFull = false)
                }
            }
        }
    }

    private fun performCopyToSim(message: Message, address: String, subscriptionId: Int, storageInfo: SimMessageCopyHelper.SimStorageInfo?, overrideIfFull: Boolean) {
        ensureBackgroundThread {
            val success = SimMessageCopyHelper.copyMessageToSim(
                context = activity,
                message = message,
                address = address,
                subscriptionId = subscriptionId,
                storageInfo = storageInfo,
                overrideIfFull = overrideIfFull
            )
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                if (success) {
                    activity.toast(R.string.sim_message_copied_to_sim)
                } else {
                    activity.toast(R.string.sim_copy_to_sim_failed)
                }
            }
        }
    }
    private fun showPopupMenu(message: Message, view: View) {
        if (activity.isDestroyed || activity.isFinishing) return
        val text = message.body
        val numbersList = text.getListNumbersFromText()

        val options = mutableListOf<OptionListItem>()
        // 본문복사
        options.add(OptionListItem(activity.getString(R.string.sms_txt_copy), action = { activity.copyToClipboard(text) }))
        // 통보문 전환
        options.add(OptionListItem(activity.getString(R.string.forward_message), action = {
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
        }))
        // 통보문 삭제
        options.add(OptionListItem(activity.getString(R.string.sms_thread_delete), action = { askConfirmDelete(message) }))
        // options.add(OptionListItem(activity.getString(com.goodwy.commons.R.string.share), action = { activity.shareTextIntent(text) }))
        // copy to sim
        if (!message.isMMS) {
            options.add(
                OptionListItem(
                    label = activity.getString(R.string.sim_copy_to_sim),
                    dismissOnSelect = false,
                    action = { copyToSimMessage(message) },
                )
            )
        }
        // 상세정보
        options.add(OptionListItem(activity.getString(R.string.message_details), action = {
            activity.findViewById<eightbitlab.com.blurview.BlurTarget>(com.android.mms.R.id.mainBlurTarget)?.let { bt ->
                MessageDetailsDialog(activity, message, bt)
            }
        }))
//        options.add(activity.getString(android.R.string.selectTextMode) to {
//            activity.findViewById<eightbitlab.com.blurview.BlurTarget>(com.android.mms.R.id.mainBlurTarget)?.let { bt ->
//                SelectTextDialog(activity, text, bt)
//            }
//        })

//        if (numbersList.isNotEmpty()) {
//            val range = if (numbersList.size > 7) 0..7 else 0 until numbersList.size
//            for (index in range) {
//                val number = numbersList[index]
//                val label = activity.getString(com.goodwy.commons.R.string.copy) + " \"$number\""
//                options.add(label to { activity.copyToClipboard(number) })
//            }
//        }

        val blurTarget = activity.findViewById<eightbitlab.com.blurview.BlurTarget>(com.android.mms.R.id.mainBlurTarget)
        val threadActivity = activity as? ThreadActivity
        if (threadActivity != null) {
            threadActivity.showMessageOptionsDialog(
                title = "",
                options = options,
                blurTarget = blurTarget,
            )
        } else {
            OptionListDialog(
                activity = activity,
                title = "",
                options = options,
                blurTarget = blurTarget,
                cancelListener = null,
            )
        }
    }

    private fun showPopupMenuCopyNumbers(numbersList: List<String>, view: View) {
        val menu = MenuBuilder(activity)
        if (numbersList.isNotEmpty()) {
            numbersList.apply {
                val size = numbersList.size
                for (index in 0 until size) {
                    val item = this[index]
                    val menuName = activity.getString(com.goodwy.commons.R.string.copy) + " \"${item}\""
                    menu.add(1, index, index, menuName).setIcon(com.goodwy.commons.R.drawable.ic_copy_vector)
                }
            }
        }
        val blurTarget = activity.findViewById<eightbitlab.com.blurview.BlurTarget>(com.android.mms.R.id.mainBlurTarget)
        showMPopupMenu(
            context = activity,
            anchor = view,
            menu = menu,
            gravity = Gravity.END,
            blurTarget = blurTarget,
            listener = MenuItem.OnMenuItemClickListener { item ->
                if (numbersList.isNotEmpty()) activity.copyToClipboard(numbersList[item.itemId])
                true
            },
        )
    }

    private fun setupReceivedMessageView(messageBinding: ItemMessageBinding, message: Message) {
        messageBinding.apply {
            with(ConstraintSet()) {
                clone(threadMessageHolder)
                clear(threadMessageWrapper.id, ConstraintSet.END)
                connect(threadMessageWrapper.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                applyTo(threadMessageHolder)
            }

            val letterBackgroundColors = activity.getLetterBackgroundColors()
            val primaryOrSenderColor =
                if (activity.config.bubbleInContactColor) letterBackgroundColors[abs(message.senderName.hashCode()) % letterBackgroundColors.size].toInt()
                else activity.getProperPrimaryColor()
            val useSurfaceColor = activity.isDynamicTheme() && !activity.isSystemInDarkMode()
            val surfaceColor = if (useSurfaceColor) activity.getProperBackgroundColor() else activity.getSurfaceColor()
            val backgroundReceived = if (activity.config.bubbleInvertColor) primaryOrSenderColor else surfaceColor
            val selectedBubbleOption = getBubbleDrawableOption(activity.config.bubbleDrawableSet)
            val contrastColorReceived = DARK_GREY

            threadMessageBodyWrapper.apply {
                setOnTouchListener(pinchToZoomTouchListener)
            }
            threadMessageBodyWrapper.apply {
                val isRtl = activity.isRTLLayout
                val bubbleStyle = activity.config.bubbleStyle

                val bubbleReceived = if (selectedBubbleOption != null) {
                    if (isRtl) selectedBubbleOption.outgoingRes else selectedBubbleOption.incomingRes
                } else {
                    when (bubbleStyle) {
                        BUBBLE_STYLE_IOS_NEW -> if (isRtl) R.drawable.item_sent_ios_new_background else R.drawable.item_received_ios_new_background
                        BUBBLE_STYLE_IOS -> if (isRtl) R.drawable.item_sent_ios_background else R.drawable.item_received_ios_background
                        BUBBLE_STYLE_ROUNDED -> if (isRtl) R.drawable.item_sent_rounded_background else R.drawable.item_received_rounded_background
                        else -> if (isRtl) R.drawable.item_sent_background else R.drawable.item_received_background
                    }
                }
                if (selectedBubbleOption == null) {
                    val bubbleDrawable = ResourcesCompat.getDrawable(resources, bubbleReceived, activity.theme)
                    background = bubbleDrawable
                    setPaddingBubble(activity, bubbleStyle)
                    background.applyColorFilter(backgroundReceived)
                } else {
                    applyCustomBubbleBackground(bubbleReceived)
                }

//                messageBinding.threadMessageBodySpacer.layoutParams.height = 40
            }

//            val alignment =
//                if (activity.config.textAlignment == TEXT_ALIGNMENT_ALONG_EDGES) View.TEXT_ALIGNMENT_VIEW_START else View.TEXT_ALIGNMENT_INHERIT
            threadMessageBody.apply {
//                textAlignment = alignment
                setTextColor(contrastColorReceived)
                setLinkTextColor(contrastColorReceived)
            }

            setupMessageTimeSim(messageBinding, message, contrastColorReceived)

            if (isGroupChat && message.body.isNotEmpty() && message.isReceivedMessage()) {
                threadMessageSenderName.apply {
                    beVisible()
//                    textAlignment = alignment
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
//        changed by sun
//        val contrastColorReceived = if (selectedBubbleOption != null) activity.getProperTextColor() else backgroundReceived.getContrastColor()
        val contrastColorReceived = DARK_GREY

        messageBinding.apply {
            with(ConstraintSet()) {
                clone(threadMessageHolder)
                clear(threadMessageWrapper.id, ConstraintSet.START)
                connect(threadMessageWrapper.id, ConstraintSet.END, R.id.thread_message_checkbox, ConstraintSet.START)
                applyTo(threadMessageHolder)
            }

            threadMessageBodyWrapper.apply {
                setOnTouchListener(pinchToZoomTouchListener)
            }

            threadMessageBodyWrapper.apply {
                updateLayoutParams<RelativeLayout.LayoutParams> {
                    removeRule(RelativeLayout.END_OF)
                    addRule(RelativeLayout.ALIGN_PARENT_END)
                }

                val isRtl = Locale.getDefault().layoutDirection == ViewCompat.LAYOUT_DIRECTION_RTL
                val bubbleStyle = activity.config.bubbleStyle

                val bubbleReceived = if (selectedBubbleOption != null) {
                    if (isRtl) selectedBubbleOption.incomingRes else selectedBubbleOption.outgoingRes
                } else {
                    when (bubbleStyle) {
                        BUBBLE_STYLE_IOS_NEW -> if (isRtl) R.drawable.item_received_ios_new_background else R.drawable.item_sent_ios_new_background
                        BUBBLE_STYLE_IOS -> if (isRtl) R.drawable.item_received_ios_background else R.drawable.item_sent_ios_background
                        BUBBLE_STYLE_ROUNDED -> if (isRtl) R.drawable.item_received_rounded_background else R.drawable.item_sent_rounded_background
                        else -> if (isRtl) R.drawable.item_received_background else R.drawable.item_sent_background
                    }
                }
                if (selectedBubbleOption == null) {
                    val bubbleDrawable = AppCompatResources.getDrawable(activity, bubbleReceived)
                    background = bubbleDrawable
                    setPaddingBubble(activity, bubbleStyle, false)
                    background.applyColorFilter(backgroundReceived)
                } else {
                    applyCustomBubbleBackground(bubbleReceived)
                }
            }
            setupMessageTimeSim(messageBinding, message, contrastColorReceived)

            threadMessageBody.apply {
                setTextColor(contrastColorReceived)
                setLinkTextColor(contrastColorReceived)

                if (message.isScheduled) {
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
//                    val scheduledDrawable = AppCompatResources.getDrawable(activity, com.goodwy.commons.R.drawable.ic_clock_vector)?.apply {
//                        applyColorFilter(contrastColorReceived)
//                        val size = lineHeight
//                        //val paddingIconBottom = context.resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.smaller_margin)
//                        setBounds(0, 0, size, size)
//                    }
//
//                    setCompoundDrawables(null, null, scheduledDrawable, null)
                    setCompoundDrawables(null, null, null, null)
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
                // Alps MmsPlayerActivity: for slideshow MMS (2+ media parts) open the player;
                // for single-attachment MMS fall through to plain viewer.
                val mediaCount = message.attachment?.attachments?.count {
                    it.mimetype.isImageMimeType() || it.mimetype.isVideoMimeType()
                } ?: 0
                if (message.isMMS && mediaCount >= 2) {
                    activity.startActivity(
                        Intent(activity, ViewMmsActivity::class.java)
                            .putExtra(EXTRA_MMS_MESSAGE_ID, message.id)
                    )
                } else {
                    activity.launchViewIntent(uri, mimetype, attachment.filename)
                }
            }
        }
        imageView.root.setOnLongClickListener {
            if (actModeCallback.isSelectable) return@setOnLongClickListener false
            showPopupMenu(message, it)
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
                onLongClick = { showPopupMenu(message, holder.itemView) },
                onViewContactDetailsClick = {
                    if (actModeCallback.isSelectable) {
                        holder.viewClicked(message)
                    } else {
                        activity.openContactDetailsFromVCardUri(uri)
                    }
                },
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
                onLongClick = { showPopupMenu(message, holder.itemView) }
            )
        }.root

        parent.addView(attachmentView)
    }

    @SuppressLint("MissingPermission")
    private fun setupMessageTimeSim(
        messageBinding: ItemMessageBinding,
        message: Message,
        textColor: Int
    ) {
        val timeStr = (message.date * 1000L).formatTime(activity, "HH:mm")
        messageBinding.threadMessageTime.apply {
            text = timeStr
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizeSmall)
        }

        val isReceived = message.isReceivedMessage()
        // Status icon for sent messages: error | sending | sent/delivered
        if (isReceived) {
            messageBinding.threadMessageStatusIcon.beGone()
            messageBinding.threadMessageRetryIcon.beGone()
        } else {
            messageBinding.threadMessageStatusIcon.apply {
                stopSendingStatusAnimation()
                clearColorFilter()
                setOnClickListener(null)
                isClickable = false
                isFocusable = false
                when (message.type) {
                    android.provider.Telephony.Sms.MESSAGE_TYPE_FAILED -> {
                        setImageResource(R.drawable.ic_sms_send_fail)
                        contentDescription = activity.getString(R.string.message_not_sent_touch_retry)
                        beVisible()
                    }
                    android.provider.Telephony.Sms.MESSAGE_TYPE_OUTBOX -> {
                        setImageResource(R.drawable.ic_circle_progress)
                        contentDescription = activity.getString(R.string.sending)
                        startSendingStatusAnimation()
                        beVisible()
                    }
                    android.provider.Telephony.Sms.MESSAGE_TYPE_QUEUED -> {
                        setImageResource(com.android.common.R.drawable.ic_cmn_alarm)
                        contentDescription = activity.getString(R.string.sending)
                        beVisible()
                    }
                    android.provider.Telephony.Sms.MESSAGE_TYPE_SENT -> {
                        val isDelivered = message.status == android.provider.Telephony.Sms.STATUS_COMPLETE
                        setImageResource(if (isDelivered) R.drawable.ic_sent_check else R.drawable.ic_send_check)
                        contentDescription = activity.getString(if (isDelivered) R.string.delivered else R.string.sent)
                        beVisible()
                    }
                    else -> beGone()
                }
            }
            messageBinding.threadMessageRetryIcon.apply {
                clearColorFilter()
                setOnClickListener(null)
                isClickable = false
                isFocusable = false
                if (message.type == android.provider.Telephony.Sms.MESSAGE_TYPE_FAILED) {
                    setImageResource(R.drawable.ic_resend_sms)
                    contentDescription = activity.getString(R.string.message_not_sent_touch_retry)
                    setOnClickListener { retryFailedMessage(message) }
                    isClickable = true
                    isFocusable = true
                    beVisible()
                } else {
                    beGone()
                }
            }
        }
        val simIndex = if (hasMultipleSIMCards && message.subscriptionId != -1) {
            activity.subscriptionManagerCompat().activeSubscriptionInfoList
                ?.indexOfFirst { it.subscriptionId == message.subscriptionId }
                ?.takeIf { it >= 0 }
        } else null
        messageBinding.threadMessageSimIcon.apply {
            if (simIndex != null) {
                val simId = simIndex + 1
                val simRes = when (simId) {
                    1 -> com.android.common.R.drawable.ic_cmn_sim1
                    2 -> com.android.common.R.drawable.ic_cmn_sim2
                    else -> R.drawable.ic_sim_vector
                }
                setImageResource(simRes)
                val simColor = activity.resolveSimIconTint(textColor, message.subscriptionId, simId)
                applyColorFilter(simColor)
                beVisible()
            } else {
                beGone()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupDateTime(view: View, dateTime: ThreadDateTime) {
        ItemThreadDateTimeBinding.bind(view).apply {
            threadDateTime.apply {
                // Show only MM.dd at the top of date group (time is in each bubble)
                val lang = Locale.getDefault().getLanguage()

                text = (dateTime.date * 1000L).formatDateOrTime(
                    context = context,
                    hideTimeOnOtherDays = true,
                    showCurrentYear = false,
                    hideTodaysDate = false,
                    dateFormat = getLocaleDateFormatPatternMonthDay()
                )
            }
            // SIM info is now shown in each message bubble; hide from date header
            threadSimIcon.beGone()
            threadSimNumber.beGone()
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isDestroyed && !activity.isFinishing) {
            val binding = (holder as ThreadViewHolder).binding
            if (binding is ItemMessageBinding) {
                binding.threadMessageBody.deactivateEmoView()
                binding.threadMessageStatusIcon.stopSendingStatusAnimation()
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
            is Message -> Message.areItemsTheSame(oldItem, newItem as Message)
            is ThreadDateTime -> {
                val new = newItem as ThreadDateTime
                oldItem.date == new.date && oldItem.simID == new.simID
            }
            else -> true
        }
    }

    override fun areContentsTheSame(oldItem: ThreadItem, newItem: ThreadItem): Boolean {
        if (oldItem::class.java != newItem::class.java) return false
        return when (oldItem) {
            is ThreadDateTime -> oldItem.simID == (newItem as ThreadDateTime).simID
            is Message -> Message.areContentsTheSame(oldItem, newItem as Message)
            else -> true
        }
    }
}
