package com.android.mms.helpers

import android.app.Activity
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.android.mms.R
import com.android.mms.databinding.ItemAttachmentAudioPreviewBinding
import com.android.mms.databinding.ItemAttachmentDocumentBinding
import com.android.mms.databinding.ItemAttachmentDocumentPreviewBinding
import com.android.mms.databinding.ItemAttachmentVcardBinding
import com.android.mms.databinding.ItemAttachmentVcardPreviewBinding
import com.android.mms.databinding.ItemAttachmentVcardStripBinding
import com.android.mms.extensions.*
import com.android.mms.models.AttachmentSelection
import com.android.mms.models.VCardPropertyWrapper
import ezvcard.property.Organization
import kotlin.math.abs

fun ItemAttachmentDocumentPreviewBinding.setupDocumentPreview(
    uri: Uri,
    title: String,
    mimeType: String,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onRemoveButtonClicked: (() -> Unit)? = null
) {
    documentAttachmentHolder.setupDocumentPreview(uri, title, mimeType, onClick, onLongClick)
    removeAttachmentButtonHolder.removeAttachmentButton.apply {
        beVisible()
        background.applyColorFilter(context.getBoarderPrimaryColor())
        if (onRemoveButtonClicked != null) {
            setOnClickListener {
                onRemoveButtonClicked.invoke()
            }
        }
    }
}

fun ItemAttachmentDocumentBinding.setupDocumentPreview(
    uri: Uri,
    title: String,
    mimeType: String,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val context = root.context
    if (title.isNotEmpty()) {
        filename.text = title
    }

    ensureBackgroundThread {
        try {
            val size = context.getFileSizeFromUri(uri)
            root.post {
                fileSize.beVisible()
                fileSize.text = size.formatSize()
            }
        } catch (e: Exception) {
            root.post {
                fileSize.beGone()
            }
        }
    }

    val textColor = context.getProperTextColor()
    val primaryColor = context.getBoarderPrimaryColor()

    filename.setTextColor(textColor)
    fileSize.setTextColor(textColor)

    icon.setImageResource(getIconResourceForMimeType(mimeType))
    icon.background.setTint(primaryColor)
    root.background.applyColorFilter(primaryColor.darkenColor())

    root.setOnClickListener {
        onClick?.invoke()
    }

    root.setOnLongClickListener {
        onLongClick?.invoke()
        true
    }
}

fun ItemAttachmentVcardStripBinding.setupVCardStrip(
    activity: Activity,
    attachment: AttachmentSelection,
    onViewClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    root.beVisible()
    vcardStripTitle.text = attachment.filename
    vcardStripSubtitle.beGone()
    vcardStripPhoto.setImageDrawable(null)
    vcardStripRemove.setOnClickListener { onRemoveClick() }
    root.setOnClickListener { onViewClick() }

    parseVCardFromUri(activity, attachment.uri) { vCards ->
        activity.runOnUiThread {
            if (vCards.isEmpty()) {
                return@runOnUiThread
            }

            val photo = vCards.firstOrNull()?.photos?.firstOrNull()
            val title = vCards.firstOrNull()?.parseNameFromVCard()
            val isCompany = vCards.firstOrNull()?.isCompanyVCard(title ?: "") ?: false
            val imageIcon = if (isCompany) {
                SimpleContactsHelper(activity).getColoredCompanyIcon(title ?: "")
            } else if (title != null) {
                SimpleContactsHelper(activity).getContactLetterIcon(title).toDrawable(activity.resources)
            } else {
                null
            }

            val roundingRadius = activity.resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.normal_margin)
            val options = RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .placeholder(imageIcon)
                .transform(CenterCrop(), RoundedCorners(roundingRadius))

            Glide.with(activity)
                .load(photo?.data ?: photo?.url)
                .apply(options)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(vcardStripPhoto)

            if (!attachment.filename.isBlank()) {
                vcardStripTitle.text = attachment.filename
            } else if (!title.isNullOrBlank()) {
                vcardStripTitle.text = activity.getString(R.string.file_attachment_vcard_name, title)
            }

            if (vCards.size > 1) {
                val quantity = vCards.size - 1
                vcardStripSubtitle.text = activity.resources.getQuantityString(
                    R.plurals.and_other_contacts,
                    quantity,
                    quantity,
                )
                vcardStripSubtitle.beVisible()
            }
        }
    }
}

