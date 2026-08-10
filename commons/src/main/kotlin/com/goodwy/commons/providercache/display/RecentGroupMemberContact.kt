package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.entities.CallLogEntity

/**
 * Picks contact display metadata from any group member, not only the latest call.
 *
 * Latest-only heads lose names when the newest row has empty [CallLogEntity.contactID] /
 * [CallLogEntity.cachedName] while an older sibling still has them (common after insert
 * before backfill finishes on the new row).
 */
object RecentGroupMemberContact {

    fun pickContactDonor(members: List<CallLogEntity>): CallLogEntity? {
        if (members.isEmpty()) return null
        members.firstOrNull { (it.contactID ?: 0) > 0 }?.let { return it }
        return members.firstOrNull { member ->
            val name = member.cachedName.trim()
            name.isNotEmpty() &&
                name != member.phoneNumber &&
                name != member.normalizedNumber
        }
    }

    /**
     * Display contact id for a group: prefer identity resolution on the contact donor,
     * then any member [CallLogEntity.contactID], then latest-only identity.
     */
    fun resolveDisplayContactId(
        members: List<CallLogEntity>,
        latest: CallLogEntity,
        mode: RecentGroupingMode,
        context: RecentGroupIdentityResolver.Context,
    ): Long? {
        val donor = pickContactDonor(members)
        if (donor != null) {
            RecentGroupIdentityResolver.resolve(donor, mode, context).displayContactId?.let { return it }
            donor.contactID?.takeIf { it > 0 }?.toLong()?.let { return it }
        }
        return RecentGroupIdentityResolver.resolve(latest, mode, context).displayContactId
            ?: members.firstOrNull { (it.contactID ?: 0) > 0 }?.contactID?.toLong()
    }

    /**
     * BY_CONTACT: merge `number:<digits>` buckets into a unique `contact:<id>` bucket that
     * already shares a canonical number (avoids split groups when only some rows resolve).
     */
    fun coalesceNumberBucketsIntoContact(
        grouped: LinkedHashMap<String, MutableList<CallLogEntity>>,
    ): LinkedHashMap<String, MutableList<CallLogEntity>> {
        if (grouped.isEmpty()) return grouped
        val contactKeys = grouped.keys.filter { CanonicalPhoneNumberResolver.isContactGroupKey(it) }
        if (contactKeys.isEmpty()) return grouped

        val digitsByContactKey = contactKeys.associateWith { key ->
            grouped[key].orEmpty().map {
                CanonicalPhoneNumberResolver.canonicalDigits(it.normalizedNumber, it.phoneNumber)
            }.filter { it.isNotEmpty() }.toSet()
        }

        val numberKeys = grouped.keys.filter { CanonicalPhoneNumberResolver.isNumberGroupKey(it) }.toList()
        for (numberKey in numberKeys) {
            val members = grouped[numberKey] ?: continue
            val numberDigits = members.map {
                CanonicalPhoneNumberResolver.canonicalDigits(it.normalizedNumber, it.phoneNumber)
            }.filter { it.isNotEmpty() }.toSet()
            if (numberDigits.isEmpty()) continue

            val matches = contactKeys.filter { contactKey ->
                val contactDigits = digitsByContactKey[contactKey].orEmpty()
                numberDigits.any { nd -> contactDigits.any { cd -> digitsOverlap(nd, cd) } }
            }
            if (matches.size != 1) continue
            val target = matches.single()
            grouped.getOrPut(target) { mutableListOf() }.addAll(members)
            grouped.remove(numberKey)
        }
        return grouped
    }

    private fun digitsOverlap(a: String, b: String): Boolean {
        if (a == b) return true
        val min = CanonicalPhoneNumberResolver.MIN_SUFFIX_MATCH_DIGITS
        if (a.length >= min && b.endsWith(a)) return true
        if (b.length >= min && a.endsWith(b)) return true
        return false
    }
}
