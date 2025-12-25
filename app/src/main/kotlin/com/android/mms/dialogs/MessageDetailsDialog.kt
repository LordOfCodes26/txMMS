package com.android.mms.dialogs

import android.annotation.SuppressLint
import android.provider.Telephony.Sms
import android.telephony.SubscriptionInfo
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.dialogs.BasePropertiesDialog
import com.goodwy.commons.extensions.formatDateOrTime
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getProperBlurOverlayColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getTimeFormatWithSeconds
import com.goodwy.commons.extensions.setupDialogStuff
import com.android.mms.R
import com.android.mms.databinding.DialogMessageDetailsBinding
import com.android.mms.databinding.ScheduleMessageDialogBinding
import com.android.mms.extensions.subscriptionManagerCompat
import com.android.mms.models.Message
import com.goodwy.commons.extensions.viewBinding
import org.joda.time.DateTime
import eightbitlab.com.blurview.BlurTarget
import kotlin.getValue

class MessageDetailsDialog(val activity: BaseSimpleActivity, val message: Message, blurTarget: BlurTarget) : BasePropertiesDialog(activity) {

    private val binding by activity.viewBinding(DialogMessageDetailsBinding::inflate)
    init {
        // Setup BlurView
        val blurView = binding.root.findViewById<eightbitlab.com.blurview.BlurView>(com.android.mms.R.id.blurView)
        val decorView = activity.window.decorView
        val windowBackground = decorView.background

        blurView?.setOverlayColor(activity.getProperBlurOverlayColor())
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(8f)
            ?.setBlurAutoUpdate(true)

        addProperty(R.string.message_type, if (message.isMMS) "MMS" else "SMS")
        addProperty(com.goodwy.strings.R.string.status, message.getStatus())

        @SuppressLint("MissingPermission")
        val availableSIMs = activity.subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty()

        addProperty(message.getSenderOrReceiverLabel(), message.getSenderOrReceiverPhoneNumbers())
        if (availableSIMs.count() > 1) {
            addProperty(R.string.message_details_sim, message.getSIM(availableSIMs))
        }
        addProperty(message.getSentOrReceivedAtLabel(), message.getSentOrReceivedAt())

        // Setup custom title view inside BlurView
        val titleTextView = binding.root.findViewById<com.goodwy.commons.views.MyTextView>(com.goodwy.commons.R.id.dialog_title)
        titleTextView?.apply {
            visibility = android.view.View.VISIBLE
            setText(R.string.message_details)
        }

        // Setup custom button inside BlurView
        val primaryColor = activity.getProperPrimaryColor()
        val buttonsContainer = binding.root.findViewById<android.widget.LinearLayout>(com.goodwy.commons.R.id.buttons_container)
        val positiveButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(com.goodwy.commons.R.id.positive_button)

        buttonsContainer?.visibility = android.view.View.VISIBLE

        positiveButton?.apply {
            visibility = android.view.View.VISIBLE
            text = activity.resources.getString(com.goodwy.commons.R.string.ok)
            setTextColor(primaryColor)
            setOnClickListener {
                // Dialog will be dismissed by setupDialogStuff
            }
        }

        activity.getAlertDialogBuilder()
            .apply {
                activity.setupDialogStuff(mDialogView.root, this, titleId = 0)
            }
    }

    private fun Message.getSenderOrReceiverLabel(): Int {
        return if (isReceivedMessage()) {
            R.string.message_details_sender
        } else {
            R.string.message_details_receiver
        }
    }

    private fun Message.getSenderOrReceiverPhoneNumbers(): String {
        return if (isReceivedMessage()) {
            formatContactInfo(senderName, senderPhoneNumber)
        } else {
            participants.joinToString(", ") {
                formatContactInfo(it.name, it.phoneNumbers.first().value)
            }
        }
    }

    private fun formatContactInfo(name: String, phoneNumber: String): String {
        return if (name != phoneNumber) {
            "$name ($phoneNumber)"
        } else {
            phoneNumber
        }
    }

    private fun Message.getSIM(availableSIMs: List<SubscriptionInfo>): String {
        return availableSIMs.firstOrNull { it.subscriptionId == subscriptionId }?.displayName?.toString()
            ?: activity.getString(com.goodwy.commons.R.string.unknown)
    }

    private fun Message.getSentOrReceivedAtLabel(): Int {
        return if (isReceivedMessage()) {
            R.string.message_details_received_at
        } else {
            R.string.message_details_sent_at
        }
    }

    private fun Message.getSentOrReceivedAt(): String {
//        return DateTime(date * 1000L).toString("${activity.config.dateFormat} ${activity.getTimeFormatWithSeconds()}")
        return (date * 1000L).formatDateOrTime(
            context = activity,
            hideTimeOnOtherDays = false,
            showCurrentYear = true,
            hideTodaysDate = false,
            timeFormat = activity.getTimeFormatWithSeconds()
        )
    }

    private fun Message.getStatus(): String {
        return when (status) {
            Sms.STATUS_COMPLETE -> activity.getString(R.string.delivered)
            Sms.STATUS_FAILED -> activity.getString(R.string.failed)
            Sms.STATUS_PENDING -> activity.getString(R.string.pending)
            else -> activity.getString(com.goodwy.commons.R.string.unknown)
        }
    }
}
