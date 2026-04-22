package com.android.mms.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import com.android.mms.extensions.subscriptionManagerCompat
import java.lang.reflect.InvocationTargetException

private const val TAG = "SystemDefaultSms"

// created by sun
// for change sms default subscription id

@SuppressLint("MissingPermission", "PrivateApi", "SoonBlockedPrivateApi", "DiscouragedPrivateApi")
fun Context.trySetSystemDefaultSmsSubscriptionId(subscriptionId: Int): Boolean {
    if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
        return false
    }
    val subManager = subscriptionManagerCompat()
    try {
        val method = SubscriptionManager::class.java.getMethod("setDefaultSmsSubId", Int::class.javaPrimitiveType)
        method.invoke(subManager, subscriptionId)
    } catch (e: InvocationTargetException) {
        Log.d(TAG, "setDefaultSmsSubId rejected by system", e.cause ?: e)
        return false
    } catch (e: Throwable) {
        Log.d(TAG, "setDefaultSmsSubId unavailable", e)
        return false
    }
    val applied = SmsManager.getDefaultSmsSubscriptionId()
    val ok = applied == subscriptionId
    return ok
}
