package com.goodwy.commons.providercache.display



import android.content.Context

import com.goodwy.commons.extensions.baseConfig

import com.goodwy.commons.extensions.getVisibleContactSources

import com.goodwy.commons.helpers.ContactListPhotoUriResolver

import com.goodwy.commons.helpers.ContactsHelper

import com.goodwy.commons.helpers.isSimOrPhoneStorageAccount

import com.goodwy.commons.models.contacts.Contact

import com.goodwy.commons.providercache.entities.ContactSummaryEntity

import com.goodwy.commons.providercache.ProviderCacheDatabase

import com.goodwy.commons.providercache.datasource.ContactsMetadataLoader

import com.goodwy.commons.providercache.debug.ProviderCacheDebugLogger

import com.goodwy.commons.providercache.filter.ContactDuplicateMergeFilter

import com.goodwy.commons.providercache.filter.ContactPagingMapper

import com.goodwy.commons.providercache.filter.ContactSourceAccountFilter

import kotlinx.coroutines.CoroutineScope

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.SupervisorJob

import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext

import java.text.Collator

import java.util.Locale



/**

 * Builds [contact_display_cache] from provider-cache summaries with filter/merge applied once

 * in the background. Supports incremental upserts and transactional full swaps.

 *

 * [ContactDisplayRebuildMode.FAST] skips global duplicate merge so sync stays fast.

 * [ContactDisplayRebuildMode.ACCURATE] runs full duplicate merge when the setting is enabled.

 */

