package com.android.mms.dialogs

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.android.common.view.MDialog
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.databinding.DialogOptionListBinding
import com.goodwy.commons.databinding.ItemOptionListRowBinding
import com.goodwy.commons.dialogs.OptionListDialog
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.setupMDialogStuff
import com.android.mms.R
import com.android.mms.extensions.config
import eightbitlab.com.blurview.BlurTarget

/** Same blurred bottom-sheet list style as conversation long-press ([com.android.mms.adapters.ConversationsAdapter.showConversationActionsDialog]). */
class QuickTextSelectionDialog(
    private val activity: BaseSimpleActivity,
    private val blurTarget: BlurTarget,
    private val callback: (selectedText: String) -> Unit
) {
    init {
        if (!activity.isDestroyed && !activity.isFinishing) {
            val quickTexts = activity.config.quickTexts.toList()

            if (quickTexts.isNotEmpty()) {
                val title = activity.getString(R.string.quick_texts)
                val options = quickTexts.map { text ->
                    text to { callback(text) }
                }
                OptionListDialog(
                    activity = activity as Activity,
                    title = title,
                    options = options,
                    blurTarget = blurTarget,
                    cancelListener = null
                )
            } else {
                showEmptyQuickTextsDialog()
            }
        }
    }

    private fun showEmptyQuickTextsDialog() {
        val view = DialogOptionListBinding.inflate(activity.layoutInflater, null, false)
        val container = view.optionListContainer

        val rowBinding = ItemOptionListRowBinding.inflate(activity.layoutInflater, null, false)
        rowBinding.optionRowText.text = activity.getString(R.string.no_quick_texts)

        var dialog: MDialog? = null
        rowBinding.root.setOnClickListener {
            dialog?.dismiss()
        }

        container.addView(
            rowBinding.root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val titleTextView = view.root.findViewById<TextView>(com.goodwy.commons.R.id.dialog_title)
        val titleDivider = view.root.findViewById<View>(com.goodwy.commons.R.id.dialog_option_list_title_divider)
        val title = activity.getString(R.string.quick_texts)
        titleTextView?.apply {
            beVisible()
            text = title
        }
        titleDivider?.beVisible()

        activity.setupMDialogStuff(
            view = view.root,
            blurView = view.blurView,
            blurTarget = blurTarget,
            titleText = "",
            cancelOnTouchOutside = true,
            cancelListener = null
        ) { mDialog ->
            dialog = mDialog
        }
    }
}
