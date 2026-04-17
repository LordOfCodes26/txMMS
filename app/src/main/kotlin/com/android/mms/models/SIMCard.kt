package com.android.mms.models

//data class SIMCard(val id: Int, val subscriptionId: Int, val label: String)

// added by sun params mnc
data class SIMCard(val id: Int, val subscriptionId: Int, val label: String, val mnc:Int)
