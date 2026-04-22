package com.android.mms.fragments

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.android.mms.activities.NewConversationActivity
import com.android.mms.activities.ThreadActivity
import com.android.mms.adapters.ConversationsAdapter
import com.android.mms.extensions.config
import com.android.mms.extensions.getBlockedConversations
import com.android.mms.extensions.hasMeaningfulLocalDraft
import com.android.mms.extensions.getThreadRecipientPhoneNumbers
import com.android.mms.extensions.getThreadTelephonyMessageCount
import com.goodwy.commons.extensions.getMyContactsCursor
import com.goodwy.commons.extensions.hideKeyboard
import com.android.mms.helpers.NEW_CONVERSATION_RESUME_DRAFT
import com.android.mms.helpers.THREAD_ID
import com.android.mms.helpers.THREAD_NUMBER
import com.android.mms.helpers.THREAD_OPENED_FROM_SECURE_CONVERSATION_LIST
import com.android.mms.helpers.THREAD_SHOW_BLOCKED_MESSAGES
import com.android.mms.helpers.THREAD_TITLE
import com.android.mms.helpers.THREAD_URI
import com.android.mms.models.Conversation
import com.goodwy.commons.R as CommonsR
import com.goodwy.commons.helpers.MyContactsContentProvider
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.views.MyRecyclerView
import com.google.gson.Gson

/**
 * Conversation list for threads that include at least one blocked number; opens [ThreadActivity] with
 * [THREAD_SHOW_BLOCKED_MESSAGES] so inbox content from blocked senders is loaded.
 */
class BlockedConversationsFragment : Fragment(CommonsR.layout.fragment_blocked_messages) {

    private lateinit var list: MyRecyclerView
    private lateinit var placeholder: View
    private var adapter: ConversationsAdapter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list = view.findViewById(CommonsR.id.blocked_messages_list)
        placeholder = view.findViewById(CommonsR.id.blocked_messages_placeholder)
        list.layoutManager = LinearLayoutManager(requireContext())
    }

    override fun onResume() {
        super.onResume()
        loadBlockedConversations()
    }

    @SuppressLint("UnsafeIntentLaunch")
    private fun loadBlockedConversations() {
        val act = activity as? BaseSimpleActivity ?: return
        ensureBackgroundThread {
            val privateCursor = act.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
            val privateContacts = MyContactsContentProvider.getSimpleContacts(act, privateCursor)
            val raw = try {
                act.getBlockedConversations(privateContacts)
            } catch (_: Exception) {
                ArrayList()
            }
            val sorted = sortLikeMainList(act, raw)
            act.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val hasItems = sorted.isNotEmpty()
                list.isVisible = hasItems
                placeholder.isVisible = !hasItems
                if (!hasItems) return@runOnUiThread
                val convAdapter = getOrCreateAdapter(act)
                convAdapter.updateConversations(sorted) {
                    list.isVisible = convAdapter.currentList.isNotEmpty()
                    placeholder.isVisible = convAdapter.currentList.isEmpty()
                }
            }
        }
    }

    private fun sortLikeMainList(act: BaseSimpleActivity, conversations: ArrayList<Conversation>): ArrayList<Conversation> {
        val cfg = act.config
        return if (cfg.unreadAtTop) {
            conversations.sortedWith(
                compareByDescending<Conversation> {
                    cfg.pinnedConversations.contains(it.threadId.toString())
                }
                    .thenBy { it.read }
                    .thenByDescending { it.date },
            ).toMutableList() as ArrayList<Conversation>
        } else {
            conversations.sortedWith(
                compareByDescending<Conversation> {
                    cfg.pinnedConversations.contains(it.threadId.toString())
                }
                    .thenByDescending { it.date }
                    .thenByDescending { it.isGroupConversation },
            ).toMutableList() as ArrayList<Conversation>
        }
    }

    private fun getOrCreateAdapter(act: BaseSimpleActivity): ConversationsAdapter {
        var curr = adapter
        if (curr == null) {
            curr = ConversationsAdapter(
                activity = act,
                recyclerView = list,
                onRefresh = { loadBlockedConversations() },
                itemClick = { openConversation(act, it as Conversation) },
            )
            list.adapter = curr
            adapter = curr
        }
        return curr
    }

    @SuppressLint("UnsafeIntentLaunch")
    private fun openConversation(act: BaseSimpleActivity, conversation: Conversation) {
        act.hideKeyboard()
        if (conversation.messageCount > 0) {
            startThreadWithBlockedVisible(act, conversation)
            return
        }
        ensureBackgroundThread {
            val telephonyMessageCount = act.getThreadTelephonyMessageCount(conversation.threadId)
            val openNewComposeForDraft =
                act.hasMeaningfulLocalDraft(conversation.threadId) && telephonyMessageCount == 0
            act.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (openNewComposeForDraft) {
                    var numbers = act.getThreadRecipientPhoneNumbers(conversation.threadId)
                    if (numbers.isEmpty() && conversation.phoneNumber.isNotEmpty()) {
                        numbers = arrayListOf(conversation.phoneNumber)
                    }
                    if (numbers.isNotEmpty()) {
                        val numberExtra = when (numbers.size) {
                            1 -> numbers[0]
                            else -> Gson().toJson(numbers.toSet())
                        }
                        Intent(act, NewConversationActivity::class.java).apply {
                            putExtra(NEW_CONVERSATION_RESUME_DRAFT, true)
                            putExtra(THREAD_ID, conversation.threadId)
                            putExtra(THREAD_TITLE, conversation.title)
                            putExtra(THREAD_NUMBER, numberExtra)
                            startActivity(this)
                        }
                    } else {
                        startThreadWithBlockedVisible(act, conversation)
                    }
                } else {
                    startThreadWithBlockedVisible(act, conversation)
                }
            }
        }
    }

    private fun startThreadWithBlockedVisible(act: BaseSimpleActivity, conversation: Conversation) {
        Intent(act, ThreadActivity::class.java).apply {
            putExtra(THREAD_ID, conversation.threadId)
            putExtra(THREAD_TITLE, conversation.title)
            putExtra(THREAD_NUMBER, conversation.phoneNumber)
            putExtra(THREAD_URI, conversation.photoUri)
            putExtra(THREAD_SHOW_BLOCKED_MESSAGES, true)
            if (act.config.selectedConversationPin > 0) {
                putExtra(THREAD_OPENED_FROM_SECURE_CONVERSATION_LIST, true)
            }
            act.startActivity(this)
        }
    }
}
