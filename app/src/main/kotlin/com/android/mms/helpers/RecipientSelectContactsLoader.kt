package com.android.mms.helpers

import android.content.Context
import com.android.mms.models.Contact
import com.goodwy.commons.extensions.getMyContactsCursor
import com.goodwy.commons.helpers.MyContactsContentProvider
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.helpers.ensureBackgroundThread

object RecipientSelectContactsLoader {
    fun load(
        context: Context,
        alreadySelectedNormalized: Set<String>,
        onResult: (contacts: List<Contact>, selectedIndices: Set<Int>) -> Unit,
        onEmpty: () -> Unit,
    ) {
        ensureBackgroundThread {
            try {
                val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
                SimpleContactsHelper(context).getAvailableContacts(false) { systemContacts ->
                    val list = ArrayList<Contact>()
                    systemContacts.forEach { simple ->
                        simple.phoneNumbers.forEach { phone ->
                            if (phone.normalizedNumber.isEmpty() && phone.value.isEmpty()) return@forEach
                            list.add(
                                Contact(
                                    name = simple.name,
                                    contactId = simple.rawId.toString(),
                                    phoneNumber = phone.value.ifEmpty { phone.normalizedNumber },
                                    photoUri = simple.photoUri,
                                ),
                            )
                        }
                    }
                    try {
                        val privateContacts = MyContactsContentProvider.getSimpleContacts(context, privateCursor)
                        privateContacts.forEach { simple ->
                            simple.phoneNumbers.forEach { phone ->
                                if (phone.normalizedNumber.isEmpty() && phone.value.isEmpty()) return@forEach
                                list.add(
                                    Contact(
                                        name = simple.name,
                                        contactId = "local_${simple.rawId}",
                                        phoneNumber = phone.value.ifEmpty { phone.normalizedNumber },
                                        photoUri = simple.photoUri,
                                    ),
                                )
                            }
                        }
                    } catch (_: Exception) {
                    }
                    if (list.isEmpty()) {
                        onEmpty()
                        return@getAvailableContacts
                    }
                    val selected = HashSet<Int>()
                    list.forEachIndexed { index, contact ->
                        if (alreadySelectedNormalized.contains(normalizePhoneNumber(contact.phoneNumber))) {
                            selected.add(index)
                        }
                    }
                    onResult(list, selected)
                }
            } catch (_: Exception) {
                onEmpty()
            }
        }
    }

    private fun normalizePhoneNumber(phone: String): String = phone.filter { it.isDigit() }
}
