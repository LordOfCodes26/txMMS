package com.android.mms.extensions

import com.goodwy.commons.views.MySearchMenu

/** Sets the large collapsing headline and clears the pinned toolbar title so the same text is not shown twice. */
fun MySearchMenu.applyLargeTitleOnly(title: String) {
    requireCustomToolbar().title = ""
    binding.collapsingTitle.text = title
}
