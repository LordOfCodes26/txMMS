package com.android.mms.helpers

import android.content.Context
import android.content.DialogInterface
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.android.common.view.MBlurView
import com.android.common.view.MDialog
import com.android.mms.R
import com.goodwy.commons.extensions.getProperTextColor
import eightbitlab.com.blurview.BlurTarget
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

/**
 * In-app voice recorder dialog, matching txNote [com.tx.note.app.attachment.SoundRecorder].
 */
class SoundRecorder(
    private val context: Context,
    private val blurTarget: BlurTarget,
    private val listener: OnSoundRecorderCompleteListener,
) {
    fun interface OnSoundRecorderCompleteListener {
        fun onRecorderComplete(soundPath: String, durationInSec: Int)
    }

    companion object {
        private val DATE_FORMATTER = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        private const val MESSAGE_UPDATE_SOUND_TIME = 1
        private const val MESSAGE_ERROR_TOAST = 2
        private const val LIMIT_TIME_SEC = 300L

        fun getSoundName(): String = synchronized(SoundRecorder::class.java) {
            DATE_FORMATTER.format(Date(System.currentTimeMillis()))
        }

        fun formatMinuteTime(elapse: Int, connectChar: String = " : "): String {
            val minutes = elapse % 3600 / 60
            val seconds = elapse % 60
            return buildString {
                append(minutes.toString().padStart(2, '0'))
                append(connectChar)
                append(seconds.toString().padStart(2, '0'))
            }
        }
    }

    private var dialog: MDialog? = null
    private var durationInSec = 0
    private var soundPath: String? = null
    private var mediaRecorder: MediaRecorder? = null
    private var pauseButton: ImageView? = null
    private var recPoint: ImageView? = null
    private var timeView: TextView? = null
    private var titleView: TextView? = null
    private var timer: Timer? = null
    private var timerTask: TimerTask? = null
    private var paused = false
    private var completed = false

    private val mainHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MESSAGE_UPDATE_SOUND_TIME -> {
                    val time = msg.arg1
                    durationInSec = time
                    if (durationInSec < LIMIT_TIME_SEC) {
                        timeView?.text = formatMinuteTime(time)
                    } else {
                        completeRecorder()
                    }
                }
                MESSAGE_ERROR_TOAST -> checkSoundRecordSuccess()
            }
        }
    }

    fun launchRecording() {
        paused = false
        completed = false
        initDialog()
        showDialog()
        if (!startRecorder()) {
            return
        }
        startElapsedTimer(startElapseSec = 0, delayMs = 0, periodMs = 1000)
        mainHandler.sendMessageDelayed(mainHandler.obtainMessage(MESSAGE_ERROR_TOAST), 1500)
    }

    private fun initDialog() {
        if (dialog != null) return

        val view = LayoutInflater.from(context).inflate(R.layout.sound_recorder_layout, null, false)
        timeView = view.findViewById(R.id.sound_recorder_time)
        recPoint = view.findViewById(R.id.sound_recorder_point)
        pauseButton = view.findViewById(R.id.sound_recorder_pause)
        titleView = view.findViewById(R.id.sound_rec_title)
        val btnCancel = view.findViewById<TextView>(com.android.common.R.id.btn_cancel)
        val btnConfirm = view.findViewById<TextView>(com.android.common.R.id.btn_confirm)

        val iconTint = context.getProperTextColor()
        pauseButton?.setColorFilter(iconTint)

        pauseButton?.setOnClickListener { togglePauseResume() }
        btnConfirm.setOnClickListener { completeRecorder() }
        btnCancel.setOnClickListener {
            discardRecording()
            dismissDialog()
        }

        val mDialog = MDialog(context, com.android.common.R.style.MDialogStyleMinWidth)
        mDialog.setContentView(view)
        mDialog.setCancelable(true)
        mDialog.setCanceledOnTouchOutside(true)
        mDialog.setOnDismissListener(DialogInterface.OnDismissListener {
            if (!completed) {
                discardRecording()
            } else {
                stopRecorder()
                cancelTimer()
            }
            dialog = null
        })
        mDialog.window?.setGravity(Gravity.BOTTOM)

        val glassBlur = view.findViewById<MBlurView>(R.id.glassBlurView)
        glassBlur.clipToOutline = true
        mDialog.bindBlurTarget(glassBlur, blurTarget)

        dialog = mDialog
    }

    private fun showDialog() {
        paused = false
        timeView?.text = formatMinuteTime(0)
        pauseButton?.setImageResource(R.drawable.ic_media_pause)
        pauseButton?.contentDescription = context.getString(R.string.sound_recorder_pause)
        titleView?.setText(R.string.sound_recorder_while)
        recPoint?.setColorFilter(context.getColor(R.color.sound_recorder_dialog_point_color))
        dialog?.show()
    }

    /**
     * Under cacheDir/attachments so FileProvider (provider_paths.xml attachment_files) can expose
     * the recording via getMyFileUri — same root as other compose attachments.
     */
    private fun getSoundOutputDirectory(): File {
        return File(context.cacheDir, "attachments").apply { mkdirs() }
    }

    /** @return false if setup failed (dialog dismissed, recorder released). */
    private fun startRecorder(): Boolean {
        val soundName = "${getSoundName()}.amr"
        val dir = getSoundOutputDirectory()
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(context, R.string.attachment_record_hint, Toast.LENGTH_SHORT).show()
            dismissDialog()
            return false
        }
        val path = File(dir, soundName).absolutePath
        soundPath = path
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mediaRecorder = recorder
        return try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            recorder.setOutputFile(path)
            recorder.setOnErrorListener { _, _, _ -> completeRecorder() }
            recorder.prepare()
            recorder.start()
            true
        } catch (_: Exception) {
            try {
                recorder.release()
            } catch (_: Exception) {
            }
            mediaRecorder = null
            soundPath = null
            Toast.makeText(context, R.string.attachment_record_hint, Toast.LENGTH_SHORT).show()
            dismissDialog()
            false
        }
    }

    private fun startElapsedTimer(startElapseSec: Int, delayMs: Long, periodMs: Long) {
        cancelTimer()
        timer = Timer()
        timerTask = object : TimerTask() {
            private var elapse = startElapseSec
            override fun run() {
                val msg = mainHandler.obtainMessage(MESSAGE_UPDATE_SOUND_TIME)
                msg.arg1 = elapse
                mainHandler.sendMessage(msg)
                elapse++
            }
        }
        timer?.schedule(timerTask, delayMs, periodMs)
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
        timerTask?.cancel()
        timerTask = null
    }

    private fun pauseRecording() {
        if (paused || mediaRecorder == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.pause()
            } else {
                return
            }
            paused = true
            cancelTimer()
            recPoint?.setColorFilter(context.getColor(R.color.sound_recorder_dialog_stop_point_color))
            pauseButton?.setImageResource(R.drawable.ic_media_play)
            pauseButton?.contentDescription = context.getString(R.string.sound_recorder_resume)
            titleView?.setText(R.string.sound_recorder_dialog_title)
        } catch (_: Exception) {
            Toast.makeText(context, R.string.attachment_record_hint, Toast.LENGTH_SHORT).show()
        }
    }

    private fun resumeRecording() {
        if (!paused || mediaRecorder == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.resume()
            } else {
                return
            }
            paused = false
            recPoint?.setColorFilter(context.getColor(R.color.sound_recorder_dialog_point_color))
            pauseButton?.setImageResource(R.drawable.ic_media_pause)
            pauseButton?.contentDescription = context.getString(R.string.sound_recorder_pause)
            titleView?.setText(R.string.sound_recorder_while)
            startElapsedTimer(startElapseSec = durationInSec + 1, delayMs = 1000, periodMs = 1000)
        } catch (_: Exception) {
            Toast.makeText(context, R.string.attachment_record_hint, Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePauseResume() {
        if (paused) resumeRecording() else pauseRecording()
    }

    private fun completeRecorder() {
        if (completed) return
        completed = true
        paused = false
        stopRecorder()
        cancelTimer()
        val path = soundPath
        val duration = durationInSec
        dismissDialog()
        if (!path.isNullOrEmpty()) {
            listener.onRecorderComplete(path, duration)
        }
        soundPath = null
        durationInSec = 0
    }

    private fun discardRecording() {
        stopRecorder()
        cancelTimer()
        mainHandler.removeMessages(MESSAGE_ERROR_TOAST)
        mainHandler.removeMessages(MESSAGE_UPDATE_SOUND_TIME)
        val path = soundPath
        soundPath = null
        durationInSec = 0
        if (!path.isNullOrEmpty()) {
            try {
                File(path).delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun checkSoundRecordSuccess() {
        if (TextUtils.isEmpty(soundPath)) return
        val file = File(soundPath!!)
        if (file.exists() && file.length() <= 0L && file.delete()) {
            cancelTimer()
            dismissDialog()
            Toast.makeText(context, R.string.attachment_record_permission_hint, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dismissDialog() {
        dialog?.dismiss()
        dialog = null
    }

    private fun stopRecorder() {
        val recorder = mediaRecorder ?: return
        mediaRecorder = null
        Thread {
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
            try {
                recorder.release()
            } catch (_: Exception) {
            }
        }.start()
    }
}
