package com.android.mms.models

/**
 * In-memory slideshow for MMS compose, matching Alps [com.android.mms.model.SlideshowModel] behavior.
 */
data class MmsSlideshow(
    val slides: List<MmsSlide>,
) {
    val size: Int get() = slides.size

    /** Alps [WorkingMessage.hasSlideshow]: true when more than one slide. */
    fun isRealSlideshow(): Boolean = slides.size > 1

    fun slideAt(index: Int): MmsSlide? = slides.getOrNull(index)

    fun withSlides(newSlides: List<MmsSlide>): MmsSlideshow = copy(slides = newSlides)

    fun moveSlideUp(index: Int): MmsSlideshow {
        if (index <= 0 || index >= slides.size) return this
        val mutable = slides.toMutableList()
        val slide = mutable.removeAt(index)
        mutable.add(index - 1, slide)
        return copy(slides = mutable)
    }

    fun moveSlideDown(index: Int): MmsSlideshow {
        if (index < 0 || index >= slides.size - 1) return this
        val mutable = slides.toMutableList()
        val slide = mutable.removeAt(index)
        mutable.add(index + 1, slide)
        return copy(slides = mutable)
    }

    fun removeSlideAt(index: Int): MmsSlideshow {
        if (index !in slides.indices) return this
        return copy(slides = slides.toMutableList().apply { removeAt(index) })
    }

    fun replaceSlideAt(index: Int, slide: MmsSlide): MmsSlideshow {
        if (index !in slides.indices) return this
        val mutable = slides.toMutableList()
        mutable[index] = slide
        return copy(slides = mutable)
    }

    fun addSlideAt(index: Int, slide: MmsSlide = MmsSlide.empty()): MmsSlideshow? {
        if (slides.size >= MAX_SLIDE_NUM) return null
        val mutable = slides.toMutableList()
        val insertAt = index.coerceIn(0, mutable.size)
        mutable.add(insertAt, slide)
        return copy(slides = mutable)
    }

    fun addSlide(slide: MmsSlide = MmsSlide.empty()): MmsSlideshow? =
        addSlideAt(slides.size, slide)

    companion object {
        const val MAX_SLIDE_NUM = 20

        fun fromMediaSelections(selections: List<AttachmentSelection>): MmsSlideshow {
            return MmsSlideshow(selections.map { MmsSlide.fromSelection(it) })
        }
    }
}
