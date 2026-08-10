package com.goodwy.commons.providercache.display

/**
 * Groups affected by contact/call mutations that may require multi-group rebuild.
 */
data class AffectedRecentGroups(
    val oldGroupKeys: Set<String>,
    val newGroupKeys: Set<String>,
    val affectedNumbers: Set<String>,
    val affectedContactIds: Set<Long>,
) {
    val allGroupKeys: Set<String> get() = oldGroupKeys + newGroupKeys

    companion object {
        val EMPTY = AffectedRecentGroups(emptySet(), emptySet(), emptySet(), emptySet())
    }
}

object RecentGroupMutationPlanner {

    fun forContactDelete(
        deletedContactId: Long,
        phoneDigits: Collection<String>,
        currentGroupKeysForContact: Collection<String> = emptyList(),
    ): AffectedRecentGroups = AffectedRecentGroups(
        oldGroupKeys = buildSet {
            addAll(currentGroupKeysForContact)
            if (deletedContactId > 0L) add(CanonicalPhoneNumberResolver.contactGroupKey(deletedContactId))
        },
        newGroupKeys = phoneDigits.map {
            CanonicalPhoneNumberResolver.numberGroupKey(it.filter { ch -> ch.isDigit() }.ifEmpty { it })
        }.toSet(),
        affectedNumbers = phoneDigits.map { it.filter { ch -> ch.isDigit() }.ifEmpty { it } }.toSet(),
        affectedContactIds = setOf(deletedContactId),
    )

    fun forPhoneEdit(
        contactId: Long,
        oldDigits: String,
        newDigits: String,
    ): AffectedRecentGroups = forPhoneEditMulti(
        contactId = contactId,
        oldDigits = setOf(oldDigits.filter { it.isDigit() }.ifEmpty { oldDigits }),
        newDigits = setOf(newDigits.filter { it.isDigit() }.ifEmpty { newDigits }),
    )

    fun forPhoneEditMulti(
        contactId: Long,
        oldDigits: Collection<String>,
        newDigits: Collection<String>,
    ): AffectedRecentGroups {
        val oldCanonical = oldDigits.map { it.filter { ch -> ch.isDigit() }.ifEmpty { it } }.filter { it.isNotEmpty() }.toSet()
        val newCanonical = newDigits.map { it.filter { ch -> ch.isDigit() }.ifEmpty { it } }.filter { it.isNotEmpty() }.toSet()
        val union = oldCanonical + newCanonical
        return AffectedRecentGroups(
            oldGroupKeys = buildSet {
                add(CanonicalPhoneNumberResolver.contactGroupKey(contactId))
                oldCanonical.forEach { add(CanonicalPhoneNumberResolver.numberGroupKey(it)) }
            },
            newGroupKeys = buildSet {
                add(CanonicalPhoneNumberResolver.contactGroupKey(contactId))
                newCanonical.forEach { add(CanonicalPhoneNumberResolver.numberGroupKey(it)) }
            },
            affectedNumbers = union,
            affectedContactIds = setOf(contactId),
        )
    }

    fun forContactMerge(
        survivingContactId: Long,
        mergedContactIds: Collection<Long>,
        sharedNumbers: Collection<String>,
    ): AffectedRecentGroups = AffectedRecentGroups(
        oldGroupKeys = buildSet {
            mergedContactIds.forEach { add(CanonicalPhoneNumberResolver.contactGroupKey(it)) }
            add(CanonicalPhoneNumberResolver.contactGroupKey(survivingContactId))
            sharedNumbers.forEach { digits ->
                add(CanonicalPhoneNumberResolver.numberGroupKey(digits))
            }
        },
        newGroupKeys = setOf(CanonicalPhoneNumberResolver.contactGroupKey(survivingContactId)),
        affectedNumbers = sharedNumbers.toSet(),
        affectedContactIds = (mergedContactIds + survivingContactId).toSet(),
    )

    fun forContactSplit(
        oldContactId: Long,
        newContactIds: Collection<Long>,
        sharedNumbers: Collection<String>,
    ): AffectedRecentGroups = AffectedRecentGroups(
        oldGroupKeys = setOf(CanonicalPhoneNumberResolver.contactGroupKey(oldContactId)),
        newGroupKeys = buildSet {
            newContactIds.forEach { add(CanonicalPhoneNumberResolver.contactGroupKey(it)) }
            sharedNumbers.forEach { add(CanonicalPhoneNumberResolver.numberGroupKey(it)) }
        },
        affectedNumbers = sharedNumbers.toSet(),
        affectedContactIds = (newContactIds + oldContactId).toSet(),
    )

