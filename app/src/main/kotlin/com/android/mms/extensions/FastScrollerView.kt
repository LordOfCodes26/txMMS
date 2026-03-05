package com.android.mms.extensions

import androidx.recyclerview.widget.RecyclerView
import com.reddit.indicatorfastscroll.FastScrollItemIndicator
import com.reddit.indicatorfastscroll.FastScrollerView
import com.android.mms.adapters.ContactPhonePair
import com.android.mms.models.Contact
import com.android.mms.utils.CharUtils

fun FastScrollerView.setupWithContactPhonePairs(
    recyclerView: RecyclerView,
    contactPhonePairs: List<ContactPhonePair>,
) = setupWithRecyclerView(recyclerView, { position ->
    val sectionIndicator = try {
        val pair = contactPhonePairs[position]
        val name = pair.contact.name

        if (name.isNotEmpty() && (CharUtils.isKoreanCharacter(name[0]) || CharUtils.isKoreanJamo(name[0]))) {
            CharUtils.getFirstConsonant(name)
        } else {
            CharUtils.defaultSection
        }
    } catch (_: IndexOutOfBoundsException) {
        CharUtils.defaultSection
    }

    FastScrollItemIndicator.Text(sectionIndicator)
})

fun FastScrollerView.setupWithContacts(
    recyclerView: RecyclerView,
    contacts: List<Contact>,
) = setupWithRecyclerView(recyclerView, { position ->
    val sectionIndicator = try {
        val contact = contacts[position]
        val name = contact.name.ifEmpty { contact.phoneNumber }

        if (name.isNotEmpty() && (CharUtils.isKoreanCharacter(name[0]) || CharUtils.isKoreanJamo(name[0]))) {
            CharUtils.getFirstConsonant(name)
        } else {
            CharUtils.defaultSection
        }
    } catch (_: IndexOutOfBoundsException) {
        CharUtils.defaultSection
    }

    FastScrollItemIndicator.Text(sectionIndicator)
})
