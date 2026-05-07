package com.android.mms.models

import android.telephony.SmsManager

data class SimMessage(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val status: Int,
    val indexOnIcc: String,
) {
    val isIncoming: Boolean
        get() = status == SmsManager.STATUS_ON_ICC_READ || status == SmsManager.STATUS_ON_ICC_UNREAD
}