fun ItemAttachmentVcardPreviewBinding.setupVCardComposePreview(
    activity: Activity,
    uri: Uri,
    sizeInfoText: String,
    previewWidth: Int,
    onViewClick: () -> Unit,
    onReplaceClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    val primaryColor = activity.getBoarderPrimaryColor()
    threadAttachmentWrapper.background?.applyColorFilter(primaryColor.darkenColor())

    if (sizeInfoText.isNotEmpty()) {
        mediaSizeInfo.text = sizeInfoText
        mediaSizeInfo.beVisible()
    } else {
        mediaSizeInfo.beGone()
    }

    viewAttachmentButton.setOnClickListener { onViewClick() }
    replaceAttachmentButton.setOnClickListener { onReplaceClick() }
    removeAttachmentButton.setOnClickListener { onRemoveClick() }

    vcardProgress.beVisible()
    thumbnail.setImageDrawable(null)
    vcardTitle.beGone()
    vcardSubtitle.beGone()

    parseVCardFromUri(activity, uri) { vCards ->
        activity.runOnUiThread {
            vcardProgress.beGone()
            if (vCards.isEmpty()) {
                vcardTitle.beVisible()
                vcardTitle.text = activity.getString(com.goodwy.commons.R.string.unknown_error_occurred)
                return@runOnUiThread
            }

            val photo = vCards.firstOrNull()?.photos?.firstOrNull()
            val title = vCards.firstOrNull()?.parseNameFromVCard()
            val isCompany = vCards.firstOrNull()?.isCompanyVCard(title ?: "") ?: false

            val imageIcon = if (isCompany) {
                SimpleContactsHelper(activity).getColoredCompanyIcon(title ?: "")
            } else if (title != null) {
                SimpleContactsHelper(activity).getContactLetterIcon(title).toDrawable(activity.resources)
            } else {
                null
            }

            val height = activity.resources.getDimension(R.dimen.attachment_media_preview_height).toInt()
            val roundedCornersRadius = activity.resources.getDimension(com.goodwy.commons.R.dimen.activity_margin).toInt()
            val options = RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .placeholder(imageIcon)
                .transform(CenterCrop(), RoundedCorners(roundedCornersRadius))

            Glide.with(activity)
                .load(photo?.data ?: photo?.url)
                .apply(options)
                .override(previewWidth.coerceAtLeast(1), height)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(thumbnail)

            if (!title.isNullOrBlank()) {
                vcardTitle.text = title
                vcardTitle.beVisible()
            }

            if (vCards.size > 1) {
                val quantity = vCards.size - 1
                vcardSubtitle.text = activity.resources.getQuantityString(
                    R.plurals.and_other_contacts,
                    quantity,
                    quantity,
                )
                vcardSubtitle.beVisible()
            }

            vcardAttachmentHolder.setOnClickListener { onViewClick() }
        }
    }
}