    fun forSharedNumberOwnerChange(
        numberDigits: String,
        oldOwnerContactId: Long?,
        newOwnerContactId: Long?,
    ): AffectedRecentGroups = AffectedRecentGroups(
        oldGroupKeys = buildSet {
            oldOwnerContactId?.let { add(CanonicalPhoneNumberResolver.contactGroupKey(it)) }
            add(CanonicalPhoneNumberResolver.numberGroupKey(numberDigits))
        },
        newGroupKeys = buildSet {
            newOwnerContactId?.let { add(CanonicalPhoneNumberResolver.contactGroupKey(it)) }
            add(CanonicalPhoneNumberResolver.numberGroupKey(numberDigits))
        },
        affectedNumbers = setOf(numberDigits),
        affectedContactIds = buildSet {
            oldOwnerContactId?.let { add(it) }
            newOwnerContactId?.let { add(it) }
        },
    )

    fun forCallIds(
        callIds: Collection<Int>,
        mode: RecentGroupingMode,
        allCalls: List<com.goodwy.commons.providercache.entities.CallLogEntity>,
        context: RecentGroupIdentityResolver.Context,
    ): AffectedRecentGroups {
        val affectedCalls = allCalls.filter { it.callId in callIds }
        val keys = affectedCalls.map {
            RecentGroupIdentityResolver.resolve(it, mode, context).groupKey
        }.toSet()
        val numbers = affectedCalls.map {
            CanonicalPhoneNumberResolver.canonicalDigits(it.normalizedNumber, it.phoneNumber)
        }.toSet()
        val contactIds = affectedCalls.mapNotNull { it.contactID?.toLong() }.toSet()
        return AffectedRecentGroups(
            oldGroupKeys = keys,
            newGroupKeys = keys,
            affectedNumbers = numbers,
            affectedContactIds = contactIds,
        )
    }

    /**
     * New call-log rows after contact backfill may resolve to different keys than existing
     * display/relational rows (e.g. `number:` → `contact:`). [existingGroupKeys] must include
     * every prior key that should be deleted when rewriting membership.
     */
    fun forCallLogInsert(
        newGroupKeys: Set<String>,
        existingGroupKeys: Set<String>,
        affectedNumbers: Set<String>,
        affectedContactIds: Set<Long>,
        mode: RecentGroupingMode,
    ): AffectedRecentGroups {
        val remapNumberKeys = if (mode == RecentGroupingMode.BY_CONTACT) {
            affectedNumbers.map { CanonicalPhoneNumberResolver.numberGroupKey(it) }.toSet()
        } else {
            emptySet()
        }
        return AffectedRecentGroups(
            oldGroupKeys = existingGroupKeys + remapNumberKeys,
            newGroupKeys = newGroupKeys,
            affectedNumbers = affectedNumbers,
            affectedContactIds = affectedContactIds,
        )
    }

    /**
     * Merges live display/relational keys and call-log digit forms into a planned mutation set.
     *
     * Contact phone-edit plans keys from phone-index digits (often E.164). Existing recents rows
     * may still use dialed/local digit forms (`number:010…`). Without this expansion,
     * [com.goodwy.commons.providercache.transaction.ProviderCacheTransactions.replaceAffectedRecentGroups]
     * leaves orphan `number:` groups after remapping to `contact:<id>`.
     */
    fun expandWithExisting(
        base: AffectedRecentGroups,
        existingGroupKeys: Set<String>,
        additionalNumbers: Collection<String>,
    ): AffectedRecentGroups {
        val extraNumbers = additionalNumbers
            .map { it.filter { ch -> ch.isDigit() }.ifEmpty { it } }
            .filter { it.isNotEmpty() }
            .toSet()
        val numberKeysFromExtra = extraNumbers
            .map { CanonicalPhoneNumberResolver.numberGroupKey(it) }
            .toSet()
        return base.copy(
            oldGroupKeys = base.oldGroupKeys + existingGroupKeys + numberKeysFromExtra,
            affectedNumbers = base.affectedNumbers + extraNumbers,
        )
    }
}
