package com.android.mms.helpers

import android.content.Context
import androidx.core.net.toUri
import com.android.mms.extensions.deleteSmsDraft
import com.android.mms.extensions.getAddresses
import com.android.mms.extensions.getThreadParticipants
import com.android.mms.messaging.sendMessageCompat
import com.android.mms.models.Attachment
import com.android.mms.models.DraftStoredAttachment
import com.goodwy.commons.extensions.getFilenameFromUri
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Sends a pending countdown when [ThreadActivity] is no longer alive to handle it in-process. */
object PendingSendCountdownFinisher {
    fun finish(context: Context, pending: PendingSendCountdown) {
        ensureBackgroundThread {
            val addresses = context.getThreadParticipants(pending.threadId, null).getAddresses()
            if (addresses.isEmpty()) {
                return@ensureBackgroundThread
            }
            val attachments = parseAttachments(context, pending.attachmentsJson)
            context.sendMessageCompat(
                text = pending.messageText,
                addresses = addresses,
                subId = pending.subscriptionId,
                attachments = attachments,
                showDeliveredToastOnSuccess = addresses.size == 1,
            )
            context.deleteSmsDraft(pending.threadId)
        }
    }

    private fun parseAttachments(context: Context, json: String?): List<Attachment> {
        if (json.isNullOrBlank()) {
            return emptyList()
        }
        return try {
            val type = object : TypeToken<List<DraftStoredAttachment>>() {}.type
            val stored: List<DraftStoredAttachment> = Gson().fromJson(json, type) ?: emptyList()
            stored
                .filter { it.slideshowJson.isNullOrBlank() && it.uriString.isNotBlank() }
                .map { entry ->
                    val uri = entry.uriString.toUri()
                    val mimeType = entry.mimetype.ifBlank {
                        context.contentResolver.getType(uri).orEmpty()
                    }.ifBlank { "application/octet-stream" }
                    val ext = mimeType.substringAfter("/").substringBefore(";").trim().ifBlank { "dat" }
                    Attachment(
                        id = null,
                        messageId = -1L,
                        uriString = uri.toString(),
                        mimetype = mimeType,
                        width = 0,
                        height = 0,
                        filename = entry.filename
                            .ifBlank { context.getFilenameFromUri(uri) }
                            .ifBlank { "attachment_${System.currentTimeMillis()}.$ext" },
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
