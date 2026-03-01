package com.goodwy.commons.models.contacts

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Room entity for storing Avatar Style configuration in the database.
 * This entity maps to [AvatarStyleConfig] data model.
 *
 * @property id Primary key, auto-generated
 * @property contactId The unique identifier of the contact this avatar style belongs to (indexed for performance)
 * @property sourceType The type of avatar source stored as String (converted from enum)
 * @property fontFamily The font family name for monogram text, nullable
 * @property fontWeight The font weight for monogram text, nullable
 * @property textColor The color of the text displayed in the avatar (ARGB format)
 * @property backgroundColors JSON string representation of background colors list, nullable
 * @property customPhotoUri URI of a custom photo image, nullable
 * @property usePosterSubject Whether to use the poster subject mask (stored as Int: 0 or 1)
 * @property updatedAt Timestamp (in milliseconds since epoch) when this configuration was last updated
 */
@Entity(
    tableName = "avatar_style_configs",
    indices = [Index(value = ["contact_id"], unique = true)]
)
data class AvatarStyleEntity(
    @PrimaryKey(autoGenerate = true) var id: Long? = null,
    @ColumnInfo(name = "contact_id") var contactId: Long,
    @ColumnInfo(name = "source_type") var sourceType: String,
    @ColumnInfo(name = "font_family") var fontFamily: String?,
    @ColumnInfo(name = "font_weight") var fontWeight: Int?,
    @ColumnInfo(name = "text_color") var textColor: Int,
    @ColumnInfo(name = "background_colors") var backgroundColors: String?,
    @ColumnInfo(name = "custom_photo_uri") var customPhotoUri: String?,
    @ColumnInfo(name = "use_poster_subject") var usePosterSubject: Int,
    @ColumnInfo(name = "updated_at") var updatedAt: Long
) {
    /**
     * Converts this entity to an [AvatarStyleConfig] model.
     */
    fun toConfig(): AvatarStyleConfig {
        return AvatarStyleConfig(
            contactId = contactId,
            sourceType = AvatarSourceType.valueOf(sourceType),
            fontFamily = fontFamily,
            fontWeight = fontWeight,
            textColor = textColor,
            backgroundColors = backgroundColors?.let {
                // Parse JSON string to List<Int>
                Gson().fromJson(it, object : TypeToken<List<Int>>() {}.type)
            },
            customPhotoUri = customPhotoUri,
            usePosterSubject = usePosterSubject == 1,
            updatedAt = updatedAt
        )
    }

    companion object {
        /**
         * Creates an [AvatarStyleEntity] from an [AvatarStyleConfig] model.
         */
        fun fromConfig(config: AvatarStyleConfig): AvatarStyleEntity {
            return AvatarStyleEntity(
                contactId = config.contactId,
                sourceType = config.sourceType.name,
                fontFamily = config.fontFamily,
                fontWeight = config.fontWeight,
                textColor = config.textColor,
                backgroundColors = config.backgroundColors?.let {
                    Gson().toJson(it)
                },
                customPhotoUri = config.customPhotoUri,
                usePosterSubject = if (config.usePosterSubject) 1 else 0,
                updatedAt = config.updatedAt
            )
        }
    }
}
