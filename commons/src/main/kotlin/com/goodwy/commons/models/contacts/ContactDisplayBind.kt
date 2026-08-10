package com.goodwy.commons.models.contacts

import java.io.Serializable

/** Precomputed contact row fields for fast RecyclerView bind. */
data class ContactDisplayBind(
    val displayName: String,
    val formattedPhone: String,
    val showPhoneNumber: Boolean,
    val sectionLetter: String = "",
    val avatarInitials: String = "",
    val avatarDrawableIndex: Int = -1,
    val avatarColor: Int = 0,
    val photoThumbUri: String = "",
    val usePhotoAvatar: Boolean = false,
    val hasValidPhotoUri: Boolean = false,
    /** Precomputed monogram gradient — empty when [avatarDrawableIndex] is set. */
    val avatarGradientColors: List<Int> = emptyList(),
) : Serializable
