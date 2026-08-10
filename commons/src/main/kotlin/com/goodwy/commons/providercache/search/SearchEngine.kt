package com.goodwy.commons.providercache.search

import android.content.Context
import android.util.Log
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.formatPhoneNumber
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.models.contacts.ContactDisplayBind
import com.goodwy.commons.models.PhoneNumber
import com.goodwy.commons.models.normalizeSingleDefaultPhoneFlag
import com.goodwy.commons.providercache.dao.ContactDisplayCacheDao
import com.goodwy.commons.providercache.dao.ContactPhoneIndexDao
import com.goodwy.commons.providercache.display.ContactDisplayListRow
import com.goodwy.commons.providercache.display.ContactDisplayLoadHelper
import com.goodwy.commons.providercache.entities.ContactDisplayCacheEntity
import com.goodwy.commons.providercache.entities.ContactPhoneIndexEntity
import com.goodwy.commons.providercache.filter.T9Mapper
import java.util.concurrent.ConcurrentHashMap

/**
 * Display-cache-only contact search with version-aware progressive paging and ranking.
 *
 * Provider → raw Room → contact_display_cache → SearchEngine → SearchPager → UI
 */
class SearchEngine(
    private val context: Context,
    private val displayCacheDao: ContactDisplayCacheDao,
    private val phoneIndexDao: ContactPhoneIndexDao,
) {
    private data class EngineSession(
        val query: String,
        val mode: SearchMode,
        val generation: Long,
        val displayCacheVersion: Long,
        val dialpadContext: DialpadSearchContext?,
        val rankedHits: MutableList<SearchRanking.RankedHit> = mutableListOf(),
        val seenContactIds: MutableSet<Int> = mutableSetOf(),
        var sqlOffset: Int = 0,
        var exhausted: Boolean = false,
    )

    private val sessions = ConcurrentHashMap<String, EngineSession>()

    suspend fun searchContactsPage(
        query: String,
        mode: SearchMode,
        limit: Int,
        offset: Int,
        displayCacheVersion: Long,
        generation: Long,
        dialpadContext: DialpadSearchContext? = null,
    ): SearchState {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || limit <= 0) {
            return SearchState.Page(emptyList(), topRankingReason = null)
        }
        if (displayCacheDao.getCount() == 0) {
            Log.d(TAG, "searchColdCache mode=$mode query=$trimmed action=SHOW_LOADING")
            return SearchState.LoadingCache
        }

        val sessionKey = sessionKey(trimmed, mode, displayCacheVersion, generation)
        val session = sessions.getOrPut(sessionKey) {
            Log.d(
                TAG,
                "searchSessionStart mode=$mode query=$trimmed generation=$generation cacheVersion=$displayCacheVersion",
            )
            EngineSession(
                query = trimmed,
                mode = mode,
                generation = generation,
                displayCacheVersion = displayCacheVersion,
                dialpadContext = dialpadContext,
            )
        }
        if (session.generation != generation || session.displayCacheVersion != displayCacheVersion) {
            sessions.remove(sessionKey)
            return searchContactsPage(trimmed, mode, limit, offset, displayCacheVersion, generation, dialpadContext)
        }

        Log.d(TAG, "searchPageStart mode=$mode query=$trimmed offset=$offset limit=$limit")
        ensureRanked(session, offset + limit)
        val pageHits = session.rankedHits.drop(offset).take(limit)
        if (pageHits.isEmpty()) {
            return SearchState.Page(emptyList(), topRankingReason = null)
        }
        val contacts = mapRankedPage(pageHits, trimmed, session)
        val topReason = pageHits.firstOrNull()?.reason
        Log.d(
            TAG,
            "searchPageApply rows=${contacts.size} generation=$generation cacheVersion=$displayCacheVersion topReason=$topReason",
        )
        if (topReason != null) {
            Log.d(TAG, "searchRanking topReason=$topReason")
        }
        return SearchState.Page(contacts, topRankingReason = topReason)
    }

    fun invalidateAllSessions() {
        sessions.clear()
    }

    fun invalidateSessionsNotMatching(displayCacheVersion: Long) {
        sessions.entries.removeIf { it.value.displayCacheVersion != displayCacheVersion }
    }

    fun discardPage(reason: String, query: String, generation: Long, displayCacheVersion: Long) {
        Log.d(TAG, "searchPageDiscard reason=$reason query=$query generation=$generation cacheVersion=$displayCacheVersion")
    }

    private suspend fun ensureRanked(session: EngineSession, targetCount: Int) {
        if (session.exhausted || session.rankedHits.size >= targetCount) return
        while (session.rankedHits.size < targetCount && !session.exhausted) {
            val batch = fetchCandidateRows(session)
            session.sqlOffset += batch.size
            if (batch.isEmpty()) {
                session.exhausted = true
                break
            }
            val contactIds = batch.map { it.contactId }.distinct()
            val entities = contactIds.chunked(200).flatMap { displayCacheDao.getByContactIds(it) }
                .associateBy { it.contactId }
            val phonesByContactId = contactIds.chunked(200)
                .flatMap { phoneIndexDao.getByContactIds(it) }
                .groupBy { it.contactId }
            for (row in batch) {
                if (!session.seenContactIds.add(row.contactId)) continue
                val entity = entities[row.contactId] ?: continue
                val phoneFields = buildPhoneFields(phonesByContactId[row.contactId].orEmpty())
                val hit = SearchRanking.rank(
                    entity = entity,
                    query = session.query,
                    mode = session.mode,
                    phoneDigitsJoined = phoneFields.phoneDigits,
                    displayNumberDigitsJoined = phoneFields.displayNumberDigits,
                    dialpadParams = session.dialpadContext,
                ) ?: continue
                insertRanked(session.rankedHits, hit)
            }
            if (batch.size < SQL_SCAN_BATCH) {
                session.exhausted = true
            }
        }
    }

    private fun insertRanked(list: MutableList<SearchRanking.RankedHit>, hit: SearchRanking.RankedHit) {
        var index = list.size
        for (i in list.indices) {
            if (SearchRanking.compare(hit, list[i]) < 0) {
                index = i
                break
            }
        }
        list.add(index, hit)
    }

    private suspend fun fetchCandidateRows(session: EngineSession): List<ContactDisplayListRow> {
        val query = session.query
        val kind = SearchMatchRules.classifyQuery(query)
        return when (session.mode) {
            SearchMode.CONTACTS_TAB, SearchMode.TOOLBAR -> fetchToolbarCandidates(query, kind, session.sqlOffset)
            SearchMode.DIALPAD -> fetchDialpadCandidates(query, session.sqlOffset, session.dialpadContext)
        }
    }

    private suspend fun fetchToolbarCandidates(
        query: String,
        @Suppress("UNUSED_PARAMETER") kind: SearchMatchRules.QueryKind,
        offset: Int,
    ): List<ContactDisplayListRow> {
        val namePattern = SearchMatchRules.likeContains(query.lowercase())
        val digits = SearchMatchRules.digitQueryText(query)
        val digitsPattern = if (digits.isNotEmpty()) {
            SearchMatchRules.likeContains(digits)
        } else {
            ""
        }
        return displayCacheDao.searchToolbarNameOrPhonePage(
            namePattern = namePattern,
            digitsPattern = digitsPattern,
            limit = SQL_SCAN_BATCH,
            offset = offset,
        )
    }

    private suspend fun fetchDialpadCandidates(
        query: String,
        offset: Int,
        dialpadContext: DialpadSearchContext?,
    ): List<ContactDisplayListRow> {
        val digitQuery = DialpadContactSearch.digitQueryText(query)
        val enableNameSearch = SearchMatchRules.enableT9NameMatch(query, SearchMode.DIALPAD)
        val letterPart = query.filter { it.isLetter() }
        val t9Digits = T9Mapper.toT9Digits(query)
        val phonePattern = when {
            digitQuery.length >= 2 -> SearchMatchRules.likePrefix(digitQuery)
            digitQuery.isNotEmpty() -> SearchMatchRules.likeContains(digitQuery)
            else -> ""
        }
        val namePattern = if (letterPart.isNotEmpty() && enableNameSearch) {
            SearchMatchRules.likeContains(letterPart.lowercase())
        } else {
            ""
        }
        val t9Pattern = when {
            !enableNameSearch -> ""
            t9Digits.isNotEmpty() -> SearchMatchRules.likePrefix(t9Digits)
            digitQuery.length >= 2 -> SearchMatchRules.likePrefix(digitQuery)
            else -> ""
        }
        return displayCacheDao.searchDialpadCandidatesPage(
            phonePattern = phonePattern,
            namePattern = namePattern,
            t9Pattern = t9Pattern,
            limit = SQL_SCAN_BATCH,
            offset = offset,
        )
    }

    private suspend fun mapRankedPage(
        hits: List<SearchRanking.RankedHit>,
        query: String,
        session: EngineSession,
    ): List<Contact> {
        val contactIds = hits.map { it.contactId }
        val rows = contactIds.chunked(200).flatMap { displayCacheDao.getListRowsByContactIds(it) }
        val byContactId = rows.associateBy { it.contactId }
        val orderedRows = contactIds.mapNotNull { byContactId[it] }
        var contacts = ContactDisplayLoadHelper.mapListRows(orderedRows).first
        val phonesByContactId = contactIds.chunked(200)
            .flatMap { phoneIndexDao.getByContactIds(it) }
            .groupBy { it.contactId }
        val formatNumbers = context.baseConfig.formatPhoneNumbers
        contacts = contacts.map { contact ->
            val indexEntries = phonesByContactId[contact.contactId].orEmpty()
            if (indexEntries.isEmpty()) return@map contact
            enrichContactPhones(contact, indexEntries, query, formatNumbers)
        }
        return contacts
    }

    private data class PhoneFields(val phoneDigits: String, val displayNumberDigits: String)

    private fun buildPhoneFields(entries: List<ContactPhoneIndexEntity>): PhoneFields {
        val phoneDigits = StringBuilder()
        val displayNumberDigits = StringBuilder()
        for (entry in entries) {
            val digits = entry.phoneDigits.ifEmpty { entry.digits }
            phoneDigits.append(digits)
            displayNumberDigits.append(entry.digits)
        }
        return PhoneFields(phoneDigits.toString(), displayNumberDigits.toString())
    }

    private fun enrichContactPhones(
        contact: Contact,
        indexEntries: List<ContactPhoneIndexEntity>,
        query: String,
        formatNumbers: Boolean,
    ): Contact {
        if (indexEntries.isEmpty()) return contact
        val digitQuery = SearchMatchRules.digitQueryText(query)
        contact.phoneNumbers = ArrayList(
            indexEntries.map { entry ->
                PhoneNumber(
                    value = entry.normalizedNumber,
                    type = 0,
                    label = "",
                    normalizedNumber = entry.normalizedNumber,
                    isPrimary = false,
                )
            },
        )
        contact.phoneNumbers.normalizeSingleDefaultPhoneFlag()
        val matchingEntry = indexEntries.firstOrNull { entry ->
            val digits = entry.digits.ifEmpty { entry.phoneDigits }
            digitQuery.isNotEmpty() && digits.contains(digitQuery)
        } ?: return contact
        val bind = contact.displayBind ?: return contact
        val rawPhone = matchingEntry.normalizedNumber
        val formattedPhone = if (formatNumbers && rawPhone.isNotEmpty()) {
            rawPhone.formatPhoneNumber()
        } else {
            rawPhone
        }
        // Prefer the matched number over the display-cache primary/first phone.
        contact.displayBind = bind.copy(
            formattedPhone = formattedPhone,
            showPhoneNumber = formattedPhone.isNotEmpty(),
        )
        return contact
    }

    private fun sessionKey(
        query: String,
        mode: SearchMode,
        displayCacheVersion: Long,
        generation: Long,
    ): String = "$mode:$query:$displayCacheVersion:$generation"

    companion object {
        private const val TAG = "searchEngine"
        private const val SQL_SCAN_BATCH = 80
    }
}
