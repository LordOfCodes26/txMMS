package com.goodwy.commons.securebox

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "secure_box_contacts")
@Keep
@kotlinx.serialization.Serializable
data class SecureBoxContact(
    @PrimaryKey var contactId: Int,
    var addedAt: Long = System.currentTimeMillis(),
    var cipherNumber: Int = 0
)


