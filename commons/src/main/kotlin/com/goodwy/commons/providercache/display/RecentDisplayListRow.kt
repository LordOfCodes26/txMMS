package com.goodwy.commons.providercache.display

import androidx.room.ColumnInfo

/**
 * Narrow Room projection for the main Recents list.
 * Column names match [com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity]:
 * groupingMode → group_by_contact, latestTimestamp → start_ts, latestCallId → call_id.
 */
data class RecentDisplayListRow(
    @ColumnInfo(name = "group_by_contact")
    val groupingMode: Int,
    @ColumnInfo(name = "group_key")
    val groupKey: String,
    @ColumnInfo(name = "call_id")
    val latestCallId: Int,
    @ColumnInfo(name = "start_ts")
    val latestTimestamp: Long,
    @ColumnInfo(name = "call_count")
    val callCount: Int,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "display_number")
    val displayNumber: String,
    @ColumnInfo(name = "contact_id")
    val displayContactId: Int?,
    @ColumnInfo(name = "phone_number")
    val phoneNumber: String,
    @ColumnInfo(name = "cached_name")
    val cachedName: String,
    @ColumnInfo(name = "photo_thumb_uri")
    val photoThumbUri: String,
    @ColumnInfo(name = "photo_uri")
    val photoUri: String,
    @ColumnInfo(name = "avatar_initials")
    val avatarInitials: String,
    @ColumnInfo(name = "avatar_drawable_index")
    val avatarDrawableIndex: Int,
    @ColumnInfo(name = "avatar_color")
    val avatarColor: Int,
    @ColumnInfo(name = "avatar_version")
    val avatarVersion: Long,
    @ColumnInfo(name = "avatar_show_profile_icon")
    val avatarShowProfileIcon: Int,
    @ColumnInfo(name = "use_photo_avatar")
    val usePhotoAvatar: Int,
    @ColumnInfo(name = "call_type_icon_key")
    val callTypeIconKey: String,
    @ColumnInfo(name = "sim_color_resolved")
    val simColorResolved: Int,
    @ColumnInfo(name = "sim_label")
    val simLabel: String,
    @ColumnInfo(name = "sim_visible")
    val simVisible: Int,
    @ColumnInfo(name = "sim_id")
    val simId: Int,
    @ColumnInfo(name = "sim_type_id")
    val simTypeId: Int,
    @ColumnInfo(name = "sim_color")
    val simColor: Int,
    @ColumnInfo(name = "type")
    val callType: Int,
    @ColumnInfo(name = "duration")
    val duration: Int,
    @ColumnInfo(name = "is_unknown_number")
    val isUnknownNumber: Boolean,
    @ColumnInfo(name = "is_voice_mail")
    val isVoiceMail: Boolean,
    @ColumnInfo(name = "block_reason")
    val blockReason: Int?,
    @ColumnInfo(name = "features")
    val features: Int?,
    @ColumnInfo(name = "name_is_missed_color")
    val nameIsMissedColor: Int,
    @ColumnInfo(name = "section_day_code")
    val sectionDayCode: String,
    @ColumnInfo(name = "section_header_text")
    val sectionHeaderText: String,
    @ColumnInfo(name = "group_count_text")
    val groupCountText: String,
    @ColumnInfo(name = "formatted_date_time")
    val formattedDateTime: String,
    @ColumnInfo(name = "display_order")
    val displayOrder: Int,
    @ColumnInfo(name = "normalized_number")
    val normalizedNumber: String,
)
