package com.android.mms.activities

import androidx.fragment.app.Fragment
import com.android.mms.fragments.BlockedConversationsFragment
import com.goodwy.commons.activities.BlockedItemsActivity

/**
 * Messages app entry for [BlockedItemsActivity]: blocked-messages tab uses [BlockedConversationsFragment]
 * (conversation list + thread with blocked content visible).
 */
class MessagingBlockedItemsActivity : BlockedItemsActivity() {
    override fun createBlockedMessagesFragment(): Fragment = BlockedConversationsFragment()
}
