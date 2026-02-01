package com.android.mms.dialogs

import android.app.Activity
import android.content.DialogInterface.BUTTON_POSITIVE
import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getProperBlurOverlayColor
import com.goodwy.commons.extensions.getProperPrimaryColor
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
            
            if (blurView != null) {
                blurView.setOverlayColor(activity.getProperBlurOverlayColor())
                blurView.setupWith(blurTarget)
                    .setFrameClearDrawable(windowBackground)
                    .setBlurRadius(5f)
                    .setBlurAutoUpdate(true)
            }
            
            renameConvEditText.apply {
                if (conversation.usesCustomTitle) {
                    setText(conversation.title)
                }

                hint = conversation.title
            }
        }

        // Setup custom title view inside BlurView
        val titleTextView = binding.root.findViewById<com.goodwy.commons.views.MyTextView>(com.goodwy.commons.R.id.dialog_title)
        titleTextView?.apply {
            visibility = android.view.View.VISIBLE
            setText(R.string.rename_conversation)
        }

        // Setup custom buttons inside BlurView
        val primaryColor = if (activity is com.goodwy.commons.activities.BaseSimpleActivity) {
            (activity as com.goodwy.commons.activities.BaseSimpleActivity).getProperPrimaryColor()
        } else {
            activity.getColor(com.goodwy.commons.R.color.color_primary)
        }
        val buttonsContainer = binding.root.findViewById<android.widget.LinearLayout>(com.goodwy.commons.R.id.buttons_container)
        val positiveButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(com.goodwy.commons.R.id.positive_button)
        val negativeButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(com.goodwy.commons.R.id.negative_button)

        buttonsContainer?.visibility = android.view.View.VISIBLE

        positiveButton?.apply {
            visibility = android.view.View.VISIBLE
            text = activity.resources.getString(com.goodwy.commons.R.string.ok)
            setTextColor(primaryColor)
            setOnClickListener {
                val newTitle = binding.renameConvEditText.text.toString()
                if (newTitle.isEmpty()) {
                    activity.toast(com.goodwy.commons.R.string.empty_name)
                    return@setOnClickListener
                }

                callback(newTitle)
                dialog?.dismiss()
            }
        }

        negativeButton?.apply {
            visibility = android.view.View.VISIBLE
            text = activity.resources.getString(com.goodwy.commons.R.string.cancel)
            setTextColor(primaryColor)
            setOnClickListener {
                dialog?.dismiss()
            }
        }

        activity.getAlertDialogBuilder()
            .apply {
                activity.setupDialogStuff(binding.root, this, titleId = 0) { alertDialog ->
                    dialog = alertDialog
                    alertDialog.showKeyboard(binding.renameConvEditText)
                }
            }
    }
}
