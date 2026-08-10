package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity

/**
 * Loads immutable inputs for relational recents grouping before Room transactions.
 */
object RecentGroupBuildContextLoader {

    suspend fun load(database: ProviderCacheDatabase): RecentGroupIdentityResolver.Context {
        val phoneIndex = database.contactPhoneIndexDao().getAll()
        val summaries = database.contactDao().getAllSummaries()
        val summariesById = summaries.associateBy { it.contactId }
        val displayByContactId = database.contactDisplayCacheDao()
            .getByContactIds(summaries.map { it.contactId })
            .groupBy { it.contactId }
            .mapValues { (_, rows) -> rows.minByOrNull { it.rawId } ?: rows.first() }
        return RecentGroupIdentityResolver.Context(
            summariesById = summariesById,
            phoneIndexByDigits = RecentGroupIdentityResolver.buildPhoneIndexByDigits(phoneIndex),
            displayByContactId = displayByContactId,
        )
    }

    suspend fun loadCalls(
        database: ProviderCacheDatabase,
        limit: Int,
    ) = database.callLogDao().getFirstEntries(limit)
}

data class RecentGroupingReplacement(
    val mode: RecentGroupingMode,
    val relational: RecentGroupRelationalBuilder.BuildResult,
    val displayRows: List<RecentDisplayCacheEntity>,
    val displayLimit: Int,
    val mutationReason: String = "",
    val rowCount: Int = 0,
    val contentChecksum: Long = 0L,
    val mutationId: Long = 0L,
)
