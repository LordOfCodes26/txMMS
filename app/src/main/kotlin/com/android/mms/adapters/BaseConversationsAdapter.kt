package com.android.mms.adapters

import android.annotation.SuppressLint
import android.provider.Telephony
import android.os.Parcelable
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.behaviorule.arturdumchev.library.pixels
import com.bumptech.glide.Glide
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import com.goodwy.commons.adapters.MyRecyclerViewListAdapter
import com.goodwy.commons.extensions.applyColorFilter
import com.goodwy.commons.models.RecyclerSelectionPayload
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beGoneIf
import com.goodwy.commons.extensions.beInvisible
import com.goodwy.commons.extensions.beInvisibleIf
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.normalizePhoneNumber
import com.goodwy.commons.extensions.formatDateOrTime
import com.goodwy.commons.extensions.getContrastColor
import com.goodwy.commons.extensions.getTextSize
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isRTLLayout
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.setHeightAndWidth
import com.goodwy.commons.extensions.setupViewBackground
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.views.MyRecyclerView
import com.android.mms.R
import com.android.mms.activities.SimpleActivity
import com.android.mms.databinding.ItemConversationBinding
import com.android.mms.extensions.config
import com.android.mms.extensions.getDisplayNumberWithoutCountryCode
import com.android.mms.extensions.getAllDrafts
import com.android.mms.helpers.*
import com.android.mms.models.Conversation
import com.android.mms.models.ConversationListItem
import com.android.mms.databinding.ItemConversationDateHeaderBinding
import com.android.mms.extensions.deleteSmsDraft
import com.android.mms.extensions.getUnreadCountsByThread
import com.android.mms.extensions.saveSmsDraft
import com.goodwy.commons.extensions.getAvatarDrawableIndexForName
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.getTextSizeSmall
import com.goodwy.commons.helpers.AvatarSource
import com.goodwy.commons.helpers.GROUP
import com.goodwy.commons.helpers.MonogramGenerator
import com.goodwy.commons.views.ContactAvatarView
import me.thanel.swipeactionview.SwipeActionView
import me.thanel.swipeactionview.SwipeDirection
import me.thanel.swipeactionview.SwipeGestureListener
import kotlin.time.Duration.Companion.days
import java.util.Calendar
import java.util.HashMap
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

