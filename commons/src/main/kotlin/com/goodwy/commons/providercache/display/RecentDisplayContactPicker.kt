package com.goodwy.commons.providercache.display

import com.goodwy.commons.helpers.AvatarIdentityResolver
import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.entities.ContactDisplayCacheEntity
import com.goodwy.commons.providercache.entities.ContactPhoneIndexEntity
import com.goodwy.commons.providercache.entities.ContactSummaryEntity

/**
 * Picks the best visible contact for a phone [identityKey] (digits-only group key).
 * Contact id and name are display metadata only — grouping stays on phone identity.
 *
 * Deterministic tie-break order (never depends on provider/SQL/HashMap order):
 * 1. visible/allowed contact
 * 2. current valid call contact_id when still eligible
 * 3. starred
 * 4. has valid photo
 * 5. non-blank display name
 * 6. lowest aggregate contact ID
 */
object RecentDisplayContactPicker {

    fun isVisibleForSnapshot(
        summary: ContactSummaryEntity,
        display: ContactDisplayCacheEntity?,
    ): Boolean = isVisible(summary, display)

    fun hasPhotoForSnapshot(
        summary: ContactSummaryEntity,
        display: ContactDisplayCacheEntity?,
    ): Boolean = hasPhoto(summary, display)

    /** Explicit comparator for tests — requires score maps passed per comparison batch. */
    fun comparatorFor(
        summariesById: Map<Int, ContactSummaryEntity>,
        displayByContactId: Map<Int, ContactDisplayCacheEntity>,
    ): Comparator<Int> =
        compareByDescending<Int> { rank(it, summariesById, displayByContactId).visibleScore }
            .thenByDescending { rank(it, summariesById, displayByContactId).preferredScore }
            .thenByDescending { rank(it, summariesById, displayByContactId).starredScore }
            .thenByDescending { rank(it, summariesById, displayByContactId).photoScore }
            .thenByDescending { rank(it, summariesById, displayByContactId).nameScore }
            .thenBy { it }

    private var preferredContactIdForBatch: Int? = null

    fun withPreferredContactId(preferredContactId: Int?, block: () -> ContactReplacementInfo?): ContactReplacementInfo? {
        val previous = preferredContactIdForBatch
        preferredContactIdForBatch = preferredContactId
        return try {
            block()
        } finally {
            preferredContactIdForBatch = previous
        }
    }

    private var scoreContext: ScoreContext? = null

    private data class ScoreContext(
        val summariesById: Map<Int, ContactSummaryEntity>,
        val displayByContactId: Map<Int, ContactDisplayCacheEntity>,
    )

    private data class ContactScoreRank(
        val visibleScore: Int,
        val preferredScore: Int,
        val starredScore: Int,
        val photoScore: Int,
        val nameScore: Int,
    )

    private fun contactScoreRank(contactId: Int): ContactScoreRank {
        val ctx = scoreContext ?: return ContactScoreRank(0, 0, 0, 0, 0)
        return rank(contactId, ctx.summariesById, ctx.displayByContactId)
    }

    private fun rank(
        contactId: Int,
        summariesById: Map<Int, ContactSummaryEntity>,
        displayByContactId: Map<Int, ContactDisplayCacheEntity>,
    ): ContactScoreRank {
        val summary = summariesById[contactId]
        val display = displayByContactId[contactId]
        return ContactScoreRank(
            visibleScore = if (summary != null && isVisible(summary, display)) 1 else 0,
            preferredScore = if (preferredContactIdForBatch != null && contactId == preferredContactIdForBatch) 1 else 0,
            starredScore = if ((display?.starred ?: 0) > 0) 1 else 0,
            photoScore = if (summary != null && hasPhoto(summary, display)) 1 else 0,
            nameScore = if (summary != null && bestDisplayName(summary, display).isNotBlank()) 1 else 0,
        )
    }

