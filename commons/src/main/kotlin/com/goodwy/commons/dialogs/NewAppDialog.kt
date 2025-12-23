package com.goodwy.commons.dialogs

import android.app.Activity
import android.graphics.drawable.Drawable
import android.text.Html
import com.goodwy.commons.R
import com.goodwy.commons.databinding.DialogNewAppsBinding
import com.goodwy.commons.extensions.*
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

// run inside: runOnUiThread { }
class NewAppDialog(
    val activity: Activity,
    private val packageName: String,
    val title: String,
    val text: String,
    val drawable: Drawable?,
    blurTarget: BlurTarget,
    val callback: () -> Unit)
{
    init {
        val view = DialogNewAppsBinding.inflate(activity.layoutInflater, null, false)
        
        // Setup BlurView with the provided BlurTarget
        val blurView = view.root.findViewById<BlurView>(R.id.blurView)
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        blurView?.setOverlayColor(0xa3ffffff.toInt())
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(8f)
            ?.setBlurAutoUpdate(true)
        
        // Setup title inside BlurView
        val titleTextView = view.root.findViewById<com.goodwy.commons.views.MyTextView>(R.id.dialog_title)
        titleTextView?.apply {
            beVisible()
            text = Html.fromHtml(title).toString()
        }
        
        // Setup custom buttons inside BlurView
        val primaryColor = activity.getProperPrimaryColor()
        val positiveButton = view.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.positive_button)
        val neutralButton = view.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.neutral_button)
        val buttonsContainer = view.root.findViewById<android.widget.LinearLayout>(R.id.buttons_container)
        
        buttonsContainer?.visibility = android.view.View.VISIBLE
        
        positiveButton?.apply {
            visibility = android.view.View.VISIBLE
            text = activity.resources.getString(R.string.later)
            setTextColor(primaryColor)
            setOnClickListener { dialogDismissed(8) }
        }
        
        neutralButton?.apply {
            visibility = android.view.View.VISIBLE
            text = activity.resources.getString(R.string.do_not_show_again)
            setTextColor(primaryColor)
            setOnClickListener { dialogDismissed(1) }
        }
        
        view.apply {
            newAppsTitle.text = Html.fromHtml(title)
            newAppsText.text = text
            newAppsIcon.setImageDrawable(drawable!!)
            newAppsHolder.setOnClickListener { dialogConfirmed() }
        }

        activity.getAlertDialogBuilder()
            .setOnCancelListener { dialogDismissed(8) }
            .apply {
                // Pass empty titleText to prevent setupDialogStuff from adding title outside BlurView
                activity.setupDialogStuff(view.root, this, titleText = "")
            }
    }

    private fun dialogDismissed(count: Int) {
        activity.baseConfig.appRecommendationDialogCount = count
        callback()
    }

    private fun dialogConfirmed() {
        val url = "https://play.google.com/store/apps/details?id=$packageName"
        activity.launchViewIntent(url)
        callback()
    }
}
