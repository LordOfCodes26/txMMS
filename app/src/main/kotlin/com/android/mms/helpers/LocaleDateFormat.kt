package com.android.mms.helpers

import java.util.Locale

// created by sun
fun getLocaleDateFormatPatternMonthDay(): String {

    val lang = Locale.getDefault().getLanguage()

    var pattern = "MMM d"
    if ("ko" == lang) {
        pattern = "M월 d일"
    } else if ("zh" == lang) {
        pattern = "M月 d日"
    }
    return pattern
}

fun getLocaleDateFormatPatternMonthDayTime(): String {

    val lang = Locale.getDefault().getLanguage()

    var pattern = "MMM d H:m"
    if ("ko" == lang) {
        pattern = "M월 d일 H:m"
    } else if ("zh" == lang) {
        pattern = "M月 d日 H:m"
    }
    return pattern
}

fun getLocaleDateFormatPatternFull(): String {

    val lang = Locale.getDefault().getLanguage()

    var pattern = "MMM d yyyy"
    if ("ko" == lang) {
        pattern = "yyyy년 M월 d일"
    } else if ("zh" == lang) {
        pattern = "yyyy年 M月 d日"
    }
    return pattern
}
