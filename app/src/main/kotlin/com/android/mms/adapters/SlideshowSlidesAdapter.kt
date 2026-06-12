package com.android.mms.adapters



import android.view.LayoutInflater

import android.view.ViewGroup

import androidx.recyclerview.widget.RecyclerView

import com.bumptech.glide.Glide

import com.bumptech.glide.load.resource.bitmap.CenterCrop

import com.bumptech.glide.load.resource.bitmap.RoundedCorners

import com.goodwy.commons.activities.BaseSimpleActivity

import com.goodwy.commons.extensions.beGone

import com.goodwy.commons.extensions.beVisible

import com.android.mms.R

import com.android.mms.databinding.ItemSlideshowSlideBinding

import com.android.mms.extensions.isAudioMimeType

import com.android.mms.extensions.isVideoMimeType

import com.android.mms.helpers.SlideshowHelper

import com.android.mms.models.MmsSlide

import com.android.mms.models.MmsSlideshow



class SlideshowSlidesAdapter(

    private val activity: BaseSimpleActivity,

    private val onSlideClicked: (Int) -> Unit,

    private val onAddSlideClicked: () -> Unit,

    private val onSlideLongClicked: ((Int, android.view.View) -> Unit)? = null,

) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {



    private var slideshow = MmsSlideshow(emptyList())

    private var showAddFooter = true



    fun submitSlideshow(newSlideshow: MmsSlideshow, canAddMore: Boolean) {

        slideshow = newSlideshow

        showAddFooter = canAddMore

        notifyDataSetChanged()

    }



    fun getSlideshow(): MmsSlideshow = slideshow



    override fun getItemViewType(position: Int): Int {

        return if (position < slideshow.size) VIEW_TYPE_SLIDE else VIEW_TYPE_ADD

    }



    override fun getItemCount(): Int = slideshow.size + if (showAddFooter) 1 else 0



    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {

        val binding = ItemSlideshowSlideBinding.inflate(LayoutInflater.from(parent.context), parent, false)

        return when (viewType) {

            VIEW_TYPE_SLIDE -> SlideViewHolder(binding)

            else -> AddSlideViewHolder(binding)

        }

    }



    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {

        when (holder) {

            is SlideViewHolder -> holder.bind(slideshow.slideAt(position)!!, position)

            is AddSlideViewHolder -> holder.bind()

        }

    }



    inner class SlideViewHolder(private val binding: ItemSlideshowSlideBinding) :

        RecyclerView.ViewHolder(binding.root) {



        fun bind(slide: MmsSlide, position: Int) {

            binding.apply {

                slideNumberText.text = activity.getString(R.string.slide_number, (position + 1).toString())

                val seconds = SlideshowHelper.durationSeconds(slide)

                slideDurationText.text = activity.resources.getQuantityString(

                    R.plurals.slide_duration,

                    seconds,

                    seconds,

                )

                slideDurationText.beVisible()



                // Alps SlideListItemView.setText: text_preview visible only when slide text is non-empty.

                val textPreview = slide.text.trim()

                if (textPreview.isNotEmpty()) {

                    slideTextPreview.text = textPreview

                    slideTextPreview.beVisible()

                } else {

                    slideTextPreview.beGone()

                }



                slideAttachmentName.beGone()

                slideAttachmentIcon.beGone()

                if (slide.uriString.isNotEmpty() && slide.mimetype.isVideoMimeType()) {

                    val attachmentLabel = slide.filename.ifBlank { activity.getString(R.string.type_video) }

                    slideAttachmentName.text = attachmentLabel

                    slideAttachmentName.beVisible()

                    slideAttachmentIcon.setImageResource(R.drawable.ic_vector_play_circle_outline)

                    slideAttachmentIcon.beVisible()

                } else if (slide.uriString.isNotEmpty() && slide.mimetype.isAudioMimeType()) {

                    val attachmentLabel = slide.filename.ifBlank { activity.getString(R.string.attach_sound) }

                    slideAttachmentName.text = attachmentLabel

                    slideAttachmentName.beVisible()

                    slideAttachmentIcon.setImageResource(R.drawable.ic_music_vector)

                    slideAttachmentIcon.beVisible()

                }



                if (slide.uriString.isNotEmpty() && slide.isMediaMimeType()) {

                    val corner = activity.resources.getDimension(com.goodwy.commons.R.dimen.normal_margin).toInt()

                    Glide.with(slideImagePreview)

                        .load(slide.uri)

                        .transform(CenterCrop(), RoundedCorners(corner))

                        .into(slideImagePreview)

                    slideImagePreview.beVisible()

                } else if (slide.uriString.isNotEmpty() && slide.mimetype.isAudioMimeType()) {

                    Glide.with(slideImagePreview).clear(slideImagePreview)

                    slideImagePreview.setImageResource(R.drawable.ic_music_vector)

                    slideImagePreview.beVisible()

                } else {

                    Glide.with(slideImagePreview).clear(slideImagePreview)

                    slideImagePreview.setImageResource(R.drawable.ic_image_vector)

                }



                slideshowSlideHolder.setOnClickListener { onSlideClicked(position) }

                slideshowSlideHolder.setOnLongClickListener {

                    onSlideLongClicked?.invoke(position, slideshowSlideHolder)

                    onSlideLongClicked != null

                }

            }

        }

    }



    /**

     * Alps [SlideshowEditActivity.createAddSlideItem]: reuses slideshow_edit_item with

     * "Add slide" title and add_slide_hint — not shown on blank slide rows.

     */

    inner class AddSlideViewHolder(private val binding: ItemSlideshowSlideBinding) :

        RecyclerView.ViewHolder(binding.root) {



        fun bind() {

            binding.apply {

                slideNumberText.text = activity.getString(R.string.add_slide)

                slideTextPreview.text = activity.getString(R.string.add_slide_hint)

                slideTextPreview.beVisible()

                slideAttachmentName.beGone()

                slideAttachmentIcon.beGone()

                slideDurationText.text = ""

                slideDurationText.beGone()

                Glide.with(slideImagePreview).clear(slideImagePreview)

                slideImagePreview.setImageResource(R.drawable.ic_image_vector)

                slideshowSlideHolder.setOnClickListener { onAddSlideClicked() }

                slideshowSlideHolder.setOnLongClickListener(null)

            }

        }

    }



    companion object {

        private const val VIEW_TYPE_SLIDE = 0

        private const val VIEW_TYPE_ADD = 1

    }

}

