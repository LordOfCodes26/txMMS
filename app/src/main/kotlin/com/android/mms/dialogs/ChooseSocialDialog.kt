package com.android.mms.dialogs

import android.app.Activity
import android.view.ViewGroup
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getProperBlurOverlayColor
import com.goodwy.commons.extensions.setupDialogStuff
import com.android.mms.extensions.getPackageDrawable
import com.android.mms.databinding.DialogChooseSocialBinding
import com.android.mms.databinding.ItemChooseSocialBinding
import com.goodwy.commons.models.contacts.SocialAction
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

class ChooseSocialDialog(val activity: Activity, actions: ArrayList<SocialAction>, val blurTarget: BlurTarget, val callback: (action: SocialAction) -> Unit) {
    private lateinit var dialog: AlertDialog

    init {
        val binding = DialogChooseSocialBinding.inflate(activity.layoutInflater)
        
        // Setup BlurView
        val blurView = binding.root.findViewById<eightbitlab.com.blurview.BlurView>(com.android.mms.R.id.blurView)
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        blurView?.setOverlayColor(activity.getProperBlurOverlayColor())
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(8f)
            ?.setBlurAutoUpdate(true)
        
        actions.sortBy { it.type }
        actions.forEach { action ->
            val item = ItemChooseSocialBinding.inflate(activity.layoutInflater).apply {
                itemSocialLabel.text = action.label
                root.setOnClickListener {
                    callback(action)
                    dialog.dismiss()
                }

                val drawable = activity.getPackageDrawable(action.packageName)
                if (drawable == null) {
                    itemSocialImage.beGone()
                } else {
                    itemSocialImage.setImageDrawable(drawable)
                }
            }

            binding.dialogChooseSocial.addView(item.root, RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val builder = activity.getAlertDialogBuilder()

        builder.apply {
            activity.setupDialogStuff(binding.root, this) { alertDialog ->
                dialog = alertDialog
            }
        }
    }
}
