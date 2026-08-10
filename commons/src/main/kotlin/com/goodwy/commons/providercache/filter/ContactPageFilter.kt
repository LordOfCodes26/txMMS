package com.goodwy.commons.providercache.filter

import com.goodwy.commons.models.contacts.Contact

/** App-supplied filter applied to each paged batch before it reaches the UI. */
fun interface ContactPageFilter {
    suspend fun filterPage(contacts: List<Contact>): List<Contact>
}
