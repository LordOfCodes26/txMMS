package com.goodwy.commons.providercache.startup

/** Result of lightweight Contacts cache validation at startup — before any full provider sync. */
sealed interface ContactsStartupDecision {
    data object UseExistingCache : ContactsStartupDecision
    data object RunIncrementalAfterFirstPaint : ContactsStartupDecision
    data class RunFullRepairAfterFirstPaint(val reason: String) : ContactsStartupDecision
    data object PermissionBlocked : ContactsStartupDecision
    data object ValidationPending : ContactsStartupDecision
}
