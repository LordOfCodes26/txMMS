package com.goodwy.commons.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.provider.ContactsContract.*
import android.provider.ContactsContract.CommonDataKinds.*
import android.util.SparseArray
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import com.goodwy.commons.R
import com.goodwy.commons.extensions.*
import com.goodwy.commons.models.PhoneNumber
import com.goodwy.commons.models.SimpleContact
import com.goodwy.commons.models.contacts.Organization as MyOrganization
import android.graphics.Bitmap
import java.text.Collator
import java.util.Locale
import kotlin.math.abs
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.util.size
import com.goodwy.commons.models.contacts.Contact

/** Temporary contact-picker perf logging (Logcat tag ContactPickerPerf). */
private const val LOG_CONTACT_PAGE_PERF = false
private const val CONTACT_PAGE_PERF_TAG = "ContactPickerPerf"

class SimpleContactsHelper(val context: Context) {
    // Helper function to check if account is SIM card or phone storage
    private fun isSimOrPhoneStorage(accountName: String, accountType: String): Boolean {
        val nameLower = accountName.lowercase(Locale.getDefault())
        val typeLower = accountType.lowercase(Locale.getDefault())
        val isPhoneStorage = (accountName.isEmpty() && accountType.isEmpty()) ||
            (nameLower == "phone" && accountType.isEmpty())
        val isSimCard = typeLower.contains("sim") || typeLower.contains("icc")
        return isPhoneStorage || isSimCard
    }
    fun getAvailableContacts(favoritesOnly: Boolean, callback: (ArrayList<SimpleContact>) -> Unit) {
        ensureBackgroundThread {
            val contacts = getAvailableContactsSync(favoritesOnly)
            callback(contacts)
        }
    }

    /**
     * Searches phone/name rows in the contacts provider with a SQL [LIMIT] on the cursor
     * (plus grouping by raw contact), for responsive recipient pickers on large address books.
     *
     * Account filtering matches [getContactPhoneNumbers] in Kotlin (not in SQL), because OEM
     * SQL support for `LIKE … ESCAPE`, `COLLATE LOCALIZED`, and strict account WHERE clauses varies
     * and can yield no rows or throw (swallowed by [queryCursor]).
     */
    @SuppressLint("MissingPermission")
    fun getAvailableContactsMatchingSearchSync(
        favoritesOnly: Boolean,
        searchText: String,
        limit: Int,
    ): ArrayList<SimpleContact> {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return ArrayList()
        }
        val trimmed = searchText.trim()
        if (trimmed.isEmpty() || limit <= 0) {
            return ArrayList()
        }

        SimpleContact.collator = Collator.getInstance(context.sysLocale())
        Contact.collator = Collator.getInstance(context.sysLocale() ?: Locale.getDefault())

        // Binder args only — strip LIKE metacharacters from user text (no ESCAPE clause; some CPs reject it).
        fun sanitizeLikeUserInput(s: String): String =
            s.replace("%", "").replace("_", "").replace("\\", "")

        val safeNeedle = sanitizeLikeUserInput(trimmed)
        if (safeNeedle.isEmpty()) {
            return ArrayList()
        }
        val likePattern = "%$safeNeedle%"
        val digitsOnly = safeNeedle.filter { it.isDigit() }
        val digitPattern = if (digitsOnly.length >= 2) "%$digitsOnly%" else null

        val searchParts = mutableListOf<String>()
        val selectionArgs = ArrayList<String>()
        searchParts.add("${Phone.NUMBER} LIKE ?")
        selectionArgs.add(likePattern)
        searchParts.add("ifnull(${Phone.NORMALIZED_NUMBER},'') LIKE ?")
        selectionArgs.add(likePattern)
        searchParts.add("ifnull(${Data.DISPLAY_NAME},'') LIKE ?")
        selectionArgs.add(likePattern)
        if (digitPattern != null) {
            searchParts.add("${Phone.NUMBER} LIKE ?")
            selectionArgs.add(digitPattern)
            searchParts.add("ifnull(${Phone.NORMALIZED_NUMBER},'') LIKE ?")
            selectionArgs.add(digitPattern)
        }

        var selection = "(${searchParts.joinToString(" OR ")})"
        if (favoritesOnly) {
            selection = "($selection) AND ${Data.STARRED} = 1"
        }

        val rowLimit = (limit * 25).coerceIn(50, 2000)
        val sortOrder = "${Data.RAW_CONTACT_ID} ASC LIMIT $rowLimit"

        val uri = Phone.CONTENT_URI
        val projection = buildList {
            add(Data.RAW_CONTACT_ID)
            add(Data.CONTACT_ID)
            add(Phone.NUMBER)
            add(Phone.NORMALIZED_NUMBER)
            add(Phone.TYPE)
            add(Phone.LABEL)
            add(Phone.IS_PRIMARY)
            add(Phone.PHOTO_URI)
            add(Data.DISPLAY_NAME)
            add(RawContacts.ACCOUNT_NAME)
            add(RawContacts.ACCOUNT_TYPE)
            if (favoritesOnly) add(Data.STARRED)
        }.toTypedArray()

