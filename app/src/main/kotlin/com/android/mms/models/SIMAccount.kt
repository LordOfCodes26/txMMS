package com.android.mms.models

import android.telecom.PhoneAccountHandle

// created by sun
data class SIMAccount(
    val id: Int,
    val simTypeId: Int = 1,
    val handle: PhoneAccountHandle,
    val label: String,
    val phoneNumber: String,
    val color: Int,
)

