package com.goodwy.commons.models

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "phone_districts",
    indices = [Index(value = ["prefix", "districtCode"], unique = true)]
)
@Keep
data class PhoneDistrict(
    @PrimaryKey(autoGenerate = true) var id: Int?,
    var prefix: String,  // City prefix (01~13 or variable length)
    var districtCode: String,  // District code (2-3 digits after prefix)
    var districtName: String  // District name
)

