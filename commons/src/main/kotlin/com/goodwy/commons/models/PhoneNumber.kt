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

/**
 * Collapses per-number primary flags to a single default (Android Phone.IS_PRIMARY): first in list order is primary.
 */
fun MutableList<PhoneNumber>.normalizeSingleDefaultPhoneFlag() {
    if (isEmpty()) return
    forEach { it.isPrimary = false }
    this[0].isPrimary = true
}
