package com.android.mms.adapters

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import com.android.common.dialogs.MConfirmDialog
import com.goodwy.commons.dialogs.OptionListDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.views.MyRecyclerView
import com.android.mms.BuildConfig
import com.android.mms.R
import com.android.mms.databinding.ItemConversationBinding
import com.android.mms.activities.MainActivity
import com.android.mms.activities.SimpleActivity
import com.android.mms.dialogs.RenameConversationDialog
import com.android.mms.extensions.config
import com.android.mms.extensions.conversationsDB
import com.android.mms.extensions.createTemporaryThread
import com.android.mms.extensions.deleteConversation
import com.android.mms.extensions.deleteMessage
import com.android.mms.extensions.deleteScheduledMessage
import com.android.mms.extensions.getContactFromAddress
import com.android.mms.extensions.launchConversationDetails
import com.android.mms.extensions.startAddContactIntent
import com.android.mms.extensions.startContactDetailsIntent
import com.android.mms.extensions.markLastMessageUnread
import com.android.mms.extensions.markThreadMessagesRead
import com.android.mms.extensions.messagesDB
import com.android.mms.extensions.moveMessageToRecycleBin
import com.android.mms.extensions.renameConversation
import com.android.mms.extensions.updateConversationArchivedStatus
import com.android.mms.extensions.updateLastConversationMessage
import com.android.mms.extensions.updateConversationPins
import com.android.mms.extensions.updateScheduledMessagesThreadId
import com.android.mms.extensions.getNameAndPhotoFromPhoneNumber
import com.android.mms.helpers.SWIPE_ACTION_ARCHIVE
import com.android.mms.helpers.SWIPE_ACTION_BLOCK
import com.android.mms.helpers.SWIPE_ACTION_CALL
import com.android.mms.helpers.SWIPE_ACTION_DELETE
import com.android.mms.helpers.SWIPE_ACTION_MESSAGE
import com.android.mms.helpers.generateRandomId
import com.android.mms.helpers.refreshConversations
import com.android.mms.messaging.cancelScheduleSendPendingIntent
import com.android.mms.messaging.isShortCodeWithLetters
import com.android.mms.models.Conversation
import com.android.mms.models.ConversationListItem
import com.android.mms.models.Message
import com.android.common.helper.IconItem
import eightbitlab.com.blurview.BlurTarget

