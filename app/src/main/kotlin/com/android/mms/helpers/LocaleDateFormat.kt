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
