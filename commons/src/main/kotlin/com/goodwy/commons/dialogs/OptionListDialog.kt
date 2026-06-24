package com.goodwy.commons.dialogs

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.goodwy.commons.views.MyTextView
import com.android.common.view.MDialog
import com.goodwy.commons.R
import com.goodwy.commons.databinding.DialogOptionListBinding
import com.goodwy.commons.databinding.ItemOptionListRowBinding
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.setupMDialogStuff
import eightbitlab.com.blurview.BlurTarget

data class OptionListItem(
    val label: CharSequence,
    val action: () -> Unit,
    val dismissOnSelect: Boolean = true,
)

fun List<Pair<CharSequence, () -> Unit>>.toOptionListItems(): List<OptionListItem> =
    map { OptionListItem(it.first, it.second) }

/**
 * Shows an MDialog with a title and a list of options. Tapping an option runs its action;
 * the dialog dismisses unless [OptionListItem.dismissOnSelect] is false.
 *
 * @param activity Activity context
 * @param title Dialog title (optional; if empty, title is hidden)
 * @param options List of (label, action) pairs
 * @param blurTarget Optional blur target for the dialog background
 * @param cancelListener Called when the dialog is dismissed without selecting an option
 * @param onDialogPrepared Optional hook to configure the dialog window before it is shown
 */
class OptionListDialog(
    private val activity: Activity,
    title: CharSequence,
    private val options: List<OptionListItem>,
    blurTarget: BlurTarget? = null,
    cancelListener: (() -> Unit)? = null,
    onDialogPrepared: ((MDialog) -> Unit)? = null,
) {
    private var dialog: MDialog? = null

    init {
        if (!activity.isDestroyed && !activity.isFinishing && options.isNotEmpty()) {
            val view = DialogOptionListBinding.inflate(activity.layoutInflater, null, false)
            val container = view.optionListContainer

            options.forEachIndexed { _, option ->
                val rowBinding = ItemOptionListRowBinding.inflate(activity.layoutInflater, null, false)
                rowBinding.optionRowText.text = option.label
                rowBinding.root.setOnClickListener {
                    option.action.invoke()
                    if (option.dismissOnSelect) {
                        dialog?.dismiss()
                    }
                }
                container.addView(
                    rowBinding.root,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                )
            }

                val titleTextView = view.root.findViewById<TextView>(R.id.dialog_title)
                val titleDivider = view.root.findViewById<View>(R.id.dialog_option_list_title_divider)
                if (title.isNotEmpty()) {
                    titleTextView?.apply {
                        beVisible()
                        text = title
                    }
                    titleDivider?.beVisible()
                } else {
                    titleTextView?.beGone()
                    titleDivider?.beGone()
                }

            activity.setupMDialogStuff(
                view = view.root,
                blurView = view.blurView,
                blurTarget = blurTarget,
                titleText = "",
                cancelOnTouchOutside = true,
                cancelListener = { cancelListener?.invoke() },
                beforeShow = { mDialog ->
                    dialog = mDialog
                    onDialogPrepared?.invoke(mDialog)
                },
            )
        }
    }
}
