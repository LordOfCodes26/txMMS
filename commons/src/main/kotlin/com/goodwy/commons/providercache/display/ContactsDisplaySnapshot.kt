package com.goodwy.commons.providercache.display

/**
 * One immutable Contacts-tab publication: full ordered rows + FastScroll metadata for the same
 * [displayVersion]. Rows and sections must always be published together.
 *
 * Stable identity for adapter rows is [ContactDisplayListRow.rawId] (mapped to [com.goodwy.commons.models.contacts.Contact.id]).
 */
data class ContactsDisplaySnapshot(
    val displayVersion: Long,
    val rows: List<ContactDisplayListRow>,
    val contacts: List<com.goodwy.commons.models.contacts.Contact>,
    val sections: List<FastScrollSection>,
    val contentChecksum: Long,
) {
    val rowCount: Int get() = rows.size
}

data class FastScrollSection(
    val label: String,
    /** Zero-based index into the contact rows list (not including header rows). */
    val firstPosition: Int,
)
