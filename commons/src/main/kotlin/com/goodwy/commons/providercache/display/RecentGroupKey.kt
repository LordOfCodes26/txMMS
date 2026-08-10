package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity

/**
 * Canonical Recents group identity — dialable digits of the phone number.
 * Contact name and contact id are display metadata only.
 *
 * Digits-only avoids splitting one dialed number across rows when Room/`CallLog`
 * stored `+1612315125` on some entries and `1612315125` on others.
 */
object RecentGroupKey {

    fun fromNormalizedNumber(normalizedNumber: String, phoneNumber: String): String {
        val raw = normalizedNumber.ifEmpty { phoneNumber }.trim()
        val digits = raw.filter { it.isDigit() }
        return digits.ifEmpty { raw }
    }

    fun fromEntity(entity: RecentDisplayCacheEntity): String =
        entity.groupKey.ifEmpty {
            fromNormalizedNumber(entity.normalizedNumber, entity.phoneNumber)
        }
}