class ContactDisplayCacheBuilder(

    private val context: Context,

    private val database: ProviderCacheDatabase,

    private val secureFilterProvider: suspend (List<Contact>) -> List<Contact>,

    private val metadataBackfillScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),

) {

    private val contactDao get() = database.contactDao()

    private val displayDao get() = database.contactDisplayCacheDao()

    private val metadataLoader = ContactsMetadataLoader(context)

    @Volatile
    private var cachedVisibleSources: Set<String>? = null

    @Volatile
    private var cachedVisibleSourcesAtMs: Long = 0L

    private fun visibleContactSources(): Set<String> {
        val now = System.currentTimeMillis()
        val cached = cachedVisibleSources
        if (cached != null && now - cachedVisibleSourcesAtMs < VISIBLE_SOURCES_CACHE_MS) {
            return cached
        }
        val fresh = context.getVisibleContactSources().toHashSet()
        cachedVisibleSources = fresh
        cachedVisibleSourcesAtMs = now
        return fresh
    }

    /** Same phone/SIM gate as [ContactsHelper] list loads — Room summaries include all accounts. */
    private fun phoneOrSimSummaries(summaries: List<ContactSummaryEntity>): List<ContactSummaryEntity> =
        summaries.filter { isSimOrPhoneStorageAccount(it.accountName, it.accountType) }

    companion object {
        private const val VISIBLE_SOURCES_CACHE_MS = 60_000L
    }



    suspend fun rebuild(

        reason: DisplayCacheRebuildReason = DisplayCacheRebuildReason.CONTACT_SYNC_COMPLETED,

        changedContactIds: Set<Int> = emptySet(),

        deletedContactIds: Set<Int> = emptySet(),

        forceFull: Boolean = false,

        mode: ContactDisplayRebuildMode = reason.defaultContactRebuildMode,

    ) = withContext(Dispatchers.IO) {

        // Drop stale filter snapshot so SOURCE_FILTER_CHANGED (and any rebuild) sees current prefs.
        cachedVisibleSources = null
        cachedVisibleSourcesAtMs = 0L

        val cacheEmpty = displayDao.getCount() == 0

        val effectiveReason = if (cacheEmpty) DisplayCacheRebuildReason.COLD_EMPTY_CACHE else reason

        val effectiveMode = if (cacheEmpty) ContactDisplayRebuildMode.FAST else mode

        ProviderCacheDebugLogger.logDisplayCacheRebuildStart(

            target = "contacts",

            reason = effectiveReason,

            changedIds = changedContactIds.size,

            deletedIds = deletedContactIds.size,

            forceFull = forceFull,

            rebuildMode = effectiveMode,

        )

        val totalStart = System.currentTimeMillis()

        // SECURE_MODE / source-filter / forceFull must replace the table: upsertAndPrune alone
        // cannot swap "normal list" ↔ "private/secure list" when the filtered set is disjoint,
        // and historically no-op'd on an empty filter result (leaving the prior list on screen).
        val replaceAll = cacheEmpty || forceFull || effectiveReason.requiresFullContactRebuild

        val metrics = when {

            replaceAll ->

                rebuildFullSwap(effectiveReason, effectiveMode, allowClear = true)

            changedContactIds.isNotEmpty() || deletedContactIds.isNotEmpty() ->

                rebuildIncremental(effectiveReason, effectiveMode, changedContactIds, deletedContactIds)

            else -> rebuildFullSwap(effectiveReason, effectiveMode, allowClear = false)

        }

        ProviderCacheDebugLogger.logDisplayCacheRebuildEnd(

            target = "contacts",

            metrics = metrics.copy(totalMs = System.currentTimeMillis() - totalStart),

        )

    }



    private suspend fun rebuildFullSwap(

        reason: DisplayCacheRebuildReason,

        rebuildMode: ContactDisplayRebuildMode,

        allowClear: Boolean,

    ): DisplayCacheRebuildMetrics {

        val rawStart = System.currentTimeMillis()

        val summaries = contactDao.getAllSummaries()

        val rawMs = System.currentTimeMillis() - rawStart

        if (summaries.isEmpty()) {
            displayDao.clearAll()
            return DisplayCacheRebuildMetrics(

                reason = reason,

                mode = if (allowClear) "full_cold" else "full_swap",

                rebuildMode = rebuildMode,

                rawQueryMs = rawMs,

            )

        }

        val legacyCount = summaries.count { it.primaryRawId == 0 && it.accountName.isEmpty() }

        if (legacyCount > 0 && displayDao.getCount() == 0) {

            scheduleLegacyMetadataBackfill(summaries.map { it.contactId })

        } else if (legacyCount > 0) {

            ProviderCacheDebugLogger.log(

                "displayCache legacyMetadataBackfillNeeded=$legacyCount (deferred, cache non-empty)",

            )

        }

        var contacts = phoneOrSimSummaries(summaries).map { ContactPagingMapper.entityToContact(it) }

        contacts = resolveListPhotos(contacts)

        val filterStart = System.currentTimeMillis()

        contacts = secureFilterProvider(contacts)

        val visibleSources = visibleContactSources()

        contacts = ContactSourceAccountFilter.filter(contacts, visibleSources)

        contacts = applyPhoneNumberFilter(contacts)

        val filterMs = System.currentTimeMillis() - filterStart

        val mergeStart = System.currentTimeMillis()

        val mergeApplied = rebuildMode == ContactDisplayRebuildMode.ACCURATE &&

            context.baseConfig.mergeDuplicateContacts

        if (mergeApplied) {

            contacts = ContactDuplicateMergeFilter.mergeAll(contacts, visibleSources, mergeEnabled = true)

        }

        val mergeMs = System.currentTimeMillis() - mergeStart

        val sortStart = System.currentTimeMillis()

        val collator = Collator.getInstance(Locale.getDefault())

        // Tie-break on raw id, or this sort is not total and the ranking is not reproducible.
        //
        // The collator returns 0 for contacts whose display names are equal — duplicates of the
        // same person, and every nameless contact, which all collapse to the same key. sortedWith
        // is stable, so those kept whatever relative order the *input* list happened to have, and
        // that order varies between syncs. Each of them still received a distinct display_order
        // from mapIndexed below, so the ranking changed even though nothing about the contacts
        // did, and the list reordered on its own after a sync.
        //
        // raw id specifically: contact.id is what the mapper writes as rawId, and the main list
        // reads `ORDER BY display_order ASC, raw_id ASC`. Using the same tiebreaker means the rank
        // assignment and the query agree instead of resolving equal names two different ways.
        contacts = contacts.sortedWith { a, b ->

            val byName = collator.compare(a.getNameToDisplay(), b.getNameToDisplay())

            if (byName != 0) byName else a.id.compareTo(b.id)

        }

        val sortMs = System.currentTimeMillis() - sortStart

        val rows = contacts.mapIndexed { index, contact ->
            ContactDisplayCacheMapper.fromContact(context, contact, displayOrder = index)
        }

        val writeStart = System.currentTimeMillis()

        if (allowClear) {

            displayDao.replaceAllCold(rows)

        } else {

            displayDao.upsertAndPrune(rows)

        }

        val writeMs = System.currentTimeMillis() - writeStart

        val modeLabel = when {

            allowClear -> "full_cold"

            mergeApplied -> "full_swap_accurate"

            else -> "full_swap_fast"

        }

        return DisplayCacheRebuildMetrics(

            reason = reason,

            mode = modeLabel,

            rebuildMode = rebuildMode,

            rawQueryMs = rawMs,

            filterMs = filterMs,

            duplicateMergeMs = mergeMs,

            sortMs = sortMs,

            dbWriteMs = writeMs,

            rowsUpdated = rows.size,

            legacyMetadataBackfillNeeded = legacyCount,

        )

    }



    private suspend fun rebuildIncremental(

        reason: DisplayCacheRebuildReason,

        rebuildMode: ContactDisplayRebuildMode,

        changedContactIds: Set<Int>,

        deletedContactIds: Set<Int>,

    ): DisplayCacheRebuildMetrics {

        var deleted = 0

        if (deletedContactIds.isNotEmpty()) {

            val writeStart = System.currentTimeMillis()

            deletedContactIds.chunked(200).forEach { chunk ->

                displayDao.deleteByContactIds(chunk)

                deleted += chunk.size

            }

            ProviderCacheDebugLogger.log(

                "displayCache incrementalDelete deleted=$deleted writeMs=${System.currentTimeMillis() - writeStart}",

            )

        }

        if (changedContactIds.isEmpty()) {

            return DisplayCacheRebuildMetrics(

                reason = reason,

                mode = "incremental_delete_only",

                rebuildMode = ContactDisplayRebuildMode.FAST,

                rowsDeleted = deleted,

            )

        }

        val rawStart = System.currentTimeMillis()

        val summaries = contactDao.getSummariesByIds(changedContactIds.toList())

        val rawMs = System.currentTimeMillis() - rawStart

        val legacyCount = summaries.count { it.primaryRawId == 0 && it.accountName.isEmpty() }

        if (legacyCount > 0) {

            scheduleLegacyMetadataBackfill(summaries.map { it.contactId })

        }

        var contacts = phoneOrSimSummaries(summaries).map { ContactPagingMapper.entityToContact(it) }

        contacts = resolveListPhotos(contacts)

        val filterStart = System.currentTimeMillis()

        contacts = secureFilterProvider(contacts)

        val visibleSources = visibleContactSources()

        contacts = ContactSourceAccountFilter.filter(contacts, visibleSources)

        contacts = applyPhoneNumberFilter(contacts)

        val filterMs = System.currentTimeMillis() - filterStart

        val existingOrder = displayDao.getByContactIds(changedContactIds.toList()).associateBy { it.rawId }

        val mappedRows = contacts.map { ContactDisplayCacheMapper.fromContact(context, it) }

        // display_order is a dense rank over the whole table, and only the collator sort in
        // rebuildFullSwap can assign it. Nothing here can place a row in that ranking: a new
        // contact has no earlier rank to inherit and would keep the mapper's default 0, pinning
        // it above "A", and a renamed one would keep the rank its old name earned. Either way the
        // list sorts wrong until something else forces a full rebuild. So when a change can move
        // a row, re-rank the table instead of writing a rank we know to be wrong.
        val ranksAreStale = mappedRows.any { row ->
            val prev = existingOrder[row.rawId]
            prev == null || prev.sortKey != row.sortKey
        }

        if (ranksAreStale) {
            return rebuildFullSwap(reason, rebuildMode, allowClear = false)
        }

        // Every row is known here, and none of them moved -- keep the ranks they already hold.
        val rows = mappedRows.map { row ->
            row.copy(displayOrder = existingOrder.getValue(row.rawId).displayOrder)
        }

        val writeStart = System.currentTimeMillis()

        changedContactIds.chunked(200).forEach { chunk ->

            displayDao.deleteByContactIds(chunk)

        }

        if (rows.isNotEmpty()) {

            displayDao.insertAll(rows)

        }

        val writeMs = System.currentTimeMillis() - writeStart

        return DisplayCacheRebuildMetrics(

            reason = reason,

            mode = "incremental_upsert",

            rebuildMode = ContactDisplayRebuildMode.FAST,

            rawQueryMs = rawMs,

            filterMs = filterMs,

            dbWriteMs = writeMs,

            rowsUpdated = rows.size,

            rowsDeleted = deleted + (changedContactIds.size - rows.size),

            legacyMetadataBackfillNeeded = legacyCount,

        )

    }




    /**
     * Applies the "show only contacts with phone numbers" setting to the display cache.
     *
     * The setting was already honoured by [ContactsProviderDataSource], which appends
     * `HAS_PHONE_NUMBER = 1` to its provider queries — but that decides what enters the *raw*
     * mirror, and it reads the flag when the data source is constructed. Turning the setting on
     * after a sync therefore changed nothing anyone could see: the numberless contacts were
     * already in Room, the display cache is rebuilt from Room, and nothing on that path filtered
     * them. The list is served from the display cache, so the setting has to be applied here too.
     *
     * Deliberately alongside [ContactSourceAccountFilter] rather than upstream: both answer the
     * same kind of question — which contacts may appear — and both must run on the full-swap and
     * incremental paths alike, or a rebuild would quietly reinstate what the other removed.
     */
    private fun applyPhoneNumberFilter(contacts: List<Contact>): List<Contact> {
        if (!context.baseConfig.showOnlyContactsWithNumbers) return contacts
        return contacts.filter { it.phoneNumbers.isNotEmpty() }
    }

    private fun resolveListPhotos(contacts: List<Contact>): List<Contact> {
        if (!com.goodwy.commons.providercache.startup.StartupPhotoBackfillGate.allowProviderPhotoProbe()) {
            return contacts
        }
        if (contacts.none { it.thumbnailUri.isEmpty() }) return contacts
        val helper = ContactsHelper(context)
        return contacts.map { contact ->
            if (contact.thumbnailUri.isNotEmpty()) return@map contact
            val resolved = ContactListPhotoUriResolver.resolveForList(
                helper = helper,
                contactId = contact.contactId,
                rawContactId = contact.id,
                providerThumbnailUri = contact.thumbnailUri,
            )
            if (resolved.isEmpty()) contact
            else contact.apply {
                thumbnailUri = resolved
                photoUri = resolved
            }
        }
    }



    private fun scheduleLegacyMetadataBackfill(contactIds: List<Int>) {

        if (contactIds.isEmpty()) return

        metadataBackfillScope.launch(Dispatchers.IO) {

            contactIds.chunked(200).forEach { chunk ->

                val summaries = contactDao.getSummariesByIds(chunk)

                val needsMeta = summaries.filter { it.primaryRawId == 0 && it.accountName.isEmpty() }

                if (needsMeta.isEmpty()) return@forEach

                val meta = metadataLoader.loadForSummaries(

                    needsMeta.map { s ->

                        com.goodwy.commons.providercache.model.ContactSummary(

                            contactId = s.contactId,

                            lookupKey = s.lookupKey,

                            displayName = s.displayName,

                            photoThumbnailUri = s.photoThumbnailUri,

                            hasPhoneNumber = s.hasPhoneNumber,

                            lastUpdatedTimestamp = s.lastUpdatedTimestamp,

                        )

                    },

                )

                if (meta.isNotEmpty()) {

                    contactDao.insertSummaries(

                        needsMeta.map { s ->

                            val m = meta[s.contactId]

                            if (m != null) ContactPagingMapper.entityToSummaryEntity(

                                com.goodwy.commons.providercache.model.ContactSummary(

                                    contactId = s.contactId,

                                    lookupKey = s.lookupKey,

                                    displayName = s.displayName,

                                    photoThumbnailUri = s.photoThumbnailUri,

                                    hasPhoneNumber = s.hasPhoneNumber,

                                    lastUpdatedTimestamp = s.lastUpdatedTimestamp,

                                ),

                                m,

                            ) else s

                        },

                    )

                }

            }

        }

    }

}


