package com.android.mms.dialogs

import android.app.Activity
import android.content.DialogInterface.BUTTON_POSITIVE
import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.setupDialogStuff
import com.goodwy.commons.extensions.showKeyboard
import com.goodwy.commons.extensions.toast
import com.android.mms.R
import com.android.mms.databinding.DialogRenameConversationBinding
import com.android.mms.models.Conversation
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

class RenameConversationDialog(
    private val activity: Activity,
    private val conversation: Conversation,
    private val blurTarget: BlurTarget,
    private val callback: (name: String) -> Unit,
) {
    private var dialog: AlertDialog? = null

    init {
        val binding = DialogRenameConversationBinding.inflate(activity.layoutInflater).apply {
            // Setup BlurView
            val blurView = root.findViewById<eightbitlab.com.blurview.BlurView>(com.android.mms.R.id.blurView)
            val decorView = activity.window.decorView
            val windowBackground = decorView.background
            
            blurView?.setOverlayColor(0xa3ffffff.toInt())
            blurView?.setupWith(blurTarget)
                ?.setFrameClearDrawable(windowBackground)
                ?.setBlurRadius(8f)
                ?.setBlurAutoUpdate(true)
            
            renameConvEditText.apply {
                if (conversation.usesCustomTitle) {
                    setText(conversation.title)
                }

                hint = conversation.title
            }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.goodwy.commons.R.string.ok, null)
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.rename_conversation) { alertDialog ->
                    dialog = alertDialog
                    alertDialog.showKeyboard(binding.renameConvEditText)
                    alertDialog.getButton(BUTTON_POSITIVE).apply {
                        setOnClickListener {
                            val newTitle = binding.renameConvEditText.text.toString()
                            if (newTitle.isEmpty()) {
                                activity.toast(com.goodwy.commons.R.string.empty_name)
                                return@setOnClickListener
                            }

                            callback(newTitle)
                            alertDialog.dismiss()
                        }
                    }
                }
            }
    }
}
