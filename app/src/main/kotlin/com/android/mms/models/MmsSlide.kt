package com.android.mms.models

import android.net.Uri
import androidx.core.net.toUri
import com.android.mms.extensions.isImageMimeType
import com.android.mms.extensions.isVideoMimeType

/**
 * One slide in an MMS slideshow, matching Alps [com.android.mms.model.SlideModel] fields used in compose.
 */
data class MmsSlide(
    val uriString: String,
    val mimetype: String,
    val filename: String,
    val text: String = "",
    val durationMs: Long = DEFAULT_DURATION_MS,
) {
    val uri: Uri get() = uriString.toUri()

    fun isMediaMimeType(): Boolean = mimetype.isImageMimeType() || mimetype.isVideoMimeType()

    companion object {
        const val DEFAULT_DURATION_MS = 5000L

        fun fromSelection(selection: AttachmentSelection): MmsSlide {
            return MmsSlide(
                uriString = selection.uri.toString(),
                mimetype = selection.mimetype,
                filename = selection.filename,
            )
        }

        fun empty(): MmsSlide = MmsSlide(uriString = "", mimetype = "text/plain", filename = "")
    }
}
