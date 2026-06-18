package com.android.mms.helpers

import com.android.mms.models.Contact

interface RecipientSelectionHost {
    fun onRecipientToggled(pageIndex: Int, contactIndex: Int, contact: Contact, selected: Boolean)
    fun selectedNormalizedNumbers(): Set<String>
    fun onSelectionChanged()
}
