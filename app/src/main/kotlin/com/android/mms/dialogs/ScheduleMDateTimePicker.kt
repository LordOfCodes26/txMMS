package com.android.mms.dialogs

import com.android.common.dialogs.MDateTimePickerDialog
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.toast
import com.android.mms.R
import eightbitlab.com.blurview.BlurTarget
import org.joda.time.DateTime
import java.util.Calendar
import java.util.Date

/**
 * Picks send time using [MDateTimePickerDialog] (same component as the attachment picker Clock fallback).
 */
fun BaseSimpleActivity.showScheduleDateTimePicker(
    blurTarget: BlurTarget,
    onDateTimeSelected: (DateTime) -> Unit
) {
    val dialog = MDateTimePickerDialog(this)
    dialog.bindBlurTarget(blurTarget)
    dialog.show()
    dialog.setOnDateSelectListener { datetime ->
        val picked = datetime.toJodaDateTime() ?: return@setOnDateSelectListener
        if (picked.millis < System.currentTimeMillis() + 1000L) {
            toast(R.string.must_pick_time_in_the_future)
            window?.decorView?.post {
                showScheduleDateTimePicker(blurTarget, onDateTimeSelected)
            }
            return@setOnDateSelectListener
        }
        onDateTimeSelected(picked)
    }
}

private fun Any.toJodaDateTime(): DateTime? {
    return when (this) {
        is Date -> DateTime(this)
        is Calendar -> DateTime(this)
        else -> try {
            DateTime(this)
        } catch (_: Exception) {
            null
        }
    }
}
