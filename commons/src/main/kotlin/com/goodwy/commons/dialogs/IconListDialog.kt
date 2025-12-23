package com.goodwy.commons.dialogs

import android.annotation.SuppressLint
import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.R
import com.goodwy.commons.databinding.DialogIconListBinding
import com.goodwy.commons.extensions.*
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

@SuppressLint("UseCompatLoadingForDrawables")
class IconListDialog(
    val activity: Activity,
    val items: ArrayList<Int>,
    val checkedItemId: Int = -1,
    val defaultItemId: Int? = null,
    val titleId: Int = 0,
    val descriptionId: String? = null,
    val size: Int? = null,
    val color: Int? = null,
    blurTarget: BlurTarget,
    val callback: (wasPositivePressed: Boolean, newValue: Int) -> Unit
) {

    private var dialog: AlertDialog? = null
    private var wasInit = false

    init {
        val view = DialogIconListBinding.inflate(activity.layoutInflater, null, false)
        
        // Setup BlurView with the provided BlurTarget
        val blurView = view.root.findViewById<BlurView>(R.id.blurView)
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        blurView?.setOverlayColor(0xa3ffffff.toInt())
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(8f)
            ?.setBlurAutoUpdate(true)
        
        view.apply {
            when (items.size) {
                2 -> {
                    arrayOf(icon3Holder, icon4Holder, icon5Holder, icon6Holder, icon7Holder, icon8Holder,
                        icon9Holder, icon10Holder, icon11Holder, icon12Holder
                    ).forEach {
                        it.beGone()
                    }
                    arrayOf(icon1, icon2).forEachIndexed { index, imageView ->
                        imageView.setImageDrawable(activity.resources.getDrawable(items[index], activity.theme))
                    }
                }
                3 -> {
                    arrayOf(icon4Holder, icon5Holder, icon6Holder, icon7Holder, icon8Holder,
                        icon9Holder, icon10Holder, icon11Holder, icon12Holder
                    ).forEach {
                        it.beGone()
                    }
                    arrayOf(icon1, icon2, icon3).forEachIndexed { index, imageView ->
                        imageView.setImageDrawable(activity.resources.getDrawable(items[index], activity.theme))
                    }
                }
                4 -> {
                    arrayOf(icon5Holder, icon6Holder, icon7Holder, icon8Holder,
                        icon9Holder, icon10Holder, icon11Holder, icon12Holder
                    ).forEach {
                        it.beGone()
                    }
                    arrayOf(icon1, icon2, icon3, icon4).forEachIndexed { index, imageView ->
                        imageView.setImageDrawable(activity.resources.getDrawable(items[index], activity.theme))
                    }
                }
                12 -> {
                    arrayOf(icon1, icon2, icon3, icon4, icon5, icon6,
                        icon7, icon8, icon9, icon10, icon11, icon12
                    ).forEachIndexed { index, imageView ->
                        imageView.setImageDrawable(activity.resources.getDrawable(items[index], activity.theme))
                    }
                }
            }

            if (size != null) {
                arrayOf(
                    icon1, icon2, icon3, icon4, icon5, icon6,
                    icon7, icon8, icon9, icon10, icon11, icon12
                ).forEach { imageView ->
                    imageView.setHeightAndWidth(size)
                }
            }

            if (color != null) {
                arrayOf(
                    icon1, icon2, icon3, icon4, icon5, icon6,
                    icon7, icon8, icon9, icon10, icon11, icon12
                ).forEach { imageView ->
                    imageView.applyColorFilter(color)
                }
            }

            arrayOf(icon1Check, icon2Check, icon3Check, icon4Check, icon5Check, icon6Check,
                icon7Check, icon8Check, icon9Check, icon10Check, icon11Check, icon12Check
            ).forEach {
                it.applyColorFilter(activity.getProperPrimaryColor())
            }

            when (checkedItemId) {
                1 -> icon1Check.beVisible()
                2 -> icon2Check.beVisible()
                3 -> icon3Check.beVisible()
                4 -> icon4Check.beVisible()
                5 -> icon5Check.beVisible()
                6 -> icon6Check.beVisible()
                7 -> icon7Check.beVisible()
                8 -> icon8Check.beVisible()
                9 -> icon9Check.beVisible()
                10 -> icon10Check.beVisible()
                11 -> icon11Check.beVisible()
                12 -> icon12Check.beVisible()
            }

            icon1.setOnClickListener { itemSelected(1) }
            icon2.setOnClickListener { itemSelected(2) }
            icon3.setOnClickListener { itemSelected(3) }
            icon4.setOnClickListener { itemSelected(4) }
            icon5.setOnClickListener { itemSelected(5) }
            icon6.setOnClickListener { itemSelected(6) }
            icon7.setOnClickListener { itemSelected(7) }
            icon8.setOnClickListener { itemSelected(8) }
            icon9.setOnClickListener { itemSelected(9) }
            icon10.setOnClickListener { itemSelected(10) }
            icon11.setOnClickListener { itemSelected(11) }
            icon12.setOnClickListener { itemSelected(12) }

            if (descriptionId != null) {
                description.beVisible()
                description.text = descriptionId
            }
        }

        // Setup title inside BlurView
        val titleView = view.root.findViewById<com.goodwy.commons.views.MyTextView>(R.id.dialog_title)
        if (titleId != 0) {
            titleView?.apply {
                visibility = android.view.View.VISIBLE
                text = activity.resources.getString(titleId)
            }
        } else {
            titleView?.visibility = android.view.View.GONE
        }

        // Setup custom buttons inside BlurView
        val primaryColor = activity.getProperPrimaryColor()
        val positiveButton = view.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.positive_button)
        val neutralButton = view.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.neutral_button)
        val buttonsContainer = view.root.findViewById<android.widget.LinearLayout>(R.id.buttons_container)
        
        buttonsContainer?.visibility = android.view.View.VISIBLE
        
        positiveButton?.apply {
            visibility = android.view.View.VISIBLE
            text = activity.resources.getString(R.string.dismiss)
            setTextColor(primaryColor)
            setOnClickListener { dialog?.dismiss() }
        }
        
        if (defaultItemId != null) {
            neutralButton?.apply {
                visibility = android.view.View.VISIBLE
                text = activity.resources.getString(R.string.set_as_default)
                setTextColor(primaryColor)
                setOnClickListener { itemSelected(defaultItemId) }
            }
        } else {
            neutralButton?.visibility = android.view.View.GONE
        }

        val builder = activity.getAlertDialogBuilder()

        builder.apply {
            // Pass empty titleText to prevent setupDialogStuff from adding title outside BlurView
            activity.setupDialogStuff(view.root, this, titleText = "", cancelOnTouchOutside = true) { alertDialog ->
                dialog = alertDialog
            }
        }

        wasInit = true
    }

    private fun itemSelected(checkedId: Int) {
        if (wasInit) {
            callback(true, checkedId)
            dialog?.dismiss()
        }
    }
}