@Suppress("LeakingThis")
abstract class BaseConversationsAdapter(
    activity: SimpleActivity,
    recyclerView: MyRecyclerView,
    onRefresh: () -> Unit,
    itemClick: (Any) -> Unit,
    var isArchived: Boolean = false,
    var isRecycleBin: Boolean = false,
) : MyRecyclerViewListAdapter<ConversationListItem>(
    activity = activity,
    recyclerView = recyclerView,
    diffUtil = ConversationListItemDiffCallback(),
    itemClick = { listItem -> if (listItem is ConversationListItem.ConversationItem) itemClick(listItem.conversation) },
    onRefresh = onRefresh
),
    RecyclerViewFastScroller.OnPopupTextUpdate {

    companion object {
        private const val MAX_UNREAD_BADGE_COUNT = 99
        private const val VIEW_TYPE_DATE_HEADER = 0
        private const val VIEW_TYPE_CONVERSATION = 1
    }

    private var fontSize = activity.getTextSize()
    var smallFontSize: Float = activity.getTextSizeSmall()
    private var drafts = HashMap<Long, String>()
    private var showContactThumbnails = activity.config.showContactThumbnails

    private var recyclerViewState: Parcelable? = null

    private var blackDarkTextColor = resources.getColor(com.android.common.R.color.tx_cardview_title, activity.theme)

    var unreadCountHash = HashMap<Long, Int>(128)
    init {
        unreadCountHash = activity.getUnreadCountsByThread() as HashMap<Long, Int>

        setupDragListener(true)
        setHasStableIds(true)
        updateDrafts()
        recyclerView.itemAnimator?.changeDuration = 0

        registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = restoreRecyclerViewState()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
                restoreRecyclerViewState()

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) =
                restoreRecyclerViewState()
        })
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateFontSize() {
        fontSize = activity.getTextSize()
        notifyDataSetChanged()
    }

    fun updateConversations(
        newConversations: ArrayList<Conversation>,
        commitCallback: (() -> Unit)? = null,
    ) {
        saveRecyclerViewState()
        submitList(groupConversationsByDateSections(newConversations), commitCallback)
    }

    private fun groupConversationsByDateSections(conversations: List<Conversation>): List<ConversationListItem> {
        if (conversations.isEmpty()) return emptyList()
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val yesterdayStart = (todayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val todayStartMillis = todayStart.timeInMillis
        val yesterdayStartMillis = yesterdayStart.timeInMillis
        val result = mutableListOf<ConversationListItem>()
        var lastSection: String? = null
        for (conv in conversations) {
            val dateMillis = conv.date * 1000L
            val section = when {
                dateMillis >= todayStartMillis -> ConversationListItem.SECTION_TODAY
                dateMillis >= yesterdayStartMillis -> ConversationListItem.SECTION_YESTERDAY
                else -> ConversationListItem.SECTION_BEFORE
            }
            if (section != lastSection) {
                val sectionTimestamp = when (section) {
                    ConversationListItem.SECTION_TODAY -> todayStartMillis
                    ConversationListItem.SECTION_YESTERDAY -> yesterdayStartMillis
                    else -> dateMillis
                }
                result += ConversationListItem.DateHeader(timestamp = sectionTimestamp, dayCode = section)
                lastSection = section
            }
            result += ConversationListItem.ConversationItem(conv)
        }
        return result
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateDrafts() {
        ensureBackgroundThread {
            val newDrafts = HashMap<Long, String>()
            fetchDrafts(newDrafts)
            activity.runOnUiThread {
                if (drafts.hashCode() != newDrafts.hashCode()) {
                    drafts = newDrafts
                    notifyDataSetChanged()
                }
            }
        }
    }

    override fun getSelectableItemCount() = currentList.count { it is ConversationListItem.ConversationItem }

    protected fun getSelectedItems(): ArrayList<Conversation> = currentList
        .filterIsInstance<ConversationListItem.ConversationItem>()
        .filter { selectedKeys.contains(it.conversation.hashCode()) }
        .map { it.conversation } as ArrayList<Conversation>

    override fun getIsItemSelectable(position: Int) = currentList.getOrNull(position) is ConversationListItem.ConversationItem

    override fun getItemSelectionKey(position: Int): Int? =
        (currentList.getOrNull(position) as? ConversationListItem.ConversationItem)?.conversation?.hashCode()

    override fun getItemKeyPosition(key: Int) = currentList.indexOfFirst {
        (it as? ConversationListItem.ConversationItem)?.conversation?.hashCode() == key
    }

    protected fun getConversationAt(position: Int): Conversation? =
        (currentList.getOrNull(position) as? ConversationListItem.ConversationItem)?.conversation

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun getItemViewType(position: Int): Int = when (currentList[position]) {
        is ConversationListItem.DateHeader -> VIEW_TYPE_DATE_HEADER
        is ConversationListItem.ConversationItem -> VIEW_TYPE_CONVERSATION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = when (viewType) {
        VIEW_TYPE_DATE_HEADER -> createViewHolder(
            ItemConversationDateHeaderBinding.inflate(layoutInflater, parent, false).root
        )
        else -> createViewHolder(ItemConversationBinding.inflate(layoutInflater, parent, false).root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (val item = currentList[position]) {
            is ConversationListItem.DateHeader -> {
                ItemConversationDateHeaderBinding.bind(holder.itemView).dateTextView.apply {
                    alpha = 0.6f
                    setTextColor(blackDarkTextColor)
                    text = when (item.dayCode) {
                        ConversationListItem.SECTION_TODAY -> activity.getString(R.string.today)
                        ConversationListItem.SECTION_YESTERDAY -> activity.getString(R.string.yesterday)
                        else -> activity.getString(R.string.previous)
                    }
                }
                val params = holder.itemView.layoutParams as? RecyclerView.LayoutParams
                params?.bottomMargin = 0
            }
            is ConversationListItem.ConversationItem -> {
                val conversation = item.conversation
                if (position == currentList.lastIndex) {
                    val params = holder.itemView.layoutParams as RecyclerView.LayoutParams
                    params.bottomMargin = activity.resources.getDimension(com.goodwy.commons.R.dimen.shortcut_size).toInt()
                    holder.itemView.layoutParams = params
                } else {
                    val params = holder.itemView.layoutParams as RecyclerView.LayoutParams
                    params.bottomMargin = 0
                    holder.itemView.layoutParams = params
                }
                holder.bindView(
                    item,
                    allowSingleClick = true,
                    allowLongClick = true
                ) { itemView, _ ->
                    setupView(itemView, conversation, holder)
                }
            }
        }
        bindViewHolder(holder)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        val payload = payloads.firstOrNull()
        if (payload is RecyclerSelectionPayload && currentList.getOrNull(position) is ConversationListItem.ConversationItem) {
            holder.itemView.isSelected = payload.selected
            try {
                val binding = ItemConversationBinding.bind(holder.itemView)
                binding.swipeView.isSelected = payload.selected
                binding.conversationFrameSelect.isSelected = payload.selected
                val isInActionMode = actModeCallback.isSelectable
                binding.conversationCheckbox.beVisibleIf(isInActionMode)
                binding.conversationCheckbox.isChecked = payload.selected
                // binding.conversationChevron.beGoneIf(isInActionMode)
            } catch (_: Exception) { }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemId(position: Int): Long = currentList[position].getItemId()

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isDestroyed && !activity.isFinishing) {
            try {
                val itemView = ItemConversationBinding.bind(holder.itemView)
                Glide.with(activity).clear(itemView.conversationImage)
            } catch (_: Exception) { }
        }
    }

    private fun fetchDrafts(drafts: HashMap<Long, String>) {
        drafts.clear()
        for ((threadId, draft) in activity.getAllDrafts()) {
            drafts[threadId] = draft
        }
    }

    private fun setupView(view: View, conversation: Conversation, holder: ViewHolder) {
        ItemConversationBinding.bind(view).apply {
            val useSurfaceColor = activity.isDynamicTheme() && !activity.isSystemInDarkMode()
            val backgroundColor = if (useSurfaceColor) activity.getSurfaceColor() else activity.getProperBackgroundColor()
            conversationFrame.setBackgroundColor(backgroundColor)
            conversationFrameSelect.setupViewBackground(activity)
            // Ensure tapping anywhere on the visible row toggles selection in act mode.
            conversationFrameSelect.setOnClickListener {
                holder.viewClicked(ConversationListItem.ConversationItem(conversation))
            }
            val isInActionMode = actModeCallback.isSelectable
            val isRowSelected = selectedKeys.contains(conversation.hashCode())
            val smsDraft = drafts[conversation.threadId]
            // SMS Draft Configuration
            conversationBodyShort.translationX = (0 * resources.displayMetrics.density)
            tvConversationCHOGO.beGone()
            val colorRed = resources.getColor(R.color.red_unread, activity.theme)
            if (smsDraft != null) {
                tvConversationCHOGO.beVisible()
                conversationMessageType.beGone()
                conversationBodyShort.translationX = (40 * resources.displayMetrics.density)
//                conversationDraft.beVisible()
//                conversationDraft.apply {
//                    setTextColor(colorRed)
//                    setTextSize(TypedValue.COMPLEX_UNIT_PX, smallFontSize)
//                }
            } else {
                tvConversationCHOGO.beGone()
//                conversationDraft.beGone()
            }
            conversationDraft.beGone()

            // Sent/received type icon (txDial item_recents_type logic): sent = ic_cmn_out, received = ic_cmn_in, draft/other = nothing (lastMessageType set on background when loading list)
            ensureBackgroundThread {
                val lastMessageType = conversation.lastMessageType
                when {
                    conversation.unreadCount > 0 -> {
                        conversationMessageType.beGone()
                        conversationDraft.beVisible()
                        conversationBodyShort.translationX = (-10 * resources.displayMetrics.density)
                        val count = unreadCountHash[conversation.threadId]
                        val szCount = "${count}"
                        conversationDraft.text = szCount
                        if (smsDraft != null){
                            conversationBodyShort.translationX = (40 * resources.displayMetrics.density)
                        }
                    }
                    lastMessageType == Telephony.Sms.MESSAGE_TYPE_INBOX -> {
                        if (smsDraft == null)   {
                            conversationMessageType.setImageResource(R.drawable.ic_sms_in)
                            conversationMessageType.beVisible()
                            conversationBodyShort.translationX = (-5 * resources.displayMetrics.density)
                        }
                        else {
                            if (smsDraft != null){
                                conversationBodyShort.translationX = (40 * resources.displayMetrics.density)
                            }
                        }
                    }
                    lastMessageType == Telephony.Sms.MESSAGE_TYPE_SENT ||
                        lastMessageType == Telephony.Sms.MESSAGE_TYPE_OUTBOX -> {
                        if (smsDraft == null)   {
                            conversationMessageType.setImageResource(R.drawable.ic_sms_out)
                            conversationMessageType.beVisible()
                            conversationBodyShort.translationX = (-5 * resources.displayMetrics.density)
                        }
                        else {
                            if (smsDraft != null){
                                conversationBodyShort.translationX = (40 * resources.displayMetrics.density)
                            }
                        }
                    }
                    lastMessageType == Telephony.Sms.MESSAGE_TYPE_FAILED -> {
                        if (smsDraft == null) {
                            conversationMessageType.setImageResource(R.drawable.ic_sms_send_fail)
                            conversationMessageType.beVisible()
                            conversationBodyShort.translationX = (-5 * resources.displayMetrics.density)
                        }
                        else {
                            if (smsDraft != null){
                                conversationBodyShort.translationX = (40 * resources.displayMetrics.density)
                            }
                        }
                    }
                    lastMessageType == Telephony.Sms.MESSAGE_TYPE_QUEUED -> {
                        if (smsDraft == null) {
                            conversationMessageType.setImageResource(R.drawable.ic_sms_progress)
                            conversationMessageType.beVisible()
                            conversationBodyShort.translationX = (-5 * resources.displayMetrics.density)
                        }
                        else {
                            if (smsDraft != null){
                                conversationBodyShort.translationX = (40 * resources.displayMetrics.density)
                            }
                        }
                    }
                    lastMessageType == Telephony.Sms.MESSAGE_TYPE_DRAFT -> {
                        if (smsDraft == null) {
                            conversationMessageType.beGone()
                            tvConversationCHOGO.beVisible()
                            conversationDraft.beGone()
                            conversationBodyShort.translationX = (-5 * resources.displayMetrics.density)
                        }
                        else {
                            if (smsDraft != null){
                                conversationBodyShort.translationX = (40 * resources.displayMetrics.density)
                            }
                        }
                    }
                    else -> conversationMessageType.beGone()
                }
            }


//            draftClear.apply {
//                beVisibleIf(smsDraft != null && !isInActionMode)
//                setColorFilter(properPrimaryColor)
//                setOnClickListener {
//                    ensureBackgroundThread {
//                        context.deleteSmsDraft(conversation.threadId)
//                        updateDrafts()
//                    }
//                }
//            }
            // val isLastConversation = holder.bindingAdapterPosition == currentList.indexOfLast { it is ConversationListItem.ConversationItem }
            if (!activity.config.useDividers) divider.beInvisible() else divider.beVisible()
            divider.setBackgroundColor(blackDarkTextColor)

            swipeView.isSelected = isRowSelected
            conversationFrameSelect.isSelected = isRowSelected
            conversationCheckbox.apply {
                beVisibleIf(isInActionMode)
                isChecked = isRowSelected
                setColors(blackDarkTextColor, properPrimaryColor, backgroundColor)
                setOnClickListener {
                    if (isInActionMode) {
                        holder.itemView.performClick()
                    }
                }
            }
            // conversationChevron.beGoneIf(isInActionMode)
            val title = conversation.title
            // Hide country code prefix (e.g. +850) when displaying raw phone number not in contacts — only when title has + prefix
            val titleForDisplay = if (title.startsWith("+") && !conversation.isGroupConversation) {
                val normalizedTitle = title.normalizePhoneNumber()
                val normalizedPhone = conversation.phoneNumber.normalizePhoneNumber()
                if (normalizedTitle == normalizedPhone || title == conversation.phoneNumber) {
                    activity.getDisplayNumberWithoutCountryCode(conversation.phoneNumber)
                } else {
                    title
                }
            } else {
                title
            }
            conversationAddress.apply {
                text = titleForDisplay
                setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize )
                if(!conversation.read /*|| conversation.isBlocked*/) {
                    setTextColor(colorRed)
                }
                else {
                    setTextColor(blackDarkTextColor)
                }
            }
            if (conversation.messageCount > 1) {
                conversationAddressCount.beVisible()
                conversationAddressCount.apply {
                    text = "(${conversation.messageCount})"
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, smallFontSize)
                    if(!conversation.read || conversation.isBlocked) setTextColor(colorRed) else setTextColor(blackDarkTextColor)
                }
            } else {
                conversationAddressCount.beGone()
            }

            conversationBodyShort.apply {
                text = smsDraft ?: conversation.snippet
                alpha = 0.6f
                //setTextSize(TypedValue.COMPLEX_UNIT_PX, smallFontSize)
            }
            conversationDate.apply {
                text = formatConversationDate(conversation)
                alpha = 0.6f
                //setTextSize(TypedValue.COMPLEX_UNIT_PX, smallFontSize)
            }

            if (conversation.isBlocked) {
                arrayListOf(conversationBodyShort, conversationDate).forEach {
                    it.setTextColor(colorRed)
                }
            } else {
                arrayListOf(conversationBodyShort, conversationDate).forEach {
                    it.setTextColor(blackDarkTextColor)
                }
            }

//            if (activity.config.unreadIndicatorPosition == UNREAD_INDICATOR_START) {
//                unreadCountBadge.beGone()
//                unreadIndicator.beInvisibleIf(!isUnread)
//                unreadIndicator.setColorFilter(properPrimaryColor)
//                pinIndicator.beVisibleIf(activity.config.pinnedConversations.contains(conversation.threadId.toString()))
//                pinIndicator.applyColorFilter(properPrimaryColor)
//            } else {
//                unreadIndicator.beGone()
//                setupBadgeCount(unreadCountBadge, isUnread, conversation.unreadCount)
//                pinIndicator.beVisibleIf(
//                    activity.config.pinnedConversations.contains(conversation.threadId.toString())
//                        && conversation.read
//                )
//                pinIndicator.applyColorFilter(properPrimaryColor)
//            }

            conversationImage.beGoneIf(!showContactThumbnails)
            if (showContactThumbnails) {
                val size = (root.context.pixels(com.goodwy.commons.R.dimen.call_icon_size) * contactThumbnailsSize).toInt()
                conversationImage.setHeightAndWidth(size)
                bindContactAvatar(conversationImage, conversation)
//                if ((title == conversation.phoneNumber || conversation.isCompany) && conversation.photoUri == "") {
//                    val drawable =
//                        if (conversation.isCompany) SimpleContactsHelper(activity).getColoredCompanyIcon(conversation.title)
//                        else SimpleContactsHelper(activity).getColoredContactIcon(conversation.title)
//                    conversationImage.setImageDrawable(drawable)
//                } else {
//                    // at group conversations we use an icon as the placeholder, not any letter
//                    val placeholder = if (conversation.isGroupConversation) {
//                        SimpleContactsHelper(activity).getColoredGroupIcon(title)
//                    } else {
//                        null
//                    }
//                    SimpleContactsHelper(activity).loadContactImage(
//                        path = conversation.photoUri,
//                        imageView = conversationImage,
//                        placeholderName = title,
//                        placeholderImage = null
//                    )
//                }
            }

            // Swipe (pill motion + animators aligned with txDial RecentCallsAdapter)
            val isRTL = activity.isRTLLayout
            val leftPillColor: Int
            val rightPillColor: Int
            if (isRecycleBin) {
                val swipeLeftResource =
                    if (isRTL) R.drawable.ic_delete_restore else com.goodwy.commons.R.drawable.ic_delete_outline
                swipeLeftIcon.setImageResource(swipeLeftResource)
                leftPillColor =
                    if (isRTL) resources.getColor(R.color.swipe_purple, activity.theme) else resources.getColor(R.color.red_unread, activity.theme)

                val swipeRightResource =
                    if (isRTL) com.goodwy.commons.R.drawable.ic_delete_outline else R.drawable.ic_delete_restore
                swipeRightIcon.setImageResource(swipeRightResource)
                rightPillColor =
                    if (isRTL) resources.getColor(R.color.red_unread, activity.theme) else resources.getColor(R.color.swipe_purple, activity.theme)

                if (activity.config.swipeRipple) {
                    swipeView.setRippleColor(SwipeDirection.Left, leftPillColor)
                    swipeView.setRippleColor(SwipeDirection.Right, rightPillColor)
                }
            } else {
                val swipeLeftAction = if (isRTL) activity.config.swipeRightAction else activity.config.swipeLeftAction
                swipeLeftIcon.setImageResource(swipeActionImageResource(swipeLeftAction, conversation.read))
                leftPillColor = swipeActionColor(swipeLeftAction)

                val swipeRightAction = if (isRTL) activity.config.swipeLeftAction else activity.config.swipeRightAction
                swipeRightIcon.setImageResource(swipeActionImageResource(swipeRightAction, conversation.read))
                rightPillColor = swipeActionColor(swipeRightAction)

                if (!activity.config.useSwipeToAction) {
                    swipeView.setDirectionEnabled(SwipeDirection.Left, false)
                    swipeView.setDirectionEnabled(SwipeDirection.Right, false)
                } else if (isArchived) {
                    if (swipeLeftAction == SWIPE_ACTION_BLOCK || swipeLeftAction == SWIPE_ACTION_NONE) swipeView.setDirectionEnabled(
                        SwipeDirection.Left,
                        false
                    )
                    if (swipeRightAction == SWIPE_ACTION_BLOCK || swipeRightAction == SWIPE_ACTION_NONE) swipeView.setDirectionEnabled(
                        SwipeDirection.Right,
                        false
                    )
                } else {
                    if (swipeLeftAction == SWIPE_ACTION_NONE) swipeView.setDirectionEnabled(SwipeDirection.Left, false)
                    if (swipeRightAction == SWIPE_ACTION_NONE) swipeView.setDirectionEnabled(SwipeDirection.Right, false)
                }

                if (activity.config.swipeRipple) {
                    swipeView.setRippleColor(SwipeDirection.Left, leftPillColor)
                    swipeView.setRippleColor(SwipeDirection.Right, rightPillColor)
                }
            }

            arrayOf(swipeLeftIcon, swipeRightIcon).forEach {
                it.setColorFilter(properPrimaryColor.getContrastColor())
            }

            val halfScreenWidth = activity.resources.displayMetrics.widthPixels / 2
            val swipeWidth = activity.resources.getDimension(com.goodwy.commons.R.dimen.swipe_width)
            if (swipeWidth > halfScreenWidth) {
                swipeRightIconHolder.updateLayoutParams<ViewGroup.LayoutParams> { width = halfScreenWidth }
                swipeLeftIconHolder.updateLayoutParams<ViewGroup.LayoutParams> { width = halfScreenWidth }
            }

            resetSwipeMotionHostVisuals(swipeRightIconMotionHost, swipeView, rightPillColor, swipeRightIcon)
            resetSwipeMotionHostVisuals(swipeLeftIconMotionHost, swipeView, leftPillColor, swipeLeftIcon)
            val motionBaseW = activity.resources.getDimensionPixelSize(R.dimen.swipe_icon_motion_host_width)
            val motionMaxW = activity.resources.getDimensionPixelSize(R.dimen.swipe_icon_motion_host_width_max)
            val motionMarginPx = activity.resources.getDimension(com.goodwy.commons.R.dimen.big_margin)
            swipeView.rightSwipeAnimator =
                swipeStripMotionHostAnimator(
                    swipeRightIconMotionHost,
                    swipeRightIconHolder,
                    swipeView,
                    motionMarginPx,
                    slideTowardContent = true,
                    baseWidthPx = motionBaseW,
                    maxWidthPx = motionMaxW,
                    pillColor = rightPillColor,
                    iconView = swipeRightIcon,
                )
            swipeView.leftSwipeAnimator =
                swipeStripMotionHostAnimator(
                    swipeLeftIconMotionHost,
                    swipeLeftIconHolder,
                    swipeView,
                    motionMarginPx,
                    slideTowardContent = false,
                    baseWidthPx = motionBaseW,
                    maxWidthPx = motionMaxW,
                    pillColor = leftPillColor,
                    iconView = swipeLeftIcon,
                )

            swipeView.useHapticFeedback = activity.config.swipeVibration
            swipeView.swipeGestureListener = object : SwipeGestureListener {
                override fun onSwipedLeft(swipeActionView: SwipeActionView): Boolean {
                    finishActMode()
                    resetSwipeMotionHostVisuals(swipeLeftIconMotionHost, swipeView, leftPillColor, swipeLeftIcon)
                    resetSwipeMotionHostVisuals(swipeRightIconMotionHost, swipeView, rightPillColor, swipeRightIcon)
                    swipedLeft(conversation)
                    return true
                }

                override fun onSwipedRight(swipeActionView: SwipeActionView): Boolean {
                    finishActMode()
                    resetSwipeMotionHostVisuals(swipeLeftIconMotionHost, swipeView, leftPillColor, swipeLeftIcon)
                    resetSwipeMotionHostVisuals(swipeRightIconMotionHost, swipeView, rightPillColor, swipeRightIcon)
                    swipedRight(conversation)
                    return true
                }

                override fun onSwipeLeftComplete(swipeActionView: SwipeActionView) {
                    resetSwipeMotionHostVisuals(swipeLeftIconMotionHost, swipeView, leftPillColor, swipeLeftIcon)
                    resetSwipeMotionHostVisuals(swipeRightIconMotionHost, swipeView, rightPillColor, swipeRightIcon)
                }

                override fun onSwipeRightComplete(swipeActionView: SwipeActionView) {
                    resetSwipeMotionHostVisuals(swipeLeftIconMotionHost, swipeView, leftPillColor, swipeLeftIcon)
                    resetSwipeMotionHostVisuals(swipeRightIconMotionHost, swipeView, rightPillColor, swipeRightIcon)
                }
            }
        }
    }

    private fun bindContactAvatar(avatarView: ContactAvatarView, conversation: Conversation) {
        val isUnsavedMessage = conversation.threadId <= 0 || conversation.phoneNumber == conversation.title
        if (isUnsavedMessage) {
            // Match txDial RecentCallsAdapter: unsaved numbers use profile icon on gradient, not a letter (empty initials → "A").
            avatarView.bind(
                AvatarSource.Monogram(
                    initials = "",
                    gradientColors = MonogramGenerator.generateGradientColors(conversation.phoneNumber),
                    drawableIndex = activity.getAvatarDrawableIndexForName(conversation.phoneNumber).takeIf { it >= 0 },
                    showProfileIcon = true
                )
            )
            return
        }

        val avatarSeed = conversation.title.ifEmpty { conversation.phoneNumber }
        val drawableIndex = activity.getAvatarDrawableIndexForName(avatarSeed).takeIf { it >= 0 }
        val shouldUsePhoto = !activity.isDestroyed &&
            !activity.isFinishing &&
            conversation.photoUri.isNotBlank() &&
            !conversation.isGroupConversation &&
            conversation.phoneNumber != conversation.title

        avatarView.bind(
            if (shouldUsePhoto) {
                AvatarSource.Photo(conversation.photoUri)
            } else if (conversation.isGroupConversation) {
                AvatarSource.Monogram(
                    initials = GROUP,
                    gradientColors = MonogramGenerator.generateGradientColors(conversation.phoneNumber),
                    drawableIndex = activity.getAvatarDrawableIndexForName(conversation.phoneNumber).takeIf { it >= 0 }
                )
            } else {
                AvatarSource.Monogram(
                    initials = MonogramGenerator.generateInitials(avatarSeed),
                    gradientColors = MonogramGenerator.generateGradientColors(avatarSeed),
                    drawableIndex = drawableIndex
                )
            }
        )
    }

    private fun setupBadgeCount(view: TextView, isUnread: Boolean, count: Int) {
        view.apply {
            beInvisibleIf(!isUnread)
            if (isUnread) {
                text = when {
                    count > MAX_UNREAD_BADGE_COUNT -> "$MAX_UNREAD_BADGE_COUNT+"
                    count == 0 -> ""
                    else -> count.toString()
                }
                setTextColor(properPrimaryColor.getContrastColor())
                background?.applyColorFilter(properPrimaryColor)
            }
        }
    }

    private fun getSectionDayCodeForPosition(position: Int): String? {
        if (position !in currentList.indices) return null
        for (index in position downTo 0) {
            val item = currentList[index]
            if (item is ConversationListItem.DateHeader) {
                return item.dayCode
            }
        }
        return null
    }
    private fun formatConversationDate(conversation: Conversation): String {
//        val primaryText = when (sectionDayCode) {
//            ConversationListItem.SECTION_TODAY -> {
//                val now = System.currentTimeMillis()
//                if (abs(now - (conversation?.date ?: 0)) < DateUtils.MINUTE_IN_MILLIS) {
//                    resources.getString(com.goodwy.commons.R.string.now)
//                } else {
//                    conversation?.date
//                }
//            }
//            ConversationListItem.SECTION_YESTERDAY -> activity.getString(R.string.yesterday)
//            ConversationListItem.SECTION_BEFORE -> conversation?.date
//            else -> conversation?.date
//        }
//        val langType = activity.resources.configuration.locales[0]?.language ?: Locale.getDefault().language
//        return primaryText.toString()
//        val normalizedPrimaryText = Converters.normalizeRelativeTextForKorean(primaryText.toString(), langType)
//        return normalizedPrimaryText
        val primaryText = if (activity.config.useRelativeDate) {
            DateUtils.getRelativeDateTimeString(
                activity,
                conversation.date * 1000L,
                1.minutes.inWholeMilliseconds,
                2.days.inWholeMilliseconds,
                0,
            )
        } else {
            (conversation.date * 1000L).formatDateOrTime(
                context = activity,
                hideTimeOnOtherDays = true,
                showCurrentYear = false,
                useShamsi = true
            )
        }.toString()
        val normalizedPrimaryText = normalizeRelativeTextForKorean(primaryText)
        return normalizedPrimaryText
    }

    private fun normalizeRelativeTextForKorean(text: String): String {
        val language = activity.resources.configuration.locales[0]?.language ?: Locale.getDefault().language
        if (language != Locale.KOREAN.language) return text

        val koreanCompact = text
            .replace("분 전", "분전")
            .replace("시간 전", "시간전")
            .replace("일 전", "일전")
            .replace("주 전", "주전")
            .replace("개월 전", "개월전")
            .replace("년 전", "년전")

        return koreanCompact
            .replace(Regex("""(\d+)\s*min\.?\s*ago""", RegexOption.IGNORE_CASE), "$1분전")
            .replace(Regex("""(\d+)\s*mins\.?\s*ago""", RegexOption.IGNORE_CASE), "$1분전")
            .replace(Regex("""(\d+)\s*hr\.?\s*ago""", RegexOption.IGNORE_CASE), "$1시간전")
            .replace(Regex("""(\d+)\s*hrs\.?\s*ago""", RegexOption.IGNORE_CASE), "$1시간전")
            .replace(Regex("""(\d+)\s*hour[s]?\s*ago""", RegexOption.IGNORE_CASE), "$1시간전")
            .replace(Regex("""(\d+)\s*day[s]?\s*ago""", RegexOption.IGNORE_CASE), "$1일전")
            .replace(Regex("""(\d+)\s*week[s]?\s*ago""", RegexOption.IGNORE_CASE), "$1주전")
            .replace(Regex("""(\d+)\s*month[s]?\s*ago""", RegexOption.IGNORE_CASE), "$1개월전")
            .replace(Regex("""(\d+)\s*year[s]?\s*ago""", RegexOption.IGNORE_CASE), "$1년전")
    }

    override fun onChange(position: Int): String = when (val item = currentList.getOrNull(position)) {
        is ConversationListItem.ConversationItem -> formatConversationDate(item.conversation)
        is ConversationListItem.DateHeader -> when (item.dayCode) {
            ConversationListItem.SECTION_TODAY -> activity.getString(R.string.today)
            ConversationListItem.SECTION_YESTERDAY -> activity.getString(R.string.yesterday)
            else -> activity.getString(R.string.previous)
        }
        null -> ""
    }

    private fun saveRecyclerViewState() {
        recyclerViewState = recyclerView.layoutManager?.onSaveInstanceState()
    }

    private fun restoreRecyclerViewState() {
        recyclerView.layoutManager?.onRestoreInstanceState(recyclerViewState)
    }

    private class ConversationListItemDiffCallback : DiffUtil.ItemCallback<ConversationListItem>() {
        override fun areItemsTheSame(oldItem: ConversationListItem, newItem: ConversationListItem): Boolean =
            oldItem.getItemId() == newItem.getItemId()

        override fun areContentsTheSame(oldItem: ConversationListItem, newItem: ConversationListItem): Boolean =
            when {
                oldItem is ConversationListItem.DateHeader && newItem is ConversationListItem.DateHeader ->
                    oldItem.timestamp == newItem.timestamp && oldItem.dayCode == newItem.dayCode
                oldItem is ConversationListItem.ConversationItem && newItem is ConversationListItem.ConversationItem ->
                    Conversation.areContentsTheSame(oldItem.conversation, newItem.conversation)
                else -> false
            }
    }

    abstract fun swipedLeft(conversation: Conversation)

    abstract fun swipedRight(conversation: Conversation)

    private fun swipeActionImageResource(swipeAction: Int, read: Boolean): Int {
        return when (swipeAction) {
            SWIPE_ACTION_DELETE -> com.goodwy.commons.R.drawable.ic_delete_outline
            SWIPE_ACTION_ARCHIVE -> if (isArchived) R.drawable.ic_unarchive_vector else R.drawable.ic_archive_vector
            SWIPE_ACTION_BLOCK -> com.goodwy.commons.R.drawable.ic_block_vector
            SWIPE_ACTION_CALL -> com.goodwy.commons.R.drawable.ic_phone_vector
            SWIPE_ACTION_MESSAGE -> R.drawable.ic_messages
            else -> if (read) R.drawable.ic_mark_unread else R.drawable.ic_mark_read
        }
    }

    private fun swipeActionColor(swipeAction: Int): Int {
        return when (swipeAction) {
            SWIPE_ACTION_DELETE -> resources.getColor(R.color.red_unread, activity.theme)
            SWIPE_ACTION_ARCHIVE -> resources.getColor(R.color.swipe_purple, activity.theme)
            SWIPE_ACTION_BLOCK -> resources.getColor(R.color.red_unread, activity.theme)
            SWIPE_ACTION_CALL -> resources.getColor(R.color.green_call_dark, activity.theme)
            SWIPE_ACTION_MESSAGE -> resources.getColor(com.goodwy.commons.R.color.ic_messages, activity.theme)
            else -> properPrimaryColor
        }
    }
}

