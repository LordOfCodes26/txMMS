package com.android.mms.adapters

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.provider.Telephony
import android.os.Parcelable
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.behaviorule.arturdumchev.library.pixels
import com.bumptech.glide.Glide
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import com.goodwy.commons.adapters.MyRecyclerViewListAdapter
import com.goodwy.commons.extensions.adjustAlpha
import com.goodwy.commons.extensions.applyColorFilter
import com.goodwy.commons.models.RecyclerSelectionPayload
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beGoneIf
import com.goodwy.commons.extensions.beInvisible
import com.goodwy.commons.extensions.beInvisibleIf
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.formatDateOrTime
import com.goodwy.commons.extensions.getContrastColor
import com.goodwy.commons.extensions.getTextSize
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isRTLLayout
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.setHeightAndWidth
import com.goodwy.commons.extensions.setupViewBackground
import com.goodwy.commons.extensions.slideLeft
import com.goodwy.commons.extensions.slideLeftReturn
import com.goodwy.commons.extensions.slideRight
import com.goodwy.commons.extensions.slideRightReturn
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.views.MyRecyclerView
import com.android.mms.R
import com.android.mms.activities.SimpleActivity
import com.android.mms.databinding.ItemConversationBinding
import com.android.mms.extensions.config
import com.android.mms.extensions.deleteSmsDraft
import com.android.mms.extensions.getAllDrafts
import com.android.mms.helpers.*
import com.android.mms.models.Conversation
import com.android.mms.models.ConversationListItem
import com.android.mms.databinding.ItemConversationDateHeaderBinding
import me.thanel.swipeactionview.SwipeActionView
import me.thanel.swipeactionview.SwipeDirection
import me.thanel.swipeactionview.SwipeGestureListener
import kotlin.time.Duration.Companion.days
import java.util.Calendar
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
    private var drafts = HashMap<Long, String>()
    private var showContactThumbnails = activity.config.showContactThumbnails

    private var recyclerViewState: Parcelable? = null

    init {
        setupDragListener(true)
        setHasStableIds(true)
        updateDrafts()

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
                    setTextColor(textColor.adjustAlpha(0.7f))
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize * 0.76f)
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
                binding.conversationChevron.beGoneIf(isInActionMode)
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
            conversationFrameSelect.setupViewBackground(activity)
            // Ensure tapping anywhere on the visible row toggles selection in act mode.
            conversationFrameSelect.setOnClickListener {
                holder.viewClicked(ConversationListItem.ConversationItem(conversation))
            }
            val isInActionMode = actModeCallback.isSelectable
            val isRowSelected = selectedKeys.contains(conversation.hashCode())
            val smsDraft = drafts[conversation.threadId]
            draftIndicator.beVisibleIf(!smsDraft.isNullOrEmpty())
            draftIndicator.setTextColor(properPrimaryColor)

            // Sent/received type icon (txDial item_recents_type logic): sent = ic_cmn_out, received = ic_cmn_in, draft/other = nothing (lastMessageType set on background when loading list)
            val lastMessageType = conversation.lastMessageType
            when {
                smsDraft != null -> conversationMessageType.beGone()
                lastMessageType == Telephony.Sms.MESSAGE_TYPE_INBOX -> {
                    conversationMessageType.setImageResource(R.drawable.ic_cmn_in)
                    conversationMessageType.beVisible()
                    conversationMessageType.applyColorFilter(textColor)
                }
                lastMessageType == Telephony.Sms.MESSAGE_TYPE_SENT ||
                    lastMessageType == Telephony.Sms.MESSAGE_TYPE_OUTBOX ||
                    lastMessageType == Telephony.Sms.MESSAGE_TYPE_FAILED ||
                    lastMessageType == Telephony.Sms.MESSAGE_TYPE_QUEUED -> {
                    conversationMessageType.setImageResource(R.drawable.ic_cmn_out)
                    conversationMessageType.beVisible()
                    conversationMessageType.applyColorFilter(textColor)
                }
                else -> conversationMessageType.beGone()
            }

            if (activity.isDynamicTheme() && !activity.isSystemInDarkMode()) {
                conversationFrame.setBackgroundColor(surfaceColor)
            } else {
                conversationFrame.setBackgroundColor(backgroundColor)
            }

            draftClear.apply {
                beVisibleIf(smsDraft != null && !isInActionMode)
                setColorFilter(properPrimaryColor)
                setOnClickListener {
                    ensureBackgroundThread {
                        context.deleteSmsDraft(conversation.threadId)
                        updateDrafts()
                    }
                }
            }

            val isLastConversation = holder.bindingAdapterPosition == currentList.indexOfLast { it is ConversationListItem.ConversationItem }
            if (isLastConversation || !activity.config.useDividers) divider.beInvisible() else divider.beVisible()

            swipeView.isSelected = isRowSelected
            conversationFrameSelect.isSelected = isRowSelected
            conversationCheckbox.apply {
                beVisibleIf(isInActionMode)
                isChecked = isRowSelected
                setColors(textColor, properPrimaryColor, backgroundColor)
                setOnClickListener {
                    if (isInActionMode) {
                        holder.itemView.performClick()
                    }
                }
            }
            conversationChevron.beGoneIf(isInActionMode)

            val title = conversation.title
            val displayTitle = if (conversation.messageCount > 0) {
                "$title (${conversation.messageCount})"
            } else {
                title
            }
            conversationAddress.apply {
                text = displayTitle
                setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize * 1.2f)
            }

            conversationBodyShort.apply {
                text = smsDraft ?: conversation.snippet
                setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize * 0.9f)
                maxLines = activity.config.linesCount
            }

            conversationDate.apply {
                text = formatConversationDate(conversation.date)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize * 0.8f)
            }

            val isUnread = !conversation.read
            val style = if (isUnread) {
                conversationBodyShort.alpha = 1f
                if (conversation.isScheduled) Typeface.BOLD_ITALIC else Typeface.BOLD
            } else {
                conversationBodyShort.alpha = 0.7f
                if (conversation.isScheduled) Typeface.ITALIC else Typeface.NORMAL
            }
            conversationAddress.setTypeface(null, style)
            conversationBodyShort.setTypeface(null, style)
            conversationDate.setTypeface(null, style)


            if (conversation.isBlocked) {
                val colorRed = resources.getColor(R.color.red_call, activity.theme)
                conversationChevron.applyColorFilter(colorRed)
                arrayListOf(conversationAddress, conversationBodyShort, conversationDate).forEach {
                    it.setTextColor(colorRed)
                }
            } else {
                conversationChevron.applyColorFilter(textColor)
                arrayListOf(conversationAddress, conversationBodyShort, conversationDate).forEach {
                    it.setTextColor(textColor)
                }
            }

            if (activity.config.unreadIndicatorPosition == UNREAD_INDICATOR_START) {
                unreadCountBadge.beGone()
                unreadIndicator.beInvisibleIf(!isUnread)
                unreadIndicator.setColorFilter(properPrimaryColor)
                pinIndicator.beVisibleIf(activity.config.pinnedConversations.contains(conversation.threadId.toString()))
                pinIndicator.applyColorFilter(properPrimaryColor)
            } else {
                unreadIndicator.beGone()
                setupBadgeCount(unreadCountBadge, isUnread, conversation.unreadCount)
                pinIndicator.beVisibleIf(
                    activity.config.pinnedConversations.contains(conversation.threadId.toString())
                        && conversation.read
                )
                pinIndicator.applyColorFilter(properPrimaryColor)
            }

            divider.setBackgroundColor(textColor)
            conversationImage.beGoneIf(!showContactThumbnails)
            if (showContactThumbnails) {
                val size = (root.context.pixels(com.goodwy.commons.R.dimen.normal_icon_size) * contactThumbnailsSize).toInt()
                conversationImage.setHeightAndWidth(size)
                if ((title == conversation.phoneNumber || conversation.isCompany) && conversation.photoUri == "") {
                    val drawable =
                        if (conversation.isCompany) SimpleContactsHelper(activity).getColoredCompanyIcon(conversation.title)
                        else SimpleContactsHelper(activity).getColoredContactIcon(conversation.title)
                    conversationImage.setImageDrawable(drawable)
                } else {
                    // at group conversations we use an icon as the placeholder, not any letter
                    val placeholder = if (conversation.isGroupConversation) {
                        SimpleContactsHelper(activity).getColoredGroupIcon(title)
                    } else {
                        null
                    }

                    SimpleContactsHelper(activity).loadContactImage(
                        path = conversation.photoUri,
                        imageView = conversationImage,
                        placeholderName = title,
                        placeholderImage = placeholder
                    )
                }
            }

            //swipe
            val isRTL = activity.isRTLLayout
            if (isRecycleBin) {
                val swipeLeftResource =
                    if (isRTL) R.drawable.ic_delete_restore else com.goodwy.commons.R.drawable.ic_delete_outline
                swipeLeftIcon.setImageResource(swipeLeftResource)
                val swipeLeftColor =
                    if (isRTL) resources.getColor(R.color.swipe_purple, activity.theme) else resources.getColor(R.color.red_call, activity.theme)
                swipeLeftIconHolder.setBackgroundColor(swipeLeftColor)

                val swipeRightResource =
                    if (isRTL) com.goodwy.commons.R.drawable.ic_delete_outline else R.drawable.ic_delete_restore
                swipeRightIcon.setImageResource(swipeRightResource)
                val swipeRightColor =
                    if (isRTL) resources.getColor(R.color.red_call, activity.theme) else resources.getColor(R.color.swipe_purple, activity.theme)
                swipeRightIconHolder.setBackgroundColor(swipeRightColor)

                if (activity.config.swipeRipple) {
                    swipeView.setRippleColor(SwipeDirection.Left, swipeLeftColor)
                    swipeView.setRippleColor(SwipeDirection.Right, swipeRightColor)
                }
            } else {
                val swipeLeftAction = if (isRTL) activity.config.swipeRightAction else activity.config.swipeLeftAction
                swipeLeftIcon.setImageResource(swipeActionImageResource(swipeLeftAction, conversation.read))
                swipeLeftIconHolder.setBackgroundColor(swipeActionColor(swipeLeftAction))

                val swipeRightAction = if (isRTL) activity.config.swipeLeftAction else activity.config.swipeRightAction
                swipeRightIcon.setImageResource(swipeActionImageResource(swipeRightAction, conversation.read))
                swipeRightIconHolder.setBackgroundColor(swipeActionColor(swipeRightAction))

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
                    swipeView.setRippleColor(SwipeDirection.Left, swipeActionColor(swipeLeftAction))
                    swipeView.setRippleColor(SwipeDirection.Right, swipeActionColor(swipeRightAction))
                }
            }

            arrayOf(
                swipeLeftIcon, swipeRightIcon
            ).forEach {
                it.setColorFilter(properPrimaryColor.getContrastColor())
            }

            swipeView.useHapticFeedback = activity.config.swipeVibration
            swipeView.swipeGestureListener = object : SwipeGestureListener {
                override fun onSwipedLeft(swipeActionView: SwipeActionView): Boolean {
                    swipeLeftIcon.slideLeftReturn(swipeLeftIconHolder)
                    swipedLeft(conversation)
                    return true
                }

                override fun onSwipedRight(swipeActionView: SwipeActionView): Boolean {
                    swipeRightIcon.slideRightReturn(swipeRightIconHolder)
                    swipedRight(conversation)
                    return true
                }

                override fun onSwipedActivated(swipedRight: Boolean) {
                    if (swipedRight) swipeRightIcon.slideRight(swipeRightIconHolder)
                    else swipeLeftIcon.slideLeft()
                }

                override fun onSwipedDeactivated(swipedRight: Boolean) {
                    if (swipedRight) swipeRightIcon.slideRightReturn(swipeRightIconHolder)
                    else swipeLeftIcon.slideLeftReturn(swipeLeftIconHolder)
                }
            }
        }
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

    private fun formatConversationDate(date: Int?): String {
        return if (date != null) {
            if (activity.config.useRelativeDate) {
                DateUtils.getRelativeDateTimeString(
                    activity,
                    date * 1000L,
                    1.minutes.inWholeMilliseconds,
                    2.days.inWholeMilliseconds,
                    0,
                )
            } else {
                (date * 1000L).formatDateOrTime(
                    context = activity,
                    hideTimeOnOtherDays = true,
                    showCurrentYear = false
                )
            }.toString()
        } else "No date"
    }

    override fun onChange(position: Int): String = when (val item = currentList.getOrNull(position)) {
        is ConversationListItem.ConversationItem -> formatConversationDate(item.conversation.date)
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
            SWIPE_ACTION_DELETE -> resources.getColor(R.color.red_call, activity.theme)
            SWIPE_ACTION_ARCHIVE -> resources.getColor(R.color.swipe_purple, activity.theme)
            SWIPE_ACTION_BLOCK -> resources.getColor(com.goodwy.commons.R.color.red_700, activity.theme)
            SWIPE_ACTION_CALL -> resources.getColor(R.color.green_call, activity.theme)
            SWIPE_ACTION_MESSAGE -> resources.getColor(com.goodwy.commons.R.color.ic_messages, activity.theme)
            else -> properPrimaryColor
        }
    }
}



