package com.android.mms.models

/** Rows for contact picker list; call-log mode adds date sections like Dialer recents. */
sealed class ContactPickerListRow {
    data class DateSection(val dayCode: String) : ContactPickerListRow() {
        companion object {
            const val SECTION_TODAY = "__section_today__"
            const val SECTION_YESTERDAY = "__section_yesterday__"
            const val SECTION_BEFORE = "__section_before__"
        }
    }

    data class ContactRow(
        val contactIndex: Int,
        val callType: Int,
        val callTimestamp: Long,
        /** Calls merged into this row (same number within loaded window); shown like Dialer recents. */
        val groupedCallCount: Int = 1,
    ) : ContactPickerListRow()
}
