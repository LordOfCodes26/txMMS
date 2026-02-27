package com.android.mms.adapters

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import com.goodwy.commons.dialogs.ConfirmationDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.KEY_PHONE
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.views.BlurPopupMenu
import com.goodwy.commons.views.MyRecyclerView
import com.android.mms.BuildConfig
import com.android.mms.R
import com.android.mms.activities.MainActivity
import com.android.mms.activities.SimpleActivity
import com.android.mms.dialogs.RenameConversationDialog
import com.android.mms.extensions.config
import com.android.mms.extensions.conversationsDB
import com.android.mms.extensions.createTemporaryThread
import com.android.mms.extensions.deleteConversation
import com.android.mms.extensions.deleteMessage
import com.android.mms.extensions.deleteScheduledMessage
import com.android.mms.extensions.launchConversationDetails
import com.android.mms.extensions.markLastMessageUnread
import com.android.mms.extensions.markThreadMessagesRead
import com.android.mms.extensions.messagesDB
import com.android.mms.extensions.moveMessageToRecycleBin
import com.android.mms.extensions.renameConversation
import com.android.mms.extensions.updateConversationArchivedStatus
import com.android.mms.extensions.updateLastConversationMessage
import com.android.mms.extensions.updateConversationPins
import com.android.mms.extensions.updateScheduledMessagesThreadId
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
import com.android.mms.models.Message

