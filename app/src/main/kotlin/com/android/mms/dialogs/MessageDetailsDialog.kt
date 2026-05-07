package com.android.mms.dialogs

import android.annotation.SuppressLint
import android.provider.Telephony.Sms
import android.telephony.SubscriptionInfo
import android.view.Gravity
import android.widget.LinearLayout
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.dialogs.BasePropertiesDialog
import com.goodwy.commons.extensions.formatDateOrTime
import com.goodwy.commons.extensions.getProperBlurOverlayColor
import com.goodwy.commons.extensions.getTimeFormatWithSeconds
import com.android.mms.R
import com.android.mms.extensions.subscriptionManagerCompat
import com.android.mms.models.Message
import com.goodwy.commons.databinding.DialogTitleBinding
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getTitle
import com.goodwy.commons.extensions.setupMDialogStuff
import eightbitlab.com.blurview.BlurTarget

@SuppressLint("SuspiciousIndentation")
class MessageDetailsDialog(val activity: BaseSimpleActivity, val message: Message, blurTarget: BlurTarget) : BasePropertiesDialog(activity) {

    init {
        val decorView = activity.window.decorView
        val windowBackground = decorView.background

            mDialogView.blurView.setOverlayColor(activity.getProperBlurOverlayColor())
            mDialogView.blurView.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(16f)
            ?.setBlurAutoUpdate(true)

        val titleBinding = DialogTitleBinding.inflate(mInflater, mDialogView.root,false)
        titleBinding.dialogTitleTextview.apply {
            setText(R.string.message_details)
            setTextColor(activity.getProperTextColor())
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,

            )
        }
        mDialogView.propertiesHolder.addView(titleBinding.root, 0)
        addProperty(R.string.message_type, if (message.isMMS) activity.getString(R.string.mms) else activity.getString(R.string.sms))
        addProperty(com.goodwy.strings.R.string.status, message.getStatus())

        @SuppressLint("MissingPermission")
        val availableSIMs = activity.subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty()

        addProperty(message.getSenderOrReceiverLabel(), message.getSenderOrReceiverPhoneNumbers())
        if (availableSIMs.count() > 1) {
            addProperty(R.string.message_details_sim, message.getSIM(availableSIMs))
        }
        addProperty(message.getSentOrReceivedAtLabel(), message.getSentOrReceivedAt())

        activity.setupMDialogStuff(
            view = mDialogView.root,
            blurView = mDialogView.blurView,
            blurTarget = blurTarget,
            titleId = 0
        )
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
