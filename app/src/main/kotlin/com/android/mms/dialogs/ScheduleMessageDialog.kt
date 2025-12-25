package com.android.mms.dialogs

import android.app.DatePickerDialog
import android.app.DatePickerDialog.OnDateSetListener
import android.app.TimePickerDialog
import android.app.TimePickerDialog.OnTimeSetListener
import android.text.format.DateFormat
import androidx.appcompat.app.AlertDialog
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.applyColorFilter
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getDatePickerDialogTheme
import com.goodwy.commons.extensions.getProperBlurOverlayColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getTimeFormat
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.setupDialogStuff
import com.goodwy.commons.extensions.toast
import com.android.mms.R
import com.android.mms.databinding.ScheduleMessageDialogBinding
import com.android.mms.extensions.config
import com.android.mms.extensions.roundToClosestMultipleOf
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import org.joda.time.DateTime
import java.util.Calendar

class ScheduleMessageDialog(
    private val activity: BaseSimpleActivity,
    private var dateTime: DateTime? = null,
    private val blurTarget: BlurTarget,
    private val callback: (dateTime: DateTime?) -> Unit
) {
    private val binding = ScheduleMessageDialogBinding.inflate(activity.layoutInflater)
    private val textColor = activity.getProperTextColor()

    private var previewDialog: AlertDialog? = null
    private var previewShown = false
    private var isNewMessage = dateTime == null

    private val calendar = Calendar.getInstance()

    init {
        // Setup BlurView
        val blurView = binding.root.findViewById<eightbitlab.com.blurview.BlurView>(com.android.mms.R.id.blurView)
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        blurView?.setOverlayColor(activity.getProperBlurOverlayColor())
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(8f)
            ?.setBlurAutoUpdate(true)
        
        arrayOf(binding.subtitle, binding.editTime, binding.editDate).forEach {
            it.setTextColor(textColor)
        }

        arrayOf(binding.dateImage, binding.timeImage).forEach {
            it.applyColorFilter(textColor)
        }

        binding.editDate.setOnClickListener { showDatePicker() }
        binding.editTime.setOnClickListener { showTimePicker() }

        val targetDateTime = dateTime ?: DateTime.now().plusHours(1)
        updateTexts(targetDateTime)

        if (isNewMessage) {
            showDatePicker()
        } else {
            showPreview()
        }
    }

    private fun updateTexts(dateTime: DateTime) {
        val dateFormat = activity.config.dateFormat
        val timeFormat = activity.getTimeFormat()
        binding.editDate.text = dateTime.toString(dateFormat)
        binding.editTime.text = dateTime.toString(timeFormat)
    }

    private fun showPreview() {
        if (previewShown) {
            return
        }

        // Setup custom title view inside BlurView
        val titleTextView = binding.root.findViewById<com.goodwy.commons.views.MyTextView>(com.goodwy.commons.R.id.dialog_title)
        titleTextView?.apply {
            visibility = android.view.View.VISIBLE
            setText(R.string.schedule_message)
        }

        // Setup custom buttons inside BlurView
        val primaryColor = activity.getProperPrimaryColor()
        val buttonsContainer = binding.root.findViewById<android.widget.LinearLayout>(com.goodwy.commons.R.id.buttons_container)
        val positiveButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(com.goodwy.commons.R.id.positive_button)
        val negativeButton = binding.root.findViewById<com.google.android.material.button.MaterialButton>(com.goodwy.commons.R.id.negative_button)

        buttonsContainer?.visibility = android.view.View.VISIBLE

        positiveButton?.apply {
            visibility = android.view.View.VISIBLE
            text = activity.resources.getString(com.goodwy.commons.R.string.ok)
            setTextColor(primaryColor)
            setOnClickListener {
                if (validateDateTime()) {
                    callback(dateTime)
                    previewDialog?.dismiss()
                }
            }
        }

        negativeButton?.apply {
            visibility = android.view.View.VISIBLE
            text = activity.resources.getString(com.goodwy.commons.R.string.cancel)
            setTextColor(primaryColor)
            setOnClickListener {
                previewDialog?.dismiss()
            }
        }

        activity.getAlertDialogBuilder()
            .apply {
                previewShown = true
                activity.setupDialogStuff(binding.root, this, titleId = 0) { dialog ->
                    previewDialog = dialog

                    dialog.setOnDismissListener {
                        previewShown = false
                        previewDialog = null
                    }
                }
            }
    }

    private fun showDatePicker() {
        val year = dateTime?.year ?: calendar.get(Calendar.YEAR)
        val monthOfYear = dateTime?.monthOfYear?.minus(1) ?: calendar.get(Calendar.MONTH)
        val dayOfMonth = dateTime?.dayOfMonth ?: calendar.get(Calendar.DAY_OF_MONTH)

        val dateSetListener = OnDateSetListener { _, y, m, d -> dateSet(y, m, d) }
        DatePickerDialog(
            activity,
            activity.getDatePickerDialogTheme(),
            dateSetListener,
            year,
            monthOfYear,
            dayOfMonth
        ).apply {
            datePicker.minDate = System.currentTimeMillis()
            show()
            getButton(AlertDialog.BUTTON_NEGATIVE).apply {
                text = activity.getString(com.goodwy.commons.R.string.cancel)
                setOnClickListener {
                    dismiss()
                }
            }
        }
    }

    private fun showTimePicker() {
        val hourOfDay = dateTime?.hourOfDay ?: getNextHour()
        val minute = dateTime?.minuteOfHour ?: getNextMinute()

        if (activity.isDynamicTheme()) {
            val timeFormat = if (DateFormat.is24HourFormat(activity)) {
                TimeFormat.CLOCK_24H
            } else {
                TimeFormat.CLOCK_12H
            }

            val timePicker = MaterialTimePicker.Builder()
                .setTimeFormat(timeFormat)
                .setHour(hourOfDay)
                .setMinute(minute)
                .build()

            timePicker.addOnPositiveButtonClickListener {
                timeSet(timePicker.hour, timePicker.minute)
            }

            timePicker.show(activity.supportFragmentManager, "")
        } else {
            val timeSetListener = OnTimeSetListener { _, hours, minutes -> timeSet(hours, minutes) }
            TimePickerDialog(
                activity,
                activity.getDatePickerDialogTheme(),
                timeSetListener,
                hourOfDay,
                minute,
                DateFormat.is24HourFormat(activity)
            ).apply {
                show()
                getButton(AlertDialog.BUTTON_NEGATIVE).apply {
                    text = activity.getString(com.goodwy.commons.R.string.cancel)
                    setOnClickListener {
                        dismiss()
                    }
                }
            }
        }
    }

    private fun dateSet(year: Int, monthOfYear: Int, dayOfMonth: Int) {
        if (isNewMessage) {
            showTimePicker()
        }

        dateTime = DateTime.now()
            .withDate(year, monthOfYear + 1, dayOfMonth)
            .run {
                if (dateTime != null) {
                    withTime(dateTime!!.hourOfDay, dateTime!!.minuteOfHour, 0, 0)
                } else {
                    withTime(getNextHour(), getNextMinute(), 0, 0)
                }
            }

        if (!isNewMessage) {
            validateDateTime()
        }

        isNewMessage = false
        updateTexts(dateTime!!)
    }

    private fun timeSet(hourOfDay: Int, minute: Int) {
        dateTime = dateTime?.withHourOfDay(hourOfDay)?.withMinuteOfHour(minute)
        if (validateDateTime()) {
            updateTexts(dateTime!!)
            showPreview()
        } else {
            showTimePicker()
        }
    }

    private fun validateDateTime(): Boolean {
        return if (dateTime?.isAfterNow == false) {
            activity.toast(R.string.must_pick_time_in_the_future)
            false
        } else {
            true
        }
    }

    private fun getNextHour(): Int {
        return (calendar.get(Calendar.HOUR_OF_DAY) + 1)
            .coerceIn(0, 23)
    }

    private fun getNextMinute(): Int {
        return (calendar.get(Calendar.MINUTE) + 5)
            .roundToClosestMultipleOf(5)
            .coerceIn(0, 59)
    }
}