class ConversationsAdapter(
    activity: SimpleActivity,
    recyclerView: MyRecyclerView,
    onRefresh: () -> Unit,
    itemClick: (Any) -> Unit
) : BaseConversationsAdapter(activity, recyclerView, onRefresh, itemClick) {

    private var getBlockedNumbers = activity.getBlockedNumbers()

    override fun getActionMenuId() = R.menu.cab_conversations

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        
        // Override long-click to show popup menu instead of action mode
        val conversation = getItem(position)
        holder.itemView.setOnLongClickListener {
            showPopupMenu(conversation, holder.itemView)
            true
        }
    }

    private fun showPopupMenu(conversation: Conversation, view: View) {
        // Select the conversation first (for compatibility with existing logic)
        val position = currentList.indexOf(conversation)
        if (position != -1) {
            selectedKeys.clear()
            selectedKeys.add(conversation.hashCode())
        }

        val popupMenu = BlurPopupMenu(activity, view, Gravity.END)
        popupMenu.inflate(R.menu.cab_conversations)

        // Use existing prepareActionMode logic to set visibility
        val menu = popupMenu.menu
        val selectedItems = getSelectedItems()
        val isSingleSelection = isOneItemSelected()
        val selectedConversation = selectedItems.firstOrNull() ?: return
        val isGroupConversation = selectedConversation.isGroupConversation
        val archiveAvailable = activity.config.isArchiveAvailable
        val isAllBlockedNumbers = isAllBlockedNumbers()
        val isAllUnblockedNumbers = isAllUnblockedNumbers()

        menu.apply {
            findItem(R.id.cab_block_number).isVisible = isAllUnblockedNumbers && !isAllBlockedNumbers
            findItem(R.id.cab_unblock_number).isVisible = isAllBlockedNumbers && !isAllUnblockedNumbers
            findItem(R.id.cab_add_number_to_contact).isVisible = isSingleSelection && !isGroupConversation
            findItem(R.id.cab_dial_number).isVisible =
                false && isSingleSelection && !isGroupConversation && !isShortCodeWithLetters(selectedConversation.phoneNumber)
            findItem(R.id.cab_copy_number).isVisible = isSingleSelection && !isGroupConversation
            findItem(R.id.cab_conversation_details).isVisible = isSingleSelection
            findItem(R.id.cab_rename_conversation).isVisible = isSingleSelection && isGroupConversation
            findItem(R.id.cab_mark_as_read).isVisible = selectedItems.any { !it.read }
            findItem(R.id.cab_mark_as_unread).isVisible = selectedItems.any { it.read }
            findItem(R.id.cab_archive).isVisible = archiveAvailable
            val isPinZeroMode = activity.config.selectedConversationPin == 0
            findItem(R.id.cab_encrypt_conversations).isVisible = isPinZeroMode
            findItem(R.id.cab_decrypt_conversations).isVisible = !isPinZeroMode
            checkPinBtnVisibility(this)
            // Hide select_all as it doesn't make sense in a popup menu for single item
            findItem(R.id.cab_select_all).isVisible = false
            
            // Remove icons from all menu items
            for (i in 0 until size()) {
                getItem(i)?.icon = null
            }
        }

        popupMenu.setOnMenuItemClickListener { item ->
            val requiresConfirmation = item.itemId == R.id.cab_block_number || 
                                      item.itemId == R.id.cab_unblock_number ||
                                      item.itemId == R.id.cab_delete ||
                                      item.itemId == R.id.cab_archive
            actionItemPressed(item.itemId)
            // Only clear selectedKeys for actions that don't require confirmation
            // For confirmation dialogs, selectedKeys will be cleared after the action completes
            if (!requiresConfirmation) {
                selectedKeys.clear()
            }
            true
        }

        // Note: BlurPopupMenu doesn't have setOnDismissListener
        // Clear selectedKeys after menu interaction instead
        popupMenu.show()
    }

    override fun prepareActionMode(menu: Menu) {
        val selectedItems = getSelectedItems()
        val isSingleSelection = isOneItemSelected()
        val selectedConversation = selectedItems.firstOrNull() ?: return
        val isGroupConversation = selectedConversation.isGroupConversation
        val archiveAvailable = activity.config.isArchiveAvailable
        val isAllBlockedNumbers = isAllBlockedNumbers()
        val isAllUnblockedNumbers = isAllUnblockedNumbers()

        menu.apply {
            //findItem(R.id.cab_block_number).title = activity.addLockedLabelIfNeeded(com.goodwy.commons.R.string.block_number)
            findItem(R.id.cab_block_number).isVisible = isAllUnblockedNumbers && !isAllBlockedNumbers
            findItem(R.id.cab_unblock_number).isVisible = isAllBlockedNumbers && !isAllUnblockedNumbers
            findItem(R.id.cab_add_number_to_contact).isVisible = isSingleSelection && !isGroupConversation
            findItem(R.id.cab_dial_number).isVisible =
                isSingleSelection && !isGroupConversation && !isShortCodeWithLetters(selectedConversation.phoneNumber)
            findItem(R.id.cab_copy_number).isVisible = isSingleSelection && !isGroupConversation
            findItem(R.id.cab_conversation_details).isVisible = isSingleSelection
            findItem(R.id.cab_rename_conversation).isVisible = isSingleSelection && isGroupConversation
            findItem(R.id.cab_mark_as_read).isVisible = selectedItems.any { !it.read }
            findItem(R.id.cab_mark_as_unread).isVisible = selectedItems.any { it.read }
            findItem(R.id.cab_archive).isVisible = archiveAvailable
            val isPinZeroMode = activity.config.selectedConversationPin == 0
            findItem(R.id.cab_encrypt_conversations).isVisible = isPinZeroMode
            findItem(R.id.cab_decrypt_conversations).isVisible = !isPinZeroMode
            checkPinBtnVisibility(this)
        }
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

    override fun actionItemPressed(id: Int) {
        // Allow select_all to work even when no items are selected
        if (id == R.id.cab_select_all) {
            selectAll()
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
            R.id.cab_conversation_details ->
                activity.launchConversationDetails(getSelectedItems().first().threadId)

            R.id.cab_rename_conversation -> renameConversation(getSelectedItems().first())
            R.id.cab_mark_as_read -> markAsRead()
            R.id.cab_mark_as_unread -> markAsUnread()
            R.id.cab_pin_conversation -> pinConversation(true)
            R.id.cab_unpin_conversation -> pinConversation(false)
            R.id.cab_encrypt_conversations -> encryptConversations()
            R.id.cab_decrypt_conversations -> decryptConversations()
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

        val blurTarget = activity.findViewById<eightbitlab.com.blurview.BlurTarget>(com.android.mms.R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        ConfirmationDialog(activity, question, blurTarget = blurTarget) {
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
            val newList = currentList.toMutableList().apply { removeAll(numbersToBlock) }
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

        val blurTarget = activity.findViewById<eightbitlab.com.blurview.BlurTarget>(com.android.mms.R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        ConfirmationDialog(activity, question, blurTarget = blurTarget) {
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

        val blurTarget = activity.findViewById<eightbitlab.com.blurview.BlurTarget>(com.android.mms.R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        ConfirmationDialog(activity, question, blurTarget = blurTarget) {
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

        val newList = try {
            currentList.toMutableList().apply { removeAll(conversationsToRemove) }
        } catch (_: Exception) {
            currentList.toMutableList()
        }

        val selectedHashCodes = conversationsToArchive.map { it.hashCode() }.toSet()
        activity.runOnUiThread {
            if (newList.none { selectedHashCodes.contains(it.hashCode()) }) {
                refreshConversations()
                finishActMode()
            } else {
                submitList(newList)
                if (newList.isEmpty()) {
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

        val newList = try {
            currentList.toMutableList().apply { removeAll(conversationsToRemove) }
        } catch (_: Exception) {
            currentList.toMutableList()
        }

        val selectedHashCodes = conversationsToDelete.map { it.hashCode() }.toSet()
        activity.runOnUiThread {
            if (newList.none { selectedHashCodes.contains(it.hashCode()) }) {
                refreshConversations()
                finishActMode()
            } else {
                submitList(newList)
                if (newList.isEmpty()) {
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
                    currentList.toMutableList().apply {
                        set(indexOf(conversation), updatedConv)
                        updateConversations(this as ArrayList<Conversation>)
                    }
                }
            }
        }
    }

    private fun markAsRead() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val conversationsMarkedAsRead =
            currentList.filter { selectedKeys.contains(it.hashCode()) } as ArrayList<Conversation>
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

        val conversationsMarkedAsUnread =
            currentList.filter { selectedKeys.contains(it.hashCode()) } as ArrayList<Conversation>
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
        Intent().apply {
            action = Intent.ACTION_INSERT_OR_EDIT
            type = "vnd.android.cursor.item/contact"
            putExtra(KEY_PHONE, conversation.phoneNumber)
            activity.launchActivityIntent(this)
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
        menu.findItem(R.id.cab_pin_conversation).isVisible =
            selectedConversations.any { !pinnedConversations.contains(it.threadId.toString()) }
        menu.findItem(R.id.cab_unpin_conversation).isVisible =
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

            val blurTarget = activity.findViewById<eightbitlab.com.blurview.BlurTarget>(com.android.mms.R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            ConfirmationDialog(activity, question, blurTarget = blurTarget) {
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

        val newList = try {
            currentList.toMutableList().apply { removeAll(conversationsToArchive) }
        } catch (_: Exception) {
            currentList.toMutableList()
        }

        activity.runOnUiThread {
            submitList(newList)
            if (newList.isEmpty()) {
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

            val blurTarget = activity.findViewById<eightbitlab.com.blurview.BlurTarget>(com.android.mms.R.id.mainBlurTarget)
                ?: throw IllegalStateException("mainBlurTarget not found")
            ConfirmationDialog(activity, question, blurTarget = blurTarget) {
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

        val newList = try {
            currentList.toMutableList().apply { removeAll(conversationsToRemove) }
        } catch (_: Exception) {
            currentList.toMutableList()
        }

        activity.runOnUiThread {
            submitList(newList)
            if (newList.isEmpty()) {
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
        itemClick.invoke(conversation)
    }

    private fun swipedCall(conversation: Conversation) {
        if (conversation.isGroupConversation || isShortCodeWithLetters(conversation.phoneNumber)) activity.toast(com.goodwy.commons.R.string.no_phone_number_found)
        else {
            activity.launchCallIntent(conversation.phoneNumber, key = BuildConfig.RIGHT_APP_KEY)
            finishActMode()
        }
    }
}
