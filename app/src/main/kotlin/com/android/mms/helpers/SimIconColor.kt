package com.android.mms.helpers

import android.Manifest
import android.content.Context
import android.telephony.SubscriptionManager
import androidx.annotation.RequiresPermission
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

@RequiresPermission(Manifest.permission.READ_PHONE_STATE)
fun Context.subscriptionIdForOneBasedSimSlot(simSlot: Int): Int {
    if (simSlot !in 1..4) return SubscriptionManager.INVALID_SUBSCRIPTION_ID
    val subs = subscriptionManagerCompat().activeSubscriptionInfoList?: return SubscriptionManager.INVALID_SUBSCRIPTION_ID
    subs.firstOrNull{ it.simSlotIndex == simSlot - 1}?.subscriptionId?.let { return it }
    subs.firstOrNull{ it.simSlotIndex == simSlot }?.subscriptionId?.let { return it }

    return subs.getOrNull(simSlot - 1)?.subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
}
