package com.android.mms.dialogs

import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.isSPlus
import com.android.mms.R
import com.android.mms.activities.SimpleActivity
import com.android.mms.databinding.DialogMessageBubbleSettingBinding
import com.android.mms.extensions.config
import com.android.mms.extensions.launchPurchase
import com.android.mms.helpers.BUBBLE_STYLE_IOS
import com.android.mms.helpers.BUBBLE_STYLE_IOS_NEW
import com.android.mms.helpers.BUBBLE_STYLE_ORIGINAL
import com.android.mms.helpers.BUBBLE_STYLE_ROUNDED
import com.google.android.material.snackbar.Snackbar
import com.mikhaellopez.rxanimation.RxAnimation
import com.mikhaellopez.rxanimation.shake
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import kotlin.math.abs

class MessageBubbleSettingDialog(
    private val activity: SimpleActivity,
    private val isPro: Boolean,
    private val blurTarget: BlurTarget,
    private val callback: (style: Int) -> Unit,
) {
    private val binding = DialogMessageBubbleSettingBinding.inflate(activity.layoutInflater)
    private val config = activity.config
    private var currentBubbleStyle = config.bubbleStyle
    private var dialog: AlertDialog? = null

    init {
        // Setup BlurView
        val blurView = binding.root.findViewById<eightbitlab.com.blurview.BlurView>(com.android.mms.R.id.blurView)
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        blurView?.setOverlayColor(activity.getProperBlurOverlayColor())
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(5f)
            ?.setBlurAutoUpdate(true)
        
        setupBubbleInvertColor()
        setupBubbleUseContactColor()
        setupColors()
        setupToggleBubbleStyle()

        val backgroundColor =
            if (activity.isDynamicTheme() && !activity.isSystemInDarkMode()) activity.getSurfaceColor()
            else activity.getProperBackgroundColor()
        if ((activity.isDynamicTheme()) || activity.isBlackTheme()) {
            val drawable = binding.root.resources.getColoredDrawableWithColor(
                R.drawable.rounded_rectangle_color,
                backgroundColor
            )

            binding.styleOriginal.background = drawable
            binding.styleRounded.background = drawable
            binding.styleIosNew.background = drawable
            binding.styleIos.background = drawable
        }
        binding.styleOriginal.setOnClickListener {
            currentBubbleStyle = BUBBLE_STYLE_ORIGINAL
            setupToggleBubbleStyle(BUBBLE_STYLE_ORIGINAL)
        }
        binding.styleRounded.setOnClickListener {
            if (isPro) {
                currentBubbleStyle = BUBBLE_STYLE_ROUNDED
                setupToggleBubbleStyle(BUBBLE_STYLE_ROUNDED)
            } else isNotPro()
        }
        binding.styleIosNew.setOnClickListener {
            currentBubbleStyle = BUBBLE_STYLE_IOS_NEW
            setupToggleBubbleStyle(BUBBLE_STYLE_IOS_NEW)
        }
        binding.styleIos.setOnClickListener {
            if (isPro) {
                currentBubbleStyle = BUBBLE_STYLE_IOS
                setupToggleBubbleStyle(BUBBLE_STYLE_IOS)
            } else isNotPro()
        }

        if (!isPro) {
            binding.styleRounded.alpha = 0.6f
            binding.styleIos.alpha = 0.6f
        }

        // Setup custom title view inside BlurView
        val titleTextView = binding.root.findViewById<com.goodwy.commons.views.MyTextView>(com.goodwy.commons.R.id.dialog_title)
        titleTextView?.apply {
            visibility = android.view.View.VISIBLE
            setText(com.goodwy.strings.R.string.speech_bubble)
        }

        // Setup custom buttons inside BlurView
        val primaryColor = activity.getProperPrimaryColor()
        val buttonsContainer = binding.root.findViewById<android.widget.LinearLayout>(com.goodwy.commons.R.id.buttons_container)
        val positiveButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(com.goodwy.commons.R.id.positive_button)
        val negativeButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(com.goodwy.commons.R.id.negative_button)

        buttonsContainer?.visibility = android.view.View.VISIBLE

        negativeButton?.apply {
            visibility = android.view.View.VISIBLE
            text = activity.resources.getString(com.goodwy.commons.R.string.cancel)
            setTextColor(primaryColor)
            setOnClickListener {
                dialog?.dismiss()
            }
        }

        positiveButton?.apply {
            visibility = android.view.View.VISIBLE
            text = activity.resources.getString(com.goodwy.commons.R.string.ok)
            setTextColor(primaryColor)
            setOnClickListener {
                config.bubbleStyle = currentBubbleStyle
                callback(currentBubbleStyle)
                dialog?.dismiss()
            }
        }

        activity.getAlertDialogBuilder()
            .apply {
                activity.setupDialogStuff(binding.root, this, titleId = 0) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }

    private fun isNotPro() {
        RxAnimation.from(binding.styleIos)
            .shake(shakeTranslation = 2f)
            .subscribe()

        val snackbar = Snackbar.make(binding.root, com.goodwy.strings.R.string.support_project_to_unlock, Snackbar.LENGTH_SHORT)
            .setAction(com.goodwy.commons.R.string.support) {
                dialog?.dismiss()
                activity.launchPurchase()
            }

        val bgDrawable = when {
            activity.isDynamicTheme() -> ResourcesCompat.getDrawable(binding.root.resources, com.goodwy.commons.R.drawable.dialog_bg, null)
            else -> ResourcesCompat.getDrawable(binding.root.resources, com.goodwy.commons.R.drawable.button_background_16dp, null)
        }
        snackbar.view.background = bgDrawable
        val properBackgroundColor = activity.getProperBackgroundColor()
        val backgroundColor =
            if (properBackgroundColor == Color.BLACK || activity.isDynamicTheme()) properBackgroundColor
            else activity.getSurfaceColor()
        snackbar.setBackgroundTint(backgroundColor)
        snackbar.setTextColor(activity.getProperTextColor())
        snackbar.setActionTextColor(activity.getProperPrimaryColor())
        snackbar.show()
    }

    private fun setupToggleBubbleStyle(style: Int = currentBubbleStyle) {
        binding.styleOriginalCheck.isActivated = style == BUBBLE_STYLE_ORIGINAL
        binding.styleRoundedCheck.isActivated = style == BUBBLE_STYLE_ROUNDED
        binding.styleIosNewCheck.isActivated = style == BUBBLE_STYLE_IOS_NEW
        binding.styleIosCheck.isActivated = style == BUBBLE_STYLE_IOS
        val states = arrayOf(
            intArrayOf(android.R.attr.state_activated),
            intArrayOf(-android.R.attr.state_activated))
        arrayOf(binding.styleOriginalCheck, binding.styleRoundedCheck, binding.styleIosNewCheck, binding.styleIosCheck).forEach {
            it.imageTintList = ColorStateList(states, intArrayOf(binding.root.context.getProperPrimaryColor(), binding.root.context.getProperTextColor()))
        }
    }

    private fun setupBubbleInvertColor() {
        binding.bubbleInvertColor.isChecked = config.bubbleInvertColor
        binding.bubbleInvertColor.setOnCheckedChangeListener { isChecked ->
            config.bubbleInvertColor = isChecked
            setupColors()
        }
        binding.bubbleInvertColorHolder.setOnClickListener {
            binding.bubbleInvertColor.toggle()
        }
    }

    private fun setupBubbleUseContactColor() {
        binding.bubbleUseContactColor.isChecked = config.bubbleInContactColor
        binding.bubbleUseContactColor.setOnCheckedChangeListener { isChecked ->
            config.bubbleInContactColor = isChecked
            setupColors()
        }
        binding.bubbleUseContactColorHolder.setOnClickListener {
            binding.bubbleUseContactColor.toggle()
        }
    }

    private fun setupColors() {
        binding.apply {
            val random = (0..10).random().toString()
            val letterBackgroundColors = root.context.getLetterBackgroundColors()
            val primaryColor =
                if (config.bubbleInContactColor) letterBackgroundColors[abs(random.hashCode()) % letterBackgroundColors.size].toInt()
                else root.context.getProperPrimaryColor()

            val useSurfaceColor = root.context.isDynamicTheme() && !root.context.isSystemInDarkMode()
            val surfaceColor = if (useSurfaceColor) root.context.getProperBackgroundColor() else root.context.getSurfaceColor()

            val backgroundReceived = if (config.bubbleInvertColor) primaryColor else surfaceColor
            val contrastColorReceived = backgroundReceived.getContrastColor()
            arrayOf(
                styleOriginalBubbleOne,
                styleOriginalBubbleTwo,
                styleRoundedBubbleOne,
                styleRoundedBubbleTwo,
                styleIosNewBubbleOne,
                styleIosNewBubbleTwo,
                styleIosBubbleOne,
                styleIosBubbleTwo,
            ).forEach {
                it.background.applyColorFilter(backgroundReceived)
                it.setTextColor(contrastColorReceived)
            }

            val backgroundSender = if (config.bubbleInvertColor) surfaceColor else primaryColor
            val contrastColorSender = backgroundSender.getContrastColor()
            arrayOf(
                styleOriginalBubbleThree,
                styleRoundedBubbleThree,
                styleIosNewBubbleThree,
                styleIosBubbleThree,
            ).forEach {
                it.background.applyColorFilter(backgroundSender)
                it.setTextColor(contrastColorSender)
            }
        }
    }
}
