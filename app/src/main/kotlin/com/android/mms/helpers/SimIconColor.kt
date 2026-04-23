package com.android.mms.helpers

import android.content.Context
import com.android.mms.extensions.subscriptionManagerCompat
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.helpers.BaseConfig


fun BaseConfig.getSimIconTintForSlot(textColor: Int, simSlotId: Int): Int {
    if (!colorSimIcons) return textColor
    return if (simSlotId in 1..4) {
        simIconsColors[simSlotId]
    } else {
        simIconsColors[0]
    }
}
@Suppress("MissingPermission")
fun Context.resolveSimIconTint(
    textColor: Int,
    subscriptionId: Int,
    simSlotId: Int
): Int {
    if (subscriptionId >= 0) {
        val sub = subscriptionManagerCompat().activeSubscriptionInfoList?.firstOrNull {
            it.subscriptionId == subscriptionId
        }
        if (sub != null) {
            val systemTint = sub.iconTint
            if (systemTint != 0) return systemTint
        }

    }
    return baseConfig.getSimIconTintForSlot(textColor, simSlotId)
}
