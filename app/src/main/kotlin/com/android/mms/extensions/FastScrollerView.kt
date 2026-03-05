package com.android.mms.extensions

import androidx.recyclerview.widget.RecyclerView
import com.reddit.indicatorfastscroll.FastScrollItemIndicator
import com.reddit.indicatorfastscroll.FastScrollerView
import com.android.mms.adapters.ContactPhonePair
import com.android.mms.models.Contact
import com.goodwy.commons.models.contacts.Contact as CommonContact

fun FastScrollerView.setupWithContactPhonePairs(
    recyclerView: RecyclerView,
    contactPhonePairs: List<ContactPhonePair>,
) = setupWithRecyclerView(recyclerView, { position ->
    try {
        val pair = contactPhonePairs[position]
        FastScrollItemIndicator.Text(CommonContact(id = 0, firstName = pair.contact.name, contactId = 0).getFirstLetter())
    } catch (_: IndexOutOfBoundsException) {
        FastScrollItemIndicator.Text("")
    }
}, useDefaultScroller = true)

fun FastScrollerView.setupWithContacts(
    recyclerView: RecyclerView,
    contacts: List<Contact>,
) = setupWithRecyclerView(recyclerView, { position ->
    try {
        val contact = contacts[position]
        FastScrollItemIndicator.Text(CommonContact(id = 0, firstName = contact.name.ifEmpty { contact.phoneNumber }, contactId = 0).getFirstLetter())
    } catch (_: IndexOutOfBoundsException) {
        FastScrollItemIndicator.Text("")
    }
}, useDefaultScroller = true)
