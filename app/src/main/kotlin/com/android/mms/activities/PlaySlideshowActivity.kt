package com.android.mms.activities

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.beVisibleIf
import com.android.mms.databinding.ActivityPlaySlideshowBinding
import com.android.mms.extensions.isImageMimeType
import com.android.mms.extensions.isVideoMimeType
import com.android.mms.extensions.launchViewIntent
import com.android.mms.helpers.EXTRA_SLIDESHOW_JSON
import com.android.mms.helpers.SlideshowHelper
import com.android.mms.models.MmsSlide
import com.android.mms.models.MmsSlideshow

/**
 * Alps [com.android.mms.ui.SlideshowActivity]: auto-advances through slideshow slides full-screen.
 * Finishes after the last slide completes, matching Alps SMIL_DOCUMENT_END_EVENT behaviour.
 */
class PlaySlideshowActivity : SimpleActivity() {

    private lateinit var binding: ActivityPlaySlideshowBinding
    private var slideshow = MmsSlideshow(emptyList())
    private var slideIndex = 0
    private val handler = Handler(Looper.getMainLooper())

    private val advanceRunnable = Runnable {
        if (isFinishing || isDestroyed || slideshow.slides.isEmpty()) return@Runnable
        if (slideIndex < slideshow.slides.size - 1) {
            slideIndex++
            showCurrentSlide()
            scheduleNextAdvance()
        } else {
            // Alps SlideshowActivity: finish when the last slide completes.
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaySlideshowBinding.inflate(layoutInflater)
        setContentView(binding.root)

        slideshow = SlideshowHelper.fromJson(intent.getStringExtra(EXTRA_SLIDESHOW_JSON))
            ?.takeIf { it.slides.isNotEmpty() }
            ?: run {
                finish()
                return
            }

        binding.playSlideshowClose.setOnClickListener { finish() }
        binding.playSlideshowImage.setOnClickListener { openCurrentSlide() }
        binding.playSlideshowVideoIcon.setOnClickListener { openCurrentSlide() }

        slideIndex = 0
        showCurrentSlide()
        scheduleNextAdvance()
    }

    override fun onDestroy() {
        handler.removeCallbacks(advanceRunnable)
        super.onDestroy()
    }

    private fun scheduleNextAdvance() {
        handler.removeCallbacks(advanceRunnable)
        val duration = slideshow.slides.getOrNull(slideIndex)?.durationMs
            ?: MmsSlide.DEFAULT_DURATION_MS
        handler.postDelayed(advanceRunnable, duration)
    }

    private fun showCurrentSlide() {
        val slide = slideshow.slides.getOrNull(slideIndex) ?: return
        showSlide(slide)
    }

    private fun showSlide(slide: MmsSlide) {
        if (slide.text.isNotBlank()) {
            binding.playSlideshowText.text = slide.text
            binding.playSlideshowText.beVisible()
        } else {
            binding.playSlideshowText.beGone()
        }

        val isVideo = slide.mimetype.isVideoMimeType()
        binding.playSlideshowVideoIcon.beVisibleIf(isVideo)

        if (isVideo) {
            Glide.with(this).clear(binding.playSlideshowImage)
            binding.playSlideshowImage.setImageDrawable(null)
            return
        }

        if (!slide.mimetype.isImageMimeType()) {
            Glide.with(this).clear(binding.playSlideshowImage)
            binding.playSlideshowImage.setImageDrawable(null)
            return
        }

        val uri = slide.uri
        Glide.with(this).clear(binding.playSlideshowImage)
        Glide.with(this)
            .load(uri)
            .fitCenter()
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    loadBitmapFallback(uri)
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean = false
            })
            .into(binding.playSlideshowImage)
    }

    private fun loadBitmapFallback(uri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)?.let { bitmap ->
                    binding.playSlideshowImage.setImageDrawable(BitmapDrawable(resources, bitmap))
                    true
                }
            } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun openCurrentSlide() {
        val slide = slideshow.slides.getOrNull(slideIndex) ?: return
        if (slide.uriString.isEmpty()) return
        launchViewIntent(slide.uri, slide.mimetype, slide.filename)
    }
}
