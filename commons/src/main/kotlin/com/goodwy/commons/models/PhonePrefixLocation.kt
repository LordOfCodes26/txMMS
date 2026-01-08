package com.goodwy.commons.models

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "phone_prefix_locations",
    indices = [Index(value = ["prefix"], unique = true)]
)
@Keep
data class PhonePrefixLocation(
    @PrimaryKey(autoGenerate = true) var id: Int?,
    var prefix: String,
    var location: String
)

