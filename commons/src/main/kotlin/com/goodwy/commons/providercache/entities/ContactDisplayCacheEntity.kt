package com.goodwy.commons.providercache.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contact_display_cache",
    indices = [
        Index(value = ["display_order"]),
        /** Matches getAllOrderedForList ORDER BY display_order ASC, raw_id ASC. */
        Index(value = ["display_order", "raw_id"]),
        Index(value = ["sort_key"]),
        Index(value = ["search_name"]),
        Index(value = ["t9_key"]),
        Index(value = ["phone_digits"]),
    ],
)
data class ContactDisplayCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "raw_id")
    val rawId: Int,
    @ColumnInfo(name = "contact_id")
    val contactId: Int,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "thumbnail_uri")
    val thumbnailUri: String,
    @ColumnInfo(name = "photo_uri")
    val photoUri: String,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "account_type")
    val accountType: String,
    @ColumnInfo(name = "first_phone")
    val firstPhone: String,
    @ColumnInfo(name = "first_email")
    val firstEmail: String,
    @ColumnInfo(name = "starred")
    val starred: Int = 0,
    @ColumnInfo(name = "section_letter")
    val sectionLetter: String,
    @ColumnInfo(name = "sort_key")
    val sortKey: String,
    @ColumnInfo(name = "search_name")
    val searchName: String,
    @ColumnInfo(name = "t9_key")
    val t9Key: String,
    @ColumnInfo(name = "phone_digits")
    val phoneDigits: String,
    /** Formatted first phone for list bind — computed at cache rebuild. */
    @ColumnInfo(name = "first_phone_formatted")
    val firstPhoneFormatted: String = "",
    @ColumnInfo(name = "show_phone_number")
    val showPhoneNumber: Int = 0,
    @ColumnInfo(name = "avatar_initials")
    val avatarInitials: String = "",
    /** Gradient drawable index (0..26) for monogram avatar background. */
    @ColumnInfo(name = "avatar_drawable_index")
    val avatarDrawableIndex: Int = -1,
    /** Resolved ARGB avatar background color for monogram rows. */
    @ColumnInfo(name = "avatar_color")
    val avatarColor: Int = 0,
    /** Pre-resolved thumb URI for list bind — thumbnail, else photo. */
    @ColumnInfo(name = "photo_thumb_uri")
    val photoThumbUri: String = "",
    @ColumnInfo(name = "use_photo_avatar")
    val usePhotoAvatar: Int = 0,
    /** 0 when [photoThumbUri] is empty or known broken; avoids repeated Glide loads. */
    @ColumnInfo(name = "has_valid_photo_uri")
    val hasValidPhotoUri: Int = 0,
    /** Stable list order — set during full rebuild; avoids COLLATE LOCALIZED at read time. */
    @ColumnInfo(name = "display_order")
    val displayOrder: Int = 0,
)
