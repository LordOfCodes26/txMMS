package com.goodwy.commons.providercache.filter

import com.goodwy.commons.models.contacts.Contact

fun interface ContactPageFilterEngine {
    suspend fun filterPage(contacts: List<Contact>): List<Contact>
}
