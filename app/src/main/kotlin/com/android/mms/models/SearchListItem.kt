package com.android.mms.models

import java.util.Calendar

/**
 * Search results list: date section headers (same sections as [ConversationListItem]) plus rows.
 */
sealed class SearchListItem {

    data class DateHeader(
        val timestamp: Long,
        val dayCode: String,
    ) : SearchListItem()

    data class ResultRow(
        val result: SearchResult,
    ) : SearchListItem()
}

/**
 * Groups search hits by calendar day (today / yesterday / earlier), matching [BaseConversationsAdapter.groupConversationsByDateSections].
 */
fun groupSearchResultsByDateSections(results: List<SearchResult>): ArrayList<SearchListItem> {
    if (results.isEmpty()) return ArrayList()
    val sorted = results.sortedByDescending { it.dateMillis }
    val todayStart = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val yesterdayStart = (todayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    val todayStartMillis = todayStart.timeInMillis
    val yesterdayStartMillis = yesterdayStart.timeInMillis

    val out = ArrayList<SearchListItem>()
    var lastSection: String? = null
    for (result in sorted) {
        val dateMillis = result.dateMillis
        val section = when {
            dateMillis >= todayStartMillis -> ConversationListItem.SECTION_TODAY
            dateMillis >= yesterdayStartMillis -> ConversationListItem.SECTION_YESTERDAY
            else -> ConversationListItem.SECTION_BEFORE
        }
        if (section != lastSection) {
            val sectionTimestamp = when (section) {
                ConversationListItem.SECTION_TODAY -> todayStartMillis
                ConversationListItem.SECTION_YESTERDAY -> yesterdayStartMillis
                else -> dateMillis
            }
            out += SearchListItem.DateHeader(timestamp = sectionTimestamp, dayCode = section)
            lastSection = section
        }
        out += SearchListItem.ResultRow(result)
    }
    return out
}
