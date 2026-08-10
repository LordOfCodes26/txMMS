package com.goodwy.commons.providercache.display

import com.goodwy.commons.helpers.AvatarBindData
import com.goodwy.commons.models.contacts.Contact

data class ContactDisplayLoadResult(
    val contacts: List<Contact>,
    val avatarBinds: Map<Int, AvatarBindData> = emptyMap(),
    val cacheVersion: Long,
    val contentHash: Long = 0L,
    val queryMs: Long,
    val mapMs: Long,
    val rowCount: Int,
    val totalRowCount: Int = rowCount,
    val hasMore: Boolean = false,
    /** Full-list snapshot with FastScroll sections; null for search/chunk loads. */
    val snapshot: ContactsDisplaySnapshot? = null,
    val sectionsMs: Long = 0L,
)
