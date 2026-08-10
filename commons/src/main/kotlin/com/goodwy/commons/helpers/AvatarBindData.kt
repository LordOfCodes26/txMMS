package com.goodwy.commons.helpers

/**
 * Unified avatar bind payload precomputed at cache-build / row-prep time.
 * RecyclerView bind must only map this to [AvatarSource] — no provider I/O or color generation.
 */
data class AvatarBindData(
    val contactId: Int,
    val rawContactId: Int,
    val displayName: String,
    val safePhotoThumbUri: String,
    /** Full Contacts PHOTO_URI when available; detail/edit load this after the list thumb. */
    val safeFullPhotoUri: String = "",
    val usePhotoAvatar: Boolean,
    val initials: String,
    val drawableIndex: Int?,
    val avatarColor: Int,
    val gradientColors: List<Int>,
    val showProfileIcon: Boolean,
    val avatarVersion: Long,
)
