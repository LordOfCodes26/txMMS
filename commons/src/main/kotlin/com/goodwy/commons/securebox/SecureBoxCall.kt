package com.goodwy.commons.securebox

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "secure_box_calls")
@Keep
@kotlinx.serialization.Serializable
data class SecureBoxCall(
    @PrimaryKey var callId: Int,
    var addedAt: Long = System.currentTimeMillis(),
    var cipherNumber: Int = 0
)


