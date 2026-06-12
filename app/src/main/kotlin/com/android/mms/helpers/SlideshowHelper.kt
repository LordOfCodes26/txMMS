package com.android.mms.helpers

import android.net.Uri
import com.android.mms.extensions.isImageMimeType
import com.android.mms.extensions.isVideoMimeType
import com.android.mms.models.AttachmentSelection
import com.android.mms.models.MmsSlide
import com.android.mms.models.MmsSlideshow
import com.goodwy.commons.activities.BaseSimpleActivity
import com.android.mms.extensions.copyToUri
import com.goodwy.commons.extensions.getFilenameFromUri
import com.goodwy.commons.extensions.getMyFileUri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

object SlideshowHelper {
    private val gson = Gson()

    fun isMediaSelection(selection: AttachmentSelection): Boolean =
        selection.mimetype.isImageMimeType() || selection.mimetype.isVideoMimeType()

    fun mediaSelections(selections: List<AttachmentSelection>): List<AttachmentSelection> =
        selections.filter { isMediaSelection(it) }

    fun toJson(slideshow: MmsSlideshow): String = gson.toJson(slideshow.slides)

    /** Compose / send: drop blank placeholder slides Alps leaves in the editor. */
    fun fromJson(json: String?): MmsSlideshow? = parseJson(json, dropEmpty = true)

    /** Slide list + slide editor: keep placeholder slides so indices stay aligned. */
    fun fromJsonForEditor(json: String?): MmsSlideshow? = parseJson(json, dropEmpty = false)

    private fun parseJson(json: String?, dropEmpty: Boolean): MmsSlideshow? {
        if (json.isNullOrBlank()) return null
        return try {
            val type = object : TypeToken<List<MmsSlide>>() {}.type
            val slides: List<MmsSlide> = gson.fromJson(json, type) ?: return null
            val kept = if (dropEmpty) {
                slides.filter { it.uriString.isNotEmpty() || it.text.isNotEmpty() }
            } else {
                slides
            }
            if (kept.isEmpty() && dropEmpty) null else MmsSlideshow(kept)
        } catch (_: Exception) {
            null
        }
    }

    /** Copy transient picker URIs into app cache so slides survive returning to compose. */
    fun stabilizeAttachmentUri(activity: BaseSimpleActivity, uri: Uri, mimeType: String): Uri {
        if (!"content".equals(uri.scheme, ignoreCase = true)) {
            return uri
        }
        return copyToAttachmentCache(activity, uri, mimeType) ?: uri
    }

    fun resolveMediaMimeType(activity: BaseSimpleActivity, uri: Uri): String? {
        activity.contentResolver.getType(uri)?.let { type ->
            if (type.isImageMimeType() || type.isVideoMimeType()) {
                return type
            }
        }
        inferMimeTypeFromFilename(activity.getFilenameFromUri(uri).lowercase())?.let { return it }
        return null
    }

    private fun inferMimeTypeFromFilename(filename: String): String? = when {
        filename.endsWith(".jpg") || filename.endsWith(".jpeg") -> "image/jpeg"
        filename.endsWith(".png") -> "image/png"
        filename.endsWith(".gif") -> "image/gif"
        filename.endsWith(".webp") -> "image/webp"
        filename.endsWith(".heic") -> "image/heic"
        filename.endsWith(".mp4") -> "video/mp4"
        filename.endsWith(".3gp") -> "video/3gpp"
        filename.endsWith(".webm") -> "video/webm"
        filename.endsWith(".mkv") -> "video/x-matroska"
        else -> null
    }

    private fun copyToAttachmentCache(activity: BaseSimpleActivity, uri: Uri, mimeType: String): Uri? {
        return try {
            val name = activity.getFilenameFromUri(uri).trim()
            val base = if (name.isNotEmpty()) name else "slide_${System.currentTimeMillis()}"
            val safeBase = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dir = File(activity.cacheDir, "attachments").apply { mkdirs() }
            val dest = if (safeBase.contains('.')) {
                File(dir, "${System.currentTimeMillis()}_$safeBase")
            } else {
                File(dir, "${System.currentTimeMillis()}_$safeBase${extensionForMimeType(mimeType)}")
            }
            val destUri = activity.getMyFileUri(dest)
            activity.copyToUri(uri, destUri)
            if (dest.length() > 0L) destUri else null
        } catch (_: Exception) {
            null
        }
    }

    private fun extensionForMimeType(mimeType: String): String = when {
        mimeType.startsWith("image/") -> when (mimeType) {
            "image/png" -> ".png"
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }
        mimeType.startsWith("video/") -> when (mimeType) {
            "video/3gpp" -> ".3gp"
            "video/webm" -> ".webm"
            else -> ".mp4"
        }
        else -> ""
    }

    fun durationSeconds(slide: MmsSlide): Int =
        ((slide.durationMs + 999L) / 1000L).toInt().coerceAtLeast(1)

    fun previewLabel(slide: MmsSlide): String {
        return when {
            slide.text.isNotBlank() -> slide.text
            slide.filename.isNotBlank() -> slide.filename
            slide.mimetype.isVideoMimeType() -> "video"
            slide.mimetype.isImageMimeType() -> "image"
            else -> ""
        }
    }
}
