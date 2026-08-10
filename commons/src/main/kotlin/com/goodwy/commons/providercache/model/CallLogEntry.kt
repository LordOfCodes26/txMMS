package com.goodwy.commons.providercache.model

data class CallLogEntry(
    val callId: Int,
    val phoneNumber: String,
    val cachedName: String,
    val cachedPhotoUri: String,
    val startTS: Long,
    val duration: Int,
    val type: Int,
    val simID: Int,
    val simTypeID: Int = 1,
    val simColor: Int = 0,
    val contactID: Int? = null,
    val features: Int? = null,
    val isUnknownNumber: Boolean = false,
    val isVoiceMail: Boolean = false,
    val blockReason: Int? = 0,
    val phoneAccountId: String = "",
    /** Android-system-normalized number with country code (e.g. +821021814406). Empty when unavailable. */
    val cachedNormalizedNumber: String = "",
    /** Number of calls in this group (>1 when loaded via SQL grouping). */
    val callCount: Int = 1,
    /** Call IDs of all calls in the group; populated when callCount > 1. */
    val groupedCallIds: List<Int> = emptyList(),
)
