package com.android.mms.dialogs

import android.view.View
import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.*
import com.android.mms.R
import com.android.mms.databinding.DialogQuickTextSelectionBinding
import com.android.mms.extensions.config
import eightbitlab.com.blurview.BlurTarget

class QuickTextSelectionDialog(
    val activity: BaseSimpleActivity,
    val blurTarget: BlurTarget,
    val callback: (selectedText: String) -> Unit
) {
    private var dialog: AlertDialog? = null

    init {
        val view = DialogQuickTextSelectionBinding.inflate(activity.layoutInflater, null, false)
        val quickTexts = activity.config.quickTexts.toList()

        // Setup BlurView
        val blurView = view.blurView
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        blurView.setOverlayColor(activity.getProperBlurOverlayColor())
        blurView.setupWith(blurTarget)
            .setFrameClearDrawable(windowBackground)
            .setBlurRadius(8f)
            .setBlurAutoUpdate(true)

        // Setup title
        val titleTextView = view.dialogTitle
        titleTextView.text = activity.resources.getString(R.string.quick_texts)
        titleTextView.beVisible()

        // Setup list
        val listContainer = view.dialogQuickTextList
        val textColor = activity.getProperTextColor()
        val primaryColor = activity.getProperPrimaryColor()
        
        if (quickTexts.isEmpty()) {
            val emptyView = activity.layoutInflater.inflate(
                com.goodwy.commons.R.layout.item_simple_list,
                listContainer,
                false
            )
            val binding = com.goodwy.commons.databinding.ItemSimpleListBinding.bind(emptyView)
            binding.bottomSheetItemTitle.text = activity.resources.getString(R.string.no_quick_texts)
            binding.bottomSheetItemTitle.setTextColor(textColor)
            binding.bottomSheetItemIcon.beGone()
            binding.bottomSheetButton.beGone()
            binding.root.isClickable = false
            listContainer.addView(emptyView)
        } else {
            quickTexts.forEach { quickText: String ->
                val itemView = activity.layoutInflater.inflate(
                    com.goodwy.commons.R.layout.item_simple_list,
                    listContainer,
                    false
                )
                val binding = com.goodwy.commons.databinding.ItemSimpleListBinding.bind(itemView)
                
                binding.bottomSheetItemTitle.text = quickText
                binding.bottomSheetItemTitle.setTextColor(textColor)
                binding.bottomSheetItemIcon.beGone()
                binding.bottomSheetButton.beGone()
                
                itemView.setOnClickListener {
                    callback(quickText)
                    dialog?.dismiss()
                }
                
                // Add ripple effect
                itemView.background = activity.resources.getDrawable(
                    com.goodwy.commons.R.drawable.ripple_all_corners,
                    activity.theme
                )
                
                listContainer.addView(itemView)
            }
        }

        // Setup cancel button
        val positiveButton = view.positiveButton
        positiveButton.visibility = View.VISIBLE
        positiveButton.text = activity.resources.getString(com.goodwy.commons.R.string.cancel)
        positiveButton.setTextColor(primaryColor)
        positiveButton.setOnClickListener {
            dialog?.dismiss()
        }

        val buttonsContainer = view.buttonsContainer
        buttonsContainer.visibility = View.VISIBLE

        val builder = activity.getAlertDialogBuilder()
        builder.apply {
            activity.setupDialogStuff(view.root, this, titleText = "", cancelOnTouchOutside = true) { alertDialog ->
                dialog = alertDialog
            }
        }
    }
}

