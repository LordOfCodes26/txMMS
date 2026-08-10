package com.goodwy.commons.providercache.grouping

import com.goodwy.commons.providercache.display.RecentGroupIdentityResolver
import com.goodwy.commons.providercache.entities.ContactDisplayCacheEntity
import com.goodwy.commons.providercache.entities.ContactSummaryEntity

fun RecentContactResolutionSnapshot.toIdentityContext(): RecentGroupIdentityResolver.Context =
    RecentGroupIdentityResolver.Context(
        summariesById = contactsById.mapKeys { it.key.toInt() },
        phoneIndexByDigits = phoneIndexByCanonicalNumber,
        displayByContactId = displayByContactId.mapKeys { it.key.toInt() },
    )

fun RecentContactResolutionSnapshot.summaryFor(contactId: Long): ContactSummaryEntity? =
    contactsById[contactId]

fun RecentContactResolutionSnapshot.displayFor(contactId: Long): ContactDisplayCacheEntity? =
    displayByContactId[contactId]
