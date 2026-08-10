package com.goodwy.commons.providercache.display

/**
 * Planned recents mutation strategy for contact-driven updates.
 */
sealed class RecentMutationPlan {

    abstract val reason: String

    data class DisplayOnlyPatch(
        override val reason: String,
        val contactId: Int,
    ) : RecentMutationPlan()

    data class SingleGroupRebuild(
        override val reason: String,
        val groupKey: String,
    ) : RecentMutationPlan()

    data class MultiGroupRebuild(
        override val reason: String,
        val affected: AffectedRecentGroups,
    ) : RecentMutationPlan()

    data class FullModeRepair(
        override val reason: String,
    ) : RecentMutationPlan()

    val planKind: String
        get() = when (this) {
            is DisplayOnlyPatch -> "DISPLAY_ONLY"
            is SingleGroupRebuild -> "SINGLE_GROUP"
            is MultiGroupRebuild -> "MULTI_GROUP"
            is FullModeRepair -> "FULL_REPAIR"
        }
}

data class ContactDisplayChangeResult(
    val updatedCallIds: List<Int>,
    val needsFullReload: Boolean,
) {
    companion object {
        val EMPTY = ContactDisplayChangeResult(emptyList(), false)
        fun fullReload() = ContactDisplayChangeResult(emptyList(), true)
    }
}

object RecentMutationPlanResolver {

    fun forContactDisplayChange(change: ContactDisplayChanged): RecentMutationPlan {
        val oldDigits = canonicalDigits(change.oldPhoneDigits + change.oldNormalizedNumbers)
        val newDigits = canonicalDigits(change.phoneDigits + change.normalizedNumbers)
        if (oldDigits != newDigits) {
            return RecentMutationPlan.MultiGroupRebuild(
                reason = "CONTACT_PHONE_EDIT",
                affected = RecentGroupMutationPlanner.forPhoneEditMulti(
                    contactId = change.contactId.toLong(),
                    oldDigits = oldDigits,
                    newDigits = newDigits,
                ),
            )
        }
        if (change.lookupKeyChanged) {
            return RecentMutationPlan.FullModeRepair(reason = "CONTACT_LOOKUP_KEY_CHANGED")
        }
        return RecentMutationPlan.DisplayOnlyPatch(
            reason = "CONTACT_DISPLAY_METADATA",
            contactId = change.contactId,
        )
    }

    fun forContactDelete(
        deleted: ContactDisplayDeleted,
        oldGroupKeys: Collection<String>,
    ): RecentMutationPlan = RecentMutationPlan.MultiGroupRebuild(
        reason = "CONTACT_DELETE",
        affected = RecentGroupMutationPlanner.forContactDelete(
            deletedContactId = deleted.contactId.toLong(),
            phoneDigits = deleted.phoneDigits + deleted.normalizedNumbers,
            currentGroupKeysForContact = oldGroupKeys,
        ),
    )

    fun forContactDeleteBatch(
        deleted: List<ContactDisplayDeleted>,
        oldGroupKeysByContact: Map<Int, Set<String>>,
    ): RecentMutationPlan {
        if (deleted.isEmpty()) {
            return RecentMutationPlan.FullModeRepair(reason = "CONTACT_DELETE_EMPTY")
        }
        if (deleted.size == 1) {
            val contact = deleted.first()
            return forContactDelete(contact, oldGroupKeysByContact[contact.contactId].orEmpty())
        }
        val allNumberList = deleted.flatMap { it.phoneDigits + it.normalizedNumbers }
            .map { canonicalDigit(it) }
            .filter { it.isNotEmpty() }
        val allNumbers = allNumberList.toSet()
        val sharedNumbers = allNumberList.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        if (sharedNumbers.isNotEmpty()) {
            val mergedIds = deleted.map { it.contactId.toLong() }
            val survivingId = mergedIds.maxOrNull() ?: mergedIds.first()
            return RecentMutationPlan.MultiGroupRebuild(
                reason = "CONTACT_MERGE_INFERRED",
                affected = RecentGroupMutationPlanner.forContactMerge(
                    survivingContactId = survivingId,
                    mergedContactIds = mergedIds,
                    sharedNumbers = sharedNumbers.toList(),
                ),
            )
        }
        val affected = AffectedRecentGroups(
            oldGroupKeys = oldGroupKeysByContact.values.flatten().toSet(),
            newGroupKeys = deleted.flatMap { contact ->
                canonicalDigits(contact.phoneDigits + contact.normalizedNumbers)
                    .map { CanonicalPhoneNumberResolver.numberGroupKey(it) }
            }.toSet(),
            affectedNumbers = allNumbers,
            affectedContactIds = deleted.map { it.contactId.toLong() }.toSet(),
        )
        return RecentMutationPlan.MultiGroupRebuild(
            reason = "CONTACT_DELETE_BATCH",
            affected = affected,
        )
    }

    fun forSharedNumberOwnerChange(
        numberDigits: String,
        oldOwnerContactId: Long?,
        newOwnerContactId: Long?,
    ): RecentMutationPlan = RecentMutationPlan.MultiGroupRebuild(
        reason = "SHARED_NUMBER_OWNER_CHANGE",
        affected = RecentGroupMutationPlanner.forSharedNumberOwnerChange(
            numberDigits = canonicalDigit(numberDigits),
            oldOwnerContactId = oldOwnerContactId,
            newOwnerContactId = newOwnerContactId,
        ),
    )

    private fun canonicalDigits(values: Collection<String>): Set<String> =
        values.map { canonicalDigit(it) }.filter { it.isNotEmpty() }.toSet()

    private fun canonicalDigit(value: String): String =
        value.filter { it.isDigit() }.ifEmpty { value }
}