        val contactsMap = LinkedHashMap<Int, SimpleContact>()
        try {
            context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs.toTypedArray(),
                sortOrder,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val accountName = cursor.getStringValue(RawContacts.ACCOUNT_NAME) ?: ""
                    val accountType = cursor.getStringValue(RawContacts.ACCOUNT_TYPE) ?: ""
                    if (!isSimOrPhoneStorage(accountName, accountType)) {
                        continue
                    }

                    val number = cursor.getStringValue(Phone.NUMBER) ?: continue
                    val normalizedNumber = cursor.getStringValue(Phone.NORMALIZED_NUMBER)
                        ?: number.normalizePhoneNumber()
                    if (normalizedNumber.isEmpty()) {
                        continue
                    }

                    val rawId = cursor.getIntValue(Data.RAW_CONTACT_ID)
                    val contactId = cursor.getIntValue(Data.CONTACT_ID)
                    val type = cursor.getIntValue(Phone.TYPE)
                    val label = cursor.getStringValue(Phone.LABEL) ?: ""
                    val isPrimary = cursor.getIntValue(Phone.IS_PRIMARY) != 0
                    val photoUri = cursor.getStringValue(Phone.PHOTO_URI) ?: ""
                    val displayName = cursor.getStringValue(Data.DISPLAY_NAME) ?: ""

                    var contact = contactsMap[rawId]
                    if (contact == null) {
                        if (contactsMap.size >= limit) {
                            continue
                        }
                        contact = SimpleContact(
                            rawId,
                            contactId,
                            displayName.trim(),
                            photoUri,
                            ArrayList(),
                            ArrayList(),
                            ArrayList(),
                        )
                        contactsMap[rawId] = contact
                    }
                    if (contact.name.isEmpty() && displayName.isNotEmpty()) {
                        contact.name = displayName.trim()
                    }
                    // edited by sun ----->     
//                    val phoneNumber = PhoneNumber(number, type, label, normalizedNumber, isPrimary)
                    val phoneNumber = PhoneNumber(number, type, label, normalizedNumber, isPrimary, accountName, accountType)
                    // <----------
                    if (contact.phoneNumbers.none { it.normalizedNumber == phoneNumber.normalizedNumber }) {
                        contact.phoneNumbers.add(phoneNumber)
                    }
                }
            }
        } catch (_: Exception) {
            return ArrayList()
        }

        val merged = contactsMap.values.mapNotNull { contact ->
            if (contact.phoneNumbers.isEmpty()) {
                return@mapNotNull null
            }
            if (contact.name.isEmpty()) {
                contact.name = contact.phoneNumbers.first().value
            }
            contact
        }.toMutableList()
        merged.sort()
        return ArrayList(merged.take(limit))
    }

    fun getAvailableContactsSync(favoritesOnly: Boolean, withPhoneNumbersOnly: Boolean = true): ArrayList<SimpleContact> {
        SimpleContact.collator = Collator.getInstance(context.sysLocale())
        val names = getContactNames(favoritesOnly)
        var allContacts = getContactPhoneNumbers(favoritesOnly)
        
        // Prefer personal name (StructuredName row, company blank) over company-only entry
        // (Organization row). associateBy keeps the last duplicate, so when both rows exist for the
        // same rawId the Organization row can overwrite the person's name. groupBy + firstOrNull
        // ensures the person's name wins when present.
        val namesMap = names.groupBy { it.rawId }.mapValues { (_, contacts) ->
            contacts.firstOrNull { it.company.isBlank() } ?: contacts.last()
        }
        allContacts.forEach { contact ->
            val nameContact = namesMap[contact.rawId]
            val name = nameContact?.name ?: contact.phoneNumbers.firstOrNull()?.value
            if (name != null) {
                contact.name = name
            }
            val photoUri = nameContact?.photoUri
            if (photoUri != null && photoUri.isNotEmpty()) {
                contact.photoUri = photoUri
            }
        }

        // Optimize: Combine distinctBy operations and filter in single pass
        val seenRawIds = HashSet<Int>()
        val seenNumbers = HashSet<String>()
        allContacts = allContacts.filter { contact ->
            if (contact.name.isEmpty()) return@filter false
            
            // Filter by phone numbers if required
            if (withPhoneNumbersOnly && contact.phoneNumbers.isEmpty()) return@filter false
            
            // Check rawId uniqueness
            if (!seenRawIds.add(contact.rawId)) return@filter false
            
            // Check phone number uniqueness (last 9 digits)
            if (contact.phoneNumbers.isNotEmpty()) {
                val normalizedNumber = contact.phoneNumbers.first().normalizedNumber
                val startIndex = 0.coerceAtLeast(normalizedNumber.length - 9)
                val numberKey = normalizedNumber.substring(startIndex)
                if (!seenNumbers.add(numberKey)) return@filter false
            }
            
            true
        }.toMutableList() as ArrayList<SimpleContact>

        // Optimize duplicate removal: Use HashMap for O(1) lookup
        val contactsToRemove = HashSet<Int>()
        allContacts.groupBy { it.name }.forEach { (_, contacts) ->
            if (contacts.size > 1) {
                val sortedContacts = contacts.sortedByDescending { it.phoneNumbers.size }
                if (sortedContacts.any { it.phoneNumbers.size == 1 } && 
                    sortedContacts.any { it.phoneNumbers.size > 1 }) {
                    val multipleNumbersContact = sortedContacts.first()
                    for (i in 1 until sortedContacts.size) {
                        val contact = sortedContacts[i]
                        if (contact.phoneNumbers.all { 
                            multipleNumbersContact.doesContainPhoneNumber(it.normalizedNumber) 
                        }) {
                            contactsToRemove.add(contact.rawId)
                        }
                    }
                }
            }
        }

        // Remove duplicates efficiently
        allContacts.removeAll { it.rawId in contactsToRemove }

        // Optimize: Use HashMap for O(1) lookup instead of O(n) firstOrNull
        val contactsMap = allContacts.associateBy { it.rawId }
        
        val birthdays = getContactEvents(true)
        val birthdaysSize = birthdays.size
        for (i in 0 until birthdaysSize) {
            contactsMap[birthdays.keyAt(i)]?.birthdays = birthdays.valueAt(i)
        }

        val anniversaries = getContactEvents(false)
        val anniversariesSize = anniversaries.size
        for (i in 0 until anniversariesSize) {
            contactsMap[anniversaries.keyAt(i)]?.anniversaries = anniversaries.valueAt(i)
        }

        val organizations = getContactOrganization()
        val organizationsSize = organizations.size
        for (i in 0 until organizationsSize) {
            val key = organizations.keyAt(i)
            val contact = contactsMap[key]
            if (contact != null) {
                val org = organizations.valueAt(i)
                contact.company = org.company
                contact.jobPosition = org.jobPosition
            }
        }

        // Use same locale-aware collator as Contacts app so fast scroll shows Korean (then English) order
        Contact.collator = Collator.getInstance(context.sysLocale() ?: Locale.getDefault())
        allContacts.sort()
        return allContacts
    }

    /**
     * Paged read from [Contacts.CONTENT_URI] (has phone), ordered like the Contacts app's
     * MainActivity (always by [Contacts.DISPLAY_NAME_PRIMARY], same as the in-memory
     * [SimpleContact.compareByFullName] sort used after merging private contacts).
     *
     * Using [Contacts.DISPLAY_NAME_PRIMARY] (rather than [Contacts.SORT_KEY_PRIMARY]) keeps
     * the SQL page order consistent with the in-memory sort so paginated chunks form a globally
     * correct list. [Contacts.SORT_KEY_PRIMARY] can differ from [Contacts.DISPLAY_NAME_PRIMARY]
     * when the device "sort by surname" setting is on, which would cause each page to be
     * re-ordered individually but pages themselves to arrive in surname order — a hybrid order
     * matching neither surname nor given-name sort.
     *
     * Many OEMs ignore or break `LIMIT`/`OFFSET` in [sortOrder] for this URI, so paging uses
     * [android.database.Cursor.moveToPosition] over a stable sort instead of SQL limits.
     *
     * Phone rows are loaded **without** the SIM/phone-storage filter used in [getContactPhoneNumbers],
     * because aggregated [Contacts._ID] rows often only carry numbers on Google/other accounts;
     * filtering there produced an empty picker.
     *
     * **Performance**: contact metadata (name, photo) is collected from the outer cursor window
     * in one pass, then a single batch `WHERE CONTACT_ID IN (...)` fetches all phone numbers for
     * the entire page. This replaces the previous per-contact phone lookup (N+1 queries) with
     * exactly 2 queries per page regardless of batch size.
     *
     * @param contactCursorOffset row index in the sorted contacts cursor to start from
     * @param maxCursorRows maximum contact **cursor** rows to scan this call (advance offset by this amount, capped at row count)
     * @return Triple(contacts, nextCursorOffset, hasMoreRowsAfterThisWindow)
     */
    @SuppressLint("MissingPermission")
    fun getSystemContactsSortedPageFromDbSync(
        favoritesOnly: Boolean,
        contactCursorOffset: Int,
        maxCursorRows: Int,
    ): Triple<ArrayList<SimpleContact>, Int, Boolean> {
        val out = ArrayList<SimpleContact>()
        val wallStart = SystemClock.elapsedRealtime()
        if (!context.hasPermission(PERMISSION_READ_CONTACTS) || maxCursorRows <= 0 || contactCursorOffset < 0) {
            return Triple(out, contactCursorOffset, false)
        }
        SimpleContact.collator = Collator.getInstance(context.sysLocale())
        Contact.collator = Collator.getInstance(context.sysLocale() ?: Locale.getDefault())

        val uri = Contacts.CONTENT_URI
        val projection = arrayOf(
            Contacts._ID,
            Contacts.DISPLAY_NAME_PRIMARY,
            Contacts.PHOTO_THUMBNAIL_URI,
        )
        val selection = buildString {
            append("${Contacts.HAS_PHONE_NUMBER} != 0")
            if (favoritesOnly) {
                append(" AND ${Contacts.STARRED} = 1")
            }
        }
        // Sort by DISPLAY_NAME_PRIMARY to match the Contacts app's MainActivity sort order
        // (always display-name-first, same key used by the in-memory SimpleContact.compareByFullName).
        val sortOrder = "${Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC, ${Contacts._ID} ASC"
        val cursor = try {
            context.contentResolver.query(uri, projection, selection, null, sortOrder)
        } catch (_: Exception) {
            try {
                val sortFallback = "${Contacts.DISPLAY_NAME_PRIMARY} ASC, ${Contacts._ID} ASC"
                context.contentResolver.query(uri, projection, selection, null, sortFallback)
            } catch (_: Exception) {
                null
            }
        } ?: return Triple(out, contactCursorOffset, false)

        val queryOpenMs = SystemClock.elapsedRealtime() - wallStart
        var nextOffset = contactCursorOffset
        var hasMoreInCursor = false
        var phoneLookupMs = 0L
        var rowsScanned = 0

        // ── Phase 1: collect page metadata from the outer cursor (no phone lookups yet) ──────
        data class ContactRow(val contactId: Int, val displayName: String, val photoUri: String)
        val pageRows = ArrayList<ContactRow>(maxCursorRows)

        cursor.use { c ->
            val idCol = c.getColumnIndex(Contacts._ID)
            val nameCol = c.getColumnIndex(Contacts.DISPLAY_NAME_PRIMARY)
            val photoCol = c.getColumnIndex(Contacts.PHOTO_THUMBNAIL_URI)
            if (idCol < 0) return@use

            val rowCount = c.count
            if (rowCount == 0) {
                nextOffset = 0
                hasMoreInCursor = false
                return@use
            }
            // COUNT_UNKNOWN (-1): `offset >= rowCount` would be true for every offset — never use that check.
            if (rowCount > 0 && contactCursorOffset >= rowCount) {
                nextOffset = rowCount
                hasMoreInCursor = false
                return@use
            }

            fun collectRowAt(pos: Int): Boolean {
                if (!c.moveToPosition(pos)) return false
                rowsScanned++
                pageRows.add(
                    ContactRow(
                        contactId = c.getInt(idCol),
                        displayName = if (nameCol >= 0) c.getString(nameCol)?.trim().orEmpty() else "",
                        photoUri = if (photoCol >= 0) c.getString(photoCol).orEmpty() else "",
                    )
                )
                return true
            }

            if (rowCount > 0) {
                val scanEnd = minOf(contactCursorOffset + maxCursorRows, rowCount)
                for (pos in contactCursorOffset until scanEnd) {
                    if (!collectRowAt(pos)) {
                        nextOffset = pos
                        hasMoreInCursor = false
                        return@use
                    }
                }
                nextOffset = scanEnd
                hasMoreInCursor = scanEnd < rowCount
            } else {
                var pos = contactCursorOffset
                repeat(maxCursorRows) {
                    if (!collectRowAt(pos)) {
                        nextOffset = pos
                        hasMoreInCursor = false
                        return@use
                    }
                    pos++
                }
                nextOffset = pos
                hasMoreInCursor = c.moveToPosition(pos)
            }
        }

        if (pageRows.isEmpty()) {
            if (LOG_CONTACT_PAGE_PERF) {
                Log.d(CONTACT_PAGE_PERF_TAG, "getSystemContactsPage offset=$contactCursorOffset maxRows=$maxCursorRows EMPTY page")
            }
            return Triple(out, nextOffset, hasMoreInCursor)
        }

        // ── Phase 2: single batch phone lookup for all contact IDs in this page ──────────────
        val tp = SystemClock.elapsedRealtime()
        val contactIdList = pageRows.map { it.contactId }
        val phonesMap = loadAllPhonesForAggregatedContactsBatch(contactIdList)
        phoneLookupMs = SystemClock.elapsedRealtime() - tp

        // ── Phase 3: assemble SimpleContact objects ───────────────────────────────────────────
        for (row in pageRows) {
            val (rawId, phones) = phonesMap[row.contactId] ?: continue
            if (phones.isNotEmpty()) {
                val name = row.displayName.ifEmpty { phones.first().value }
                out.add(
                    SimpleContact(
                        rawId = rawId,
                        contactId = row.contactId,
                        name = name,
                        photoUri = row.photoUri,
                        phoneNumbers = phones,
                        birthdays = ArrayList(),
                        anniversaries = ArrayList(),
                    ),
                )
            }
        }

        if (LOG_CONTACT_PAGE_PERF) {
            val totalMs = SystemClock.elapsedRealtime() - wallStart
            Log.d(
                CONTACT_PAGE_PERF_TAG,
                "getSystemContactsPage offset=$contactCursorOffset maxRows=$maxCursorRows outSimple=${out.size} " +
                    "nextOffset=$nextOffset hasMore=$hasMoreInCursor rowsScanned=$rowsScanned " +
                    "queryOpenMs=$queryOpenMs phoneLookupMs=$phoneLookupMs totalMs=$totalMs",
            )
        }
        return Triple(out, nextOffset, hasMoreInCursor)
    }

    /**
     * Batch phone lookup: fetches all phone rows for [contactIds] in a **single** query
     * (`WHERE CONTACT_ID IN (...)`), returning a map of contactId → (firstRawId, phones).
     *
     * This replaces the previous per-contact [loadAllPhonesForAggregatedContact] loop that fired
     * N separate queries for an N-row page — e.g. 40 queries per 40-item page batch.
     */
    private fun loadAllPhonesForAggregatedContactsBatch(
        contactIds: List<Int>,
    ): Map<Int, Pair<Int, ArrayList<PhoneNumber>>> {
        if (contactIds.isEmpty()) return emptyMap()
        val result = HashMap<Int, Pair<Int, ArrayList<PhoneNumber>>>(contactIds.size * 2)
        val uri = Phone.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            Data.CONTACT_ID,
            Phone.NORMALIZED_NUMBER,
            Phone.NUMBER,
            Phone.TYPE,
            Phone.LABEL,
            Phone.IS_PRIMARY,
            RawContacts.ACCOUNT_NAME,
            RawContacts.ACCOUNT_TYPE,
        )
        val placeholders = contactIds.joinToString(",") { "?" }
        val selection = "${Phone.CONTACT_ID} IN ($placeholders)"
        val selectionArgs = contactIds.map { it.toString() }.toTypedArray()
        context.queryCursor(uri, projection, selection, selectionArgs) { cursor ->
            val number = cursor.getStringValue(Phone.NUMBER) ?: return@queryCursor
            val normalizedNumber = cursor.getStringValue(Phone.NORMALIZED_NUMBER)
                ?: number.normalizePhoneNumber()
            val rawId = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val contactId = cursor.getIntValue(Data.CONTACT_ID)
            val type = cursor.getIntValue(Phone.TYPE)
            val label = cursor.getStringValue(Phone.LABEL) ?: ""
            val isPrimary = cursor.getIntValue(Phone.IS_PRIMARY) != 0
            val accountName = cursor.getStringValue(RawContacts.ACCOUNT_NAME) ?: ""
            val accountType = cursor.getStringValue(RawContacts.ACCOUNT_TYPE) ?: ""
            val phoneNumber = PhoneNumber(number, type, label, normalizedNumber, isPrimary, accountName, accountType)
            val existing = result[contactId]
            if (existing == null) {
                result[contactId] = rawId to ArrayList<PhoneNumber>().also { it.add(phoneNumber) }
            } else {
                existing.second.add(phoneNumber)
            }
        }
        return result
    }

    private fun getContactNames(favoritesOnly: Boolean): List<SimpleContact> {
        val contacts = ArrayList<SimpleContact>()
        val startNameWithSurname = context.baseConfig.startNameWithSurname
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            Data.CONTACT_ID,
            StructuredName.PREFIX,
            StructuredName.GIVEN_NAME,
            StructuredName.MIDDLE_NAME,
            StructuredName.FAMILY_NAME,
            StructuredName.SUFFIX,
            StructuredName.PHOTO_THUMBNAIL_URI,
            Organization.COMPANY,
            Organization.TITLE,
            Data.MIMETYPE,
            RawContacts.ACCOUNT_NAME,
            RawContacts.ACCOUNT_TYPE
        )

        var selection = "(${Data.MIMETYPE} = ? OR ${Data.MIMETYPE} = ?)"

        if (favoritesOnly) {
            selection += " AND ${Data.STARRED} = 1"
        }

        val selectionArgs = arrayOf(
            StructuredName.CONTENT_ITEM_TYPE,
            Organization.CONTENT_ITEM_TYPE
        )

        context.queryCursor(uri, projection, selection, selectionArgs) { cursor ->
            val accountName = cursor.getStringValue(RawContacts.ACCOUNT_NAME) ?: ""
            val accountType = cursor.getStringValue(RawContacts.ACCOUNT_TYPE) ?: ""
            
            // Load phone storage and SIM card contacts - use helper function
            if (!isSimOrPhoneStorage(accountName, accountType)) {
                return@queryCursor
            }
            
            val rawId = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val contactId = cursor.getIntValue(Data.CONTACT_ID)
            val mimetype = cursor.getStringValue(Data.MIMETYPE)
            val photoUri = cursor.getStringValue(StructuredName.PHOTO_THUMBNAIL_URI) ?: ""

            val isPerson = mimetype == StructuredName.CONTENT_ITEM_TYPE
            if (isPerson) {
                val prefix = cursor.getStringValue(StructuredName.PREFIX) ?: ""
                val givenName = cursor.getStringValue(StructuredName.GIVEN_NAME) ?: ""
                val middleName = cursor.getStringValue(StructuredName.MIDDLE_NAME) ?: ""
                val familyName = cursor.getStringValue(StructuredName.FAMILY_NAME) ?: ""
                val suffix = cursor.getStringValue(StructuredName.SUFFIX) ?: ""
                // Combine all name parts into a single name field
                if (givenName.isNotEmpty() || middleName.isNotEmpty() || familyName.isNotEmpty()) {
                    val nameParts = listOf(prefix, givenName, middleName, familyName, suffix).filter { it.isNotEmpty() }
                    val fullName = if (nameParts.isNotEmpty()) {
                        nameParts.joinToString(" ").trim()
                    } else {
                        ""
                    }
                    val contact = SimpleContact(rawId, contactId, fullName, photoUri, ArrayList(), ArrayList(), ArrayList())
                    contacts.add(contact)
                }
            }

            val isOrganization = mimetype == Organization.CONTENT_ITEM_TYPE
            if (isOrganization) {
                val company = cursor.getStringValue(Organization.COMPANY) ?: ""
                val jobTitle = cursor.getStringValue(Organization.TITLE) ?: ""
                if (company.isNotBlank() && jobTitle.isNotBlank()) {
                    val fullName = "$company, $jobTitle".trim()
                    val contact = SimpleContact(rawId, contactId, fullName, photoUri, ArrayList(), ArrayList(), ArrayList(), company, jobTitle)
                    contacts.add(contact)
                } else if (company.isNotBlank()) {
                    val fullName = company.trim()
                    val contact = SimpleContact(rawId, contactId, fullName, photoUri, ArrayList(), ArrayList(), ArrayList(), company, jobTitle)
                    contacts.add(contact)
                } else if (jobTitle.isNotBlank()) {
                    val fullName = jobTitle.trim()
                    val contact = SimpleContact(rawId, contactId, fullName, photoUri, ArrayList(), ArrayList(), ArrayList(), company, jobTitle)
                    contacts.add(contact)
                }
            }
        }
        return contacts
    }

    private fun getContactPhoneNumbers(favoritesOnly: Boolean): ArrayList<SimpleContact> {
        val contacts = ArrayList<SimpleContact>()
        val contactsMap = HashMap<Int, SimpleContact>() // Optimize: HashMap for O(1) lookup
        val uri = Phone.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            Data.CONTACT_ID,
            Phone.NORMALIZED_NUMBER,
            Phone.NUMBER,
            Phone.TYPE,
            Phone.LABEL,
            Phone.IS_PRIMARY,
            Phone.PHOTO_URI,
            Data.STARRED,
            RawContacts.ACCOUNT_NAME,
            RawContacts.ACCOUNT_TYPE
        )

        val selection = if (favoritesOnly) "${Data.STARRED} = 1" else null

        context.queryCursor(uri, projection, selection) { cursor ->
            val accountName = cursor.getStringValue(RawContacts.ACCOUNT_NAME) ?: ""
            val accountType = cursor.getStringValue(RawContacts.ACCOUNT_TYPE) ?: ""
            
            // Load phone storage and SIM card contacts - use helper function
            if (!isSimOrPhoneStorage(accountName, accountType)) {
                return@queryCursor
            }
            
            val number = cursor.getStringValue(Phone.NUMBER) ?: return@queryCursor
            val normalizedNumber = cursor.getStringValue(Phone.NORMALIZED_NUMBER)
                ?: cursor.getStringValue(Phone.NUMBER)?.normalizePhoneNumber() ?: return@queryCursor

            val rawId = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val contactId = cursor.getIntValue(Data.CONTACT_ID)
            val type = cursor.getIntValue(Phone.TYPE)
            val label = cursor.getStringValue(Phone.LABEL) ?: ""
            val isPrimary = cursor.getIntValue(Phone.IS_PRIMARY) != 0
            val photoUri = cursor.getStringValue(Phone.PHOTO_URI) ?: ""

            // Optimize: Use HashMap for O(1) lookup instead of O(n) firstOrNull
            var contact = contactsMap[rawId]
            if (contact == null) {
                contact = SimpleContact(rawId, contactId, "", photoUri, ArrayList(), ArrayList(), ArrayList())
                contacts.add(contact)
                contactsMap[rawId] = contact
            }

            // edited by sun ----->  
            val phoneNumber = PhoneNumber(number, type, label, normalizedNumber, isPrimary, accountName, accountType)
            // <------------
            contact.phoneNumbers.add(phoneNumber)
        }
        return contacts
    }

    private fun getContactEvents(getBirthdays: Boolean): SparseArray<ArrayList<String>> {
        val eventDates = SparseArray<ArrayList<String>>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            Event.START_DATE
        )

        val selection = "${Event.MIMETYPE} = ? AND ${Event.TYPE} = ?"
        val requiredType = if (getBirthdays) Event.TYPE_BIRTHDAY.toString() else Event.TYPE_ANNIVERSARY.toString()
        val selectionArgs = arrayOf(Event.CONTENT_ITEM_TYPE, requiredType)

        context.queryCursor(uri, projection, selection, selectionArgs) { cursor ->
            val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val startDate = cursor.getStringValue(Event.START_DATE) ?: return@queryCursor

            if (eventDates[id] == null) {
                eventDates.put(id, ArrayList())
            }

            eventDates[id]!!.add(startDate)
        }

        return eventDates
    }

    private fun getContactOrganization(): SparseArray<MyOrganization> {
        val organizations = SparseArray<MyOrganization>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            Organization.COMPANY,
            Organization.TITLE,
        )

        val selection = "(${Data.MIMETYPE} = ? OR ${Data.MIMETYPE} = ?)"
        val selectionArgs = arrayOf(Organization.CONTENT_ITEM_TYPE)

        context.queryCursor(uri, projection, selection, selectionArgs) { cursor ->
            val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val company = cursor.getStringValue(Organization.COMPANY) ?: ""
            val title = cursor.getStringValue(Organization.TITLE) ?: ""
            if (company.isEmpty() && title.isEmpty()) {
                return@queryCursor
            }

            val organization = MyOrganization(company, title)
            organizations.put(id, organization)
        }

        return organizations
    }

    fun getNameFromPhoneNumber(number: String): String {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return number
        }

        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(
            PhoneLookup.DISPLAY_NAME
        )

        try {
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            cursor.use {
                if (cursor?.moveToFirst() == true) {
                    return cursor.getStringValue(PhoneLookup.DISPLAY_NAME)
                }
            }
        } catch (_: Exception) {
        }

        return number
    }

    fun getPhotoUriFromPhoneNumber(number: String): String {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return ""
        }

        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(
            PhoneLookup.PHOTO_URI
        )

        try {
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            cursor.use {
                if (cursor?.moveToFirst() == true) {
                    return cursor.getStringValue(PhoneLookup.PHOTO_URI) ?: ""
                }
            }
        } catch (_: Exception) {
        }

        return ""
    }

    fun loadContactImage(path: String, imageView: ImageView, placeholderName: String, placeholderImage: Drawable? = null, letter: Boolean = true) {
        // Generate placeholder only if not provided
        val placeholder = placeholderImage ?: run {
            val letterOrIcon = if (letter) getContactLetterIcon(placeholderName) else getContactIconBg(placeholderName)
            letterOrIcon.toDrawable(context.resources)
        }

        // Combine all RequestOptions into a single apply for better performance
        val options = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .error(placeholder)
            .centerCrop()
            .circleCrop()
            // Add signature to help with cache invalidation when contact photos are updated
            // Using the photo URI as signature key ensures different URIs are cached separately
            .signature(if (path.isNotEmpty()) ObjectKey(path) else ObjectKey(placeholderName))

        Glide.with(context)
            .load(path)
            .transition(DrawableTransitionOptions.withCrossFade())
            .placeholder(placeholder)
            .apply(options)
            .into(imageView)
    }

    fun getContactLetterIcon(name: String): Bitmap {
        val emoji = name.take(2)
        val letter = if (emoji.isEmoji()) emoji else name.getNameLetter()
        val size = context.resources.getDimension(R.dimen.contact_photo_big_size).toInt()
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)

        // Use drawable background instead of drawing with Canvas
        val backgroundDrawable = if (context.baseConfig.useColoredContacts) {
            val drawableIndex = context.getAvatarDrawableIndexForName(name)
            if (drawableIndex >= 0) {
                context.createAvatarGradientDrawable(drawableIndex, isDarkMode = context.isNightDisplay())
            } else {
                @SuppressLint("UseCompatLoadingForDrawables")
                context.resources.getDrawable(R.drawable.placeholder_contact, context.theme)?.let {
                    (it as? LayerDrawable)?.findDrawableByLayerId(R.id.placeholder_contact_background)
                } ?: run {
                    // Fallback: create a simple gradient drawable
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0xFFa4a8b5.toInt())
                    }
                }
            }
        } else {
            // Use default gradient for non-colored contacts
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                colors = intArrayOf(0xFFa4a8b5.toInt(), 0xFF878b94.toInt())
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
            }
        }

        // Draw the background drawable
        backgroundDrawable.setBounds(0, 0, size, size)
        backgroundDrawable.draw(canvas)

        // Draw the letter/text on top
        val wantedTextSize = size / 2f
        val textPaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = wantedTextSize
            style = Paint.Style.FILL
            // Disable font padding for better centering
            isFakeBoldText = false
        }

        // Perfect centering: center horizontally and vertically
        val xPos = canvas.width / 2f
        // Use precise vertical centering accounting for text metrics
        val fontMetrics = textPaint.fontMetrics
        val yPos = canvas.height / 2f - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(letter, xPos, yPos, textPaint)
        
        return bitmap
    }

    fun getContactIconBg(name: String): Bitmap {
        val size = context.resources.getDimension(R.dimen.contact_photo_big_size).toInt()
        val output = createBitmap(size, size)
        val canvas = Canvas(output)

        // Use drawable background instead of drawing with Canvas
        val backgroundDrawable = if (context.baseConfig.useColoredContacts) {
            val drawableIndex = context.getAvatarDrawableIndexForName(name)
            if (drawableIndex >= 0) {
                context.createAvatarGradientDrawable(drawableIndex, isDarkMode = context.isNightDisplay())
            } else {
                @SuppressLint("UseCompatLoadingForDrawables")
                context.resources.getDrawable(R.drawable.placeholder_contact, context.theme)?.let {
                    (it as? LayerDrawable)?.findDrawableByLayerId(R.id.placeholder_contact_background)
                } ?: run {
                    // Fallback: create a simple gradient drawable
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0xFFa4a8b5.toInt())
                    }
                }
            }
        } else {
            // Use default gradient for non-colored contacts
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                colors = intArrayOf(0xFFa4a8b5.toInt(), 0xFF878b94.toInt())
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
            }
        }

        // Draw the background drawable
        backgroundDrawable.setBounds(0, 0, size, size)
        backgroundDrawable.draw(canvas)
        
        return output
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    fun getColoredContactIcon(title: String): Drawable {
        val icon = context.resources.getDrawable(R.drawable.placeholder_contact, context.theme)
        if (context.baseConfig.useColoredContacts) {
            val letterBackgroundColors = context.getLetterBackgroundColors()
            val bgColor = letterBackgroundColors[abs(title.hashCode()) % letterBackgroundColors.size].toInt()
            (icon as LayerDrawable).findDrawableByLayerId(R.id.placeholder_contact_background).applyColorFilter(bgColor)
        }
        return icon
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    fun getColoredGroupIcon(title: String): Drawable {
        val icon = context.resources.getDrawable(R.drawable.placeholder_group, context.theme)
        if (context.baseConfig.useColoredContacts) {
            val letterBackgroundColors = context.getLetterBackgroundColors()
            val bgColor = letterBackgroundColors[abs(title.hashCode()) % letterBackgroundColors.size].toInt()
            (icon as LayerDrawable).findDrawableByLayerId(R.id.placeholder_group_background).applyColorFilter(bgColor)
        }
        return icon
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    fun getColoredCompanyIcon(title: String): Drawable {
        val icon = context.resources.getDrawable(R.drawable.placeholder_company, context.theme)
        if (context.baseConfig.useColoredContacts) {
            val letterBackgroundColors = context.getLetterBackgroundColors()
            val bgColor = letterBackgroundColors[abs(title.hashCode()) % letterBackgroundColors.size].toInt()
            (icon as LayerDrawable).findDrawableByLayerId(R.id.placeholder_company_background).applyColorFilter(bgColor)
        }
        return icon
    }

    fun getContactLookupKey(contactId: String): String {
        val uri = Data.CONTENT_URI
        val projection = arrayOf(Data.CONTACT_ID, Data.LOOKUP_KEY)
        //val selection = "${Data.MIMETYPE} = ? AND ${Data.RAW_CONTACT_ID} = ?"
        //val selectionArgs = arrayOf(StructuredName.CONTENT_ITEM_TYPE, contactId)
        val selection = "${Data.RAW_CONTACT_ID} = ?"
        val selectionArgs = arrayOf(contactId)

        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                val id = cursor.getIntValue(Data.CONTACT_ID)
                val lookupKey = cursor.getStringValue(Data.LOOKUP_KEY)
                return "$lookupKey/$id"
            }
        }

        return ""
    }

