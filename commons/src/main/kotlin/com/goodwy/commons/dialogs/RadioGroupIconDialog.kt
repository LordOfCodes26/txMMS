package com.goodwy.commons.dialogs

import android.app.Activity
import com.goodwy.commons.models.RadioItem
import eightbitlab.com.blurview.BlurTarget

class RadioGroupIconDialog(
    activity: Activity,
    items: ArrayList<RadioItem>,
    checkedItemId: Int = -1,
    titleId: Int = 0,
    showOKButton: Boolean = false,
    defaultItemId: Int? = null,
    cancelCallback: (() -> Unit)? = null,
    blurTarget: BlurTarget,
    callback: (newValue: Any) -> Unit,
) {
    init {
        RadioGroupDialog(
            activity = activity,
            items = items,
            checkedItemId = checkedItemId,
            titleId = titleId,
            showOKButton = showOKButton,
            defaultItemId = defaultItemId,
            cancelCallback = cancelCallback,
            blurTarget = blurTarget,
            callback = callback,
        )
    }
}
