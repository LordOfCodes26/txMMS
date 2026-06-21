package com.android.mms.adapters

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.*
import com.android.mms.R
import com.android.mms.activities.VCardViewerActivity
import com.android.mms.databinding.ItemAttachmentAudioPreviewBinding
import com.android.mms.databinding.ItemAttachmentDocumentPreviewBinding
import com.android.mms.databinding.ItemAttachmentMediaPreviewBinding
import com.android.mms.databinding.ItemAttachmentSlideshowPreviewBinding
import com.android.mms.databinding.ItemAttachmentVcardPreviewBinding
import com.android.mms.databinding.ItemAttachmentVcardStripBinding
import com.android.mms.models.MmsSlideshow
import com.android.mms.extensions.*
import com.android.mms.helpers.*
import com.android.mms.models.AttachmentSelection

class AttachmentsAdapter(
    val activity: BaseSimpleActivity,
    val recyclerView: RecyclerView,
    val onAttachmentsRemoved: () -> Unit,
    val onReady: (() -> Unit),
    val onReplaceAttachment: (AttachmentSelection) -> Unit,
    val getSlideshow: (() -> MmsSlideshow?)? = null,
    val onEditSlideshow: (() -> Unit)? = null,
    val onPlaySlideshow: (() -> Unit)? = null,
    val onRemoveSlideshow: (() -> Unit)? = null,
    val onSendSlideshow: (() -> Unit)? = null,
    /** Fired when compression completes after the row was promoted into a slideshow or removed. */
    val onCompressedMediaOrphaned: ((oldUri: android.net.Uri, newUri: android.net.Uri?) -> Unit)? = null,
    val onMediaAttachmentRemoved: ((AttachmentSelection) -> Unit)? = null,
) : ListAdapter<AttachmentSelection, AttachmentsAdapter.AttachmentsViewHolder>(AttachmentDiffCallback()) {

    private val config = activity.config
    private val resources = activity.resources
    private val primaryColor = activity.getBoarderPrimaryColor()
    private val imageCompressor by lazy { ImageCompressor(activity) }

    private var audioMediaPlayer: MediaPlayer? = null
    private var playingAudioUri: Uri? = null

    val attachments = mutableListOf<AttachmentSelection>()
    private var latestSubmitId = 0

    override fun getItemViewType(position: Int): Int {
        return resolveComposeViewType(getItem(position))
    }

    /**
     * Alps [WorkingMessage.hasSlideshow]: when the in-memory model has 2+ slides, always bind the
     * slideshow preview (play overlay + Edit) even if a stale media row is still in the list.
     */
    private fun resolveComposeViewType(attachment: AttachmentSelection): Int {
        if (shouldShowSlideshowPreview(attachment)) {
            return ATTACHMENT_SLIDESHOW
        }
        return attachment.viewType
    }

    private fun shouldShowSlideshowPreview(attachment: AttachmentSelection): Boolean {
        if (attachment.id == SLIDESHOW_ATTACHMENT_ID || attachment.viewType == ATTACHMENT_SLIDESHOW) {
            return true
        }
        val slideshow = getSlideshow?.invoke()
        if (slideshow?.isRealSlideshow() == true) {
            return true
        }
        val mediaInList = attachments.count { SlideshowHelper.isMediaSelection(it) }
        val mediaInModel = slideshow?.slides?.count { it.uriString.isNotEmpty() } ?: 0
        return maxOf(mediaInList, mediaInModel) > 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AttachmentsViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = when (viewType) {
            ATTACHMENT_SLIDESHOW -> ItemAttachmentSlideshowPreviewBinding.inflate(inflater, parent, false)
            ATTACHMENT_DOCUMENT -> ItemAttachmentDocumentPreviewBinding.inflate(inflater, parent, false)
            ATTACHMENT_VCARD -> ItemAttachmentVcardPreviewBinding.inflate(inflater, parent, false)
            ATTACHMENT_MEDIA -> ItemAttachmentMediaPreviewBinding.inflate(inflater, parent, false)
            ATTACHMENT_AUDIO -> ItemAttachmentAudioPreviewBinding.inflate(inflater, parent, false)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }

        return AttachmentsViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AttachmentsViewHolder, position: Int) {
        val attachment = getItem(position)
        val viewType = resolveComposeViewType(attachment)
        holder.bindView { binding, _ ->
            when (viewType) {
                ATTACHMENT_SLIDESHOW -> setupSlideshowPreview(
                    binding = binding as ItemAttachmentSlideshowPreviewBinding,
                )
                ATTACHMENT_DOCUMENT -> {
                    (binding as ItemAttachmentDocumentPreviewBinding).setupDocumentPreview(
                        uri = attachment.uri,
                        title = attachment.filename,
                        mimeType = attachment.mimetype,
                        onClick = { activity.launchViewIntent(attachment.uri, attachment.mimetype, attachment.filename) },
                        onRemoveButtonClicked = { removeAttachment(attachment) }
                    )
                }
                ATTACHMENT_VCARD -> setupVCardPreview(
                    binding = binding as ItemAttachmentVcardPreviewBinding,
                    attachment = attachment,
                )
                ATTACHMENT_MEDIA -> setupMediaPreview(
                    binding = binding as ItemAttachmentMediaPreviewBinding,
                    attachment = attachment
                )
                ATTACHMENT_AUDIO -> {
                    (binding as ItemAttachmentAudioPreviewBinding).apply {
                        bindEmbeddedVCardStrip(vcardStrip)
                        setupAudioPreview(
                            uri = attachment.uri,
                            title = attachment.filename,
                            isPlaying = playingAudioUri == attachment.uri,
                            maxSizeBytes = config.mmsFileSizeLimit,
                            onTogglePlay = { toggleAudioPlayback(attachment.uri) },
                            onRemoveButtonClicked = { removeAttachment(attachment) },
                        )
                    }
                }
            }
        }
    }

    fun clear() {
        stopAudio()
        attachments.clear()
        submitList(emptyList())
        onAttachmentsRemoved()
    }

    fun addAttachment(attachment: AttachmentSelection) {
        if (SlideshowHelper.isMediaSelection(attachment) && getSlideshow?.invoke()?.isRealSlideshow() == true) {
            return
        }
        attachments.removeAll { AttachmentSelection.areItemsTheSame(it, attachment) }
        attachments.add(attachment)
        publishAttachmentsList()
    }

    /**
     * Replaces all rows in one [submitList] call. Draft restore used to call [clear] then many
     * [addAttachment] calls; [androidx.recyclerview.widget.AsyncListDiffer] can apply those
     * [submitList] updates out of order so an empty list wins and previews never appear.
     */
    fun submitAttachments(newAttachments: List<AttachmentSelection>) {
        attachments.clear()
        attachments.addAll(reconcileComposeAttachments(newAttachments))
        publishAttachmentsList()
        if (attachments.isEmpty()) {
            onAttachmentsRemoved()
        }
    }

    /**
     * [AsyncListDiffer] can apply rapid [submitList] calls out of order (e.g. single-media row after
     * slideshow promotion). Reconcile against the live slideshow model so 2+ slides always show one
     * slideshow preview row.
     */
    private fun reconcileComposeAttachments(requested: List<AttachmentSelection>): List<AttachmentSelection> {
        val slideshow = getSlideshow?.invoke() ?: return requested
        val nonMedia = requested.filter {
            !SlideshowHelper.isMediaSelection(it) && !isSlideshowComposeRow(it)
        }
        if (!slideshow.isRealSlideshow()) {
            return requested
        }
        val slideshowRow = requested.firstOrNull { isSlideshowComposeRow(it) }
        if (slideshowRow != null) {
            return listOf(slideshowRow) + nonMedia
        }
        val first = slideshow.slides.firstOrNull { it.uriString.isNotEmpty() } ?: return requested
        return listOf(
            AttachmentSelection(
                id = SLIDESHOW_ATTACHMENT_ID,
                uri = first.uri,
                mimetype = "application/vnd.wap.mms-slideshow",
                filename = activity.getString(R.string.attachment),
                isPending = false,
                viewType = ATTACHMENT_SLIDESHOW,
            ),
        ) + nonMedia
    }

    private fun isSlideshowComposeRow(selection: AttachmentSelection): Boolean =
        selection.id == SLIDESHOW_ATTACHMENT_ID || selection.viewType == ATTACHMENT_SLIDESHOW

    private fun publishAttachmentsList() {
        val submitId = ++latestSubmitId
        // Alps AttachmentEditor shows only one view per message: if an image/video or slideshow
        // row is present, the audio is part of the same MMS slide and must not show as a second
        // row.  We still keep audio in `attachments` so it is included when sending.
        val snapshot = buildDisplayList()
        submitList(snapshot) {
            if (submitId != latestSubmitId) {
                submitList(buildDisplayList())
            }
        }
    }

    private fun buildDisplayList(): ArrayList<AttachmentSelection> {
        val hasMediaOrSlideshow = attachments.any {
            it.viewType == ATTACHMENT_MEDIA || it.viewType == ATTACHMENT_SLIDESHOW
        }
        val hasPrimary = hasPrimaryComposeAttachment()
        val hideEmbeddedVCard: (AttachmentSelection) -> Boolean = { selection ->
            selection.viewType == ATTACHMENT_VCARD && hasPrimary
        }
        return if (hasMediaOrSlideshow) {
            ArrayList(attachments.filter { it.viewType != ATTACHMENT_AUDIO && !hideEmbeddedVCard(it) })
        } else if (hasPrimary) {
            ArrayList(attachments.filter { !hideEmbeddedVCard(it) })
        } else {
            ArrayList(attachments)
        }
    }

    private fun hasPrimaryComposeAttachment(): Boolean =
        attachments.any {
            it.viewType == ATTACHMENT_MEDIA ||
                it.viewType == ATTACHMENT_SLIDESHOW ||
                it.viewType == ATTACHMENT_AUDIO
        }

    private fun getVCardAttachment(): AttachmentSelection? =
        attachments.firstOrNull { it.viewType == ATTACHMENT_VCARD }

    private fun openVCardViewer(attachment: AttachmentSelection) {
        val intent = Intent(activity, VCardViewerActivity::class.java).also {
            it.putExtra(EXTRA_VCARD_URI, attachment.uri)
        }
        activity.startActivity(intent)
    }

    private fun bindEmbeddedVCardStrip(strip: ItemAttachmentVcardStripBinding) {
        val vcard = getVCardAttachment()
        if (vcard == null) {
            strip.root.beGone()
            return
        }
        strip.setupVCardStrip(
            activity = activity,
            attachment = vcard,
            onViewClick = { openVCardViewer(vcard) },
            onRemoveClick = { removeAttachment(vcard) },
        )
    }

    private fun removeAttachment(attachment: AttachmentSelection) {
        if (attachment.viewType == ATTACHMENT_MEDIA) {
            onMediaAttachmentRemoved?.invoke(attachment)
        }
        if (playingAudioUri == attachment.uri) {
            stopAudio()
        }
        attachments.removeAll { AttachmentSelection.areItemsTheSame(it, attachment) }
        if (attachments.isEmpty()) {
            clear()
        } else {
            publishAttachmentsList()
        }
    }

    private fun toggleAudioPlayback(uri: Uri) {
        if (playingAudioUri == uri) {
            stopAudio()
        } else {
            stopAudio()
            playingAudioUri = uri
            try {
                audioMediaPlayer = MediaPlayer().apply {
                    setAudioStreamType(AudioManager.STREAM_MUSIC)
                    setDataSource(activity, uri)
                    prepare()
                    setOnCompletionListener {
                        activity.runOnUiThread {
                            stopAudio()
                            notifyAudioPlayStateChanged()
                        }
                    }
                    setOnErrorListener { _, _, _ ->
                        activity.runOnUiThread {
                            stopAudio()
                            notifyAudioPlayStateChanged()
                        }
                        true
                    }
                    start()
                }
            } catch (_: Exception) {
                stopAudio()
            }
        }
        notifyAudioPlayStateChanged()
    }

    fun stopAudio() {
        try {
            audioMediaPlayer?.stop()
            audioMediaPlayer?.release()
        } catch (_: Exception) {
        } finally {
            audioMediaPlayer = null
            playingAudioUri = null
        }
    }

    private fun notifyAudioPlayStateChanged() {
        for (i in 0 until itemCount) {
            if (getItem(i).viewType == ATTACHMENT_AUDIO) {
                notifyItemChanged(i)
            }
        }
    }

    fun replaceAttachment(oldId: String, newAttachment: AttachmentSelection): Boolean {
        val index = attachments.indexOfFirst { it.id == oldId }
        if (index < 0) {
            return false
        }
        attachments[index] = newAttachment
        publishAttachmentsList()
        return true
    }

    private fun setupVCardPreview(binding: ItemAttachmentVcardPreviewBinding, attachment: AttachmentSelection) {
        val previewWidth = vcardPreviewThumbnailWidth(binding)
        if (previewWidth <= 1 && recyclerView.width <= 0) {
            binding.root.post { setupVCardPreview(binding, attachment) }
            return
        }

        binding.setupVCardComposePreview(
            activity = activity,
            uri = attachment.uri,
            sizeInfoText = buildSizeInfoText(),
            previewWidth = previewWidth,
            onViewClick = {
                val intent = Intent(activity, VCardViewerActivity::class.java).also {
                    it.putExtra(EXTRA_VCARD_URI, attachment.uri)
                }
                activity.startActivity(intent)
            },
            onReplaceClick = { onReplaceAttachment(attachment) },
            onRemoveClick = { removeAttachment(attachment) },
        )
    }

    private fun vcardPreviewThumbnailWidth(binding: ItemAttachmentVcardPreviewBinding): Int {
        val actionButtonWidth = resources.getDimension(R.dimen.attachment_media_action_button_width).toInt()
        val itemInnerWidth = binding.root.width
            .takeIf { it > 0 }
            ?: (recyclerView.width - recyclerView.paddingLeft - recyclerView.paddingRight)
        return (itemInnerWidth - actionButtonWidth).coerceAtLeast(1)
    }

    private fun setupMediaPreview(binding: ItemAttachmentMediaPreviewBinding, attachment: AttachmentSelection) {
        binding.apply {
            bindEmbeddedVCardStrip(vcardStrip)

            // RecyclerView reuse: XML leaves compression_progress visible by default; a recycled row can
            // hide the thumbnail until Glide finishes unless we reset before branching.
            Glide.with(thumbnail).clear(thumbnail)
            thumbnail.setImageDrawable(null)
            compressionProgress.beGone()
            playIcon.beGone()

            threadAttachmentWrapper.background?.applyColorFilter(primaryColor.darkenColor())
            audioIndicator.beVisibleIf(attachments.any { it.viewType == ATTACHMENT_AUDIO })
            audioIndicator.setColorFilter(primaryColor)

            val sizeText = buildSizeInfoText()
            if (sizeText.isNotEmpty()) {
                mediaSizeInfo.text = sizeText
                mediaSizeInfo.beVisible()
            } else {
                mediaSizeInfo.beGone()
            }

            viewAttachmentButton.setOnClickListener {
                activity.launchViewIntent(attachment.uri, attachment.mimetype, attachment.filename)
            }
            replaceAttachmentButton.setOnClickListener {
                onReplaceAttachment(attachment)
            }
            removeAttachmentButton.setOnClickListener {
                removeAttachment(attachment)
            }

            val compressImage = attachment.mimetype.isImageMimeType() && !attachment.mimetype.isGifMimeType()
            if (compressImage && attachment.isPending && config.mmsFileSizeLimit != FILE_SIZE_NONE) {
                thumbnail.beGone()
                compressionProgress.beVisible()

                imageCompressor.compressImage(
                    attachment.uri,
                    config.mmsFileSizeLimit,
                    mimeType = attachment.mimetype,
                ) { compressedUri ->
                    activity.runOnUiThread {
                        handleCompressionResult(binding, attachment, compressedUri)
                    }
                }
            } else {
                if (attachment.isPending) {
                    attachments.find { it.uri == attachment.uri }?.isPending = false
                    onReady()
                }
                loadMediaPreview(this, attachment)
            }
        }
    }

    private fun handleCompressionResult(
        binding: ItemAttachmentMediaPreviewBinding,
        attachment: AttachmentSelection,
        compressedUri: android.net.Uri?,
    ) {
        val row = attachments.find { it.viewType == ATTACHMENT_MEDIA && it.uri == attachment.uri }
        if (row == null) {
            onCompressedMediaOrphaned?.invoke(attachment.uri, compressedUri)
            onReady()
            return
        }

        when (compressedUri) {
            attachment.uri -> {
                row.isPending = false
                loadMediaPreview(binding, row)
            }

            null -> {
                val inSlideshow = getSlideshow?.invoke()?.slides?.any { it.uriString == attachment.uri.toString() } == true
                if (inSlideshow) {
                    onCompressedMediaOrphaned?.invoke(attachment.uri, null)
                } else {
                    activity.toast(R.string.compress_error)
                    removeAttachment(row)
                }
            }

            else -> {
                val inSlideshow = getSlideshow?.invoke()?.slides?.any {
                    it.uriString == attachment.uri.toString()
                } == true
                if (inSlideshow) {
                    onCompressedMediaOrphaned?.invoke(attachment.uri, compressedUri)
                } else {
                    attachments.removeAll { it.viewType == ATTACHMENT_MEDIA && it.uri == attachment.uri }
                    addAttachment(row.copy(uri = compressedUri, isPending = false))
                }
            }
        }
        onReady()
    }

    private fun setupSlideshowPreview(binding: ItemAttachmentSlideshowPreviewBinding) {
        val slideshow = getSlideshow?.invoke() ?: return
        val firstSlide = slideshow.slides.firstOrNull { it.uriString.isNotEmpty() } ?: return

        val previewWidth = mediaPreviewThumbnailWidthForSlideshow(binding)
        if (previewWidth <= 1 && recyclerView.width <= 0) {
            binding.root.post { setupSlideshowPreview(binding) }
            return
        }

        binding.apply {
            bindEmbeddedVCardStrip(vcardStrip)
            Glide.with(slideshowImage).clear(slideshowImage)
            threadAttachmentWrapper.background?.applyColorFilter(primaryColor.darkenColor())
            audioIndicator.beVisibleIf(attachments.any { it.viewType == ATTACHMENT_AUDIO })
            audioIndicator.setColorFilter(primaryColor)

            val sizeText = buildSizeInfoText()
            if (sizeText.isNotEmpty()) {
                mediaSizeInfo.text = sizeText
                mediaSizeInfo.beVisible()
            } else {
                mediaSizeInfo.beGone()
            }
            slideshowText.text = firstSlide.text.ifBlank {
                activity.getString(R.string.slide_number, "1")
            }
            // setupSlideshowPreview is only reached when the slideshow view is active
            // (2+ media items present), so the Send button is always shown here.
            sendSlideshowButton.beVisible()
            sendSlideshowButton.setOnClickListener { onSendSlideshow?.invoke() }
            editSlideshowButton.setOnClickListener { onEditSlideshow?.invoke() }
            playSlideshowButton.beVisible()
            playSlideshowButton.bringToFront()
            playSlideshowButton.setOnClickListener { onPlaySlideshow?.invoke() }
            slideshowPreviewHolder.setOnClickListener { onPlaySlideshow?.invoke() }
            removeSlideshowButton.setOnClickListener { onRemoveSlideshow?.invoke() }

            loadSlideshowPreviewImage(
                uri = firstSlide.uri,
                mimeType = firstSlide.mimetype,
                imageView = slideshowImage,
                previewWidth = previewWidth,
            )
        }
    }

    private fun mediaPreviewThumbnailWidthForSlideshow(binding: ItemAttachmentSlideshowPreviewBinding): Int {
        val actionButtonWidth = resources.getDimension(R.dimen.attachment_media_action_button_width).toInt()
        val itemInnerWidth = binding.slideshowAttachmentRow.width
            .takeIf { it > 0 }
            ?: binding.root.width
                .takeIf { it > 0 }
            ?: (recyclerView.width - recyclerView.paddingLeft - recyclerView.paddingRight)
        return (itemInnerWidth - actionButtonWidth).coerceAtLeast(1)
    }

    private fun isMediaRowStillBound(attachment: AttachmentSelection): Boolean =
        attachments.any { it.viewType == ATTACHMENT_MEDIA && it.uri == attachment.uri }

    private fun loadMediaPreview(binding: ItemAttachmentMediaPreviewBinding, attachment: AttachmentSelection) {
        if (!isMediaRowStillBound(attachment)) {
            return
        }

        val previewWidth = mediaPreviewThumbnailWidth(binding)
        if (previewWidth <= 1 && recyclerView.width <= 0) {
            binding.root.post { loadMediaPreview(binding, attachment) }
            return
        }

        binding.thumbnail.tag = attachment.uri
        binding.compressionProgress.beGone()
        loadSlidePreviewImage(
            uri = attachment.uri,
            mimeType = attachment.mimetype,
            imageView = binding.thumbnail,
            previewWidth = previewWidth,
            showPlayIcon = attachment.mimetype.isVideoMimeType(),
            playIcon = binding.playIcon,
            onStale = { binding.thumbnail.tag != attachment.uri || !isMediaRowStillBound(attachment) },
            onHardFailure = {
                if (isMediaRowStillBound(attachment)) {
                    binding.thumbnail.setImageDrawable(null)
                    binding.thumbnail.beVisible()
                    binding.playIcon.beVisibleIf(attachment.mimetype.isVideoMimeType())
                }
            },
        )
    }

    private fun loadSlidePreviewImage(
        uri: android.net.Uri,
        mimeType: String,
        imageView: android.widget.ImageView,
        previewWidth: Int,
        showPlayIcon: Boolean,
        playIcon: android.view.View,
        onStale: (() -> Boolean)? = null,
        onHardFailure: (() -> Unit)? = null,
    ) {
        val height = resources.getDimension(R.dimen.attachment_media_preview_height).toInt()
        val roundedCornersRadius = resources.getDimension(com.goodwy.commons.R.dimen.activity_margin).toInt()
        val options = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .transform(CenterCrop(), RoundedCorners(roundedCornersRadius))

        Glide.with(imageView)
            .load(uri)
            .transition(DrawableTransitionOptions.withCrossFade())
            .override(previewWidth.coerceAtLeast(1), height)
            .apply(options)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    if (onStale?.invoke() == true) {
                        return true
                    }
                    if (mimeType.isVideoMimeType()) {
                        imageView.setImageDrawable(null)
                        imageView.beVisible()
                        playIcon.beVisible()
                        return true
                    }
                    if (decodePreviewBitmap(uri, mimeType)?.let { bitmap ->
                            imageView.setImageDrawable(BitmapDrawable(resources, bitmap))
                            imageView.beVisible()
                            playIcon.beVisibleIf(showPlayIcon)
                            true
                        } == true) {
                        return true
                    }
                    onHardFailure?.invoke()
                    return onHardFailure == null
                }

                override fun onResourceReady(
                    dr: Drawable,
                    a: Any,
                    t: Target<Drawable>,
                    d: DataSource,
                    i: Boolean,
                ): Boolean {
                    if (onStale?.invoke() == true) {
                        return true
                    }
                    imageView.beVisible()
                    playIcon.beVisibleIf(showPlayIcon)
                    return false
                }
            })
            .into(imageView)
    }

    /** Slideshow preview: load thumbnail only; play overlay stays visible (Alps [AttachmentEditor]). */
    private fun loadSlideshowPreviewImage(
        uri: android.net.Uri,
        mimeType: String,
        imageView: android.widget.ImageView,
        previewWidth: Int,
    ) {
        val height = resources.getDimension(R.dimen.attachment_media_preview_height).toInt()
        val roundedCornersRadius = resources.getDimension(com.goodwy.commons.R.dimen.activity_margin).toInt()
        val options = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .transform(CenterCrop(), RoundedCorners(roundedCornersRadius))

        Glide.with(imageView)
            .load(uri)
            .transition(DrawableTransitionOptions.withCrossFade())
            .override(previewWidth.coerceAtLeast(1), height)
            .apply(options)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    if (mimeType.isVideoMimeType()) {
                        imageView.setImageDrawable(null)
                        imageView.beVisible()
                        return true
                    }
                    decodePreviewBitmap(uri, mimeType)?.let { bitmap ->
                        imageView.setImageDrawable(BitmapDrawable(resources, bitmap))
                        imageView.beVisible()
                    }
                    return true
                }

                override fun onResourceReady(
                    dr: Drawable,
                    a: Any,
                    t: Target<Drawable>,
                    d: DataSource,
                    i: Boolean,
                ): Boolean {
                    imageView.beVisible()
                    return false
                }
            })
            .into(imageView)
    }

    private fun decodePreviewBitmap(uri: android.net.Uri, mimeType: String): Bitmap? {
        if (!mimeType.isImageMimeType() && !mimeType.isGifMimeType()) {
            return null
        }
        return try {
            activity.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun mediaPreviewThumbnailWidth(binding: ItemAttachmentMediaPreviewBinding): Int {
        val actionButtonWidth = resources.getDimension(R.dimen.attachment_media_action_button_width).toInt()
        val itemInnerWidth = binding.mediaAttachmentRow.width
            .takeIf { it > 0 }
            ?: binding.root.width
                .takeIf { it > 0 }
            ?: (recyclerView.width - recyclerView.paddingLeft - recyclerView.paddingRight)
        return (itemInnerWidth - actionButtonWidth).coerceAtLeast(1)
    }

    /**
     * Alps AttachmentEditor format: "{ceilKB}K/{maxKB}K" (e.g. "45K/300K").
     * Sums all attachment sizes so audio-only and combined media show accurate totals.
     */
    private fun buildSizeInfoText(): String {
        val totalBytes = attachments.sumOf { sel ->
            val size = activity.getFileSizeFromUri(sel.uri)
            if (size > 0L) size else 0L
        }
        if (totalBytes <= 0L) return ""
        val ceilKb = ((totalBytes - 1L) / 1024L + 1L).toInt()
        val limitBytes = config.mmsFileSizeLimit
        return if (limitBytes == FILE_SIZE_NONE || limitBytes <= 0L) {
            "${ceilKb}K"
        } else {
            val maxKb = (limitBytes / 1024L).toInt()
            "${ceilKb}K/${maxKb}K"
        }
    }

    inner class AttachmentsViewHolder(val binding: ViewBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bindView(callback: (binding: ViewBinding, adapterPosition: Int) -> Unit) {
            callback(binding, adapterPosition)
        }
    }
}

private class AttachmentDiffCallback : DiffUtil.ItemCallback<AttachmentSelection>() {
    override fun areItemsTheSame(oldItem: AttachmentSelection, newItem: AttachmentSelection): Boolean {
        return AttachmentSelection.areItemsTheSame(oldItem, newItem)
    }

    override fun areContentsTheSame(oldItem: AttachmentSelection, newItem: AttachmentSelection): Boolean {
        return AttachmentSelection.areContentsTheSame(oldItem, newItem)
    }
}
