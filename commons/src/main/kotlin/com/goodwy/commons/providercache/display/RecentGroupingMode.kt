package com.goodwy.commons.providercache.display

enum class RecentGroupingMode(val dbValue: Int) {
    BY_NUMBER(0),
    BY_CONTACT(1),
    ;

    companion object {
        fun fromDbValue(value: Int): RecentGroupingMode =
            entries.firstOrNull { it.dbValue == value } ?: BY_NUMBER
    }
}
