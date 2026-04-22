package com.android.mms.dialogs

import android.app.Activity
import com.android.common.dialogs.MRenameDialog
import com.goodwy.commons.extensions.toast
import com.android.mms.R
import com.android.mms.models.Conversation
import eightbitlab.com.blurview.BlurTarget

class RenameConversationDialog(
    private val activity: Activity,
    private val conversation: Conversation,
    private val blurTarget: BlurTarget,
    private val callback: (name: String) -> Unit,
) {
//    private var dialog: AlertDialog? = null

    init {
        val dialog = MRenameDialog(activity)
        dialog.bindBlurTarget(blurTarget)
        dialog.setTitle(activity.getString(R.string.rename_conversation))
        dialog.setHintText(conversation.title)
        dialog.setContentText(
            if (conversation.usesCustomTitle) conversation.title else ""
        )
        dialog.setOnRenameListener { newTitle ->
            if (newTitle.isEmpty()) {
                activity.toast(com.goodwy.commons.R.string.empty_name)
                return@setOnRenameListener
            }
            callback(newTitle)
        }
        dialog.show()
    }
}
