package com.goodwy.commons.providercache.entities

import kotlin.DeprecationLevel
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "recent_display_cache",
    primaryKeys = ["call_id", "group_by_contact"],
    indices = [
        Index(value = ["start_ts"]),
        Index(value = ["phone_number"]),
        Index(value = ["group_key", "group_by_contact"], unique = true),
        /** Matches getAllOrderedForList WHERE + ORDER BY. */
        Index(value = ["group_by_contact", "start_ts", "call_id", "group_key"]),
    ],
)
data class RecentDisplayCacheEntity(
    @ColumnInfo(name = "call_id")
    val callId: Int,
    @ColumnInfo(name = "phone_number")
    val phoneNumber: String,
    @ColumnInfo(name = "cached_name")
    val cachedName: String,
    @ColumnInfo(name = "photo_uri")
    val photoUri: String,
    @ColumnInfo(name = "start_ts")
    val startTS: Long,
    @ColumnInfo(name = "duration")
    val duration: Int,
    @ColumnInfo(name = "type")
    val type: Int,
    @ColumnInfo(name = "sim_id")
    val simID: Int,
    @ColumnInfo(name = "sim_type_id")
    val simTypeID: Int,
    @ColumnInfo(name = "sim_color")
    val simColor: Int,
    @ColumnInfo(name = "contact_id")
    val contactID: Int?,
    @ColumnInfo(name = "call_count")
    val callCount: Int,
    @Deprecated(
        message = "Compatibility only. Use recent_group_calls for membership.",
        level = DeprecationLevel.WARNING,
    )
    @ColumnInfo(name = "grouped_call_ids")
    val groupedCallIds: String,
    @ColumnInfo(name = "normalized_number")
    val normalizedNumber: String,
    @ColumnInfo(name = "group_key")
    val groupKey: String = "",
    @ColumnInfo(name = "is_unknown_number")
    val isUnknownNumber: Boolean,
    @ColumnInfo(name = "is_voice_mail")
    val isVoiceMail: Boolean,
    @ColumnInfo(name = "block_reason")
    val blockReason: Int?,
    @ColumnInfo(name = "features")
    val features: Int?,
    @ColumnInfo(name = "group_by_contact")
    val groupByContact: Int,
    @ColumnInfo(name = "display_order")
    val displayOrder: Int,
    @ColumnInfo(name = "display_name")
    val displayName: String = "",
    @ColumnInfo(name = "display_number")
    val displayNumber: String = "",
    @ColumnInfo(name = "formatted_date_time")
    val formattedDateTime: String = "",
    @ColumnInfo(name = "group_count_text")
    val groupCountText: String = "",
    @ColumnInfo(name = "call_type_icon_key")
    val callTypeIconKey: String = "",
    @ColumnInfo(name = "sim_color_resolved")
    val simColorResolved: Int = 0,
    @ColumnInfo(name = "sim_label")
    val simLabel: String = "",
    @ColumnInfo(name = "sim_visible")
    val simVisible: Int = 0,
    @ColumnInfo(name = "avatar_initials")
    val avatarInitials: String = "",
    @ColumnInfo(name = "avatar_drawable_index")
    val avatarDrawableIndex: Int = -1,
    @ColumnInfo(name = "avatar_show_profile_icon")
    val avatarShowProfileIcon: Int = 0,
    @ColumnInfo(name = "photo_thumb_uri")
    val photoThumbUri: String = "",
    @ColumnInfo(name = "avatar_color")
    val avatarColor: Int = 0,
    @ColumnInfo(name = "use_photo_avatar")
    val usePhotoAvatar: Int = 0,
    @ColumnInfo(name = "name_is_missed_color")
    val nameIsMissedColor: Int = 0,
    @ColumnInfo(name = "section_day_code")
    val sectionDayCode: String = "",
    @ColumnInfo(name = "section_header_text")
    val sectionHeaderText: String = "",
    @ColumnInfo(name = "avatar_seed_type")
    val avatarSeedType: String = "",
    @ColumnInfo(name = "avatar_seed_value")
    val avatarSeedValue: String = "",
    @ColumnInfo(name = "avatar_version")
    val avatarVersion: Long = 0L,
)
