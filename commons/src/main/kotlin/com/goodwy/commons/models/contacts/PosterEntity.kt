package com.goodwy.commons.models.contacts

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Room entity for storing Contact Poster configuration in the database.
 * This entity maps to [ContactPosterConfig] data model.
 *
 * @property id Primary key, auto-generated
 * @property contactId The unique identifier of the contact this poster belongs to (indexed for performance)
 * @property backgroundType The type of background stored as String (converted from enum)
 * @property backgroundUri URI of the background image, nullable
 * @property subjectMaskUri URI of the subject mask image, nullable
 * @property gradientColors JSON string representation of gradient colors list, nullable
 * @property textColor The color of the text displayed on the poster (ARGB format)
 * @property textStyle The style of the text stored as String (converted from enum)
 * @property nameLayoutStyle Integer representing the layout style for the contact name
 * @property avatarVisible Whether the contact's avatar/photo should be visible (stored as Int: 0 or 1)
 * @property updatedAt Timestamp (in milliseconds since epoch) when this configuration was last updated
 */
@Entity(
    tableName = "poster_configs",
    indices = [Index(value = ["contact_id"], unique = true)]
)
data class PosterEntity(
    @PrimaryKey(autoGenerate = true) var id: Long? = null,
    @ColumnInfo(name = "contact_id") var contactId: Long,
    @ColumnInfo(name = "background_type") var backgroundType: String,
    @ColumnInfo(name = "background_uri") var backgroundUri: String?,
    @ColumnInfo(name = "subject_mask_uri") var subjectMaskUri: String?,
    @ColumnInfo(name = "gradient_colors") var gradientColors: String?,
    @ColumnInfo(name = "text_color") var textColor: Int,
    @ColumnInfo(name = "text_style") var textStyle: String,
    @ColumnInfo(name = "name_layout_style") var nameLayoutStyle: Int,
    @ColumnInfo(name = "avatar_visible") var avatarVisible: Int,
    @ColumnInfo(name = "updated_at") var updatedAt: Long
) {
    /**
     * Converts this entity to a [ContactPosterConfig] model.
     */
    fun toConfig(): ContactPosterConfig {
        return ContactPosterConfig(
            contactId = contactId,
            backgroundType = PosterBackgroundType.valueOf(backgroundType),
            backgroundUri = backgroundUri,
            subjectMaskUri = subjectMaskUri,
            gradientColors = gradientColors?.let { 
                // Parse JSON string to List<Int>
                Gson().fromJson(it, object : TypeToken<List<Int>>() {}.type)
            },
            textColor = textColor,
            textStyle = PosterTextStyle.valueOf(textStyle),
            nameLayoutStyle = nameLayoutStyle,
            avatarVisible = avatarVisible == 1,
            updatedAt = updatedAt
        )
    }

    companion object {
        /**
         * Creates a [PosterEntity] from a [ContactPosterConfig] model.
         */
        fun fromConfig(config: ContactPosterConfig): PosterEntity {
            return PosterEntity(
                contactId = config.contactId,
                backgroundType = config.backgroundType.name,
                backgroundUri = config.backgroundUri,
                subjectMaskUri = config.subjectMaskUri,
                gradientColors = config.gradientColors?.let {
                    Gson().toJson(it)
                },
                textColor = config.textColor,
                textStyle = config.textStyle.name,
                nameLayoutStyle = config.nameLayoutStyle,
                avatarVisible = if (config.avatarVisible) 1 else 0,
                updatedAt = config.updatedAt
            )
        }
    }
}