    suspend fun resolveForIdentityKeys(
        database: ProviderCacheDatabase,
        identityKeys: Collection<String>,
        excludeContactIds: Set<Int> = emptySet(),
    ): Map<String, ContactReplacementInfo?> {
        if (identityKeys.isEmpty()) return emptyMap()
        val keys = identityKeys.map { it.filter { ch -> ch.isDigit() }.ifEmpty { it } }.distinct()
        if (keys.isEmpty()) return emptyMap()

        val indexRows = database.contactPhoneIndexDao().findByPhoneDigits(keys)
        if (indexRows.isEmpty()) {
            return keys.associateWith { null }
        }

        val contactIds = indexRows.map { it.contactId }
            .filter { it > 0 && it !in excludeContactIds }
            .distinct()
        if (contactIds.isEmpty()) {
            return keys.associateWith { null }
        }

        val summariesById = database.contactDao()
            .getSummariesByIds(contactIds)
            .associateBy { it.contactId }
        val displayByContactId = database.contactDisplayCacheDao()
            .getByContactIds(contactIds)
            .groupBy { it.contactId }
            .mapValues { (_, rows) -> rows.minByOrNull { it.rawId } ?: rows.first() }

        AvatarIdentityResolver.registerFromDisplayEntities(displayByContactId.values)

        return keys.associateWith { identityKey ->
            val candidates = indexRows.filter { row ->
                row.contactId !in excludeContactIds && phoneIndexMatchesIdentity(row, identityKey)
            }
            pickBest(candidates, summariesById, displayByContactId)
        }
    }

    fun pickBest(
        indexRows: List<ContactPhoneIndexEntity>,
        summariesById: Map<Int, ContactSummaryEntity>,
        displayByContactId: Map<Int, ContactDisplayCacheEntity>,
        preferredContactId: Int? = null,
    ): ContactReplacementInfo? {
        val previous = scoreContext
        scoreContext = ScoreContext(summariesById, displayByContactId)
        try {
            val contactIds = indexRows.map { it.contactId }.filter { it > 0 }.distinct().sorted()
            if (contactIds.isEmpty()) return null

            val preferredEligible = preferredContactId?.takeIf { id ->
                id in contactIds &&
                    summariesById.containsKey(id) &&
                    isVisible(summariesById[id]!!, displayByContactId[id])
            }
            val bestId = preferredEligible
                ?: contactIds.minWithOrNull(comparatorFor(summariesById, displayByContactId))
                ?: return null
            val summary = summariesById[bestId] ?: return null
            val display = displayByContactId[bestId]
            return ContactReplacementInfo(
                contactId = bestId,
                lookupKey = summary.lookupKey,
                displayName = bestDisplayName(summary, display),
                photoThumbUri = bestPhotoUri(summary, display),
                avatarInitials = display?.avatarInitials.orEmpty(),
            )
        } finally {
            scoreContext = previous
        }
    }

    private fun isVisible(
        summary: ContactSummaryEntity,
        display: ContactDisplayCacheEntity?,
    ): Boolean {
        if (isHiddenAccount(summary.accountName, summary.accountType, display?.source.orEmpty())) {
            return false
        }
        return true
    }

    private fun hasPhoto(
        summary: ContactSummaryEntity,
        display: ContactDisplayCacheEntity?,
    ): Boolean {
        if ((display?.hasValidPhotoUri ?: 0) > 0) return true
        if (display?.photoThumbUri?.isNotBlank() == true) return true
        return summary.photoThumbnailUri.isNotBlank()
    }

    private fun bestDisplayName(
        summary: ContactSummaryEntity,
        display: ContactDisplayCacheEntity?,
    ): String = summary.displayName.ifBlank { display?.displayName.orEmpty() }

    private fun bestPhotoUri(
        summary: ContactSummaryEntity,
        display: ContactDisplayCacheEntity?,
    ): String = display?.photoThumbUri?.ifBlank { summary.photoThumbnailUri }
        ?: summary.photoThumbnailUri

    private fun phoneIndexMatchesIdentity(
        row: ContactPhoneIndexEntity,
        identityKey: String,
    ): Boolean {
        val keyDigits = identityKey.filter { it.isDigit() }.ifEmpty { identityKey }
        if (keyDigits.isEmpty()) return false
        val rowDigits = listOf(row.phoneDigits, row.digits, row.normalizedNumber.filter { it.isDigit() })
            .filter { it.isNotEmpty() }
        if (rowDigits.any { it == keyDigits }) return true
        val rowNorm = row.normalizedNumber.filter { it.isDigit() }
        return rowNorm.isNotEmpty() && rowNorm == keyDigits
    }

    private fun isHiddenAccount(accountName: String, accountType: String, source: String): Boolean {
        if (accountName.contains("hidden", ignoreCase = true)) return true
        if (accountType.contains("hidden", ignoreCase = true)) return true
        if (source.contains("hidden", ignoreCase = true)) return true
        return false
    }
}
