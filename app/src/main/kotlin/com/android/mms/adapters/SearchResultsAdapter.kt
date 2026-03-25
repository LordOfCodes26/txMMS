package com.android.mms.adapters

import android.util.TypedValue
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.behaviorule.arturdumchev.library.pixels
import com.goodwy.commons.adapters.MyRecyclerViewAdapter
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.AvatarSource
import com.goodwy.commons.helpers.GROUP
import com.goodwy.commons.helpers.MonogramGenerator
import com.goodwy.commons.views.ContactAvatarView
import com.goodwy.commons.views.MyRecyclerView
import com.android.mms.R
import com.android.mms.activities.SimpleActivity
import com.android.mms.databinding.ItemConversationDateHeaderBinding
import com.android.mms.databinding.ItemSearchResultBinding
import com.android.mms.extensions.config
import com.android.mms.models.ConversationListItem
import com.android.mms.models.SearchListItem
import com.android.mms.models.SearchResult

class SearchResultsAdapter(
    activity: SimpleActivity,
    var items: ArrayList<SearchListItem>,
    recyclerView: MyRecyclerView,
    highlightText: String,
    itemClick: (Any) -> Unit,
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick) {

    private var fontSize = activity.getTextSize()
    private var textToHighlight = highlightText
    private var showContactThumbnails = activity.config.showContactThumbnails

    private val blackDarkTextColor =
        resources.getColor(com.android.common.R.color.tx_cardview_title, activity.theme)

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ROW = 1
    }

    override fun getActionMenuId() = 0
    override fun getMorePopupMenuId() = 0

    override fun prepareActionMode(menu: Menu) {}

    override fun actionItemPressed(id: Int) {}

    override fun getSelectableItemCount() = items.count { it is SearchListItem.ResultRow }

    override fun getIsItemSelectable(position: Int) = false

    override fun getItemSelectionKey(position: Int) = when (val item = items.getOrNull(position)) {
        is SearchListItem.ResultRow -> item.result.hashCode()
        else -> null
    }

    override fun getItemKeyPosition(key: Int) = items.indexOfFirst {
        it is SearchListItem.ResultRow && it.result.hashCode() == key
    }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is SearchListItem.DateHeader -> VIEW_TYPE_HEADER
        is SearchListItem.ResultRow -> VIEW_TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = when (viewType) {
        VIEW_TYPE_HEADER -> createViewHolder(
            ItemConversationDateHeaderBinding.inflate(layoutInflater, parent, false).root,
        )
        else -> createViewHolder(ItemSearchResultBinding.inflate(layoutInflater, parent, false).root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (val item = items[position]) {
            is SearchListItem.DateHeader -> {
                val params = holder.itemView.layoutParams as RecyclerView.LayoutParams
                params.bottomMargin = 0
                holder.itemView.layoutParams = params
                ItemConversationDateHeaderBinding.bind(holder.itemView).dateTextView.apply {
                    alpha = 0.6f
                    setTextColor(blackDarkTextColor)
                    text = when (item.dayCode) {
                        ConversationListItem.SECTION_TODAY -> activity.getString(R.string.today)
                        ConversationListItem.SECTION_YESTERDAY -> activity.getString(R.string.yesterday)
                        else -> activity.getString(R.string.previous)
                    }
                }
                holder.bindView(item, allowSingleClick = false, allowLongClick = false) { _, _ -> }
            }
            is SearchListItem.ResultRow -> {
                val isLastRow = position == items.lastIndex
                if (isLastRow) {
                    val params = holder.itemView.layoutParams as RecyclerView.LayoutParams
                    val margin =
                        activity.resources.getDimension(com.goodwy.commons.R.dimen.shortcut_size).toInt()
                    params.bottomMargin = margin
                    holder.itemView.layoutParams = params
                } else {
                    val params = holder.itemView.layoutParams as RecyclerView.LayoutParams
                    params.bottomMargin = 0
                    holder.itemView.layoutParams = params
                }
                val searchResult = item.result
                holder.bindView(searchResult, allowSingleClick = true, allowLongClick = false) { itemView, _ ->
                    setupView(itemView, searchResult, position)
                }
            }
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: ArrayList<SearchListItem>, highlightText: String = "") {
        if (newItems.hashCode() != items.hashCode()) {
            items = ArrayList(newItems)
            textToHighlight = highlightText
            notifyDataSetChanged()
        } else if (textToHighlight != highlightText) {
            textToHighlight = highlightText
            notifyDataSetChanged()
        }
    }

    private fun setupView(view: View, searchResult: SearchResult, position: Int) {
        fontSize = activity.getTextSize()
        showContactThumbnails = activity.config.showContactThumbnails

        ItemSearchResultBinding.bind(view).apply {
            val useSurfaceColor = activity.isDynamicTheme() && !activity.isSystemInDarkMode()
            val backgroundColor =
                if (useSurfaceColor) activity.getSurfaceColor() else activity.getProperBackgroundColor()
            searchResultFrame.setBackgroundColor(backgroundColor)
            searchResultFrameSelect.setupViewBackground(activity)

            val colorRed = resources.getColor(R.color.red_unread, activity.theme)

            val isLastResultRow = position == items.lastIndex
            if (!activity.config.useDividers || isLastResultRow) {
                divider.beInvisible()
            } else {
                divider.beVisible()
            }
            divider.setBackgroundColor(blackDarkTextColor)

            searchResultTitle.apply {
                text = searchResult.title.highlightTextPart(textToHighlight, properPrimaryColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
                when {
                    searchResult.isBlocked -> setTextColor(colorRed)
                    else -> setTextColor(blackDarkTextColor)
                }
            }

            searchResultSnippet.apply {
                text = searchResult.snippet.highlightTextPart(textToHighlight, properPrimaryColor)
                alpha = 0.6f
                if (searchResult.isBlocked) {
                    setTextColor(colorRed)
                } else {
                    setTextColor(blackDarkTextColor)
                }
            }

            searchResultDate.apply {
                text = searchResult.date
                alpha = 0.6f
                if (searchResult.isBlocked) {
                    setTextColor(colorRed)
                } else {
                    setTextColor(blackDarkTextColor)
                }
            }

            searchResultImage.beGoneIf(!showContactThumbnails)
            if (showContactThumbnails) {
                val size =
                    (root.context.pixels(com.goodwy.commons.R.dimen.call_icon_size) * contactThumbnailsSize).toInt()
                searchResultImage.setHeightAndWidth(size)
                bindSearchAvatar(searchResultImage, searchResult)
            }
        }
    }

    private fun bindSearchAvatar(avatarView: ContactAvatarView, searchResult: SearchResult) {
        val phoneNumber = searchResult.phoneNumber ?: ""
        val title = searchResult.title
        val threadId = searchResult.threadId
        val isUnsavedMessage = threadId <= 0 || phoneNumber == title
        if (isUnsavedMessage) {
            avatarView.bind(
                AvatarSource.Monogram(
                    initials = "",
                    gradientColors = MonogramGenerator.generateGradientColors(phoneNumber),
                    drawableIndex = activity.getAvatarDrawableIndexForName(phoneNumber).takeIf { it >= 0 },
                    showProfileIcon = true,
                ),
            )
            return
        }

        val isGroupConversation = title.contains(",")
        val avatarSeed = title.ifEmpty { phoneNumber }
        val drawableIndex = activity.getAvatarDrawableIndexForName(avatarSeed).takeIf { it >= 0 }
        val shouldUsePhoto =
            !activity.isDestroyed &&
                !activity.isFinishing &&
                searchResult.photoUri.isNotBlank() &&
                !isGroupConversation &&
                phoneNumber != title

        avatarView.bind(
            if (shouldUsePhoto) {
                AvatarSource.Photo(searchResult.photoUri)
            } else if (isGroupConversation) {
                AvatarSource.Monogram(
                    initials = GROUP,
                    gradientColors = MonogramGenerator.generateGradientColors(phoneNumber),
                    drawableIndex = activity.getAvatarDrawableIndexForName(phoneNumber).takeIf { it >= 0 },
                )
            } else {
                AvatarSource.Monogram(
                    initials = MonogramGenerator.generateInitials(avatarSeed),
                    gradientColors = MonogramGenerator.generateGradientColors(avatarSeed),
                    drawableIndex = drawableIndex,
                )
            },
        )
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (holder.itemViewType == VIEW_TYPE_ROW && !activity.isDestroyed && !activity.isFinishing) {
            val binding = ItemSearchResultBinding.bind(holder.itemView)
            Glide.with(activity).clear(binding.searchResultImage)
        }
    }
}
