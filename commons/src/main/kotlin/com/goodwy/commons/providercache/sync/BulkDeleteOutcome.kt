package com.goodwy.commons.providercache.sync

data class BulkDeleteRemainingRow(
    val contactId: Int,
    val rawContactId: Int,
    val accountName: String,
    val accountType: String,
    val sourceId: String,
    val deleted: Int,
)

data class BulkDeleteOutcome(
    val success: Boolean,
    val providerRawContacts: Int,
    val providerContacts: Int,
    val roomContacts: Int,
    val displayRows: Int,
    val remainingSamples: List<BulkDeleteRemainingRow> = emptyList(),
)
