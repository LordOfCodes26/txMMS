package com.goodwy.commons.providercache.identity

data class ContactIdentity(
    val aggregateContactId: Long,
    val rawContactIds: Set<Long>,
    val lookupKey: String?,
    val phoneDigits: Set<String>,
)

enum class ContactIdentityInputType {
    RAW,
    AGGREGATE,
    LOOKUP_KEY,
    PHONE_DIGITS,
}
