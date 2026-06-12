package com.android.mms.helpers

import com.android.mms.models.MmsSlideshow

/**
 * In-memory slideshow shared across compose, [ManageSlideshowActivity], and [EditSlideActivity].
 * Alps [WorkingMessage] keeps [SlideshowModel] on the activity — not in Intent JSON.
 */
object ComposeSlideshowBridge {
    var slideshow: MmsSlideshow? = null
    var editingSlideIndex: Int = 0

    fun clear() {
        slideshow = null
        editingSlideIndex = 0
    }
}