fun ItemAttachmentVcardBinding.setupVCardPreview(
    activity: Activity,
    uri: Uri,
    attachment: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onVCardLoaded: (() -> Unit)? = null,
    onViewContactDetailsClick: (() -> Unit)? = null,
) {
    val context = root.context
    val textColor = activity.getProperTextColor()
    val primaryColor = activity.getBoarderPrimaryColor()

    root.background.applyColorFilter(primaryColor.darkenColor())
    vcardTitle.setTextColor(textColor)
    vcardSubtitle.setTextColor(textColor)

    arrayOf(vcardPhoto, vcardTitle, vcardSubtitle, viewContactDetails).forEach {
        it.beGone()
    }

    parseVCardFromUri(activity, uri) { vCards ->
        activity.runOnUiThread {
            if (vCards.isEmpty()) {
                vcardTitle.beVisible()
                vcardTitle.text = context.getString(com.goodwy.commons.R.string.unknown_error_occurred)
                return@runOnUiThread
            }

            val photo = vCards.firstOrNull()?.photos?.firstOrNull()
            val title = vCards.firstOrNull()?.parseNameFromVCard()
            val isCompany = vCards.firstOrNull()?.isCompanyVCard(title ?: "") ?: false

            val imageIcon = if (isCompany) {
                SimpleContactsHelper(activity).getColoredCompanyIcon(title ?: "")
            } else if (title != null) {
                SimpleContactsHelper(activity).getContactLetterIcon(title).toDrawable(activity.resources)
            } else {
                null
            }

            val roundingRadius = activity.resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.big_margin)
            val transformation = RoundedCorners(roundingRadius)
            val options = RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .placeholder(imageIcon)
                .transform(transformation)
            Glide.with(activity)
                .load(photo?.data ?: photo?.url)
                .apply(options)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(vcardPhoto)

            arrayOf(vcardPhoto, vcardTitle).forEach {
                it.beVisible()
            }

//            vcardPhoto.setImageBitmap(imageIcon)
            vcardTitle.text = title

            if (vCards.size > 1) {
                vcardSubtitle.beVisible()
                val quantity = vCards.size - 1
                vcardSubtitle.text = context.resources.getQuantityString(R.plurals.and_other_contacts, quantity, quantity)
            } else {
                vcardSubtitle.beGone()
            }

            if (attachment) {
                onVCardLoaded?.invoke()
                viewContactDetails.setOnClickListener(null)
                viewContactDetails.isClickable = false
            } else {
                viewContactDetails.setTextColor(primaryColor)
                viewContactDetails.beVisible()
                if (onViewContactDetailsClick != null) {
                    viewContactDetails.isClickable = true
                    viewContactDetails.setOnClickListener { onViewContactDetailsClick() }
                } else {
                    viewContactDetails.isClickable = false
                    viewContactDetails.setOnClickListener(null)
                }
            }

            vcardAttachmentHolder.setOnClickListener {
                onClick?.invoke()
            }
            vcardAttachmentHolder.setOnLongClickListener {
                onLongClick?.invoke()
                true
            }
        }
    }
}

fun ItemAttachmentAudioPreviewBinding.setupAudioPreview(
    uri: Uri,
    title: String,
    isPlaying: Boolean,
    maxSizeBytes: Long = FILE_SIZE_NONE,
    onTogglePlay: () -> Unit,
    onRemoveButtonClicked: (() -> Unit)? = null,
) {
    val context = root.context
    val primaryColor = context.getBoarderPrimaryColor()
    val textColor = context.getProperTextColor()

    threadAttachmentWrapper.background?.applyColorFilter(primaryColor.darkenColor())
    audioIcon.setColorFilter(primaryColor)
    filename.setTextColor(textColor)
    fileSize.setTextColor(textColor)

    if (title.isNotEmpty()) {
        filename.text = title
    }

    updateAudioPlayStopIcon(isPlaying)
    viewAttachmentButton.setText(if (isPlaying) R.string.pause else R.string.play)

    val togglePlay = { onTogglePlay() }
    audioContentHolder.setOnClickListener { togglePlay() }
    playStopIcon.setOnClickListener { togglePlay() }
    viewAttachmentButton.setOnClickListener { togglePlay() }

    if (onRemoveButtonClicked != null) {
        removeAttachmentButton.setOnClickListener { onRemoveButtonClicked() }
    }

    ensureBackgroundThread {
        try {
            val bytes = context.getFileSizeFromUri(uri)
            root.post {
                if (bytes > 0L) {
                    val ceilKb = ((bytes - 1L) / 1024L + 1L).toInt()
                    fileSize.text = if (maxSizeBytes == FILE_SIZE_NONE || maxSizeBytes <= 0L) {
                        "${ceilKb}K"
                    } else {
                        val maxKb = (maxSizeBytes / 1024L).toInt()
                        "${ceilKb}K/${maxKb}K"
                    }
                    fileSize.beVisible()
                } else {
                    fileSize.beGone()
                }
            }
        } catch (_: Exception) {
            root.post { fileSize.beGone() }
        }
    }
}

fun ItemAttachmentAudioPreviewBinding.updateAudioPlayStopIcon(isPlaying: Boolean) {
    playStopIcon.setImageResource(
        if (isPlaying) R.drawable.ic_stop_vector else R.drawable.ic_vector_play_circle_outline,
    )
}

private fun getIconResourceForMimeType(mimeType: String) = when {
    mimeType.isAudioMimeType() -> R.drawable.ic_vector_audio_file
    mimeType.isCalendarMimeType() -> R.drawable.ic_calendar_month_vector
    mimeType.isPdfMimeType() -> R.drawable.ic_vector_pdf
    mimeType.isZipMimeType() -> R.drawable.ic_vector_folder_zip
    else -> R.drawable.ic_document_vector
}
