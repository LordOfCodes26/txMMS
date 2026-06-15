package com.android.mms.helpers

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.media.CamcorderProfile
import android.net.Uri
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.android.mms.R
import com.android.mms.extensions.config
import com.android.mms.extensions.getFileSizeFromUri
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.getMyFileUri
import com.goodwy.commons.extensions.hideKeyboard
import com.goodwy.commons.extensions.showErrorToast
import com.goodwy.commons.extensions.toast
import java.io.File

/**
 * Launches attachment pickers using the same flows as Alps MMS [ComposeMessageActivity.addAttachment].
 */
class AttachmentIntentLauncher(
    private val activity: BaseSimpleActivity,
    private val messageHolderHelper: MessageHolderHelper,
) {
    companion object {
        private const val MMS_ATTACHMENT_SLOP_BYTES = 1024L
        private const val MIN_SIZE_FOR_CAPTURE_VIDEO = 10 * 1024L
    }

    fun launchSelectImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launchActivityForResult(Intent.createChooser(intent, null), MessageHolderHelper.PICK_PHOTO_INTENT)
    }

    fun launchSelectVideo() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "video/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launchActivityForResult(Intent.createChooser(intent, null), MessageHolderHelper.PICK_VIDEO_INTENT)
    }

    fun launchCapturePhoto() {
        // Alps MMS MessageUtils.capturePicture: EXTRA_OUTPUT = TempFileProvider.SCRAP_CONTENT_URI
        MmsCaptureTempFiles.clearScrapPhoto(activity)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, MmsCaptureTempFiles.scrapPhotoUri())
        }
        launchActivityForResult(intent, MessageHolderHelper.CAPTURE_PHOTO_INTENT)
    }

    fun launchCaptureVideo() {
        val sizeLimit = computeRemainingAttachmentBytes()
        if (sizeLimit <= MIN_SIZE_FOR_CAPTURE_VIDEO) {
            activity.toast(R.string.space_not_enough, length = Toast.LENGTH_SHORT)
            return
        }

        messageHolderHelper.setCapturedVideoUri(null)
        MmsCaptureTempFiles.clearScrapVideo(activity)

        val scrapVideoUri = MmsCaptureTempFiles.scrapVideoUri()
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0)
            if (sizeLimit < Long.MAX_VALUE) {
                putExtra("android.intent.extra.sizeLimit", sizeLimit.coerceAtMost(Int.MAX_VALUE.toLong()))
            }
            val durationLimit = getVideoCaptureDurationLimit()
            if (durationLimit > 0) {
                putExtra("android.intent.extra.durationLimit", durationLimit)
            }
            putExtra(MediaStore.EXTRA_OUTPUT, scrapVideoUri)
            putExtra("CanShare", false)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(activity.contentResolver, "video", scrapVideoUri)
        }
        launchActivityForResult(intent, MessageHolderHelper.CAPTURE_VIDEO_INTENT)
    }

    fun showPickAudioDialog() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.add_music)
            .setItems(
                arrayOf(
                    activity.getString(R.string.attach_ringtone),
                    activity.getString(R.string.attach_sound),
                ),
            ) { _, which ->
                when (which) {
                    0 -> launchSelectRingtone()
                    1 -> launchSelectAudio()
                }
            }
            .show()
    }

    fun launchSelectAudio() {
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            activity.toast(R.string.insert_sdcard, length = Toast.LENGTH_LONG)
            return
        }
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("audio/*", "audio/ogg", "application/ogg", "application/x-ogg"),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launchActivityForResult(intent, MessageHolderHelper.PICK_SOUND_INTENT)
    }

    fun launchSelectRingtone() {
        val intent = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_INCLUDE_DRM, false)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, activity.getString(R.string.select_audio))
            putExtra(
                android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                android.media.RingtoneManager.TYPE_RINGTONE,
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launchActivityForResult(intent, MessageHolderHelper.PICK_RINGTONE_INTENT)
    }

    fun launchPickDocument() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("*/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launchActivityForResult(intent, MessageHolderHelper.PICK_DOCUMENT_INTENT)
    }

    fun launchPickContactForReplace(attachmentId: String) {
        messageHolderHelper.setPendingReplaceAttachmentId(attachmentId)
        launchPickContact()
    }

    fun launchPickContact() {
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
        }
        launchActivityForResult(intent, MessageHolderHelper.PICK_CONTACT_INTENT)
    }

    fun showReplaceImageDialog(attachmentId: String) {
        messageHolderHelper.setPendingReplaceAttachmentId(attachmentId)
        AlertDialog.Builder(activity)
            .setItems(
                arrayOf(
                    activity.getString(com.goodwy.commons.R.string.choose_photo),
                    activity.getString(com.goodwy.commons.R.string.take_photo),
                ),
            ) { _, which ->
                when (which) {
                    0 -> launchSelectImage()
                    1 -> launchCapturePhoto()
                }
            }
            .setOnCancelListener { messageHolderHelper.clearPendingReplaceAttachmentId() }
            .show()
    }

    fun showReplaceVideoDialog(attachmentId: String) {
        messageHolderHelper.setPendingReplaceAttachmentId(attachmentId)
        AlertDialog.Builder(activity)
            .setItems(
                arrayOf(
                    activity.getString(com.goodwy.commons.R.string.choose_video),
                    activity.getString(com.goodwy.commons.R.string.record_video),
                ),
            ) { _, which ->
                when (which) {
                    0 -> launchSelectVideo()
                    1 -> launchCaptureVideo()
                }
            }
            .setOnCancelListener { messageHolderHelper.clearPendingReplaceAttachmentId() }
            .show()
    }

    private fun computeRemainingAttachmentBytes(): Long {
        val limit = activity.config.mmsFileSizeLimit
        if (limit == FILE_SIZE_NONE) {
            return Long.MAX_VALUE
        }

        var remaining = limit - MMS_ATTACHMENT_SLOP_BYTES
        val text = messageHolderHelper.getMessageText()
        if (text.isNotEmpty()) {
            remaining -= text.toByteArray().size
        }
        for (selection in messageHolderHelper.getAttachmentSelections()) {
            val size = activity.getFileSizeFromUri(selection.uri)
            if (size > 0L) {
                remaining -= size
            }
        }
        return remaining
    }

    private fun getVideoCaptureDurationLimit(): Int {
        return try {
            CamcorderProfile.get(CamcorderProfile.QUALITY_LOW)?.duration ?: 0
        } catch (_: RuntimeException) {
            0
        }
    }

    private fun getAttachmentsDir(): File {
        return File(activity.cacheDir, "attachments").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    private fun launchActivityForResult(
        intent: Intent,
        requestCode: Int,
        @StringRes error: Int = com.goodwy.commons.R.string.no_app_found,
    ) {
        activity.hideKeyboard()
        try {
            activity.startActivityForResult(intent, requestCode)
        } catch (_: ActivityNotFoundException) {
            activity.showErrorToast(activity.getString(error))
        } catch (e: Exception) {
            activity.showErrorToast(e)
        }
    }
}
