package com.goodwy.commons.providercache.display

import androidx.room.ColumnInfo

/** Narrow Room projection for the main contacts list — UI-ready columns only. */
data class ContactDisplayListRow(
    @ColumnInfo(name = "raw_id")
    val rawId: Int,
    @ColumnInfo(name = "contact_id")
    val contactId: Int,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    /** RawContacts account name — required for SIM icon/label and source filtering. */
    @ColumnInfo(name = "source")
    val source: String = "",
    @ColumnInfo(name = "starred")
    val starred: Int,
    @ColumnInfo(name = "section_letter")
    val sectionLetter: String,
    @ColumnInfo(name = "first_phone_formatted")
    val firstPhoneFormatted: String,
    @ColumnInfo(name = "show_phone_number")
    val showPhoneNumber: Int,
    @ColumnInfo(name = "avatar_initials")
    val avatarInitials: String,
    @ColumnInfo(name = "avatar_drawable_index")
    val avatarDrawableIndex: Int,
    @ColumnInfo(name = "avatar_color")
    val avatarColor: Int,
    @ColumnInfo(name = "photo_thumb_uri")
    val photoThumbUri: String,
    @ColumnInfo(name = "use_photo_avatar")
    val usePhotoAvatar: Int,
    @ColumnInfo(name = "has_valid_photo_uri")
    val hasValidPhotoUri: Int,
    @ColumnInfo(name = "display_order")
    val displayOrder: Int,
)
