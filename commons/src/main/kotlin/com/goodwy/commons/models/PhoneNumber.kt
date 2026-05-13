package com.goodwy.commons.models

import kotlinx.serialization.Serializable

@Serializable
data class PhoneNumber(
    var value: String,
    var type: Int,
    var label: String,
    var normalizedNumber: String,
    var isPrimary: Boolean = false,
    // added by sun --->
    var accountName: String? = null,
    var accountType: String? = null
    // <--------
)
