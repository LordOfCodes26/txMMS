package com.goodwy.commons.models.contacts

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Data class representing a Contact Poster configuration.
 * Stores image URI, transform parameters, and text styling options.
 */
@Serializable
data class ContactPosterData(
    val imageUri: String = "",
    val scale: Float = 1.0f,
    val offsetX: Float = 0.0f,
    val offsetY: Float = 0.0f,
    val textStyle: PosterTextStyle = PosterTextStyle()
)

/**
 * Text styling configuration for the poster.
 */
@Serializable
data class PosterTextStyle(
    val textColor: Long = 0xFFFFFFFF, // ARGB as Long
    val fontSize: Float = 48f,
    val fontWeight: Int = 700, // Font weight (400 = normal, 700 = bold)
    val textAlignment: String = "start" // "start", "center", "end"
)

/**
 * Room entity for storing contact poster data.
 */
@Entity(
    tableName = "contact_posters",
    indices = [Index(value = ["contact_id"], unique = true)]
)
data class ContactPoster(
    @PrimaryKey(autoGenerate = true) var id: Int? = null,
    @ColumnInfo(name = "contact_id") var contactId: Int,
    @ColumnInfo(name = "image_uri") var imageUri: String = "",
    @ColumnInfo(name = "scale") var scale: Float = 1.0f,
    @ColumnInfo(name = "offset_x") var offsetX: Float = 0.0f,
    @ColumnInfo(name = "offset_y") var offsetY: Float = 0.0f,
    @ColumnInfo(name = "text_color") var textColor: Long = 0xFFFFFFFF,
    @ColumnInfo(name = "font_size") var fontSize: Float = 48f,
    @ColumnInfo(name = "font_weight") var fontWeight: Int = 700,
    @ColumnInfo(name = "text_alignment") var textAlignment: String = "start"
) {
    fun toPosterData(): ContactPosterData {
        return ContactPosterData(
            imageUri = imageUri,
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY,
            textStyle = PosterTextStyle(
                textColor = textColor,
                fontSize = fontSize,
                fontWeight = fontWeight,
                textAlignment = textAlignment
            )
        )
    }

    companion object {
        fun fromPosterData(contactId: Int, data: ContactPosterData): ContactPoster {
            return ContactPoster(
                contactId = contactId,
                imageUri = data.imageUri,
                scale = data.scale,
                offsetX = data.offsetX,
                offsetY = data.offsetY,
                textColor = data.textStyle.textColor,
                fontSize = data.textStyle.fontSize,
                fontWeight = data.textStyle.fontWeight,
                textAlignment = data.textStyle.textAlignment
            )
        }
    }
}
