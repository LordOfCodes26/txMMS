package com.android.mms.helpers

import androidx.annotation.DrawableRes
import com.android.mms.R

data class BubbleDrawableOption(
    val id: Int,
    @DrawableRes val incomingRes: Int,
    @DrawableRes val outgoingRes: Int,
    val minHeightDp: Int
)

val BUBBLE_DRAWABLE_OPTIONS = listOf(
    BubbleDrawableOption(
        id = 1,
        incomingRes = R.drawable.bubble_incoming_1,
        outgoingRes = R.drawable.bubble_outgoing_1,
        minHeightDp = 42
    ),
    BubbleDrawableOption(
        id = 2,
        incomingRes = R.drawable.bubble_incoming_2,
        outgoingRes = R.drawable.bubble_outgoing_2,
        minHeightDp = 42
    ),
    BubbleDrawableOption(
        id = 3,
        incomingRes = R.drawable.bubble_incoming_3,
        outgoingRes = R.drawable.bubble_outgoing_3,
        minHeightDp = 42
    ),
    BubbleDrawableOption(
        id = 4,
        incomingRes = R.drawable.bubble_incoming_4,
        outgoingRes = R.drawable.bubble_outgoing_4,
        minHeightDp = 42
    ),
    BubbleDrawableOption(
        id = 5,
        incomingRes = R.drawable.bubble_incoming_5,
        outgoingRes = R.drawable.bubble_outgoing_5,
        minHeightDp = 42
    ),
    BubbleDrawableOption(
        id = 6,
        incomingRes = R.drawable.bubble_incoming_6,
        outgoingRes = R.drawable.bubble_outgoing_6,
        minHeightDp = 42
    ),
    BubbleDrawableOption(
        id = 7,
        incomingRes = R.drawable.bubble_incoming_7,
        outgoingRes = R.drawable.bubble_outgoing_7,
        minHeightDp = 42
    ),
    BubbleDrawableOption(
        id = 8,
        incomingRes = R.drawable.bubble_incoming_8,
        outgoingRes = R.drawable.bubble_outgoing_8,
        minHeightDp = 42
    ),
    BubbleDrawableOption(
        id = 9,
        incomingRes = R.drawable.bubble_incoming_9,
        outgoingRes = R.drawable.bubble_outgoing_9,
        minHeightDp = 42
    ),
    BubbleDrawableOption(
        id = 10,
        incomingRes = R.drawable.bubble_incoming_10,
        outgoingRes = R.drawable.bubble_outgoing_10,
        minHeightDp = 42
    ),
    BubbleDrawableOption(
        id = 11,
        incomingRes = R.drawable.bubble_incoming_11,
        outgoingRes = R.drawable.bubble_outgoing_11,
        minHeightDp = 42
    ),
    BubbleDrawableOption(
        id = 12,
        incomingRes = R.drawable.bubble_incoming_12,
        outgoingRes = R.drawable.bubble_outgoing_12,
        minHeightDp = 42
    ),
    BubbleDrawableOption(
        id = 13,
        incomingRes = R.drawable.bubble_incoming_13,
        outgoingRes = R.drawable.bubble_outgoing_13,
        minHeightDp = 42
    ),
    BubbleDrawableOption(
        id = 14,
        incomingRes = R.drawable.bubble_incoming_14,
        outgoingRes = R.drawable.bubble_outgoing_14,
        minHeightDp = 42
    ),
    BubbleDrawableOption(
        id = 15,
        incomingRes = R.drawable.bubble_incoming_15,
        outgoingRes = R.drawable.bubble_outgoing_15,
        minHeightDp = 42
    )
)

fun getBubbleDrawableOption(optionId: Int): BubbleDrawableOption? {
    return BUBBLE_DRAWABLE_OPTIONS.firstOrNull { it.id == optionId }
}