//    fun deleteContactRawIDs(ids: ArrayList<Int>, callback: () -> Unit) {
//        ensureBackgroundThread {
//            val uri = Data.CONTENT_URI
//            if (uri != null && ids.isNotEmpty()) {
//                ids.chunked(30).forEach { chunk ->
//                    val selection = "${Data.RAW_CONTACT_ID} IN (${getQuestionMarks(chunk.size)})"
//                    val selectionArgs = chunk.map { it.toString() }.toTypedArray()
//                    context.contentResolver.delete(uri, selection, selectionArgs)
//                }
//            }
//            callback()
//        }
//    }

    fun deleteContactRawIDs(ids: ArrayList<Int>, callback: () -> Unit) {
        ensureBackgroundThread {
            if (!context.hasPermission(PERMISSION_WRITE_CONTACTS)) {
                callback()
                return@ensureBackgroundThread
            }

            val resolver = context.contentResolver ?: run {
                callback()
                return@ensureBackgroundThread
            }

            val validIds = ids.filter { it > 0 }
            if (validIds.isEmpty()) {
                callback()
                return@ensureBackgroundThread
            }

            // Use RawContacts.CONTENT_URI for bulk delete - much more efficient than Data.CONTENT_URI
            // Increase chunk size from 30 to 500 for better performance with large contact lists
            val uri = RawContacts.CONTENT_URI
            validIds.chunked(500).forEach { chunk ->
                val selection = "${RawContacts._ID} IN (${getQuestionMarks(chunk.size)})"
                val selectionArgs = chunk.map { it.toString() }.toTypedArray()

                try {
                    resolver.delete(uri, selection, selectionArgs)
                } catch (e: Exception) {
                    // Log error but continue with remaining chunks
                    context.showErrorToast(e)
                }
            }

            callback()
        }
    }

    fun getShortcutImage(path: String, placeholderName: String, isCompany: Boolean, callback: (image: Bitmap) -> Unit) {
        ensureBackgroundThread {
            if (isCompany) {
                try {
                    val bitmap = SimpleContactsHelper(context).getColoredCompanyIcon(placeholderName).toBitmap()
                    callback(bitmap)
                } catch (_: Exception) {
                    @SuppressLint("UseCompatLoadingForDrawables")
                    val placeholder = context.resources.getDrawable( R.drawable.placeholder_company, context.theme).toBitmap()
                    callback(placeholder)
                }
            } else {
                val placeholder = getContactLetterIcon(placeholderName).toDrawable(context.resources)
                try {
                    val options = RequestOptions()
                        .format(DecodeFormat.PREFER_ARGB_8888)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .error(placeholder)
                        .centerCrop()

                    val size = context.resources.getDimension(R.dimen.shortcut_size).toInt()
                    val bitmap = Glide.with(context).asBitmap()
                        .load(path)
                        .placeholder(placeholder)
                        .apply(options)
                        .apply(RequestOptions.circleCropTransform())
                        .into(size, size)
                        .get()

                    callback(bitmap)
                } catch (_: Exception) {
                    callback(placeholder.bitmap)
                }
            }
        }
    }

    fun exists(number: String, callback: (Boolean) -> Unit) {
        SimpleContactsHelper(context).getAvailableContacts(false) { contacts ->
            val contact = contacts.firstOrNull { it.doesHavePhoneNumber(number) }
            callback.invoke(contact != null)
        }
    }
}
