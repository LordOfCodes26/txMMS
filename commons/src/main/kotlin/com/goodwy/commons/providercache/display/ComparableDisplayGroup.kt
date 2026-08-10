package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity

/**
 * Stable display snapshot for cosmetic comparison (excludes relative-time clock text).
 * Authority fields (contact id, numbers, membership, counts, timestamps) are compared
 * via [ComparableRecentGroup] / [RecentAuthorityComparator.compareSemanticGroups].
 */
data class ComparableDisplayGroup(
    val semanticKey: String,
    val displayOrder: Int,
    val displayContactId: Long?,
    val displayName: String,
    val displayNumber: String,
    val photoThumbUri: String,
    val avatarInitials: String,
    val avatarDrawableIndex: Int,
    val usePhotoAvatar: Boolean,
    val avatarShowProfileIcon: Boolean,
    val avatarColor: Int,
    val simLabel: String,
    val simColorResolved: Int,
    val simVisible: Boolean,
    val callTypeIconKey: String,
    val groupCountText: String,
    val nameIsMissedColor: Boolean,
    val sectionDayCode: String,
    val sectionHeaderText: String,
)

/**
 * Display-layer fields. [isCosmetic] fields never count toward authority / dualWriteMismatch.
 * Contact id, number, and group existence remain on the semantic compare path.
 */
enum class ComparableDisplayField(val isCosmetic: Boolean) {
    DISPLAY_ORDER(true),
    DISPLAY_CONTACT_ID(false),
    DISPLAY_NAME(true),
    DISPLAY_NUMBER(false),
    PHOTO_THUMB_URI(true),
    AVATAR_INITIALS(true),
    AVATAR_DRAWABLE_INDEX(true),
    USE_PHOTO_AVATAR(true),
    AVATAR_SHOW_PROFILE_ICON(true),
    AVATAR_COLOR(true),
    SIM_LABEL(true),
    SIM_COLOR(true),
    SIM_VISIBLE(true),
    CALL_TYPE_ICON(true),
    GROUP_COUNT_TEXT(true),
    NAME_IS_MISSED_COLOR(true),
    SECTION_DAY_CODE(true),
    SECTION_HEADER_TEXT(true),
    GROUP_EXISTENCE(false),
}

data class ComparableDisplayMismatch(
    val mode: RecentGroupingMode,
    val semanticKey: String,
    val field: ComparableDisplayField,
    val oldValue: String,
    val newValue: String,
)

object ComparableDisplayGroupDeriver {

    fun fromEntity(
        row: RecentDisplayCacheEntity,
        mode: RecentGroupingMode,
    ): ComparableDisplayGroup {
        val semanticKey = ComparableRecentGroup.semanticKeyFromStoredGroupKey(
            row.groupKey,
            row.contactID?.toLong(),
        )
        return ComparableDisplayGroup(
            semanticKey = semanticKey,
            displayOrder = row.displayOrder,
            displayContactId = row.contactID?.toLong(),
            displayName = row.displayName.ifEmpty { row.cachedName },
            displayNumber = row.displayNumber.ifEmpty { row.phoneNumber },
            photoThumbUri = row.photoThumbUri.ifEmpty { row.photoUri },
            avatarInitials = row.avatarInitials,
            avatarDrawableIndex = row.avatarDrawableIndex,
            usePhotoAvatar = row.usePhotoAvatar == 1,
            avatarShowProfileIcon = row.avatarShowProfileIcon == 1,
            avatarColor = row.avatarColor,
            simLabel = row.simLabel,
            simColorResolved = row.simColorResolved,
            simVisible = row.simVisible == 1,
            callTypeIconKey = row.callTypeIconKey,
            groupCountText = row.groupCountText,
            nameIsMissedColor = row.nameIsMissedColor == 1,
            sectionDayCode = row.sectionDayCode,
            sectionHeaderText = row.sectionHeaderText,
        )
    }

    fun fromEntities(
        rows: List<RecentDisplayCacheEntity>,
        mode: RecentGroupingMode,
    ): Map<String, ComparableDisplayGroup> = rows.associate { row ->
        val key = ComparableRecentGroup.semanticKeyFromStoredGroupKey(row.groupKey, row.contactID?.toLong())
        key to fromEntity(row, mode)
    }
}
