package com.goodwy.commons.models

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "phone_number_formats",
    indices = [Index(value = ["prefix", "districtCodePattern"], unique = true)]
)
@Keep
data class PhoneNumberFormat(
    @PrimaryKey(autoGenerate = true) var id: Int?,
    var prefix: String,  // City prefix (can be 2, 3, or 4 digits like "01", "021", "0219") or "all" for any prefix
    var prefixLength: Int,  // Length of prefix (2, 3, or 4 digits)
    var districtCodePattern: String,  // Pattern like "4XX", "5XX", "7XX", "09", "**", "*", etc.
    var formatTemplate: String,  // Format template like "01-XXX-XXXX", "021-XX-XXXX", "0219-X-XXXX"
    var districtCodeLength: Int,  // Length of district code (1, 2, or 3)
    var description: String = ""  // Optional description
)