class ConversationsAdapter(
    activity: SimpleActivity,
    recyclerView: MyRecyclerView,
    onRefresh: () -> Unit,
    itemClick: (Any) -> Unit
) : BaseConversationsAdapter(activity, recyclerView, onRefresh, itemClick) {

    private var getBlockedNumbers = activity.getBlockedNumbers()

    override fun getActionMenuId() = MainActivity.ACTION_MODE_MENU_SELECT
    override fun getMorePopupMenuId() = R.menu.cab_conversations
    override fun getMoreItemId() = 0
    override fun onMorePopupMenuItemClick(item: MenuItem) = actionItemPressed(item.itemId).let { true }

    fun isActionModeActive(): Boolean = actModeCallback.isSelectable

    @SuppressLint("NotifyDataSetChanged")
    override fun onActionModeCreated() {
        // Keep select mode visuals consistent with Dialer action mode.
        val useSurfaceColor = activity.isDynamicTheme() && !activity.isSystemInDarkMode()
        val cabBackgroundColor = if (useSurfaceColor) {
            activity.getSurfaceColor()
        } else {
            activity.getProperBackgroundColor()
        }

        val actModeBar = actMode?.customView?.parent as? View
        actModeBar?.setBackgroundColor(cabBackgroundColor)

        val toolbar =
            (actMode?.customView as? com.goodwy.commons.views.CustomActionModeToolbar) ?: actBarToolbar
        toolbar?.updateTextColorForBackground(cabBackgroundColor)
        toolbar?.updateColorsForBackground(cabBackgroundColor)

//        if (activity is com.goodwy.commons.activities.EdgeToEdgeActivity) {
//            activity.window.statusBarColor = cabBackgroundColor
//            activity.window.setSystemBarsAppearance(cabBackgroundColor)
//        }

        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onActionModeDestroyed() {
        notifyDataSetChanged()
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val conversation = getConversationAt(position) ?: return
        // Root is SwipeActionView; it consumes touch for swipe and long-press may never fire there.
        // Attach long-click to the inner content view so the actions dialog is triggered.
        holder.itemView.setOnLongClickListener(null)
        ItemConversationBinding.bind(holder.itemView).conversationFrameSelect.apply {
            isLongClickable = true
            setOnLongClickListener {
                showConversationActionsDialog(conversation, this)
                true
            }
        }
    }

    /** 1:1 only: [PhoneLookup] says this number is saved as a contact. */
    private fun Conversation.hasPhoneNumberInContacts(): Boolean {
        if (isGroupConversation) return false
        return activity.getNameAndPhotoFromPhoneNumber(phoneNumber).isContact
    }

    /** Details: group threads, or 1:1 with a contact-backed number (not raw unknown). */
    private fun Conversation.shouldShowConversationDetailsAction(): Boolean {
        if (isGroupConversation) return true
        return hasPhoneNumberInContacts()
    }

    /** Add to contacts: 1:1 when the number is not in the contacts directory. */
    private fun Conversation.shouldOfferAddNumberToContactAction(): Boolean {
        if (isGroupConversation) return false
        if (isShortCodeWithLetters(phoneNumber)) return false
        return !hasPhoneNumberInContacts()
    }

    /** Same visibility rules as the overflow menu; used for CAB and for long-press [OptionListDialog]. */
    private fun configureCabConversationsMenu(
        menu: Menu,
        includeDialNumber: Boolean = false,
        isRipple: Boolean = false,
        /** When showing long-press actions, pass the conversation here — do not put it in [selectedKeys] or the row looks selected after time-label refresh. */
        selectionOverride: List<Conversation>? = null
    ) {
        val selectedItems = selectionOverride?.let { ArrayList(it) } ?: getSelectedItems()
        // One *conversation* in the list, not only one selected key (keys and list can drift briefly).
        val isSingleSelection = selectedItems.size == 1
        val selectedConversation = selectedItems.firstOrNull()
        val isGroupConversation = selectedConversation?.isGroupConversation == true
        val archiveAvailable = activity.config.isArchiveAvailable
        val isAllBlockedNumbers = isAllBlockedNumbers()
        val isAllUnblockedNumbers = isAllUnblockedNumbers()

        if (isRipple){
            menu.apply {
                val isPinZeroMode = activity.config.selectedConversationPin == 0
                val isPinPrivateSpaceMode = activity.config.selectedConversationPin == 1
                findItem(R.id.cab_ripple_message_conversion)?.isVisible = true
                findItem(R.id.cab_ripple_copy)?.isVisible = true
                findItem(R.id.cab_ripple_delete)?.isVisible = true
                findItem(R.id.cab_ripple_secure_box_lock)?.isVisible = isPinZeroMode
                findItem(R.id.cab_ripple_secure_box_unlock)?.isVisible = !isPinZeroMode && !isPinPrivateSpaceMode
                findItem(R.id.cab_ripple_address_add)?.isVisible = isSingleSelection && (selectedConversation?.shouldOfferAddNumberToContactAction() == true)
                findItem(R.id.cab_ripple_private_space_add)?.isVisible = isPinZeroMode
                findItem(R.id.cab_ripple_private_space_delete)?.isVisible = isPinPrivateSpaceMode
                findItem(R.id.cab_conversation_details)?.isVisible =
                    isSingleSelection && (selectedConversation?.shouldShowConversationDetailsAction() == true)
            }
            return
        }

        menu.apply {
//            findItem(R.id.cab_archive)?.isVisible = true
//            findItem(R.id.cab_conversation_details)?.isVisible = true
            findItem(R.id.cab_block_number)?.isVisible = !(isAllUnblockedNumbers && !isAllBlockedNumbers)
            findItem(R.id.cab_unblock_number)?.isVisible = isAllBlockedNumbers && !isAllUnblockedNumbers
            findItem(R.id.cab_add_number_to_contact)?.isVisible =
                isSingleSelection && (selectedConversation?.shouldOfferAddNumberToContactAction() == true)
            findItem(R.id.cab_dial_number)?.isVisible =
                includeDialNumber && isSingleSelection && !isGroupConversation &&
                    (selectedConversation?.let { !isShortCodeWithLetters(it.phoneNumber) } ?: false)
            findItem(R.id.cab_copy_number)?.isVisible = isSingleSelection && !isGroupConversation
            findItem(R.id.cab_conversation_details)?.isVisible =
                isSingleSelection && (selectedConversation?.shouldShowConversationDetailsAction() == true)
            findItem(R.id.cab_rename_conversation)?.isVisible = isSingleSelection && isGroupConversation
            findItem(R.id.cab_mark_as_read)?.isVisible = false
            findItem(R.id.cab_mark_as_unread)?.isVisible = false
//            findItem(R.id.cab_mark_as_read)?.isVisible = selectedItems.any { !it.read }
//            findItem(R.id.cab_mark_as_unread)?.isVisible = selectedItems.any { it.read }
            findItem(R.id.cab_archive)?.isVisible = archiveAvailable
            val isPinZeroMode = activity.config.selectedConversationPin == 0
            findItem(R.id.cab_encrypt_conversations)?.isVisible = isPinZeroMode
            findItem(R.id.cab_decrypt_conversations)?.isVisible = !isPinZeroMode
            checkPinBtnVisibility(this)
            findItem(R.id.cab_secure_space_add)?.isVisible = true
            findItem(R.id.cab_secure_space_delete)?.isVisible = false

            findItem(R.id.cab_ripple_message_conversion)?.isVisible = false
            findItem(R.id.cab_ripple_copy)?.isVisible = false
            findItem(R.id.cab_ripple_delete)?.isVisible = false
            findItem(R.id.cab_ripple_secure_box_lock)?.isVisible = false
            findItem(R.id.cab_ripple_secure_box_unlock)?.isVisible = false
            findItem(R.id.cab_ripple_address_add)?.isVisible = false
            findItem(R.id.cab_ripple_private_space_add)?.isVisible = false
            findItem(R.id.cab_ripple_private_space_delete)?.isVisible = false
        }
    }

    /** Long-press: show actions in a blurred list dialog (txDial Recents pattern), not a popup menu. */
    private fun showConversationActionsDialog(conversation: Conversation, view: View) {
        if (activity.isDestroyed || activity.isFinishing) {
            return
        }
        finishActMode()
        selectedKeys.clear()

        val popupMenu = PopupMenu(activity, view)
        activity.menuInflater.inflate(R.menu.cab_conversations, popupMenu.menu)
        configureCabConversationsMenu(
            popupMenu.menu,
            includeDialNumber = false,
            isRipple = false,
            selectionOverride = listOf(conversation),
        )

        val options = mutableListOf<Pair<CharSequence, () -> Unit>>()
        val menu = popupMenu.menu
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (!item.isVisible) continue
            val itemId = item.itemId
            val label = item.title ?: ""
            options.add(label to {
                val requiresConfirmation = itemId == R.id.cab_block_number ||
                    itemId == R.id.cab_unblock_number ||
                    itemId == R.id.cab_delete ||
                    itemId == R.id.cab_archive
                selectedKeys.clear()
                selectedKeys.add(conversation.hashCode())
                actionItemPressed(itemId)
                if (!requiresConfirmation) {
                    selectedKeys.clear()
                }
            })
        }

        if (options.isEmpty()) return

        val blurTarget = activity.findViewById<BlurTarget>(com.android.mms.R.id.mainBlurTarget)
        try {
            OptionListDialog(
                activity = activity,
                title = conversation.title,
                options = options,
                blurTarget = blurTarget,
                cancelListener = null
            )
        } catch (_: Exception) {
        }
    }

    override fun prepareActionMode(menu: Menu) {
        // Top bar only has select-all; overflow actions are on [MRippleToolBar] (see MainActivity).
    }

    override fun updateSelectAllButtonIconIfAvailable(selectableItemCount: Int, selectedCount: Int) {
        super.updateSelectAllButtonIconIfAvailable(selectableItemCount, selectedCount)
        (activity as? MainActivity)?.refreshActionModeRippleToolbarIfNeeded()
    }

    /** Label for block/unblock slot on the bottom ripple toolbar (txDial [RecentCallsAdapter] pattern). */
    fun rippleToolbarBlockActionTitle(): String {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) {
            return activity.getString(com.goodwy.commons.R.string.block_number)
        }
        getBlockedNumbers = activity.getBlockedNumbers()
        val allBlocked = selectedItems.all { activity.isNumberBlocked(it.phoneNumber, getBlockedNumbers) }
        val single = selectedItems.size == 1
        return if (allBlocked) {
            activity.getString(if (single) com.goodwy.strings.R.string.unblock_number else com.goodwy.strings.R.string.unblock_numbers)
        } else {
            activity.getString(if (single) com.goodwy.commons.R.string.block_number else com.goodwy.commons.R.string.block_numbers)
        }
    }

    /**
     * Builds bottom toolbar items for [com.android.common.view.MRippleToolBar] in [MainActivity].
     */
    fun buildConversationListRippleToolbar(): Pair<ArrayList<IconItem>, ArrayList<Int>> {
        val items = ArrayList<IconItem>()
        val ids = ArrayList<Int>()
        val popupMenu = PopupMenu(activity, recyclerView)
        activity.menuInflater.inflate(R.menu.cab_conversations, popupMenu.menu)
        configureCabConversationsMenu(popupMenu.menu, includeDialNumber = false, true)
        val options = mutableListOf<Pair<CharSequence, () -> Unit>>()
        val m = popupMenu.menu
        fun add(icon: Int, title: String, id: Int) {
            items.add(
                IconItem().apply {
                    this.icon = icon
                    this.title = title
                },
            )
            ids.add(id)
        }

        // Single-select only; visibility comes from [configureCabConversationsMenu] (details: group or contact; add: unknown number).
        val selectedItemSize = getSelectedItems().size
        // val exactlyOneConversation = getSelectedItems().size == 1
        if (selectedItemSize >= 1){
            add(
                com.android.common.R.drawable.ic_cmn_delete_fill,
                activity.getString(com.goodwy.commons.R.string.delete),
                R.id.cab_ripple_delete,
            )
            if (m.findItem(R.id.cab_ripple_address_add)?.isVisible == true) {
                add(
                    R.drawable.ic_sms_ripple_add_address,
                    activity.getString(com.goodwy.strings.R.string.add_address),
                    R.id.cab_ripple_address_add,
                )
            }
            if (m.findItem(R.id.cab_conversation_details)?.isVisible == true) {
                add(
                    com.android.common.R.drawable.ic_cmn_info_fill,
                    activity.getString(R.string.conversation_details),
                    R.id.cab_conversation_details,
                )
            }
            if (m.findItem(R.id.cab_ripple_private_space_add)?.isVisible == true) {
                add(
                    R.drawable.ic_sms_ripple_shield,
                    activity.getString(R.string.private_space_add),
                    R.id.cab_ripple_private_space_add,
                )
            }
            if (m.findItem(R.id.cab_ripple_private_space_delete)?.isVisible == true) {
                add(
                    R.drawable.ic_sms_ripple_shield,
                    activity.getString(R.string.private_space_delete),
                    R.id.cab_ripple_private_space_delete,
                )
            }
            if (m.findItem(R.id.cab_ripple_secure_box_lock)?.isVisible == true) {
                add(
                    com.android.common.R.drawable.ic_cmn_lock_fill,
                    activity.getString(R.string.encrypt),
                    R.id.cab_ripple_secure_box_lock,
                )
            }
            if (m.findItem(R.id.cab_ripple_secure_box_unlock)?.isVisible == true) {
                add(
                    com.android.common.R.drawable.ic_cmn_unlock_fill,
                    activity.getString(R.string.decrypt),
                    R.id.cab_ripple_secure_box_unlock,
                )
            }
        }

//        if (exactlyOneConversation && m.findItem(R.id.cab_conversation_details)?.isVisible == true) {
//            add(
//                com.goodwy.commons.R.drawable.ic_info_vector,
//                activity.getString(R.string.conversation_details),
//                R.id.cab_conversation_details,
//            )
//        }
//        if (exactlyOneConversation && m.findItem(R.id.cab_add_number_to_contact)?.isVisible == true) {
//            add(
//                com.goodwy.commons.R.drawable.ic_add_person_vector,
//                activity.getString(com.goodwy.commons.R.string.add_number_to_contact),
//                R.id.cab_add_number_to_contact,
//            )
//        }
//        when {
//            m.findItem(R.id.cab_block_number)?.isVisible == true ->
//                add(com.goodwy.commons.R.drawable.ic_block_vector, rippleToolbarBlockActionTitle(), R.id.cab_block_number)
//            m.findItem(R.id.cab_unblock_number)?.isVisible == true ->
//                add(R.drawable.ic_show_block, rippleToolbarBlockActionTitle(), R.id.cab_unblock_number)
//        }
//        if (m.findItem(R.id.cab_mark_as_read)?.isVisible == true) {
//            add(R.drawable.ic_mark_read, activity.getString(R.string.mark_as_read), R.id.cab_mark_as_read)
//        }
//        if (m.findItem(R.id.cab_pin_conversation)?.isVisible == true) {
//            add(
//                R.drawable.ic_pin_angle_filled,
//                activity.getString(R.string.pin_conversation),
//                R.id.cab_pin_conversation,
//            )
//        }
//        if (m.findItem(R.id.cab_unpin_conversation)?.isVisible == true) {
//            add(
//                R.drawable.ic_pin_angle,
//                activity.getString(R.string.unpin_conversation),
//                R.id.cab_unpin_conversation,
//            )
//        }
//        when {
//            m.findItem(R.id.cab_encrypt_conversations)?.isVisible == true ->
//                add(
//                    com.goodwy.commons.R.drawable.ic_lock_outlined_vector,
//                    activity.getString(R.string.encrypt),
//                    R.id.cab_encrypt_conversations,
//                )
//            m.findItem(R.id.cab_decrypt_conversations)?.isVisible == true ->
//                add(
//                    com.goodwy.commons.R.drawable.ic_lock_outlined_vector,
//                    activity.getString(R.string.decrypt),
//                    R.id.cab_decrypt_conversations,
//                )
//        }

        return items to ids
    }

    fun dispatchRippleToolbarAction(index: Int) {
        if (selectedKeys.isEmpty()) return
        val (_, actionIds) = buildConversationListRippleToolbar()
        val id = actionIds.getOrNull(index) ?: return
        actionItemPressed(id)
    }

    private fun isAllBlockedNumbers(): Boolean {
        getSelectedItems().map { it.phoneNumber }.forEach { number ->
            if (activity.isNumberBlocked(number, getBlockedNumbers)) return true
        }
        return false
    }

    private fun isAllUnblockedNumbers(): Boolean {
        getSelectedItems().map { it.phoneNumber }.forEach { number ->
            if (!activity.isNumberBlocked(number, getBlockedNumbers)) return true
        }
        return false
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

    override fun actionItemPressed(id: Int) {
        // Match txDial [RecentCallsAdapter]: first tap selects all, second tap clears selection.
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
            R.id.cab_add_number_to_contact -> addNumberToContact()
            R.id.cab_block_number -> askConfirmBlock()
            R.id.cab_unblock_number -> askConfirmBlock()
            R.id.cab_dial_number -> dialNumber()
            R.id.cab_copy_number -> copyNumberToClipboard()
            R.id.cab_delete -> askConfirmDelete()
            R.id.cab_archive -> askConfirmArchive()
            R.id.cab_conversation_details -> openConversationDetails()

            R.id.cab_rename_conversation -> renameConversation(getSelectedItems().first())
            R.id.cab_mark_as_read -> markAsRead()
            R.id.cab_mark_as_unread -> markAsUnread()
            R.id.cab_pin_conversation -> pinConversation(true)
            R.id.cab_unpin_conversation -> pinConversation(false)
            R.id.cab_encrypt_conversations -> encryptConversations()
            R.id.cab_decrypt_conversations -> decryptConversations()
            R.id.cab_history_delete -> askConfirmDelete()

            //when ripple
            R.id.cab_ripple_delete -> askConfirmDelete()
            R.id.cab_ripple_address_add -> addNumberToContact()
            R.id.cab_ripple_secure_box_lock -> encryptConversations()
            R.id.cab_ripple_secure_box_unlock -> decryptConversations()
            R.id.cab_ripple_private_space_add -> {}
            R.id.cab_ripple_private_space_delete -> {}

        }
    }

    private fun encryptConversations() {
        val threadIds = getSelectedItems().map { it.threadId }.distinct().toLongArray()
        if (threadIds.isEmpty()) return
        val mainActivity = activity as? MainActivity ?: return
        mainActivity.requestEncryptConversations(threadIds)
    }

    private fun decryptConversations() {
        val threadIds = getSelectedItems().map { it.threadId }.distinct().toLongArray()
        if (threadIds.isEmpty()) return
        ensureBackgroundThread {
            activity.updateConversationPins(threadIds, 0)
            refreshConversationsAndFinishActMode()
        }
    }

    private fun askConfirmBlock() {
        // Capture selected items before showing dialog, as selectedKeys may be cleared
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) {
            return
        }
        
        val numbers = selectedItems.distinctBy { it.phoneNumber }.map { it.phoneNumber }
        val numbersString = TextUtils.join(", ", numbers)
        val isBlockNumbers = numbers.any { activity.isNumberBlocked(it, activity.getBlockedNumbers()) }
        val baseString = if (isBlockNumbers) com.goodwy.strings.R.string.unblock_confirmation else com.goodwy.commons.R.string.block_confirmation
        val question = String.format(resources.getString(baseString), numbersString)

        showMConfirmDialog(question) {
            blockNumbers(isBlockNumbers, selectedItems)
        }
    }

    private fun blockNumbers(unblock: Boolean, numbersToBlock: List<Conversation> = getSelectedItems()) {
        if (numbersToBlock.isEmpty()) {
            return
        }
        if (unblock) {
            ensureBackgroundThread {
                numbersToBlock.map { it.phoneNumber }.forEach { number ->
                    activity.deleteBlockedNumber(number)
                }

                activity.runOnUiThread {
                    selectedKeys.clear()
                    finishActMode()
                    refreshConversations()
                    getBlockedNumbers = activity.getBlockedNumbers()
                }
            }
        } else {
            val toRemoveSet = numbersToBlock.toSet()
            val newList = removeEmptyDateSections(
                currentList.toMutableList().apply {
                    removeAll { it is ConversationListItem.ConversationItem && (it as ConversationListItem.ConversationItem).conversation in toRemoveSet }
                },
            )
            ensureBackgroundThread {
                //mark read
                numbersToBlock.filter { conversation -> !conversation.read }.forEach {
                    activity.conversationsDB.markRead(it.threadId)
                    activity.markThreadMessagesRead(it.threadId)
                }

                //block
                numbersToBlock.map { it.phoneNumber }.forEach { number ->
                    activity.addBlockedNumber(number)
                }

                activity.runOnUiThread {
                    if (!activity.config.showBlockedNumbers) submitList(newList)
                    selectedKeys.clear()
                    finishActMode()
                    refreshConversations()
                    getBlockedNumbers = activity.getBlockedNumbers()
                }
            }
        }
    }

    private fun dialNumber() {
        val conversation = getSelectedItems().firstOrNull() ?: return
        activity.launchCallIntent(conversation.phoneNumber, key = BuildConfig.RIGHT_APP_KEY)
        finishActMode()
    }

    private fun copyNumberToClipboard() {
        val conversation = getSelectedItems().firstOrNull() ?: return
        activity.copyToClipboard(conversation.phoneNumber)
        finishActMode()
    }

    private fun askConfirmDelete() {
        // Capture selected items before showing dialog, as selectedKeys may be cleared
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) {
            return
        }
        
        val itemsCnt = selectedItems.size
        val items = resources.getQuantityString(R.plurals.delete_conversations, itemsCnt, itemsCnt)

        val baseString = if (activity.config.useRecycleBin) {
            com.goodwy.commons.R.string.move_to_recycle_bin_confirmation
        } else {
            com.goodwy.commons.R.string.deletion_confirmation
        }
        val question = String.format(resources.getString(baseString), items)

        showMConfirmDialog(question) {
            ensureBackgroundThread {
                deleteConversations(selectedItems)
            }
        }
    }

    private fun askConfirmArchive() {
        // Capture selected items before showing dialog, as selectedKeys may be cleared
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) {
            return
        }
        
        val itemsCnt = selectedItems.size
        val items = resources.getQuantityString(R.plurals.delete_conversations, itemsCnt, itemsCnt)

        val baseString = R.string.archive_confirmation
        val question = String.format(resources.getString(baseString), items)

        showMConfirmDialog(question) {
            ensureBackgroundThread {
                archiveConversations(selectedItems)
            }
        }
    }

    private fun archiveConversations(conversationsToArchive: List<Conversation> = getSelectedItems()) {
        if (conversationsToArchive.isEmpty()) {
            return
        }

        val conversationsToRemove = conversationsToArchive as ArrayList<Conversation>
        conversationsToRemove.forEach {
            activity.updateConversationArchivedStatus(it.threadId, true)
            activity.notificationManager.cancel(it.threadId.hashCode())
        }

        val toRemoveSet = conversationsToRemove.toSet()
        val newList = try {
            removeEmptyDateSections(
                currentList.toMutableList().apply {
                    removeAll { it is ConversationListItem.ConversationItem && (it as ConversationListItem.ConversationItem).conversation in toRemoveSet }
                },
            )
        } catch (_: Exception) {
            removeEmptyDateSections(currentList.toMutableList())
        }

        val selectedHashCodes = conversationsToArchive.map { it.hashCode() }.toSet()
        activity.runOnUiThread {
            if (newList.none { it is ConversationListItem.ConversationItem && selectedHashCodes.contains((it as ConversationListItem.ConversationItem).conversation.hashCode()) }) {
                refreshConversations()
                finishActMode()
            } else {
                submitList(newList)
                if (hasNoConversationRows(newList)) {
                    refreshConversations()
                }
            }
        }
    }

    private fun deleteConversations(conversationsToDelete: List<Conversation> = getSelectedItems()) {
        if (conversationsToDelete.isEmpty()) {
            return
        }

        val conversationsToRemove = conversationsToDelete as ArrayList<Conversation>
        if (activity.config.useRecycleBin) {
            conversationsToRemove.forEach {
                deleteMessages(it, true)
                activity.notificationManager.cancel(it.threadId.hashCode())
            }
        } else {
            conversationsToRemove.forEach {
                activity.deleteConversation(it.threadId)
                activity.notificationManager.cancel(it.threadId.hashCode())
            }
        }

        val toRemoveSet = conversationsToRemove.toSet()
        val newList = try {
            removeEmptyDateSections(
                currentList.toMutableList().apply {
                    removeAll { it is ConversationListItem.ConversationItem && (it as ConversationListItem.ConversationItem).conversation in toRemoveSet }
                },
            )
        } catch (_: Exception) {
            removeEmptyDateSections(currentList.toMutableList())
        }

        val selectedHashCodes = conversationsToDelete.map { it.hashCode() }.toSet()
        activity.runOnUiThread {
            if (newList.none { it is ConversationListItem.ConversationItem && selectedHashCodes.contains((it as ConversationListItem.ConversationItem).conversation.hashCode()) }) {
                refreshConversations()
                finishActMode()
            } else {
                submitList(newList)
                if (hasNoConversationRows(newList)) {
                    refreshConversations()
                }
            }
        }
    }

    private fun renameConversation(conversation: Conversation) {
        val blurTarget = activity.findViewById<eightbitlab.com.blurview.BlurTarget>(com.android.mms.R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        RenameConversationDialog(activity, conversation, blurTarget) {
            ensureBackgroundThread {
                val updatedConv = activity.renameConversation(conversation, newTitle = it)
                activity.runOnUiThread {
                    finishActMode()
                    val newList = currentList.toMutableList()
                    val idx = newList.indexOfFirst { it is ConversationListItem.ConversationItem && (it as ConversationListItem.ConversationItem).conversation.threadId == conversation.threadId }
                    if (idx >= 0) {
                        newList[idx] = ConversationListItem.ConversationItem(updatedConv)
                        submitList(newList)
                    } else {
                        refreshConversations()
                    }
                }
            }
        }
    }

    private fun markAsRead() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val conversationsMarkedAsRead = currentList
            .filterIsInstance<ConversationListItem.ConversationItem>()
            .filter { selectedKeys.contains(it.conversation.hashCode()) }
            .map { it.conversation } as ArrayList<Conversation>
        ensureBackgroundThread {
            conversationsMarkedAsRead.filter { conversation -> !conversation.read }.forEach {
                activity.conversationsDB.markRead(it.threadId)
                activity.markThreadMessagesRead(it.threadId)
            }

            refreshConversationsAndFinishActMode()
        }
    }

    private fun markAsUnread() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val conversationsMarkedAsUnread = currentList
            .filterIsInstance<ConversationListItem.ConversationItem>()
            .filter { selectedKeys.contains(it.conversation.hashCode()) }
            .map { it.conversation } as ArrayList<Conversation>
        ensureBackgroundThread {
            conversationsMarkedAsUnread.filter { conversation -> conversation.read }.forEach {
                activity.conversationsDB.markUnread(it.threadId)
//                activity.markThreadMessagesUnread(it.threadId)
                activity.markLastMessageUnread(it.threadId)
            }

            refreshConversationsAndFinishActMode()
        }
    }

    private fun addNumberToContact() {
        val conversation = getSelectedItems().firstOrNull() ?: return
        activity.startAddContactIntent(conversation.phoneNumber)
    }

    /**
     * txDial [MainActivityRecents] profile icon: [startContactDetailsIntent] for a known contact;
     * group threads keep in-app [launchConversationDetails].
     */
    private fun openConversationDetails() {
        val conversation = getSelectedItems().firstOrNull() ?: return
        if (conversation.isGroupConversation) {
            activity.launchConversationDetails(conversation.threadId)
            return
        }
        activity.getContactFromAddress(conversation.phoneNumber) { contact ->
            activity.runOnUiThread {
                if (contact != null) {
                    activity.startContactDetailsIntent(contact)
                } else {
                    activity.launchConversationDetails(conversation.threadId)
                }
            }
        }
    }

    private fun pinConversation(pin: Boolean) {
        val conversations = getSelectedItems()
        if (conversations.isEmpty()) {
            return
        }

        if (pin) {
            activity.config.addPinnedConversations(conversations)
        } else {
            activity.config.removePinnedConversations(conversations)
        }

        getSelectedItemPositions().forEach {
            notifyItemChanged(it)
        }
        refreshConversationsAndFinishActMode()
    }

    private fun checkPinBtnVisibility(menu: Menu) {
        val pinnedConversations = activity.config.pinnedConversations
        val selectedConversations = getSelectedItems()
        menu.findItem(R.id.cab_pin_conversation)?.isVisible =
            !selectedConversations.any { !pinnedConversations.contains(it.threadId.toString()) }
        menu.findItem(R.id.cab_unpin_conversation)?.isVisible =
            selectedConversations.any { pinnedConversations.contains(it.threadId.toString()) }
    }

    private fun refreshConversationsAndFinishActMode() {
        activity.runOnUiThread {
            refreshConversations()
            finishActMode()
        }
    }

    override fun swipedLeft(conversation: Conversation) {
        val swipeLeftAction = if (activity.isRTLLayout) activity.config.swipeRightAction else activity.config.swipeLeftAction
        swipeAction(swipeLeftAction, conversation)
    }

    override fun swipedRight(conversation: Conversation) {
        val swipeRightAction = if (activity.isRTLLayout) activity.config.swipeLeftAction else activity.config.swipeRightAction
        swipeAction(swipeRightAction, conversation)
    }

    private fun swipeAction(swipeAction: Int, conversation: Conversation) {
        when (swipeAction) {
            SWIPE_ACTION_DELETE -> swipedDelete(conversation)
            SWIPE_ACTION_ARCHIVE -> swipedArchive(conversation)
            SWIPE_ACTION_BLOCK -> swipedBlock(conversation)
            SWIPE_ACTION_CALL -> swipedCall(conversation)
            SWIPE_ACTION_MESSAGE -> swipedSMS(conversation)
            else -> swipedMarkRead(conversation)
        }
    }

    private fun swipedArchive(conversation: Conversation) {
        if (activity.baseConfig.skipArchiveConfirmation) {
            ensureBackgroundThread {
                swipedArchiveConversations(conversation)
            }
        } else {
            val item = conversation.title
            val baseString = R.string.archive_confirmation
            val question = String.format(resources.getString(baseString), item)

            showMConfirmDialog(question) {
                ensureBackgroundThread {
                    swipedArchiveConversations(conversation)
                }
            }
        }
    }

    private fun swipedArchiveConversations(conversation: Conversation) {
        val conversationsToArchive = ArrayList<Conversation>()
        conversationsToArchive.add(conversation)
        conversationsToArchive.forEach {
            activity.updateConversationArchivedStatus(it.threadId, true)
            activity.notificationManager.cancel(it.threadId.hashCode())
        }

        val toRemoveSet = conversationsToArchive.toSet()
        val newList = try {
            removeEmptyDateSections(
                currentList.toMutableList().apply {
                    removeAll { it is ConversationListItem.ConversationItem && (it as ConversationListItem.ConversationItem).conversation in toRemoveSet }
                },
            )
        } catch (_: Exception) {
            removeEmptyDateSections(currentList.toMutableList())
        }

        activity.runOnUiThread {
            submitList(newList)
            if (hasNoConversationRows(newList)) {
                refreshConversations()
            }
        }
    }

    private fun swipedDelete(conversation: Conversation) {
        if (activity.baseConfig.skipDeleteConfirmation) {
            ensureBackgroundThread {
                swipedDeleteConversations(conversation)
            }
        } else {
            val item = conversation.title
            val baseString = if (activity.config.useRecycleBin) {
                com.goodwy.commons.R.string.move_to_recycle_bin_confirmation
            } else {
                com.goodwy.commons.R.string.deletion_confirmation
            }
            val question = String.format(resources.getString(baseString), item)

            showMConfirmDialog(question) {
                ensureBackgroundThread {
                    swipedDeleteConversations(conversation)
                }
            }
        }
    }

    private fun swipedDeleteConversations(conversation: Conversation) {
        val conversationsToRemove = ArrayList<Conversation>()
        conversationsToRemove.add(conversation)
        if (activity.config.useRecycleBin) {
            conversationsToRemove.forEach {
                deleteMessages(it, true)
                activity.notificationManager.cancel(it.threadId.hashCode())
            }
        } else {
            conversationsToRemove.forEach {
                activity.deleteConversation(it.threadId)
                activity.notificationManager.cancel(it.threadId.hashCode())
            }
        }

        val toRemoveSet = conversationsToRemove.toSet()
        val newList = try {
            removeEmptyDateSections(
                currentList.toMutableList().apply {
                    removeAll { it is ConversationListItem.ConversationItem && (it as ConversationListItem.ConversationItem).conversation in toRemoveSet }
                },
            )
        } catch (_: Exception) {
            removeEmptyDateSections(currentList.toMutableList())
        }

        activity.runOnUiThread {
            submitList(newList)
            if (hasNoConversationRows(newList)) {
                refreshConversations()
            }
        }
    }

    private fun deleteMessages(
        conversation: Conversation,
        toRecycleBin: Boolean,
    ) {
        val threadId = conversation.threadId
        val messagesToRemove = try {
            if (activity.config.useRecycleBin) {
                activity.messagesDB.getNonRecycledThreadMessages(threadId)
            } else {
                activity.messagesDB.getThreadMessages(threadId)
            }.toMutableList() as ArrayList<Message>
        } catch (_: Exception) {
            ArrayList()
        }

        messagesToRemove.forEach { message ->
            val messageId = message.id
            if (message.isScheduled) {
                activity.deleteScheduledMessage(messageId)
                activity.cancelScheduleSendPendingIntent(messageId)
            } else {
                if (toRecycleBin) {
                    activity.moveMessageToRecycleBin(messageId)
                } else {
                    activity.deleteMessage(messageId, message.isMMS)
                }
            }
        }
        activity.updateLastConversationMessage(threadId)

        // move all scheduled messages to a temporary thread when there are no real messages left
        if (messagesToRemove.isNotEmpty() && messagesToRemove.all { it.isScheduled }) {
            val scheduledMessage = messagesToRemove.last()
            val fakeThreadId = generateRandomId()
            activity.createTemporaryThread(scheduledMessage, fakeThreadId, conversation)
            activity.updateScheduledMessagesThreadId(messagesToRemove, fakeThreadId)
        }
    }

    private fun swipedMarkRead(conversation: Conversation) {
        ensureBackgroundThread {
            if (conversation.read) {
                activity.conversationsDB.markUnread(conversation.threadId)
//                activity.markThreadMessagesUnread(conversation.threadId)
                activity.markLastMessageUnread(conversation.threadId)
            } else {
                activity.conversationsDB.markRead(conversation.threadId)
                activity.markThreadMessagesRead(conversation.threadId)
            }

            Handler(Looper.getMainLooper()).postDelayed({
                refreshConversationsAndFinishActMode()
            }, 100)
        }
    }

    private fun swipedBlock(conversation: Conversation) {
        selectedKeys.add(conversation.hashCode())
        askConfirmBlock()
    }

    private fun swipedSMS(conversation: Conversation) {
        itemClick.invoke(ConversationListItem.ConversationItem(conversation))
    }

    private fun swipedCall(conversation: Conversation) {
        if (conversation.isGroupConversation || isShortCodeWithLetters(conversation.phoneNumber)) activity.toast(com.goodwy.commons.R.string.no_phone_number_found)
        else {
            activity.launchCallIntent(conversation.phoneNumber, key = BuildConfig.RIGHT_APP_KEY)
            finishActMode()
        }
    }
}
