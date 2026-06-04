package com.goodwy.commons.helpers

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.*
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.*
import android.provider.MediaStore
import android.text.TextUtils
import android.util.SparseArray
import com.goodwy.commons.R
import com.goodwy.commons.extensions.*
import com.goodwy.commons.models.PhoneNumber
import com.goodwy.commons.models.contacts.*
import com.goodwy.commons.overloads.times
import java.text.Collator
import java.util.Locale
import androidx.core.util.size
import androidx.core.net.toUri
import androidx.core.graphics.scale
import java.util.LinkedHashSet
import com.goodwy.commons.helpers.getQuestionMarks
import kotlin.math.max

class ContactsHelper(val context: Context) {
    companion object {
        private val contactSourcesCache = mutableMapOf<String, LinkedHashSet<ContactSource>>()

        /** Min distinct contacts before showing bulk favorite progress UI (list + select-contacts flow). */
        const val FAVORITES_BULK_PROGRESS_UI_MIN = 15

        /** SIM ADN phonebooks support at most this many phone numbers per contact entry. */
        const val SIM_MAX_PHONE_NUMBERS = 2

        /** Throttle high-frequency contact transfer progress toasts. */
        fun shouldReportContactTransferProgress(current: Int, total: Int): Boolean {
            if (total <= 0) return false
            if (current <= 1 || current >= total) return true
            if (total < 40) return true
            val step = max(1, total / 80)
            return current % step == 0
        }
    }

    private val BATCH_SIZE = 50

    /** Icc ADN can briefly fail right after RawContact insert; retry off the UI thread only. */
    fun insertIccAdnForSimRawContactWithRetries(
        accountName: String,
        accountType: String,
        number: String,
        displayName: String,
        logContext: String,
        showProgressToasts: Boolean = true,
        toastOnSuccess: Boolean = true,
    ): Boolean {
        val maxAttempts = 3
        repeat(maxAttempts) { attempt ->
            if (context.tryInsertIccAdnForSimContact(
                    accountName = accountName,
                    accountType = accountType,
                    number = number,
                    displayName = displayName,
                    logContext = "$logContext attempt=${attempt + 1}",
                )
            ) {
                if (showProgressToasts && toastOnSuccess) {
                    context.toast(R.string.sim_adn_progress_writing_ok)
                }
                return true
            }
            if (attempt < maxAttempts - 1) {
                if (showProgressToasts) {
                    context.toast(
                        context.getString(R.string.sim_adn_progress_retry_wait, attempt + 1, maxAttempts),
                    )
                }
                if (!isOnMainThread()) {
                    try {
                        Thread.sleep(400L * (attempt + 1))
                    } catch (_: InterruptedException) {
                        return false
                    }
                }
            }
        }
        return false
    }

    /**
     * After merging duplicate SIM contacts, ensures the SIM ADN phonebook holds exactly one
     * entry for [contact]. Removes all extra duplicate ADN rows (by row ID first, then by
     * selection+re-insert as fallback). Called on the *keeper* contact after the merge
     * ContactsContract operations are complete.
     */
    fun deduplicateSimAdnForMerge(contact: Contact) {
        val accountType = getContactSourceType(contact.source)
        if (!isSimAccountTypeForPersistence(accountType)) return
        val phone = contact.phoneNumbers.firstOrNull()?.value ?: return
        context.deduplicateIccAdnForMerge(
            accountName = contact.source,
            accountType = accountType,
            phoneNumber = phone,
            displayName = contact.getNameToDisplay(),
            logContext = "mergeDedup rawId=${contact.id}",
        )
    }

    /** Max operations per [ContentResolver.applyBatch] — stay below provider limits / TransactionTooLarge. */
    private val MAX_PROVIDER_BATCH_OPS = 450
    private var displayContactSources = ArrayList<String>()

    fun getContacts(
        getAll: Boolean = false,
        gettingDuplicates: Boolean = false,
        ignoredContactSources: HashSet<String> = HashSet(),
        showOnlyContactsWithNumbers: Boolean = context.baseConfig.showOnlyContactsWithNumbers,
        loadExtendedFields: Boolean = true, // Set to false for list views to skip addresses, events, notes
        /** When false, skips group membership queries/assignment (large win for pickers that do not show groups). */
        assignContactGroups: Boolean = true,
        /**
         * When false, skips duplicate collapsing even if merge-duplicates is enabled in settings
         * (faster load for pickers; row count may exceed the merged main list).
         */
        mergeDuplicates: Boolean = true,
        callback: (ArrayList<Contact>) -> Unit
    ) {
        ensureBackgroundThread {
            val contacts = SparseArray<Contact>()
            displayContactSources = context.getVisibleContactSources()

            if (getAll) {
                displayContactSources = if (ignoredContactSources.isEmpty()) {
                    context.getAllContactSources().map { it.name }.toMutableList() as ArrayList
                } else {
                    context.getAllContactSources().filter {
                        it.getFullIdentifier().isNotEmpty() && !ignoredContactSources.contains(it.getFullIdentifier())
                    }.map { it.name }.toMutableList() as ArrayList
                }
            }

            getDeviceContacts(contacts, ignoredContactSources, gettingDuplicates, loadExtendedFields)

            val contactsSize = contacts.size
            val tempContacts = ArrayList<Contact>(contactsSize)
            val resultContacts = ArrayList<Contact>(contactsSize)

            // Optimize filtering by avoiding intermediate collections
            for (i in 0 until contactsSize) {
                val contact = contacts.valueAt(i)
                if (ignoredContactSources.isEmpty() && showOnlyContactsWithNumbers) {
                    if (contact.phoneNumbers.isNotEmpty()) {
                        tempContacts.add(contact)
                    }
                } else {
                    tempContacts.add(contact)
                }
            }

            if (mergeDuplicates && context.baseConfig.mergeDuplicateContacts && ignoredContactSources.isEmpty() && !getAll) {
                // Optimize duplicate merging: filter and group in single pass
                // Optimize: Use HashSet for O(1) lookup instead of O(n) contains
                val displaySourcesSet = displayContactSources.toHashSet()
                val contactsToMerge = ArrayList<Contact>()
                val contactsToAdd = ArrayList<Contact>()
                for (contact in tempContacts) {
                    if (displaySourcesSet.contains(contact.source)) {
                        contactsToMerge.add(contact)
                    } else {
                        contactsToAdd.add(contact)
                    }
                }
                
                // Group by name + first phone + first email so that contacts with the same display name
                // but different numbers/emails (e.g. imported list with "Samsung Electronics #1", "#2"...) stay separate
                val groupedByName = HashMap<String, ArrayList<Contact>>()
                for (contact in contactsToMerge) {
                    val nameKey = contact.getNameToDisplay().lowercase(Locale.getDefault())
                    val firstPhone = contact.phoneNumbers.firstOrNull()?.normalizedNumber?.trim() ?: ""
                    val firstEmail = contact.emails.firstOrNull()?.value?.trim() ?: ""
                    val key = "$nameKey\t$firstPhone\t$firstEmail"
                    groupedByName.getOrPut(key) { ArrayList() }.add(contact)
                }
                
                // Process grouped contacts
                for (group in groupedByName.values) {
                    if (group.size == 1) {
                        resultContacts.add(group.first())
                    } else {
                        // Optimize: Use maxByOrNull instead of sort + firstOrNull
                        val bestMatch = group.maxByOrNull { contact ->
                            val compareLength = contact.getStringToCompare().length
                            // Prefer contacts with phone numbers
                            if (contact.phoneNumbers.isNotEmpty()) compareLength + 1000 else compareLength
                        } ?: group.first()
                        resultContacts.add(bestMatch)
                    }
                }
                
                resultContacts.addAll(contactsToAdd)
            } else {
                resultContacts.addAll(tempContacts)
            }

            // groups are obtained with contactID, not rawID, so assign them to proper contacts like this
            if (assignContactGroups) {
                // Cache stored groups and use map for O(1) lookup instead of firstOrNull
                val storedGroups = getStoredGroupsSync()
                val groups = getContactGroups(storedGroups)
                val contactIdToContactMap = resultContacts.associateBy { it.contactId }
                val groupsSize = groups.size
                for (i in 0 until groupsSize) {
                    val key = groups.keyAt(i)
                    contactIdToContactMap[key]?.groups = groups.valueAt(i)
                }
            }

            Contact.sorting = SORT_BY_FULL_NAME
            Contact.startWithSurname = context.baseConfig.startNameWithSurname
            Contact.showNicknameInsteadNames = context.baseConfig.showNicknameInsteadNames
            Contact.sortingSymbolsFirst = true  // Fixed order: symbols, Korean, English, other
            Contact.collator = Collator.getInstance(context.sysLocale())
            System.setProperty("java.util.Arrays.useLegacyMergeSort", "true") //https://stackoverflow.com/questions/11441666/java-error-comparison-method-violates-its-general-contract
            resultContacts.sort()

            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post { callback(resultContacts) }
        }
    }

    private fun getContentResolverAccounts(): HashSet<ContactSource> {
        val sources = HashSet<ContactSource>()
        arrayOf(Groups.CONTENT_URI, Settings.CONTENT_URI, RawContacts.CONTENT_URI).forEach {
            fillSourcesFromUri(it, sources)
        }

        return sources
    }

    private fun fillSourcesFromUri(uri: Uri, sources: HashSet<ContactSource>) {
        val projection = arrayOf(
            RawContacts.ACCOUNT_NAME,
            RawContacts.ACCOUNT_TYPE
        )

        context.queryCursor(uri, projection) { cursor ->
            val name = cursor.getStringValue(RawContacts.ACCOUNT_NAME) ?: ""
            val type = cursor.getStringValue(RawContacts.ACCOUNT_TYPE) ?: ""
            var publicName = name

            val source = ContactSource(name, type, publicName)
            sources.add(source)
        }
    }

    // Helper function to check if account is SIM card or phone storage
    private fun isSimOrPhoneStorage(accountName: String, accountType: String): Boolean {
        val nameLower = accountName.lowercase(Locale.getDefault())
        val typeLower = accountType.lowercase(Locale.getDefault())
        
        // Phone storage: blank account name/type or "phone" account.
        // Use isBlank() instead of isEmpty() because the protection mechanism stores
        // a single space (' ') for account name/type rather than an empty string.
        val isPhoneStorage = (accountName.isBlank() && accountType.isBlank()) ||
            (nameLower.trim() == "phone" && accountType.isBlank())
        
        // SIM card: account type contains "sim" or "icc"
        val isSimCard = typeLower.contains("sim") || typeLower.contains("icc")
        
        return isPhoneStorage || isSimCard
    }

    @SuppressLint("UseKtx")
    private fun getDeviceContacts(contacts: SparseArray<Contact>, ignoredContactSources: HashSet<String>?, gettingDuplicates: Boolean, loadExtendedFields: Boolean = true) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return
        }

        val ignoredSources = ignoredContactSources ?: context.baseConfig.ignoredContactSources
        val uri = Data.CONTENT_URI
        val projection = getContactProjection()

        // Re-establish the unlock state on this thread's Binder connection. The provider
        // tracks unlock state per connection; the main thread's unlock does not carry over
        // to this background thread automatically.
        com.goodwy.commons.helpers.ContactProtectionHelper.ensureUnlockedForThread(context)

        arrayOf(CommonDataKinds.Organization.CONTENT_ITEM_TYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE).forEach { mimetype ->
            val selection = "${Data.MIMETYPE} = ?"
            val selectionArgs = arrayOf(mimetype)
            val sortOrder = getSortString()

            context.queryCursor(uri, projection, selection, selectionArgs, sortOrder, true) { cursor ->
                val accountName = cursor.getStringValue(RawContacts.ACCOUNT_NAME) ?: ""
                val accountType = cursor.getStringValue(RawContacts.ACCOUNT_TYPE) ?: ""

                // Load phone storage and SIM card contacts
                if (!isSimOrPhoneStorage(accountName, accountType)) {
                    return@queryCursor
                }
                // Optimize: Cache account identifier to avoid repeated string concatenation
                val accountIdentifier = if (accountName.isEmpty() && accountType.isEmpty()) {
                    ":"
                } else {
                    "$accountName:$accountType"
                }
                if (ignoredSources.contains(accountIdentifier)) {
                    return@queryCursor
                }

                val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
                
                // Check if contact already exists (e.g., from Organization entry when processing StructuredName, or vice versa)
                val existingContact = contacts.get(id)
                
                var prefix = ""
                var name = ""
                var middleName = ""
                var surname = ""
                var suffix = ""

                // ignore names at Organization type contacts
                if (mimetype == CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) {
                    prefix = cursor.getStringValue(CommonDataKinds.StructuredName.PREFIX) ?: ""
                    val givenName = cursor.getStringValue(CommonDataKinds.StructuredName.GIVEN_NAME) ?: ""
                    middleName = cursor.getStringValue(CommonDataKinds.StructuredName.MIDDLE_NAME) ?: ""
                    val familyName = cursor.getStringValue(CommonDataKinds.StructuredName.FAMILY_NAME) ?: ""
                    suffix = cursor.getStringValue(CommonDataKinds.StructuredName.SUFFIX) ?: ""
                    
                    name = composeStructuredRowDisplayName(cursor, prefix, givenName, middleName, familyName, suffix)
                    // Clear other name fields since we're using single name
                    prefix = ""
                    middleName = ""
                    surname = ""
                    suffix = ""
                    
                    // If contact already exists (from Organization entry), update name but preserve organization
                    if (existingContact != null) {
                        existingContact.firstName = name
                        return@queryCursor
                    }
                } else {
                    // Processing Organization entry - if contact already exists (from StructuredName), skip
                    // Organization will be loaded later via getOrganizations()
                    if (existingContact != null) {
                        return@queryCursor
                    }
                }

                var photoUri = ""
                var starred = 0
                var contactId = 0
                var thumbnailUri = ""
                var ringtone: String? = null

                if (!gettingDuplicates) {
                    photoUri = cursor.getStringValue(CommonDataKinds.StructuredName.PHOTO_URI) ?: ""
                    starred = cursor.getIntValue(CommonDataKinds.StructuredName.STARRED)
                    contactId = cursor.getIntValue(Data.CONTACT_ID)
                    thumbnailUri = cursor.getStringValue(CommonDataKinds.StructuredName.PHOTO_THUMBNAIL_URI) ?: ""
                    ringtone = cursor.getStringValue(CommonDataKinds.StructuredName.CUSTOM_RINGTONE)
                }

                val nickname = ""
                val numbers = ArrayList<PhoneNumber>()          // proper value is obtained below
                val emails = ArrayList<Email>()
                val addresses = ArrayList<Address>()
                val events = ArrayList<Event>()
                val notes = ""
                val groups = ArrayList<Group>()
                val organization = Organization("", "")
                val ims = ArrayList<IM>()
                val contact = Contact(
                    id, prefix, name, middleName, surname, suffix, nickname,
                    photoUri, numbers, emails, addresses, events, accountName,
                    starred, contactId, thumbnailUri, null, notes, groups, organization,
                    ims, mimetype, ringtone
                )

                contacts.put(id, contact)
            }
        }

        // The provider's unlock_all_with_pin lifts protection on the contacts/raw_contacts views
        // but NOT on Data.CONTENT_URI — protected contacts' data rows remain hidden even after
        // unlock. Load any unlocked contacts missed by the Data pass using Contacts.CONTENT_URI,
        // which does respect the unlock state.
        if (ContactProtectionHelper.isUnlockedInSession()) {
            val unlockedIds = ContactProtectionHelper.getUnlockedRawContactIds()

            // Secure mode: show ONLY unlocked contacts
            contacts.clear()

            if (unlockedIds != null && unlockedIds.isNotEmpty()) {
                val idsClause = unlockedIds.joinToString(",")

                // Step 1 — raw_id → (contact_id, account_name)
                val rawIdToContactId = mutableMapOf<Int, Int>()
                val rawIdToAccountName = mutableMapOf<Int, String>()
                context.queryCursor(
                    RawContacts.CONTENT_URI,
                    arrayOf(RawContacts._ID, RawContacts.CONTACT_ID, RawContacts.ACCOUNT_NAME),
                    "${RawContacts._ID} IN ($idsClause)", null, null, true
                ) { cursor ->
                    val rawId = cursor.getIntValue(RawContacts._ID)
                    rawIdToContactId[rawId] = cursor.getIntValue(RawContacts.CONTACT_ID)
                    rawIdToAccountName[rawId] = cursor.getStringValue(RawContacts.ACCOUNT_NAME) ?: ""
                }

                val contactIdSet = rawIdToContactId.values.toSet()
                if (contactIdSet.isNotEmpty()) {
                    data class ContactInfo(
                        val displayName: String, val photoUri: String,
                        val thumbnailUri: String, val starred: Int, val ringtone: String?
                    )
                    val contactInfoMap = mutableMapOf<Int, ContactInfo>()
                    context.queryCursor(
                        Contacts.CONTENT_URI,
                        arrayOf(
                            Contacts._ID, Contacts.DISPLAY_NAME_PRIMARY,
                            Contacts.PHOTO_URI, Contacts.PHOTO_THUMBNAIL_URI,
                            Contacts.STARRED, Contacts.CUSTOM_RINGTONE
                        ),
                        "${Contacts._ID} IN (${contactIdSet.joinToString(",")})",
                        null, null, true
                    ) { cursor ->
                        val cid = cursor.getIntValue(Contacts._ID)
                        contactInfoMap[cid] = ContactInfo(
                            displayName = cursor.getStringValue(Contacts.DISPLAY_NAME_PRIMARY) ?: "",
                            photoUri = if (gettingDuplicates) "" else cursor.getStringValue(Contacts.PHOTO_URI) ?: "",
                            thumbnailUri = if (gettingDuplicates) "" else cursor.getStringValue(Contacts.PHOTO_THUMBNAIL_URI) ?: "",
                            starred = if (gettingDuplicates) 0 else cursor.getIntValue(Contacts.STARRED),
                            ringtone = cursor.getStringValue(Contacts.CUSTOM_RINGTONE)
                        )
                    }

                    for ((rawId, contactId) in rawIdToContactId) {
                        val info = contactInfoMap[contactId] ?: continue
                        val accountName = rawIdToAccountName[rawId] ?: ""
                        contacts.put(
                            rawId,
                            Contact(
                                rawId, "", info.displayName, "", "", "", "",
                                info.photoUri, ArrayList(), ArrayList(), ArrayList(), ArrayList(),
                                accountName, info.starred, contactId, info.thumbnailUri,
                                null, "", ArrayList(), Organization("", ""),
                                ArrayList(),
                                CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, info.ringtone
                            )
                        )
                    }
                }
            }

            // IMPORTANT: stop further normal-list population after secure-mode handling
            return
        }

        // Helper function to apply SparseArray values to contacts
        fun <T> applySparseArrayToContacts(sparseArray: SparseArray<T>, apply: (Contact, T) -> Unit) {
            val size = sparseArray.size
            for (i in 0 until size) {
                val key = sparseArray.keyAt(i)
                contacts[key]?.let { apply(it, sparseArray.valueAt(i)) }
            }
        }

        applySparseArrayToContacts(getEmails()) { contact, emails -> contact.emails = emails }
        applySparseArrayToContacts(getOrganizations()) { contact, org -> 
            contact.organization = org
            // If contact has no name but has organization, set firstName to organization name
            // This ensures getNameToDisplay() works correctly for business contacts
            if (contact.firstName.isEmpty()) {
                val fullOrganization = if (org.company.isEmpty()) "" else "${org.company}, "
                val fullCompanyName = (fullOrganization + org.jobPosition).trim().trimEnd(',')
                if (fullCompanyName.isNotEmpty()) {
                    contact.firstName = fullCompanyName
                }
            }
        }

        // no need to fetch some fields if we are only getting duplicates of the current contact
        if (gettingDuplicates) {
            return
        }

        // Populate photo URIs from aggregate Contacts table (same as contact view) so list avatars show photo or monogram.
        // For list loads (loadExtendedFields = false), skip per-contact openContactPhotoInputStream — that is O(n) I/O and
        // dominates startup when many contacts have empty PHOTO_URI but inline photo bytes.
        refreshContactListPhotosFromAggregate(contacts, resolveEmptyPhotoUris = loadExtendedFields)

        val phoneNumbers = getPhoneNumbers(null)
        val phoneSize = phoneNumbers.size
        for (i in 0 until phoneSize) {
            val key = phoneNumbers.keyAt(i)
            contacts[key]?.let { it.phoneNumbers = phoneNumbers.valueAt(i) }
        }

        // Nickname removed - always set to empty
        val contactsSize = contacts.size
        for (i in 0 until contactsSize) {
            contacts.valueAt(i).nickname = ""
        }
        
        // Ensure all contacts have a name set - use fallback logic from getNameToDisplay()
        for (i in 0 until contactsSize) {
            val contact = contacts.valueAt(i)
            if (contact.firstName.isEmpty()) {
                // Try to set name from organization, email, or phone number
                val organization = contact.getFullCompany()
                val email = contact.emails.firstOrNull()?.value?.trim()
                val phoneNumber = contact.phoneNumbers.firstOrNull()?.value
                
                when {
                    organization.isNotEmpty() -> contact.firstName = organization
                    !email.isNullOrEmpty() -> contact.firstName = email
                    !phoneNumber.isNullOrEmpty() -> contact.firstName = phoneNumber
                }
            }
        }
        
        // Only load extended fields if requested (skip for list views to improve performance)
        if (loadExtendedFields) {
            applySparseArrayToContacts(getAddresses()) { contact, addresses -> contact.addresses = addresses }
            applySparseArrayToContacts(getEvents()) { contact, events -> contact.events = events }
            applySparseArrayToContacts(getNotes()) { contact, note -> contact.notes = note }
        }
    }

    /** Legacy provider types we no longer surface map to [CommonDataKinds.Phone.TYPE_OTHER] (mobile/home/work/other/custom model). */
    private fun normalizedPhoneTypeForAppModel(type: Int): Int =
        when (type) {
            CommonDataKinds.Phone.TYPE_MAIN,
            CommonDataKinds.Phone.TYPE_FAX_WORK,
            CommonDataKinds.Phone.TYPE_FAX_HOME,
            CommonDataKinds.Phone.TYPE_PAGER -> CommonDataKinds.Phone.TYPE_OTHER
            else -> type
        }

    private fun getPhoneNumbers(contactId: Int? = null): SparseArray<ArrayList<PhoneNumber>> {
        val phoneNumbers = SparseArray<ArrayList<PhoneNumber>>()
        val uri = CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            CommonDataKinds.Phone.NUMBER,
            CommonDataKinds.Phone.NORMALIZED_NUMBER,
            CommonDataKinds.Phone.TYPE,
            CommonDataKinds.Phone.LABEL,
            CommonDataKinds.Phone.IS_PRIMARY
        )

        val selection = if (contactId == null) getSourcesSelection() else "${Data.RAW_CONTACT_ID} = ?"
        val selectionArgs = if (contactId == null) getSourcesSelectionArgs() else arrayOf(contactId.toString())

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val number = cursor.getStringValue(CommonDataKinds.Phone.NUMBER) ?: return@queryCursor
            val normalizedNumber = cursor.getStringValue(CommonDataKinds.Phone.NORMALIZED_NUMBER) ?: number.normalizePhoneNumber()
            val type = normalizedPhoneTypeForAppModel(cursor.getIntValue(CommonDataKinds.Phone.TYPE))
            val label = cursor.getStringValue(CommonDataKinds.Phone.LABEL) ?: ""
            val isPrimary = cursor.getIntValue(CommonDataKinds.Phone.IS_PRIMARY) != 0

            if (phoneNumbers[id] == null) {
                phoneNumbers.put(id, ArrayList())
            }

            val phoneNumber = PhoneNumber(number, type, label, normalizedNumber, isPrimary)
            phoneNumbers[id].add(phoneNumber)
        }

        return phoneNumbers
    }

    private fun getNicknames(contactId: Int? = null): SparseArray<String> {
        val nicknames = SparseArray<String>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            CommonDataKinds.Nickname.NAME
        )

        val selection = getSourcesSelection(true, contactId != null)
        val selectionArgs = getSourcesSelectionArgs(CommonDataKinds.Nickname.CONTENT_ITEM_TYPE, contactId)

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val nickname = cursor.getStringValue(CommonDataKinds.Nickname.NAME) ?: return@queryCursor
            nicknames.put(id, nickname)
        }

        return nicknames
    }

    private fun getEmails(contactId: Int? = null): SparseArray<ArrayList<Email>> {
        val emails = SparseArray<ArrayList<Email>>()
        val uri = CommonDataKinds.Email.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            CommonDataKinds.Email.DATA,
            CommonDataKinds.Email.TYPE,
            CommonDataKinds.Email.LABEL
        )

        val selection = if (contactId == null) getSourcesSelection() else "${Data.RAW_CONTACT_ID} = ?"
        val selectionArgs = if (contactId == null) getSourcesSelectionArgs() else arrayOf(contactId.toString())

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val email = cursor.getStringValue(CommonDataKinds.Email.DATA) ?: return@queryCursor
            val type = cursor.getIntValue(CommonDataKinds.Email.TYPE)
            val label = cursor.getStringValue(CommonDataKinds.Email.LABEL) ?: ""

            if (emails[id] == null) {
                emails.put(id, ArrayList())
            }

            emails[id]!!.add(Email(email, type, label))
        }

        return emails
    }

    private fun getAddresses(contactId: Int? = null): SparseArray<ArrayList<Address>> {
        val addresses = SparseArray<ArrayList<Address>>()
        val uri = CommonDataKinds.StructuredPostal.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
            CommonDataKinds.StructuredPostal.COUNTRY,
            CommonDataKinds.StructuredPostal.REGION,
            CommonDataKinds.StructuredPostal.CITY,
            CommonDataKinds.StructuredPostal.POSTCODE,
            CommonDataKinds.StructuredPostal.POBOX,
            CommonDataKinds.StructuredPostal.STREET,
            CommonDataKinds.StructuredPostal.NEIGHBORHOOD,
            CommonDataKinds.StructuredPostal.TYPE,
            CommonDataKinds.StructuredPostal.LABEL
        )

        val selection = if (contactId == null) getSourcesSelection() else "${Data.RAW_CONTACT_ID} = ?"
        val selectionArgs = if (contactId == null) getSourcesSelectionArgs() else arrayOf(contactId.toString())

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val address = cursor.getStringValue(CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS) ?: return@queryCursor
            val country = cursor.getStringValue(CommonDataKinds.StructuredPostal.COUNTRY) ?: ""
            val region = cursor.getStringValue(CommonDataKinds.StructuredPostal.REGION) ?: ""
            val city = cursor.getStringValue(CommonDataKinds.StructuredPostal.CITY) ?: ""
            val postcode = cursor.getStringValue(CommonDataKinds.StructuredPostal.POSTCODE) ?: ""
            val pobox = cursor.getStringValue(CommonDataKinds.StructuredPostal.POBOX) ?: ""
            val street = cursor.getStringValue(CommonDataKinds.StructuredPostal.STREET) ?: ""
            val neighborhood = cursor.getStringValue(CommonDataKinds.StructuredPostal.NEIGHBORHOOD) ?: ""
            val type = cursor.getIntValue(CommonDataKinds.StructuredPostal.TYPE)
            val label = cursor.getStringValue(CommonDataKinds.StructuredPostal.LABEL) ?: ""

            if (addresses[id] == null) {
                addresses.put(id, ArrayList())
            }

            addresses[id]!!.add(Address(address, type, label, country, region, city, postcode, pobox, street,
                neighborhood))
        }

        return addresses
    }

    private fun getEvents(contactId: Int? = null): SparseArray<ArrayList<Event>> {
        val events = SparseArray<ArrayList<Event>>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            CommonDataKinds.Event.START_DATE,
            CommonDataKinds.Event.TYPE,
            CommonDataKinds.Event.LABEL
        )

        val selection = getSourcesSelection(true, contactId != null)
        val selectionArgs = getSourcesSelectionArgs(CommonDataKinds.Event.CONTENT_ITEM_TYPE, contactId)

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val startDate = cursor.getStringValue(CommonDataKinds.Event.START_DATE) ?: ""
            val type = cursor.getIntValue(CommonDataKinds.Event.TYPE)
            if (type != CommonDataKinds.Event.TYPE_BIRTHDAY || startDate.isBlank()) {
                return@queryCursor
            }

            if (events[id] == null) {
                events.put(id, ArrayList())
            }

            events[id]!!.add(Event(startDate, CommonDataKinds.Event.TYPE_BIRTHDAY, ""))
        }

        return events
    }

    private fun getNotes(contactId: Int? = null): SparseArray<String> {
        val notes = SparseArray<String>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            CommonDataKinds.Note.NOTE
        )

        val selection = getSourcesSelection(true, contactId != null)
        val selectionArgs = getSourcesSelectionArgs(CommonDataKinds.Note.CONTENT_ITEM_TYPE, contactId)

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val note = cursor.getStringValue(CommonDataKinds.Note.NOTE) ?: return@queryCursor
            notes.put(id, note)
        }

        return notes
    }

    private fun getOrganizations(contactId: Int? = null): SparseArray<Organization> {
        val organizations = SparseArray<Organization>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            CommonDataKinds.Organization.COMPANY,
            CommonDataKinds.Organization.TITLE
        )

        val selection = getSourcesSelection(true, contactId != null)
        val selectionArgs = getSourcesSelectionArgs(CommonDataKinds.Organization.CONTENT_ITEM_TYPE, contactId)

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val company = cursor.getStringValue(CommonDataKinds.Organization.COMPANY) ?: ""
            val title = cursor.getStringValue(CommonDataKinds.Organization.TITLE) ?: ""
            if (company.isEmpty() && title.isEmpty()) {
                return@queryCursor
            }

            val organization = Organization(company, title)
            organizations.put(id, organization)
        }

        return organizations
    }

    private fun getContactGroups(storedGroups: ArrayList<Group>, contactId: Int? = null): SparseArray<ArrayList<Group>> {
        val groups = SparseArray<ArrayList<Group>>()
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return groups
        }

        val uri = Data.CONTENT_URI
        val projection = arrayOf(
            Data.CONTACT_ID,
            Data.DATA1
        )

        val selection = getSourcesSelection(true, contactId != null, false)
        val selectionArgs = getSourcesSelectionArgs(CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, contactId)

        // Use map for O(1) lookup instead of firstOrNull in loop
        val storedGroupsMap = storedGroups.associateBy { it.id }
        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getIntValue(Data.CONTACT_ID)
            val newRowId = cursor.getLongValue(Data.DATA1)

            val groupTitle = storedGroupsMap[newRowId]?.title ?: return@queryCursor
            val group = Group(newRowId, groupTitle)
            if (groups[id] == null) {
                groups.put(id, ArrayList())
            }
            groups[id]!!.add(group)
        }

        return groups
    }

    private fun getQuestionMarksForSources() = ("?," * displayContactSources.filter { it.isNotEmpty() }.size).trimEnd(',')

    private fun getSourcesSelection(addMimeType: Boolean = false, addContactId: Boolean = false, useRawContactId: Boolean = true): String {
        val strings = ArrayList<String>()
        if (addMimeType) {
            strings.add("${Data.MIMETYPE} = ?")
        }

        if (addContactId) {
            strings.add("${if (useRawContactId) Data.RAW_CONTACT_ID else Data.CONTACT_ID} = ?")
        } else {
            // sometimes local device storage has null account_name, handle it properly
            val accountnameString = StringBuilder()
            if (displayContactSources.contains("")) {
                accountnameString.append("(")
            }
            accountnameString.append("${RawContacts.ACCOUNT_NAME} IN (${getQuestionMarksForSources()})")
            if (displayContactSources.contains("")) {
                accountnameString.append(" OR ${RawContacts.ACCOUNT_NAME} IS NULL)")
            }
            strings.add(accountnameString.toString())
        }

        return TextUtils.join(" AND ", strings)
    }

    private fun getSourcesSelectionArgs(mimetype: String? = null, contactId: Int? = null): Array<String> {
        val args = ArrayList<String>()

        if (mimetype != null) {
            args.add(mimetype)
        }

        if (contactId != null) {
            args.add(contactId.toString())
        } else {
            args.addAll(displayContactSources.filter { it.isNotEmpty() })
        }

        return args.toTypedArray()
    }

    fun getStoredGroups(callback: (ArrayList<Group>) -> Unit) {
        ensureBackgroundThread {
            val groups = getStoredGroupsSync()
            Handler(Looper.getMainLooper()).post {
                callback(groups)
            }
        }
    }

    fun getStoredGroupsSync(): ArrayList<Group> {
        val groups = getDeviceStoredGroups()
        groups.addAll(context.groupsDB.getGroups())
        return groups
    }

    private fun getDeviceStoredGroups(): ArrayList<Group> {
        val groups = ArrayList<Group>()
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return groups
        }

        val uri = Groups.CONTENT_URI
        val projection = arrayOf(
            Groups._ID,
            Groups.TITLE,
            Groups.SYSTEM_ID,
            Groups.ACCOUNT_NAME,
            Groups.ACCOUNT_TYPE,
        )

        val selection = "${Groups.AUTO_ADD} = ? AND ${Groups.FAVORITES} = ?"
        val selectionArgs = arrayOf("0", "0")

        // Optimize: Use HashSet for O(1) lookup instead of O(n) map.contains
        val seenTitles = HashSet<String>()
        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val accountName = cursor.getStringValue(Groups.ACCOUNT_NAME) ?: ""
            val accountType = cursor.getStringValue(Groups.ACCOUNT_TYPE) ?: ""
            // Local phone storage groups only (same account as [createNewGroup] with empty name/type).
            if (accountName.isNotEmpty() || accountType.isNotEmpty()) {
                return@queryCursor
            }

            val id = cursor.getLongValue(Groups._ID)
            val title = cursor.getStringValue(Groups.TITLE) ?: return@queryCursor

            val systemId = cursor.getStringValue(Groups.SYSTEM_ID)
            if (!seenTitles.add(title) && systemId != null) {
                return@queryCursor
            }

            groups.add(Group(id, title))
        }
        return groups
    }

    fun createNewGroup(title: String, accountName: String, accountType: String): Group? {
        val operations = ArrayList<ContentProviderOperation>()
        ContentProviderOperation.newInsert(Groups.CONTENT_URI).apply {
            withValue(Groups.TITLE, title)
            withValue(Groups.GROUP_VISIBLE, 1)
            withValue(Groups.ACCOUNT_NAME, accountName)
            withValue(Groups.ACCOUNT_TYPE, accountType)
            operations.add(build())
        }

        try {
            val results = context.contentResolver.applyBatch(AUTHORITY, operations)
            val rawId = ContentUris.parseId(results[0].uri!!)
            return Group(rawId, title)
        } catch (e: Exception) {
            context.showErrorToast(e)
        }
        return null
    }

    fun renameGroup(group: Group) {
        val operations = ArrayList<ContentProviderOperation>()
        ContentProviderOperation.newUpdate(Groups.CONTENT_URI).apply {
            val selection = "${Groups._ID} = ?"
            val selectionArgs = arrayOf(group.id.toString())
            withSelection(selection, selectionArgs)
            withValue(Groups.TITLE, group.title)
            operations.add(build())
        }

        try {
            context.contentResolver.applyBatch(AUTHORITY, operations)
        } catch (e: Exception) {
            context.showErrorToast(e)
        }
    }

    fun deleteGroup(id: Long) {
        val operations = ArrayList<ContentProviderOperation>()
        val uri = ContentUris.withAppendedId(Groups.CONTENT_URI, id).buildUpon()
            .appendQueryParameter(CALLER_IS_SYNCADAPTER, "true")
            .build()

        operations.add(ContentProviderOperation.newDelete(uri).build())

        try {
            context.contentResolver.applyBatch(AUTHORITY, operations)
        } catch (e: Exception) {
            context.showErrorToast(e)
        }
    }

    fun getContactWithId(id: Int): Contact? {
        if (id == 0) {
            return null
        }

        val selection = "(${Data.MIMETYPE} = ? OR ${Data.MIMETYPE} = ?) AND ${Data.RAW_CONTACT_ID} = ?"
        val selectionArgs = arrayOf(CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, CommonDataKinds.Organization.CONTENT_ITEM_TYPE, id.toString())
        val contact = parseContactCursor(selection, selectionArgs)
        if (contact != null) {
            // Photo is stored on the aggregate Contacts table; Data rows don't expose it.
            // Refresh photo URI from Contacts.CONTENT_URI so updated avatars show after edit.
            if (contact.contactId != 0) {
                refreshContactPhotoFromAggregate(contact)
            }
            return contact
        }

        // Fallback: Data.CONTENT_URI does not expose rows for protected contacts even after
        // unlock_all_with_pin. If this raw contact is a known unlocked contact, build the
        // Contact from Contacts.CONTENT_URI which does respect the unlock state.
        if (!ContactProtectionHelper.isUnlockedInSession()) return null
        val unlockedIds = ContactProtectionHelper.getUnlockedRawContactIds() ?: return null
        if (!unlockedIds.contains(id.toLong())) return null
        return buildContactFromContactsUri(id)
    }

    private fun buildContactFromContactsUri(rawContactId: Int): Contact? {
        // Step 1: raw_contact_id → (contact_id, account_name)
        var contactId = 0
        var accountName = ""
        context.contentResolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts.CONTACT_ID, RawContacts.ACCOUNT_NAME),
            "${RawContacts._ID} = ?", arrayOf(rawContactId.toString()), null
        )?.use { c ->
            if (c.moveToFirst()) {
                contactId = c.getIntValue(RawContacts.CONTACT_ID)
                accountName = c.getStringValue(RawContacts.ACCOUNT_NAME) ?: ""
            }
        }
        if (contactId == 0) return null

        // Step 2: contact_id → display info from Contacts.CONTENT_URI (unlocked here)
        var displayName = ""
        var photoUri = ""
        var thumbnailUri = ""
        var starred = 0
        var ringtone: String? = null
        context.contentResolver.query(
            Contacts.CONTENT_URI,
            arrayOf(Contacts._ID, Contacts.DISPLAY_NAME_PRIMARY,
                    Contacts.PHOTO_URI, Contacts.PHOTO_THUMBNAIL_URI,
                    Contacts.STARRED, Contacts.CUSTOM_RINGTONE),
            "${Contacts._ID} = ?", arrayOf(contactId.toString()), null
        )?.use { c ->
            if (c.moveToFirst()) {
                displayName  = c.getStringValue(Contacts.DISPLAY_NAME_PRIMARY) ?: ""
                photoUri     = c.getStringValue(Contacts.PHOTO_URI) ?: ""
                thumbnailUri = c.getStringValue(Contacts.PHOTO_THUMBNAIL_URI) ?: ""
                starred      = c.getIntValue(Contacts.STARRED)
                ringtone     = c.getStringValue(Contacts.CUSTOM_RINGTONE)
            }
        }

        // Step 3: load extended data rows by raw contact ID (may be empty if Data is still
        // filtered for this contact, but at minimum the detail view will open)
        val phoneNumbers = getPhoneNumbers(rawContactId)[rawContactId] ?: ArrayList()
        val emails       = getEmails(rawContactId)[rawContactId]       ?: ArrayList()
        val addresses    = getAddresses(rawContactId)[rawContactId]    ?: ArrayList()
        val events       = getEvents(rawContactId)[rawContactId]       ?: ArrayList()
        val notes        = getNotes(rawContactId)[rawContactId]        ?: ""
        val organization = getOrganizations(rawContactId)[rawContactId] ?: Organization("", "")
        val storedGroups = getStoredGroupsSync()
        val groups       = getContactGroups(storedGroups, contactId)[contactId] ?: ArrayList()

        return Contact(
            rawContactId, "", displayName, "", "", "", "",
            photoUri, phoneNumbers, emails, addresses, events, accountName,
            starred, contactId, thumbnailUri, null, notes, groups, organization,
            ArrayList(),
            CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, ringtone
        )
    }

    fun getContactFromUri(uri: Uri): Contact? {
        val key = getLookupKeyFromUri(uri) ?: return null
        return getContactWithLookupKey(key)
    }

    private fun getLookupKeyFromUri(lookupUri: Uri): String? {
        val projection = arrayOf(Contacts.LOOKUP_KEY)
        val cursor = context.contentResolver.query(lookupUri, projection, null, null, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                return cursor.getStringValue(Contacts.LOOKUP_KEY)
            }
        }
        return null
    }

    fun getContactWithLookupKey(key: String): Contact? {
        val selection = "(${Data.MIMETYPE} = ? OR ${Data.MIMETYPE} = ?) AND ${Data.LOOKUP_KEY} = ?"
        val selectionArgs = arrayOf(CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, CommonDataKinds.Organization.CONTENT_ITEM_TYPE, key)
        return parseContactCursor(selection, selectionArgs)
    }

    private fun parseContactCursor(selection: String, selectionArgs: Array<String>): Contact? {
        var storedGroups: ArrayList<Group>? = null
        val latch = java.util.concurrent.CountDownLatch(1)

        ensureBackgroundThread {
            storedGroups = getStoredGroupsSync()
            latch.countDown()
        }

        latch.await() // Waiting for the background task to complete
        storedGroups = storedGroups ?: ArrayList()

        val uri = Data.CONTENT_URI
        val projection = getContactProjection()

        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                val id = cursor.getIntValue(Data.RAW_CONTACT_ID)

                var prefix = ""
                var name = ""
                var middleName = ""
                var surname = ""
                var suffix = ""
                var mimetype = cursor.getStringValue(Data.MIMETYPE)

                // if first line is an Organization type contact, go to next line if available
                if (mimetype != CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) {
                    if (!cursor.isLast && cursor.moveToNext()) {
                        mimetype = cursor.getStringValue(Data.MIMETYPE)
                    }
                }
                // ignore names at Organization type contacts
                if (mimetype == CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) {
                    prefix = cursor.getStringValue(CommonDataKinds.StructuredName.PREFIX) ?: ""
                    val givenName = cursor.getStringValue(CommonDataKinds.StructuredName.GIVEN_NAME) ?: ""
                    middleName = cursor.getStringValue(CommonDataKinds.StructuredName.MIDDLE_NAME) ?: ""
                    val familyName = cursor.getStringValue(CommonDataKinds.StructuredName.FAMILY_NAME) ?: ""
                    suffix = cursor.getStringValue(CommonDataKinds.StructuredName.SUFFIX) ?: ""
                    
                    name = composeStructuredRowDisplayName(cursor, prefix, givenName, middleName, familyName, suffix)
                    // Clear other name fields since we're using single name
                    prefix = ""
                    middleName = ""
                    surname = ""
                    suffix = ""
                }

                val nickname = "" // Nickname removed
                val photoUri = cursor.getStringValueOrNull(CommonDataKinds.Phone.PHOTO_URI) ?: ""
                val number = getPhoneNumbers(id)[id] ?: ArrayList()
                val emails = getEmails(id)[id] ?: ArrayList()
                val addresses = getAddresses(id)[id] ?: ArrayList()
                val events = getEvents(id)[id] ?: ArrayList()
                val notes = getNotes(id)[id] ?: ""
                val accountName = cursor.getStringValue(RawContacts.ACCOUNT_NAME) ?: ""
                val starred = cursor.getIntValue(CommonDataKinds.StructuredName.STARRED)
                val ringtone = cursor.getStringValue(CommonDataKinds.StructuredName.CUSTOM_RINGTONE)
                val contactId = cursor.getIntValue(Data.CONTACT_ID)
                val groups = getContactGroups(storedGroups, contactId)[contactId] ?: ArrayList()
                val thumbnailUri = cursor.getStringValue(CommonDataKinds.StructuredName.PHOTO_THUMBNAIL_URI) ?: ""
                val organization = getOrganizations(id)[id] ?: Organization("", "")
                val ims = ArrayList<IM>() // IMs removed - always empty
                return Contact(
                    id, prefix, name, middleName, surname, suffix, nickname,
                    photoUri, number, emails, addresses, events, accountName, starred,
                    contactId, thumbnailUri, null, notes, groups, organization,
                    ims, mimetype, ringtone
                )
            }
        }

        return null
    }

    /**
     * Refreshes contact.photoUri and contact.thumbnailUri from the aggregate Contacts table.
     * When the aggregate returns empty (e.g. after save or aggregation lag), tries the contact's
     * photo sub-URI (Contacts.CONTENT_URI/contactId/photo) so the photo still shows in list/view.
     */
    private fun refreshContactPhotoFromAggregate(contact: Contact) {
        if (contact.contactId == 0) return
        val cursor = context.contentResolver.query(
            Contacts.CONTENT_URI,
            arrayOf(Contacts.PHOTO_URI, Contacts.PHOTO_THUMBNAIL_URI),
            "${Contacts._ID} = ?",
            arrayOf(contact.contactId.toString()),
            null
        )
        cursor?.use { c ->
            if (c.moveToFirst()) {
                var photoUri = c.getStringValue(Contacts.PHOTO_URI) ?: ""
                var thumbnailUri = c.getStringValue(Contacts.PHOTO_THUMBNAIL_URI) ?: ""
                if (photoUri.isEmpty()) {
                    val fallback = getContactPhotoUriFromProvider(contact.contactId, contact.id)
                    if (!fallback.isEmpty()) {
                        photoUri = fallback
                        thumbnailUri = fallback
                    }
                }
                contact.photoUri = photoUri
                contact.thumbnailUri = thumbnailUri
            }
        }
    }

    /**
     * When Contacts.PHOTO_URI is empty the aggregate may still have photo data. Use the platform
     * openContactPhotoInputStream(contactUri) to detect; if it returns a stream, use the contact's
     * photo sub-URI for display so Glide can load it.
     */
    private fun getContactPhotoUriFromProvider(contactId: Int, rawContactId: Int = 0): String {
        if (contactId == 0) return ""
        val contactUri = ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId.toLong())
        val contactPhotoUri = try {
            Contacts.openContactPhotoInputStream(context.contentResolver, contactUri)?.use {
                Uri.withAppendedPath(contactUri, Contacts.Photo.CONTENT_DIRECTORY).toString()
            } ?: ""
        } catch (_: Exception) {
            ""
        }
        if (contactPhotoUri.isNotEmpty()) {
            return contactPhotoUri
        }

        if (rawContactId != 0) {
            val rawUri = ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId.toLong())
            val rawDisplayPhotoUri = Uri.withAppendedPath(rawUri, RawContacts.DisplayPhoto.CONTENT_DIRECTORY)
            return try {
                context.contentResolver.openAssetFileDescriptor(rawDisplayPhotoUri, "r")?.use {
                    rawDisplayPhotoUri.toString()
                } ?: ""
            } catch (_: Exception) {
                ""
            }
        }

        return ""
    }

    /**
     * Batch-refresh photoUri and thumbnailUri for all contacts in the list from the aggregate Contacts table.
     * Ensures main contact list avatars use the same logic as contact view: photo when available, else monogram.
     *
     * @param resolveEmptyPhotoUris When true, contacts with empty [Contacts.PHOTO_URI] are probed via
     * [getContactPhotoUriFromProvider] (expensive for large lists). When false, only batch URI columns apply
     * (fast path for main list).
     */
    private fun refreshContactListPhotosFromAggregate(contacts: SparseArray<Contact>, resolveEmptyPhotoUris: Boolean = true) {
        val contactIds = mutableSetOf<Int>()
        val size = contacts.size
        for (i in 0 until size) {
            val c = contacts.valueAt(i)
            if (c.contactId != 0) contactIds.add(c.contactId)
        }
        if (contactIds.isEmpty()) return
        val photoMap = mutableMapOf<Int, Pair<String, String>>()
        // Chunk IN (...) to stay under SQLite limits and avoid huge single queries.
        val idChunks = contactIds.chunked(450)
        for (chunk in idChunks) {
            val idList = chunk.joinToString(",")
            context.contentResolver.query(
                Contacts.CONTENT_URI,
                arrayOf(Contacts._ID, Contacts.PHOTO_URI, Contacts.PHOTO_THUMBNAIL_URI),
                "${Contacts._ID} IN ($idList)",
                null,
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val contactId = c.getIntValue(Contacts._ID)
                    var photoUri = c.getStringValue(Contacts.PHOTO_URI) ?: ""
                    var thumbnailUri = c.getStringValue(Contacts.PHOTO_THUMBNAIL_URI) ?: ""
                    photoMap[contactId] = Pair(photoUri, thumbnailUri)
                }
            }
        }
        for (i in 0 until size) {
            val contact = contacts.valueAt(i)
            val mapped = photoMap[contact.contactId]
            if (mapped != null) {
                var photoUri = mapped.first
                var thumbnailUri = mapped.second
                if (photoUri.isEmpty() && resolveEmptyPhotoUris) {
                    val fallback = getContactPhotoUriFromProvider(contact.contactId, contact.id)
                    if (fallback.isNotEmpty()) {
                        photoUri = fallback
                        thumbnailUri = fallback
                    }
                }
                contact.photoUri = photoUri
                contact.thumbnailUri = thumbnailUri
            }
        }
    }

    /**
     * Returns a copy of contact sources if [getDeviceContactSources] has already populated the
     * in-memory cache; otherwise null. Safe to call on the main thread when the cache is warm
     * (avoids a background hop for dialogs that open repeatedly).
     */
    fun peekCachedContactSources(): ArrayList<ContactSource>? {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return null
        }
        val cacheKey = "contact_sources_${context.baseConfig.wasLocalAccountInitialized}"
        val cachedSources = contactSourcesCache[cacheKey] ?: return null
        return ArrayList(cachedSources.map { it.copy() })
    }

    fun getContactSources(callback: (ArrayList<ContactSource>) -> Unit) {
        ensureBackgroundThread {
            callback(getContactSourcesSync())
        }
    }

    private fun getContactSourcesSync(): ArrayList<ContactSource> {
        val sources = getDeviceContactSources()
        return ArrayList(sources)
    }

    fun getSaveableContactSources(callback: (ArrayList<ContactSource>) -> Unit) {
        ensureBackgroundThread {
            val ignoredTypes = arrayListOf(
                THREEMA_PACKAGE
            )

            val contactSources = getContactSourcesSync()
            val filteredSources = contactSources.filter { !ignoredTypes.contains(it.type) }.toMutableList() as ArrayList<ContactSource>
            callback(filteredSources)
        }
    }

    fun getDeviceContactSources(): LinkedHashSet<ContactSource> {
        val sources = LinkedHashSet<ContactSource>()
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return sources
        }

        // Method-level caching
        val cacheKey = "contact_sources_${context.baseConfig.wasLocalAccountInitialized}"
        val cachedSources = contactSourcesCache[cacheKey]
        if (cachedSources != null) {
            return LinkedHashSet(cachedSources)
        }

        if (!context.baseConfig.wasLocalAccountInitialized) {
            initializeLocalPhoneAccount()
            context.baseConfig.wasLocalAccountInitialized = true
        }

        val phoneStorageName = context.getString(R.string.phone_storage)
        val accounts = AccountManager.get(context).accounts
        val seenAccountKeys = HashSet<String>()
        val existingAccountKeys = HashSet<String>().apply {
            for (account in accounts) {
                add("${account.name}|${account.type}")
            }
        }
        
        for (account in accounts) {
            if (ContentResolver.getIsSyncable(account, AUTHORITY) >= 0) {
                // Only include SIM card and phone storage accounts
                if (isSimOrPhoneStorage(account.name, account.type)) {
                    val accountKey = "${account.name}|${account.type}"
                    if (seenAccountKeys.add(accountKey)) {
                        val public = context.resolveSimAccountDisplayName(account.name, account.type)
                        sources.add(ContactSource(account.name, account.type, public))
                    }
                }
            }
        }

        var hadEmptyAccount = false
        val contentResolverAccounts = getContentResolverAccounts()
        for (account in contentResolverAccounts) {
            when {
                account.name.isEmpty() && account.type.isEmpty() -> {
                    if (!hadEmptyAccount) {
                        val hasPhoneAccount = contentResolverAccounts.any {
                            it.name.lowercase(Locale.getDefault()) == "phone"
                        }
                        hadEmptyAccount = !hasPhoneAccount
                    }
                }
                account.name.isNotEmpty() && account.type.isNotEmpty() -> {
                    // Only include SIM card accounts from content resolver
                    val typeLower = account.type.lowercase(Locale.getDefault())
                    val isSimCard = typeLower.contains("sim") || typeLower.contains("icc")
                    if (isSimCard) {
                        val accountKey = "${account.name}|${account.type}"
                        if (!existingAccountKeys.contains(accountKey)) {
                            val public = context.resolveSimAccountDisplayName(account.name, account.type)
                            sources.add(ContactSource(account.name, account.type, public))
                        }
                    }
                }
            }
        }

        if (hadEmptyAccount) {
            sources.add(ContactSource("", "", phoneStorageName))
        }

        // Cache the result
        contactSourcesCache[cacheKey] = LinkedHashSet(sources)
        return sources
    }

    fun clearContactSourcesCache() {
        contactSourcesCache.clear()
        cachedContactSourcesMap = null
    }

    // make sure the local Phone contact source is initialized and available
    // https://stackoverflow.com/a/6096508/1967672
    private fun initializeLocalPhoneAccount() {
        try {
            val operations = ArrayList<ContentProviderOperation>()
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI).apply {
                withValue(RawContacts.ACCOUNT_NAME, null)
                withValue(RawContacts.ACCOUNT_TYPE, null)
                operations.add(build())
            }

            val results = context.contentResolver.applyBatch(AUTHORITY, operations)
            val rawContactUri = results.firstOrNull()?.uri ?: return
            context.contentResolver.delete(rawContactUri, null, null)
        } catch (ignored: Exception) {
        }
    }

    // Cache: RawContacts account name -> type (phone storage + SIM accounts from [getDeviceContactSources]).
    private var cachedContactSourcesMap: Map<String, String>? = null

    fun getContactSourceType(accountName: String): String {
        if (cachedContactSourcesMap == null) {
            cachedContactSourcesMap = getDeviceContactSources().associateBy({ it.name }, { it.type })
        }
        return cachedContactSourcesMap?.get(accountName) ?: ""
    }

    private fun getContactProjection() = arrayOf(
        Data.MIMETYPE,
        Data.CONTACT_ID,
        Data.RAW_CONTACT_ID,
        CommonDataKinds.StructuredName.PREFIX,
        CommonDataKinds.StructuredName.GIVEN_NAME,
        CommonDataKinds.StructuredName.MIDDLE_NAME,
        CommonDataKinds.StructuredName.FAMILY_NAME,
        CommonDataKinds.StructuredName.SUFFIX,
        CommonDataKinds.StructuredName.DISPLAY_NAME,
        CommonDataKinds.StructuredName.PHOTO_URI,
        CommonDataKinds.StructuredName.PHOTO_THUMBNAIL_URI,
        CommonDataKinds.StructuredName.STARRED,
        CommonDataKinds.StructuredName.CUSTOM_RINGTONE,
        RawContacts.ACCOUNT_NAME,
        RawContacts.ACCOUNT_TYPE
    )

    private fun getSortString(): String {
        val sorting = context.baseConfig.sorting
        return when {
            sorting and SORT_BY_FIRST_NAME != 0 -> "${CommonDataKinds.StructuredName.GIVEN_NAME} COLLATE NOCASE"
            sorting and SORT_BY_MIDDLE_NAME != 0 -> "${CommonDataKinds.StructuredName.MIDDLE_NAME} COLLATE NOCASE"
            sorting and SORT_BY_SURNAME != 0 -> "${CommonDataKinds.StructuredName.FAMILY_NAME} COLLATE NOCASE"
            sorting and SORT_BY_FULL_NAME != 0 -> CommonDataKinds.StructuredName.DISPLAY_NAME
            else -> Data.RAW_CONTACT_ID
        }
    }

    private fun getRealContactId(id: Long): Int {
        val uri = Data.CONTENT_URI
        val projection = getContactProjection()
        val selection = "(${Data.MIMETYPE} = ? OR ${Data.MIMETYPE} = ?) AND ${Data.RAW_CONTACT_ID} = ?"
        val selectionArgs = arrayOf(CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, CommonDataKinds.Organization.CONTENT_ITEM_TYPE, id.toString())

        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                return cursor.getIntValue(Data.CONTACT_ID)
            }
        }

        return 0
    }

    fun updateContact(contact: Contact, photoUpdateStatus: Int, showUpdatingToast: Boolean = true): Boolean {
        if (showUpdatingToast) {
            context.toast(R.string.updating)
        }

        var accountType = getContactSourceType(contact.source)
        if (accountType.isEmpty() && contact.source.isNotEmpty()) {
            clearContactSourcesCache()
            accountType = getContactSourceType(contact.source)
        }
        val isSimDestination = isSimAccountTypeForPersistence(accountType)
        val previousSimSnapshot = if (isSimDestination && contact.id != 0) {
            getContactWithId(contact.id)
        } else {
            null
        }

        try {
            if (isSimDestination) {
                android.util.Log.d(
                    "SimContactSave",
                    "updateContact: SIM path rawId=${contact.id} contactId=${contact.contactId} accountName=${contact.source} accountType=$accountType",
                )
            }
            android.util.Log.d(
                "ContactPhotoSave",
                "updateContact start rawId=${contact.id} contactId=${contact.contactId} photoUpdateStatus=$photoUpdateStatus photoUri=${contact.photoUri.take(120)}"
            )
            logContactPhotoState("before_applyBatch", contact.id, contact.contactId)
            val operations = ArrayList<ContentProviderOperation>()
            appendUpdateContactOperations(contact, photoUpdateStatus, operations)
            context.contentResolver.applyBatch(AUTHORITY, operations)
            logContactPhotoState("after_applyBatch", contact.id, contact.contactId)
            if (previousSimSnapshot != null) {
                context.toast(R.string.sim_adn_progress_updating_card)
                val adnOk = context.tryUpdateIccAdnAfterSimContactEdit(
                    accountName = contact.source,
                    accountType = accountType,
                    previousPrimaryNumber = previousSimSnapshot.phoneNumbers.firstOrNull()?.value ?: "",
                    previousDisplayName = previousSimSnapshot.getNameToDisplay(),
                    newPrimaryNumber = contact.phoneNumbers.firstOrNull()?.value ?: "",
                    newDisplayName = contact.getNameToDisplay(),
                    logContext = "updateContact rawId=${contact.id}",
                )
                if (adnOk) {
                    context.toast(R.string.sim_adn_progress_writing_ok)
                } else {
                    context.toast(R.string.sim_adn_write_failed)
                    SimAdnPendingSync.enqueueAfterAdnFailure(
                        context,
                        SimAdnPendingEntry(
                            rawContactId = contact.id,
                            accountName = contact.source,
                            accountType = accountType,
                            number = contact.phoneNumbers.firstOrNull()?.value ?: "",
                            displayName = contact.getNameToDisplay(),
                            previousNumber = previousSimSnapshot.phoneNumbers.firstOrNull()?.value,
                            previousDisplayName = previousSimSnapshot.getNameToDisplay(),
                        ),
                    )
                }
            }
            return true
        } catch (e: Exception) {
            android.util.Log.e("ContactPhotoSave", "updateContact failed rawId=${contact.id} contactId=${contact.contactId}", e)
            context.showErrorToast(e)
            return false
        }
    }

    /**
     * Applies [appendUpdateContactOperations] for many contacts, grouping several contacts into each
     * [ContentResolver.applyBatch] where possible (faster than one batch per contact).
     */
    fun updateContactsBatch(
        contacts: List<Contact>,
        photoUpdateStatus: Int,
        showUpdatingToast: Boolean = false,
        onProgress: ((persistedCount: Int, totalCount: Int) -> Unit)? = null
    ): Boolean {
        if (contacts.isEmpty()) {
            onProgress?.invoke(0, 0)
            return true
        }
        if (showUpdatingToast) {
            context.toast(R.string.updating)
        }
        return try {
            val batch = ArrayList<ContentProviderOperation>()
            val total = contacts.size
            var contactsFullyWritten = 0
            var contactsInCurrentBatch = 0
            contacts.forEach { contact ->
                val contactOps = ArrayList<ContentProviderOperation>()
                appendUpdateContactOperations(contact, photoUpdateStatus, contactOps)
                if (contactOps.size > MAX_PROVIDER_BATCH_OPS) {
                    if (batch.isNotEmpty()) {
                        context.contentResolver.applyBatch(AUTHORITY, batch)
                        contactsFullyWritten += contactsInCurrentBatch
                        contactsInCurrentBatch = 0
                        batch.clear()
                        onProgress?.invoke(contactsFullyWritten, total)
                    }
                    context.contentResolver.applyBatch(AUTHORITY, contactOps)
                    contactsFullyWritten++
                    onProgress?.invoke(contactsFullyWritten, total)
                    return@forEach
                }
                if (batch.isNotEmpty() && batch.size + contactOps.size > MAX_PROVIDER_BATCH_OPS) {
                    context.contentResolver.applyBatch(AUTHORITY, batch)
                    contactsFullyWritten += contactsInCurrentBatch
                    contactsInCurrentBatch = 0
                    batch.clear()
                    onProgress?.invoke(contactsFullyWritten, total)
                }
                batch.addAll(contactOps)
                contactsInCurrentBatch++
            }
            if (batch.isNotEmpty()) {
                context.contentResolver.applyBatch(AUTHORITY, batch)
                contactsFullyWritten += contactsInCurrentBatch
            }
            onProgress?.invoke(contactsFullyWritten, total)
            true
        } catch (e: Exception) {
            context.showErrorToast(e)
            false
        }
    }

    private fun appendUpdateContactOperations(
        contact: Contact,
        photoUpdateStatus: Int,
        operations: ArrayList<ContentProviderOperation>,
    ) {
        ContentProviderOperation.newUpdate(Data.CONTENT_URI).apply {
            val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?"
            val selectionArgs = arrayOf(contact.id.toString(), contact.mimetype)
            withSelection(selection, selectionArgs)
            // Use single name field - store in GIVEN_NAME, clear other name fields
            withValue(CommonDataKinds.StructuredName.PREFIX, "")
            withValue(CommonDataKinds.StructuredName.GIVEN_NAME, contact.firstName) // firstName property stores the single name
            withValue(CommonDataKinds.StructuredName.DISPLAY_NAME, contact.getNameToDisplay())
            withValue(CommonDataKinds.StructuredName.MIDDLE_NAME, "")
            withValue(CommonDataKinds.StructuredName.FAMILY_NAME, "")
            withValue(CommonDataKinds.StructuredName.SUFFIX, "")
            operations.add(build())
        }

        // Nickname removed - always delete any existing nickname
        ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
            val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
            val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Nickname.CONTENT_ITEM_TYPE)
            withSelection(selection, selectionArgs)
            operations.add(build())
        }

        ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
            val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
            val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            withSelection(selection, selectionArgs)
            operations.add(build())
        }

        contact.phoneNumbers.forEach {
            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValue(Data.RAW_CONTACT_ID, contact.id)
                withValue(Data.MIMETYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                withValue(CommonDataKinds.Phone.NUMBER, it.value)
                withValue(CommonDataKinds.Phone.NORMALIZED_NUMBER, it.normalizedNumber)
                withValue(CommonDataKinds.Phone.TYPE, it.type)
                withValue(CommonDataKinds.Phone.LABEL, it.label)
                withValue(CommonDataKinds.Phone.IS_PRIMARY, it.isPrimary)
                operations.add(build())
            }
        }

        ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
            val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
            val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Email.CONTENT_ITEM_TYPE)
            withSelection(selection, selectionArgs)
            operations.add(build())
        }

        contact.emails.forEach {
            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValue(Data.RAW_CONTACT_ID, contact.id)
                withValue(Data.MIMETYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                withValue(CommonDataKinds.Email.DATA, it.value)
                withValue(CommonDataKinds.Email.TYPE, it.type)
                withValue(CommonDataKinds.Email.LABEL, it.label)
                operations.add(build())
            }
        }

        ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
            val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
            val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
            withSelection(selection, selectionArgs)
            operations.add(build())
        }

        contact.addresses.forEach {
            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValue(Data.RAW_CONTACT_ID, contact.id)
                withValue(Data.MIMETYPE, CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                withValue(CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, it.value)
                withValue(CommonDataKinds.StructuredPostal.COUNTRY, it.country)
                withValue(CommonDataKinds.StructuredPostal.REGION, it.region)
                withValue(CommonDataKinds.StructuredPostal.CITY, it.city)
                withValue(CommonDataKinds.StructuredPostal.POSTCODE, it.postcode)
                withValue(CommonDataKinds.StructuredPostal.POBOX, it.pobox)
                withValue(CommonDataKinds.StructuredPostal.STREET, it.street)
                withValue(CommonDataKinds.StructuredPostal.NEIGHBORHOOD, it.neighborhood)
                withValue(CommonDataKinds.StructuredPostal.TYPE, it.type)
                withValue(CommonDataKinds.StructuredPostal.LABEL, it.label)
                operations.add(build())
            }
        }

        ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
            val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
            val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Event.CONTENT_ITEM_TYPE)
            withSelection(selection, selectionArgs)
            operations.add(build())
        }

        contact.events
            .filter { it.type == CommonDataKinds.Event.TYPE_BIRTHDAY }
            .take(1)
            .forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValue(Data.RAW_CONTACT_ID, contact.id)
                    withValue(Data.MIMETYPE, CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.Event.START_DATE, it.value)
                    withValue(CommonDataKinds.Event.TYPE, CommonDataKinds.Event.TYPE_BIRTHDAY)
                    withValue(CommonDataKinds.Event.LABEL, "")
                    operations.add(build())
                }
            }

        ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
            val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
            val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Note.CONTENT_ITEM_TYPE)
            withSelection(selection, selectionArgs)
            operations.add(build())
        }

        ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
            withValue(Data.RAW_CONTACT_ID, contact.id)
            withValue(Data.MIMETYPE, CommonDataKinds.Note.CONTENT_ITEM_TYPE)
            withValue(CommonDataKinds.Note.NOTE, contact.notes)
            operations.add(build())
        }

        ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
            val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
            val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
            withSelection(selection, selectionArgs)
            operations.add(build())
        }

        if (contact.organization.isNotEmpty()) {
            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValue(Data.RAW_CONTACT_ID, contact.id)
                withValue(Data.MIMETYPE, CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                withValue(CommonDataKinds.Organization.COMPANY, contact.organization.company)
                withValue(CommonDataKinds.Organization.TYPE, DEFAULT_ORGANIZATION_TYPE)
                withValue(CommonDataKinds.Organization.TITLE, contact.organization.jobPosition)
                withValue(CommonDataKinds.Organization.TYPE, DEFAULT_ORGANIZATION_TYPE)
                operations.add(build())
            }
        }

        // Drop all group memberships for this raw contact, then re-insert (device + app DB groups only
        // in [contact.groups] after [getStoredGroupsSync] / merge filtering).
        ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
            val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?"
            val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
            withSelection(selection, selectionArgs)
            operations.add(build())
        }

        contact.groups.forEach {
            val groupRowId = it.id ?: return@forEach
            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValue(Data.RAW_CONTACT_ID, contact.id)
                withValue(Data.MIMETYPE, CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                withValue(CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupRowId)
                operations.add(build())
            }
        }

        ContentProviderOperation.newUpdate(Uri.withAppendedPath(Contacts.CONTENT_URI, contact.contactId.toString())).apply {
            withValue(Contacts.STARRED, contact.starred)
            withValue(Contacts.CUSTOM_RINGTONE, contact.ringtone)
            operations.add(build())
        }

        when (photoUpdateStatus) {
            PHOTO_ADDED, PHOTO_CHANGED -> addPhoto(contact, operations)
            PHOTO_REMOVED -> removePhoto(contact, operations)
        }
    }

    private fun addPhoto(contact: Contact, operations: ArrayList<ContentProviderOperation>): ArrayList<ContentProviderOperation> {
        android.util.Log.d(
            "ContactPhotoSave",
            "addPhoto rawId=${contact.id} contactId=${contact.contactId} photoUri=${contact.photoUri.take(120)}"
        )
        if (contact.photoUri.isNotEmpty()) {
            val photoUri = contact.photoUri.toUri()
            val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, photoUri)

            val thumbnailSize = context.getPhotoThumbnailSize()
            val scaledPhoto = bitmap.scale(thumbnailSize, thumbnailSize, false)
            val scaledSizePhotoData = scaledPhoto.getByteArray()
            scaledPhoto.recycle()

            val fullSizePhotoData = bitmap.getByteArray()
            bitmap.recycle()

            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValue(Data.RAW_CONTACT_ID, contact.id)
                withValue(Data.MIMETYPE, CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                withValue(CommonDataKinds.Photo.PHOTO, scaledSizePhotoData)
                operations.add(build())
            }

            addFullSizePhoto(contact.id.toLong(), fullSizePhotoData)
            android.util.Log.d(
                "ContactPhotoSave",
                "addPhoto prepared bytes thumb=${scaledSizePhotoData.size} full=${fullSizePhotoData.size}"
            )
        } else {
            android.util.Log.d("ContactPhotoSave", "addPhoto skipped because photoUri is empty")
        }
        return operations
    }

    private fun removePhoto(contact: Contact, operations: ArrayList<ContentProviderOperation>): ArrayList<ContentProviderOperation> {
        android.util.Log.d(
            "ContactPhotoSave",
            "removePhoto rawId=${contact.id} contactId=${contact.contactId}"
        )
        ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
            val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?"
            val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
            withSelection(selection, selectionArgs)
            operations.add(build())
        }

        return operations
    }

    private fun logContactPhotoState(stage: String, rawContactId: Int, contactId: Int) {
        try {
            var aggregatePhotoUri = ""
            var aggregateThumbUri = ""
            context.contentResolver.query(
                Contacts.CONTENT_URI,
                arrayOf(Contacts.PHOTO_URI, Contacts.PHOTO_THUMBNAIL_URI),
                "${Contacts._ID} = ?",
                arrayOf(contactId.toString()),
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    aggregatePhotoUri = c.getStringValue(Contacts.PHOTO_URI) ?: ""
                    aggregateThumbUri = c.getStringValue(Contacts.PHOTO_THUMBNAIL_URI) ?: ""
                }
            }

            var photoRows = 0
            context.contentResolver.query(
                Data.CONTENT_URI,
                arrayOf(Data._ID),
                "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
                arrayOf(rawContactId.toString(), CommonDataKinds.Photo.CONTENT_ITEM_TYPE),
                null
            )?.use { c ->
                photoRows = c.count
            }

            android.util.Log.d(
                "ContactPhotoSave",
                "$stage rawId=$rawContactId contactId=$contactId aggregatePhotoUri=${aggregatePhotoUri.take(120)} aggregateThumbUri=${aggregateThumbUri.take(120)} dataPhotoRows=$photoRows"
            )
        } catch (e: Exception) {
            android.util.Log.e("ContactPhotoSave", "logContactPhotoState failed stage=$stage rawId=$rawContactId contactId=$contactId", e)
        }
    }

    fun addContactsToGroup(contacts: ArrayList<Contact>, groupId: Long) {
        try {
            val operations = ArrayList<ContentProviderOperation>()
            contacts.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValue(Data.RAW_CONTACT_ID, it.id)
                    withValue(Data.MIMETYPE, CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId)
                    operations.add(build())
                }

                if (operations.size % BATCH_SIZE == 0) {
                    context.contentResolver.applyBatch(AUTHORITY, operations)
                    operations.clear()
                }
            }

            context.contentResolver.applyBatch(AUTHORITY, operations)
        } catch (e: Exception) {
            context.showErrorToast(e)
        }
    }

    fun removeContactsFromGroup(contacts: ArrayList<Contact>, groupId: Long) {
        try {
            val operations = ArrayList<ContentProviderOperation>()
            contacts.forEach {
                ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                    val selection = "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${Data.DATA1} = ?"
                    val selectionArgs = arrayOf(it.contactId.toString(), CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, groupId.toString())
                    withSelection(selection, selectionArgs)
                    operations.add(build())
                }

                if (operations.size % BATCH_SIZE == 0) {
                    context.contentResolver.applyBatch(AUTHORITY, operations)
                    operations.clear()
                }
            }
            context.contentResolver.applyBatch(AUTHORITY, operations)
        } catch (e: Exception) {
            context.showErrorToast(e)
        }
    }

    /**
     * @param showSimAdnProgressToasts When false, skips SIM write/retry/success/fail toasts (batch copy/move/export).
     */
    fun insertContact(contact: Contact, showSimAdnProgressToasts: Boolean = true): Boolean {
        try {
            val operations = ArrayList<ContentProviderOperation>()
            var accountType = getContactSourceType(contact.source)
            if (accountType.isEmpty() && contact.source.isNotEmpty()) {
                clearContactSourcesCache()
                accountType = getContactSourceType(contact.source)
            }
            val isSimDestination = isSimAccountTypeForPersistence(accountType)
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI).apply {
                if (accountType.isEmpty()) {
                    withValue(RawContacts.ACCOUNT_NAME, null)
                    withValue(RawContacts.ACCOUNT_TYPE, null)
                } else {
                    withValue(RawContacts.ACCOUNT_NAME, contact.source)
                    withValue(RawContacts.ACCOUNT_TYPE, accountType)
                }
                operations.add(build())
            }

            // names — SIM only supports a single display string + numbers; skip other data rows below.
            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValueBackReference(Data.RAW_CONTACT_ID, 0)
                withValue(Data.MIMETYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                withValue(CommonDataKinds.StructuredName.PREFIX, "")
                if (isSimDestination) {
                    val simDisplay = contact.getNameToDisplay()
                    withValue(CommonDataKinds.StructuredName.GIVEN_NAME, simDisplay)
                    withValue(CommonDataKinds.StructuredName.DISPLAY_NAME, simDisplay)
                    withValue(CommonDataKinds.StructuredName.MIDDLE_NAME, "")
                    withValue(CommonDataKinds.StructuredName.FAMILY_NAME, "")
                    withValue(CommonDataKinds.StructuredName.SUFFIX, "")
                } else {
                    withValue(CommonDataKinds.StructuredName.GIVEN_NAME, contact.firstName)
                    withValue(CommonDataKinds.StructuredName.MIDDLE_NAME, "")
                    withValue(CommonDataKinds.StructuredName.FAMILY_NAME, "")
                    withValue(CommonDataKinds.StructuredName.SUFFIX, "")
                }
                operations.add(build())
            }

            // Nickname removed - not inserting nickname

            // phone numbers
            contact.phoneNumbers.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    withValue(Data.MIMETYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.Phone.NUMBER, it.value)
                    withValue(CommonDataKinds.Phone.NORMALIZED_NUMBER, it.normalizedNumber)
                    withValue(CommonDataKinds.Phone.TYPE, it.type)
                    withValue(CommonDataKinds.Phone.LABEL, it.label)
                    withValue(CommonDataKinds.Phone.IS_PRIMARY, it.isPrimary)
                    operations.add(build())
                }
            }

            if (!isSimDestination) {
                // emails
                contact.emails.forEach {
                    ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                        withValueBackReference(Data.RAW_CONTACT_ID, 0)
                        withValue(Data.MIMETYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        withValue(CommonDataKinds.Email.DATA, it.value)
                        withValue(CommonDataKinds.Email.TYPE, it.type)
                        withValue(CommonDataKinds.Email.LABEL, it.label)
                        operations.add(build())
                    }
                }

                // addresses
                contact.addresses.forEach {
                    ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                        withValueBackReference(Data.RAW_CONTACT_ID, 0)
                        withValue(Data.MIMETYPE, CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                        withValue(CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, it.value)
                        withValue(CommonDataKinds.StructuredPostal.COUNTRY, it.country)
                        withValue(CommonDataKinds.StructuredPostal.REGION, it.region)
                        withValue(CommonDataKinds.StructuredPostal.CITY, it.city)
                        withValue(CommonDataKinds.StructuredPostal.POSTCODE, it.postcode)
                        withValue(CommonDataKinds.StructuredPostal.POBOX, it.pobox)
                        withValue(CommonDataKinds.StructuredPostal.STREET, it.street)
                        withValue(CommonDataKinds.StructuredPostal.NEIGHBORHOOD, it.neighborhood)
                        withValue(CommonDataKinds.StructuredPostal.TYPE, it.type)
                        withValue(CommonDataKinds.StructuredPostal.LABEL, it.label)
                        operations.add(build())
                    }
                }

                // events
                contact.events
                    .filter { it.type == CommonDataKinds.Event.TYPE_BIRTHDAY }
                    .take(1)
                    .forEach {
                    ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                        withValueBackReference(Data.RAW_CONTACT_ID, 0)
                        withValue(Data.MIMETYPE, CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                        withValue(CommonDataKinds.Event.START_DATE, it.value)
                        withValue(CommonDataKinds.Event.TYPE, CommonDataKinds.Event.TYPE_BIRTHDAY)
                        withValue(CommonDataKinds.Event.LABEL, "")
                        operations.add(build())
                    }
                }

                // notes
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    withValue(Data.MIMETYPE, CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.Note.NOTE, contact.notes)
                    operations.add(build())
                }

                // organization
                if (contact.organization.isNotEmpty()) {
                    ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                        withValueBackReference(Data.RAW_CONTACT_ID, 0)
                        withValue(Data.MIMETYPE, CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                        withValue(CommonDataKinds.Organization.COMPANY, contact.organization.company)
                        withValue(CommonDataKinds.Organization.TYPE, DEFAULT_ORGANIZATION_TYPE)
                        withValue(CommonDataKinds.Organization.TITLE, contact.organization.jobPosition)
                        withValue(CommonDataKinds.Organization.TYPE, DEFAULT_ORGANIZATION_TYPE)
                        operations.add(build())
                    }
                }

                // groups
                contact.groups.forEach {
                    ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                        withValueBackReference(Data.RAW_CONTACT_ID, 0)
                        withValue(Data.MIMETYPE, CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                        withValue(CommonDataKinds.GroupMembership.GROUP_ROW_ID, it.id)
                        operations.add(build())
                    }
                }
            }

            // photo (inspired by https://gist.github.com/slightfoot/5985900)
            var fullSizePhotoData: ByteArray? = null
            if (!isSimDestination && contact.photoUri.isNotEmpty()) {
                val photoUri = contact.photoUri.toUri()
                fullSizePhotoData = context.contentResolver.openInputStream(photoUri)?.readBytes()
            }

            val results = context.contentResolver.applyBatch(AUTHORITY, operations)
            val rawId = ContentUris.parseId(results[0].uri!!)

            // Also persist to SIM ADN (IccPhonebookProvider); subscription-scoped for dual-SIM.
            if (isSimDestination) {
                android.util.Log.d(
                    "SimContactSave",
                    "insertContact: applyBatch OK isSimDestination=true rawId=$rawId accountName=${contact.source} accountType=$accountType",
                )
                if (showSimAdnProgressToasts) {
                    context.toast(R.string.sim_adn_progress_writing)
                }
                val adnOk = insertIccAdnForSimRawContactWithRetries(
                    accountName = contact.source,
                    accountType = accountType,
                    number = contact.phoneNumbers.firstOrNull()?.value ?: "",
                    displayName = contact.getNameToDisplay(),
                    logContext = "insertContact rawId=$rawId",
                    showProgressToasts = showSimAdnProgressToasts,
                    toastOnSuccess = showSimAdnProgressToasts,
                )
                if (!adnOk) {
                    if (showSimAdnProgressToasts) {
                        context.toast(R.string.sim_adn_write_failed)
                    }
                    try {
                        val deleted = context.contentResolver.delete(
                            ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId),
                            null,
                            null,
                        )
                        android.util.Log.w(
                            "SimContactSave",
                            "insertContact: SIM ADN failed after retries; removed orphan rawContact rawId=$rawId deletedRows=$deleted",
                        )
                    } catch (e: Exception) {
                        android.util.Log.e(
                            "SimContactSave",
                            "insertContact: SIM ADN failed and rollback delete threw rawId=$rawId",
                            e,
                        )
                    }
                    return false
                }
            }

            // fullsize photo
            contact.id = rawId.toInt()
            if (!isSimDestination && contact.photoUri.isNotEmpty() && fullSizePhotoData != null) {
                addFullSizePhoto(rawId, fullSizePhotoData)
            }

            // favorite, ringtone
            if (!isSimDestination) {
                val userId = getRealContactId(rawId)
                if (userId != 0) {
                    val uri = Uri.withAppendedPath(Contacts.CONTENT_URI, userId.toString())
                    val contentValues = ContentValues(2)
                    contentValues.put(Contacts.STARRED, contact.starred)
                    contentValues.put(Contacts.CUSTOM_RINGTONE, contact.ringtone)
                    context.contentResolver.update(uri, contentValues, null, null)
                }
            }

            return true
        } catch (e: Exception) {
            context.showErrorToast(e)
            return false
        }
    }

    /** Used by the host app batch contact import (VCF). */
    fun getAggregateContactIdForRawContact(rawContactId: Long): Int = getRealContactId(rawContactId)

    /** Used by the host app batch contact import (VCF). */
    fun writeFullSizePhotoForRawContact(rawContactId: Long, photoBytes: ByteArray) {
        addFullSizePhoto(rawContactId, photoBytes)
    }

    /**
     * Inserts a contact into the system ContactsProvider that is hidden from the main contacts list
     * but still appears in search results (Contacts app or Dialer).
     * 
     * @param contact The contact to insert
     * @param hiddenAccountName Custom account name for hidden contacts (default: "Hidden Contacts")
     * @param hiddenAccountType Custom account type for hidden contacts (default: "com.goodwy.contacts.hidden")
     * @return true if the contact was successfully inserted, false otherwise
     */
    fun insertHiddenContact(
        contact: Contact,
        hiddenAccountName: String = "Hidden Contacts",
        hiddenAccountType: String = "com.goodwy.contacts.hidden"
    ): Boolean {
        try {
            val operations = ArrayList<ContentProviderOperation>()
            
            // Insert raw contact with custom account
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI).apply {
                withValue(RawContacts.ACCOUNT_NAME, hiddenAccountName)
                withValue(RawContacts.ACCOUNT_TYPE, hiddenAccountType)
                operations.add(build())
            }

            // names - use single name field
            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValueBackReference(Data.RAW_CONTACT_ID, 0)
                withValue(Data.MIMETYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                withValue(CommonDataKinds.StructuredName.PREFIX, "")
                withValue(CommonDataKinds.StructuredName.GIVEN_NAME, contact.firstName)
                withValue(CommonDataKinds.StructuredName.MIDDLE_NAME, "")
                withValue(CommonDataKinds.StructuredName.FAMILY_NAME, "")
                withValue(CommonDataKinds.StructuredName.SUFFIX, "")
                operations.add(build())
            }

            // phone numbers
            contact.phoneNumbers.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    withValue(Data.MIMETYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.Phone.NUMBER, it.value)
                    withValue(CommonDataKinds.Phone.NORMALIZED_NUMBER, it.normalizedNumber)
                    withValue(CommonDataKinds.Phone.TYPE, it.type)
                    withValue(CommonDataKinds.Phone.LABEL, it.label)
                    withValue(CommonDataKinds.Phone.IS_PRIMARY, it.isPrimary)
                    operations.add(build())
                }
            }

            // emails
            contact.emails.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    withValue(Data.MIMETYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.Email.DATA, it.value)
                    withValue(CommonDataKinds.Email.TYPE, it.type)
                    withValue(CommonDataKinds.Email.LABEL, it.label)
                    operations.add(build())
                }
            }

            // addresses
            contact.addresses.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    withValue(Data.MIMETYPE, CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, it.value)
                    withValue(CommonDataKinds.StructuredPostal.COUNTRY, it.country)
                    withValue(CommonDataKinds.StructuredPostal.REGION, it.region)
                    withValue(CommonDataKinds.StructuredPostal.CITY, it.city)
                    withValue(CommonDataKinds.StructuredPostal.POSTCODE, it.postcode)
                    withValue(CommonDataKinds.StructuredPostal.POBOX, it.pobox)
                    withValue(CommonDataKinds.StructuredPostal.STREET, it.street)
                    withValue(CommonDataKinds.StructuredPostal.NEIGHBORHOOD, it.neighborhood)
                    withValue(CommonDataKinds.StructuredPostal.TYPE, it.type)
                    withValue(CommonDataKinds.StructuredPostal.LABEL, it.label)
                    operations.add(build())
                }
            }

            // events
            contact.events
                .filter { it.type == CommonDataKinds.Event.TYPE_BIRTHDAY }
                .take(1)
                .forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    withValue(Data.MIMETYPE, CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.Event.START_DATE, it.value)
                    withValue(CommonDataKinds.Event.TYPE, CommonDataKinds.Event.TYPE_BIRTHDAY)
                    withValue(CommonDataKinds.Event.LABEL, "")
                    operations.add(build())
                }
            }

            // notes
            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValueBackReference(Data.RAW_CONTACT_ID, 0)
                withValue(Data.MIMETYPE, CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                withValue(CommonDataKinds.Note.NOTE, contact.notes)
                operations.add(build())
            }

            // organization
            if (contact.organization.isNotEmpty()) {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    withValue(Data.MIMETYPE, CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.Organization.COMPANY, contact.organization.company)
                    withValue(CommonDataKinds.Organization.TYPE, DEFAULT_ORGANIZATION_TYPE)
                    withValue(CommonDataKinds.Organization.TITLE, contact.organization.jobPosition)
                    operations.add(build())
                }
            }

            // groups (skip groups for hidden contacts to avoid visibility issues)
            // contact.groups.forEach { ... }

            // photo
            var fullSizePhotoData: ByteArray? = null
            if (contact.photoUri.isNotEmpty()) {
                val photoUri = contact.photoUri.toUri()
                fullSizePhotoData = context.contentResolver.openInputStream(photoUri)?.readBytes()
            }

            val results = context.contentResolver.applyBatch(AUTHORITY, operations)
            val rawId = ContentUris.parseId(results[0].uri!!)
            
            // fullsize photo
            if (contact.photoUri.isNotEmpty() && fullSizePhotoData != null) {
                addFullSizePhoto(rawId, fullSizePhotoData)
            }

            // Get the contact ID and set it as hidden (IN_VISIBLE_GROUP = 0)
            val userId = getRealContactId(rawId)
            if (userId != 0) {
                val uri = Uri.withAppendedPath(Contacts.CONTENT_URI, userId.toString())
                val contentValues = ContentValues(3)
                contentValues.put(Contacts.STARRED, contact.starred)
                contentValues.put(Contacts.CUSTOM_RINGTONE, contact.ringtone)
                // Set IN_VISIBLE_GROUP to 0 to hide from main list but keep searchable
                contentValues.put(Contacts.IN_VISIBLE_GROUP, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }

            return true
        } catch (e: Exception) {
            context.showErrorToast(e)
            return false
        }
    }

    private fun addFullSizePhoto(contactId: Long, fullSizePhotoData: ByteArray) {
        val baseUri = ContentUris.withAppendedId(RawContacts.CONTENT_URI, contactId)
        val displayPhotoUri = Uri.withAppendedPath(baseUri, RawContacts.DisplayPhoto.CONTENT_DIRECTORY)
        val fileDescriptor = context.contentResolver.openAssetFileDescriptor(displayPhotoUri, "rw")
        val photoStream = fileDescriptor!!.createOutputStream()
        photoStream.write(fullSizePhotoData)
        photoStream.close()
        fileDescriptor.close()
    }

    fun getContactMimeTypeId(contactId: String, mimeType: String): String {
        val uri = Data.CONTENT_URI
        val projection = arrayOf(Data._ID, Data.RAW_CONTACT_ID, Data.MIMETYPE)
        val selection = "${Data.MIMETYPE} = ? AND ${Data.RAW_CONTACT_ID} = ?"
        val selectionArgs = arrayOf(mimeType, contactId)


        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                return cursor.getStringValue(Data._ID)
            }
        }
        return ""
    }

    fun addFavorites(
        contacts: ArrayList<Contact>,
        callback: (() -> Unit)? = null,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ) {
        ensureBackgroundThread {
            if (context.hasContactPermissions()) {
                toggleFavorites(contacts, true, callback, onProgress)
            } else {
                callback?.invoke()
            }
        }
    }

    fun removeFavorites(
        contacts: ArrayList<Contact>,
        callback: (() -> Unit)? = null,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ) {
        ensureBackgroundThread {
            if (context.hasContactPermissions()) {
                toggleFavorites(contacts, false, callback, onProgress)
            } else {
                callback?.invoke()
            }
        }
    }

    /**
     * Applies add and/or remove favorite updates sequentially on the **current** thread (no [ensureBackgroundThread]).
     * Use from a worker thread for a single progress stream (e.g. select-contacts add+remove).
     */
    fun applyFavoriteBulkChanges(
        addToFavorites: ArrayList<Contact>?,
        removeFromFavorites: ArrayList<Contact>?,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ) {
        val addList = addToFavorites?.distinctBy { it.contactId } ?: emptyList()
        val removeList = removeFromFavorites?.distinctBy { it.contactId } ?: emptyList()
        val grandTotal = addList.size + removeList.size
        if (grandTotal == 0 || !context.hasContactPermissions()) {
            return
        }
        onProgress?.invoke(0, grandTotal)
        val addSize = addList.size
        if (addList.isNotEmpty()) {
            toggleFavorites(ArrayList(addList), true, null, onProgress = { d, _ ->
                onProgress?.invoke(d, grandTotal)
            })
        }
        if (removeList.isNotEmpty()) {
            toggleFavorites(ArrayList(removeList), false, null, onProgress = { d, _ ->
                onProgress?.invoke(addSize + d, grandTotal)
            })
        }
    }

    /** Fewer [applyBatch] round trips than [BATCH_SIZE] — star/unstar is one update op per contact. */
    private fun toggleFavorites(
        contacts: ArrayList<Contact>,
        addToFavorites: Boolean,
        callback: (() -> Unit)? = null,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ) {
        try {
            val list = contacts.distinctBy { it.contactId }
            val total = list.size
            if (total == 0) {
                onProgress?.invoke(0, 0)
                callback?.invoke()
                return
            }
            onProgress?.invoke(0, total)
            val operations = ArrayList<ContentProviderOperation>()
            val flushBatch = {
                if (operations.isNotEmpty()) {
                    context.contentResolver.applyBatch(AUTHORITY, operations)
                    operations.clear()
                }
            }
            val starredValue = if (addToFavorites) 1 else 0
            // Progress was only reported when hitting MAX_PROVIDER_BATCH_OPS (e.g. 450), so batches
            // smaller than that never updated the UI until the end — show ~15 steps for any total.
            val progressStep = (total / 15).coerceAtLeast(1)
            var processed = 0
            list.forEach { contact ->
                val uri = Uri.withAppendedPath(Contacts.CONTENT_URI, contact.contactId.toString())
                ContentProviderOperation.newUpdate(uri).apply {
                    withValue(Contacts.STARRED, starredValue)
                    operations.add(build())
                }
                processed++
                if (processed % progressStep == 0 || processed == total) {
                    onProgress?.invoke(processed, total)
                }
                if (operations.size >= MAX_PROVIDER_BATCH_OPS) {
                    flushBatch()
                    onProgress?.invoke(processed, total)
                }
            }
            flushBatch()
            onProgress?.invoke(total, total)
            callback?.invoke()
        } catch (e: Exception) {
            context.showErrorToast(e)
            callback?.invoke()
        }
    }

    fun updateRingtone(contactId: String, newUri: String) {
        try {
            val operations = ArrayList<ContentProviderOperation>()
            val uri = Uri.withAppendedPath(Contacts.CONTENT_URI, contactId)
            ContentProviderOperation.newUpdate(uri).apply {
                withValue(Contacts.CUSTOM_RINGTONE, newUri)
                operations.add(build())
            }

            context.contentResolver.applyBatch(AUTHORITY, operations)
        } catch (e: Exception) {
            context.showErrorToast(e)
        }
    }

    fun deleteContact(originalContact: Contact, deleteClones: Boolean = false, callback: (success: Boolean) -> Unit) {
        ensureBackgroundThread {
            if (deleteClones) {
                getDuplicatesOfContact(originalContact, true) { contacts ->
                    ensureBackgroundThread {
                        if (deleteContacts(contacts)) {
                            callback(true)
                        }
                    }
                }
            } else {
                if (deleteContacts(arrayListOf(originalContact))) {
                    callback(true)
                }
            }
        }
    }

    fun moveContacts(contacts: ArrayList<Contact>, destinationSource: String, callback: (success: Boolean, movedCount: Int) -> Unit) {
        moveContacts(contacts, destinationSource, null, null, callback)
    }

    fun moveContacts(contacts: ArrayList<Contact>, destinationSource: String, progressCallback: ((current: Int, total: Int) -> Unit)?, callback: (success: Boolean, movedCount: Int) -> Unit) {
        moveContacts(contacts, destinationSource, progressCallback, null, callback)
    }

    fun moveContacts(
        contacts: ArrayList<Contact>,
        destinationSource: String,
        progressCallback: ((current: Int, total: Int) -> Unit)?,
        onContactProgress: ((current: Int, total: Int, contactLabel: String) -> Unit)?,
        callback: (success: Boolean, movedCount: Int) -> Unit
    ) {
        ensureBackgroundThread {
            val handler = Handler(Looper.getMainLooper())
            
            if (!context.hasPermission(PERMISSION_WRITE_CONTACTS)) {
                handler.post {
                    callback(false, 0)
                }
                return@ensureBackgroundThread
            }

            val destAccountType = getContactSourceType(destinationSource)
            val destIsSim = isSimAccountTypeForPersistence(destAccountType)

            val totalContacts = contacts.size
            var movedCount = 0
            var failedCount = 0
            val contactsToDelete = ArrayList<Contact>()
            val BATCH_SIZE = 10
            val BATCH_DELAY_MS = 50L

            try {
                var currentIndex = 0
                var processedCount = 0

                if (totalContacts > 0 && onContactProgress != null) {
                    handler.post {
                        onContactProgress(0, totalContacts, "")
                    }
                }

                while (currentIndex < totalContacts) {
                    val batchEnd = minOf(currentIndex + BATCH_SIZE, totalContacts)
                    val batch = contacts.subList(currentIndex, batchEnd)
                    
                    batch.forEach { contact ->
                        processedCount++
                        // Get full contact data before copying
                        val fullContact = getContactWithId(contact.id)
                        val labelForProgress = fullContact?.getNameToDisplay()?.ifBlank {
                            fullContact.phoneNumbers.firstOrNull()?.value
                        }?.ifBlank { "…" } ?: "…"

                        if (onContactProgress != null && shouldReportContactTransferProgress(processedCount, totalContacts)) {
                            handler.post {
                                onContactProgress(processedCount, totalContacts, labelForProgress)
                            }
                        }

                        if (fullContact == null) {
                            failedCount++
                            return@forEach
                        }

                        // Create a copy of the contact with the new source
                        val newContact = fullContact.copy()
                        newContact.source = destinationSource
                        newContact.id = 0 // Reset ID for new contact
                        newContact.contactId = 0

                        // SIM ADN only supports up to 2 phone numbers per entry.
                        if (destIsSim && newContact.phoneNumbers.size > SIM_MAX_PHONE_NUMBERS) {
                            newContact.phoneNumbers = ArrayList(newContact.phoneNumbers.take(SIM_MAX_PHONE_NUMBERS))
                        }

                        // Insert contact to new location (no per-contact SIM toasts; host shows one summary)
                        val insertSuccess = insertContact(newContact, showSimAdnProgressToasts = false)

                        if (insertSuccess) {
                            // Store original contact for deletion after all copies succeed
                            contactsToDelete.add(fullContact)
                            movedCount++
                        } else {
                            failedCount++
                        }
                    }
                    
                    currentIndex = batchEnd
                    
                    // Update progress on UI thread
                    if (progressCallback != null) {
                        handler.post {
                            progressCallback(currentIndex, totalContacts)
                        }
                    }
                    
                    // Small delay between batches to keep UI responsive
                    if (currentIndex < totalContacts) {
                        Thread.sleep(BATCH_DELAY_MS)
                    }
                }

                // Delete original contacts after successful copy
                if (contactsToDelete.isNotEmpty()) {
                    deleteContacts(contactsToDelete)
                }

                handler.post {
                    callback(movedCount > 0, movedCount)
                }
            } catch (e: Exception) {
                context.showErrorToast(e)
                handler.post {
                    callback(false, movedCount)
                }
            }
        }
    }

    /**
     * Inserts copies of [contacts] into [destinationSource] without deleting originals (e.g. export to SIM).
     */
    fun copyContactsToDestination(
        contacts: ArrayList<Contact>,
        destinationSource: String,
        progressCallback: ((current: Int, total: Int) -> Unit)? = null,
        onContactProgress: ((current: Int, total: Int, contactLabel: String) -> Unit)? = null,
        callback: (success: Boolean, copiedCount: Int) -> Unit
    ) {
        ensureBackgroundThread {
            val handler = Handler(Looper.getMainLooper())

            if (!context.hasPermission(PERMISSION_WRITE_CONTACTS)) {
                handler.post {
                    callback(false, 0)
                }
                return@ensureBackgroundThread
            }

            val destAccountType = getContactSourceType(destinationSource)
            val destIsSim = isSimAccountTypeForPersistence(destAccountType)

            val totalContacts = contacts.size
            var copiedCount = 0
            val BATCH_SIZE = 10
            val BATCH_DELAY_MS = 50L

            try {
                var currentIndex = 0
                var processedCount = 0

                if (totalContacts > 0 && onContactProgress != null) {
                    handler.post {
                        onContactProgress(0, totalContacts, "")
                    }
                }

                while (currentIndex < totalContacts) {
                    val batchEnd = minOf(currentIndex + BATCH_SIZE, totalContacts)
                    val batch = contacts.subList(currentIndex, batchEnd)

                    batch.forEach { contact ->
                        processedCount++
                        val fullContact = getContactWithId(contact.id)
                        val labelForProgress = fullContact?.getNameToDisplay()?.ifBlank {
                            fullContact.phoneNumbers.firstOrNull()?.value
                        }?.ifBlank { "…" } ?: "…"

                        if (onContactProgress != null && shouldReportContactTransferProgress(processedCount, totalContacts)) {
                            handler.post {
                                onContactProgress(processedCount, totalContacts, labelForProgress)
                            }
                        }

                        if (fullContact == null) {
                            return@forEach
                        }

                        val newContact = fullContact.copy()
                        newContact.source = destinationSource
                        newContact.id = 0
                        newContact.contactId = 0

                        // SIM ADN only supports up to 2 phone numbers per entry.
                        if (destIsSim && newContact.phoneNumbers.size > SIM_MAX_PHONE_NUMBERS) {
                            newContact.phoneNumbers = ArrayList(newContact.phoneNumbers.take(SIM_MAX_PHONE_NUMBERS))
                        }

                        if (insertContact(newContact, showSimAdnProgressToasts = false)) {
                            copiedCount++
                        }
                    }

                    currentIndex = batchEnd

                    if (progressCallback != null) {
                        handler.post {
                            progressCallback(currentIndex, totalContacts)
                        }
                    }

                    if (currentIndex < totalContacts) {
                        Thread.sleep(BATCH_DELAY_MS)
                    }
                }

                handler.post {
                    callback(copiedCount > 0, copiedCount)
                }
            } catch (e: Exception) {
                context.showErrorToast(e)
                handler.post {
                    callback(false, copiedCount)
                }
            }
        }
    }

    fun getAvailableMoveDestinations(contact: Contact): ArrayList<ContactSource> {
        val destinations = ArrayList<ContactSource>()
        val allSources = getDeviceContactSources()
        val currentSourceType = getContactSourceType(contact.source)
        val currentSourceName = contact.source
        val isCurrentSim = isSimAccountTypeForPersistence(currentSourceType)
        val isCurrentPhoneStorage = (currentSourceName.isEmpty() && currentSourceType.isEmpty()) ||
            (currentSourceName.lowercase(Locale.getDefault()) == "phone" && currentSourceType.isEmpty())
        
        // Add phone storage as an option (if not already on phone storage)
        if (!isCurrentPhoneStorage) {
            destinations.add(ContactSource("", "", context.getString(R.string.phone_storage)))
        }
        
        // Add SIM sources (excluding the current one)
        allSources.forEach { source ->
            val sourceType = source.type.lowercase(Locale.getDefault())
            val isSim = sourceType.contains("sim") || sourceType.contains("icc")
            
            if (isSim) {
                // Check if this is a different SIM than the current one
                val isDifferentSim = if (isCurrentSim) {
                    // Current contact is on SIM, check if this is a different SIM
                    // Compare both name and type to identify different SIM cards
                    val currentName = currentSourceName.lowercase(Locale.getDefault())
                    val sourceName = source.name.lowercase(Locale.getDefault())
                    val currentType = currentSourceType.lowercase(Locale.getDefault())
                    val sourceTypeLower = sourceType
                    // Different if names don't match OR types don't match (SIM1 vs SIM2, etc.)
                    currentName != sourceName || currentType != sourceTypeLower
                } else {
                    // Current contact is not on SIM, so any SIM is valid
                    true
                }
                
                if (isDifferentSim) {
                    destinations.add(source)
                }
            }
        }
        
        return destinations
    }

    fun deleteContacts(
        contacts: ArrayList<Contact>,
        onProgress: ((deleted: Int, total: Int) -> Unit)? = null
    ): Boolean {
        // SIM ADN cleanup is centralised in [deleteRawContactIds] so every delete path
        // (single contact, bulk foreground service, edit-and-move) purges the physical SIM.
        val contactIds = contacts.map { it.id.toLong() }.filter { it > 0 }
        return deleteRawContactIds(contactIds, onProgress)
    }

    /**
     * Deletes raw contacts by [android.provider.ContactsContract.RawContacts] row ids (same as [Contact.id]).
     *
     * Before removing rows from `ContactsContract`, this also purges the matching ADN entries
     * from the physical SIM card. Without this step the platform re-syncs the old ADN entries
     * back into `ContactsContract` the next time the SIM is inserted, undoing the delete.
     *
     * Two strategies are used (in order):
     *  1. **MediaTek path** — read `index_in_sim` + `indicate_phone_sim` from the `RawContacts`
     *     row and call `delete(simUri, "index = $indexInSim", null)` on the subscription-scoped
     *     PBR/ADN URI. Mirrors DialContact's `SimDeleteProcessor`.
     *  2. **Phone-number fallback** — for AOSP devices without those MTK columns, search the
     *     ICC provider for a row whose normalised `number` matches the contact's first phone.
     */
    fun deleteRawContactIds(
        rawContactIds: List<Long>,
        onProgress: ((deleted: Int, total: Int) -> Unit)? = null
    ): Boolean {
        if (!context.hasPermission(PERMISSION_WRITE_CONTACTS)) {
            return false
        }

        return try {
            val resolver = context.contentResolver
            val contactIds = rawContactIds.filter { it > 0 }
            val total = contactIds.size

            if (contactIds.isEmpty()) {
                onProgress?.invoke(0, total)
                return true
            }

            // SIM ADN purge MUST happen before the RawContacts delete: we need to read
            // [account, index_in_sim, phone number] from the row, which disappears after.
            purgeSimAdnEntriesBeforeRawContactsDelete(contactIds)

            onProgress?.invoke(0, total)

            // Chunk size scales with total: smaller batches for small deletes (more progress updates), larger for big deletes.
            val chunkSize = (total / 15).coerceIn(10, 500)
            val uri = RawContacts.CONTENT_URI
            var deleted = 0
            contactIds.chunked(chunkSize).forEach { chunk ->
                val selection = "${RawContacts._ID} IN (${getQuestionMarks(chunk.size)})"
                val selectionArgs = chunk.map { it.toString() }.toTypedArray()
                resolver.delete(uri, selection, selectionArgs)
                deleted = minOf(deleted + chunk.size, total)
                onProgress?.invoke(deleted, total)
            }

            true
        } catch (e: Exception) {
            context.showErrorToast(e)
            false
        }
    }

    /**
     * Internal: load `[account, MediaTek SIM metadata]` for each raw contact id, then call
     * [Context.tryDeleteIccAdnByMtkSimIndex] (preferred) or [Context.tryDeleteIccAdnForSimContact]
     * (phone-number fallback) so the SIM card itself loses the corresponding ADN entries.
     *
     * If neither strategy succeeds for a row, the metadata (incl. number+name+SIM index) is
     * captured into [SimAdnPendingDeleteSync] so we can retry on the next SIM-ready broadcast.
     * Without that retry, the platform's automatic `IccProvider → ContactsContract` resync
     * would re-create the contact when the user reinserts the SIM, defeating the delete.
     */
    private fun purgeSimAdnEntriesBeforeRawContactsDelete(rawContactIds: List<Long>) {
        if (rawContactIds.isEmpty()) return
        val simRows = loadSimRawContactDeleteMetadata(rawContactIds)
        if (simRows.isEmpty()) return

        simRows.forEach { meta ->
            // Number + name read up-front so retry-on-reinsert still has them after the
            // RawContacts row is gone. Empty when no Phone/Name data row exists, in which
            // case we lean on the MTK index.
            val firstNumber = loadFirstPhoneNumberForRawContactId(meta.rawContactId)
            val displayName = loadDisplayNameForRawContactId(meta.rawContactId)

            // Strategy 1: MediaTek-style delete by index_in_sim + subId. Most reliable.
            val mtkOk = meta.indexInSim > 0 && meta.mtkSubId > 0 &&
                context.tryDeleteIccAdnByMtkSimIndex(
                    mtkSubId = meta.mtkSubId,
                    indexInSim = meta.indexInSim,
                    accountName = meta.accountName,
                    accountType = meta.accountType,
                    logContext = "deleteRawContactIds rawId=${meta.rawContactId}",
                )
            if (mtkOk) return@forEach

            // Strategy 2: AOSP-friendly fallback — look up the phone number, then search the ADN.
            val aospOk = (firstNumber.isNotEmpty() || displayName.isNotEmpty()) &&
                context.tryDeleteIccAdnForSimContact(
                    accountName = meta.accountName,
                    accountType = meta.accountType,
                    phoneNumber = firstNumber,
                    displayName = displayName,
                    logContext = "deleteRawContactIds rawId=${meta.rawContactId} fallback",
                )
            if (aospOk) return@forEach

            // Both immediate strategies failed: queue the entry so the next SIM-ready
            // broadcast (or the next foreground sync poke) retries while we still have all
            // the metadata. Will be a no-op when there's nothing addressable.
            SimAdnPendingDeleteSync.enqueueAfterAdnDeleteFailure(
                context,
                SimAdnPendingDeleteEntry(
                    rawContactId = meta.rawContactId.toInt(),
                    accountName = meta.accountName,
                    accountType = meta.accountType,
                    mtkSubId = meta.mtkSubId,
                    indexInSim = meta.indexInSim,
                    normalizedNumber = normalizeIccDialableNumber(firstNumber),
                    displayName = displayName,
                ),
            )
        }
    }

    private data class SimRawContactMeta(
        val rawContactId: Long,
        val accountName: String,
        val accountType: String,
        val indexInSim: Int,
        val mtkSubId: Int,
    )

    /** Returns metadata for the SIM-backed entries among [rawContactIds]; non-SIM rows are skipped. */
    private fun loadSimRawContactDeleteMetadata(rawContactIds: List<Long>): List<SimRawContactMeta> {
        val out = ArrayList<SimRawContactMeta>(rawContactIds.size)
        val placeholders = getQuestionMarks(rawContactIds.size)
        val selection = "${RawContacts._ID} IN ($placeholders)"
        val args = rawContactIds.map { it.toString() }.toTypedArray()

        // First attempt: include MediaTek SIM columns. AOSP devices throw IllegalArgumentException
        // for unknown columns; we silently fall back to the basic projection in that case.
        val mtkProjection = arrayOf(
            RawContacts._ID,
            RawContacts.ACCOUNT_NAME,
            RawContacts.ACCOUNT_TYPE,
            "index_in_sim",
            "indicate_phone_sim",
        )
        try {
            context.contentResolver.query(RawContacts.CONTENT_URI, mtkProjection, selection, args, null)?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(RawContacts._ID)
                val nameIdx = c.getColumnIndex(RawContacts.ACCOUNT_NAME)
                val typeIdx = c.getColumnIndex(RawContacts.ACCOUNT_TYPE)
                val indexIdx = c.getColumnIndex("index_in_sim")
                val subIdIdx = c.getColumnIndex("indicate_phone_sim")
                while (c.moveToNext()) {
                    val accountType = if (typeIdx >= 0) c.getString(typeIdx) ?: "" else ""
                    if (!isSimAccountTypeForPersistence(accountType)) continue
                    out.add(
                        SimRawContactMeta(
                            rawContactId = c.getLong(idIdx),
                            accountName = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else "",
                            accountType = accountType,
                            indexInSim = if (indexIdx >= 0) c.getInt(indexIdx) else 0,
                            mtkSubId = if (subIdIdx >= 0) c.getInt(subIdIdx) else 0,
                        )
                    )
                }
            }
            // MTK projection succeeded (even if zero rows matched); skip the AOSP fallback.
            return out
        } catch (e: Exception) {
            android.util.Log.d(
                "SimContactSave",
                "loadSimRawContactDeleteMetadata: MTK projection failed, falling back to AOSP — ${e.javaClass.simpleName}",
            )
        }

        // Fallback projection (AOSP without MediaTek SIM columns).
        val basicProjection = arrayOf(
            RawContacts._ID,
            RawContacts.ACCOUNT_NAME,
            RawContacts.ACCOUNT_TYPE,
        )
        try {
            context.contentResolver.query(RawContacts.CONTENT_URI, basicProjection, selection, args, null)?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(RawContacts._ID)
                val nameIdx = c.getColumnIndex(RawContacts.ACCOUNT_NAME)
                val typeIdx = c.getColumnIndex(RawContacts.ACCOUNT_TYPE)
                while (c.moveToNext()) {
                    val accountType = if (typeIdx >= 0) c.getString(typeIdx) ?: "" else ""
                    if (!isSimAccountTypeForPersistence(accountType)) continue
                    out.add(
                        SimRawContactMeta(
                            rawContactId = c.getLong(idIdx),
                            accountName = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else "",
                            accountType = accountType,
                            indexInSim = 0,
                            mtkSubId = 0,
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SimContactSave", "loadSimRawContactDeleteMetadata basic query failed", e)
        }
        return out
    }

    /** Read the first phone number for a raw contact id (for the AOSP ADN-search fallback). */
    private fun loadFirstPhoneNumberForRawContactId(rawContactId: Long): String {
        return try {
            context.contentResolver.query(
                Data.CONTENT_URI,
                arrayOf(CommonDataKinds.Phone.NUMBER),
                "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
                arrayOf(rawContactId.toString(), CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
                null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) ?: "" else ""
            } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /** Read the display name for a raw contact id (used by the ADN-search fallback's tag match). */
    private fun loadDisplayNameForRawContactId(rawContactId: Long): String {
        return try {
            context.contentResolver.query(
                Data.CONTENT_URI,
                arrayOf(CommonDataKinds.StructuredName.DISPLAY_NAME),
                "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
                arrayOf(rawContactId.toString(), CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
                null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) ?: "" else ""
            } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun getDuplicatesOfContact(contact: Contact, addOriginal: Boolean, callback: (ArrayList<Contact>) -> Unit) {
        ensureBackgroundThread {
            getContacts(true, true) { contacts ->
                val duplicates =
                    contacts.filter { it.id != contact.id && it.getHashToCompare() == contact.getHashToCompare() }.toMutableList() as ArrayList<Contact>
                if (addOriginal) {
                    duplicates.add(contact)
                }
                callback(duplicates)
            }
        }
    }

    fun getContactsForRecents(
        callback: (ArrayList<Contact>) -> Unit
    ) {
        ensureBackgroundThread {
            ContactProtectionHelper.ensureUnlockedForThread(context)
            val contacts = SparseArray<Contact>()
            // For recents, always include all phone storage and SIM contacts regardless of filter
            // Get all sources (phone storage and SIM) without applying the current filter
            val allPhoneAndSimSources = context.getAllContactSources()
            displayContactSources = allPhoneAndSimSources.map { it.name }.toMutableList() as ArrayList

            // Pass empty ignored sources to load all phone storage and SIM contacts for recents
            getDeviceContactsForRecents(contacts, HashSet())

            val contactsSize = contacts.size
            val tempContacts = ArrayList<Contact>(contactsSize)
            val resultContacts = ArrayList<Contact>(contactsSize)

            // Optimize filtering by avoiding intermediate collections
            for (i in 0 until contactsSize) {
                val contact = contacts.valueAt(i)
                if (contact.phoneNumbers.isNotEmpty()) {
                    tempContacts.add(contact)
                }
            }

            if (context.baseConfig.mergeDuplicateContacts) {
                // Optimize: Use HashSet for O(1) lookup instead of O(n) contains
                val displaySourcesSet = displayContactSources.toHashSet()
                tempContacts.filter { displaySourcesSet.contains(it.source) }.groupBy { it.getNameToDisplay().lowercase() }.values.forEach { group ->
                    if (group.size == 1) {
                        resultContacts.add(group.first())
                    } else {
                        // Optimize: Use maxByOrNull instead of sorted + first
                        val bestMatch = group.maxByOrNull { it.getStringToCompare().length }
                        if (bestMatch != null) {
                            resultContacts.add(bestMatch)
                        }
                    }
                }
            } else {
                resultContacts.addAll(tempContacts)
            }

            // groups are obtained with contactID, not rawID, so assign them to proper contacts like this
            // Cache stored groups and use map for O(1) lookup instead of firstOrNull
            val storedGroups = getStoredGroupsSync()
            val groups = getContactGroups(storedGroups)
            val contactIdToContactMap = resultContacts.associateBy { it.contactId }
            val groupsSize = groups.size
            for (i in 0 until groupsSize) {
                val key = groups.keyAt(i)
                contactIdToContactMap[key]?.groups = groups.valueAt(i)
            }

            Contact.sorting = SORT_BY_FULL_NAME
            Contact.startWithSurname = context.baseConfig.startNameWithSurname
            Contact.showNicknameInsteadNames = context.baseConfig.showNicknameInsteadNames
            Contact.sortingSymbolsFirst = true  // Fixed order: symbols, Korean, English, other
            Contact.collator = Collator.getInstance(context.sysLocale())

            callback(resultContacts)
        }
    }

    private fun getDeviceContactsForRecents(contacts: SparseArray<Contact>, ignoredSources: HashSet<String>? = null) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return
        }

        val ignoredSourcesToUse = ignoredSources ?: context.baseConfig.ignoredContactSources
        val uri = Data.CONTENT_URI
        val projection = getContactProjection()

        arrayOf(CommonDataKinds.Organization.CONTENT_ITEM_TYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE).forEach { mimetype ->
            val selection = "${Data.MIMETYPE} = ?"
            val selectionArgs = arrayOf(mimetype)
            val sortOrder = getSortString()

            context.queryCursor(uri, projection, selection, selectionArgs, sortOrder, true) { cursor ->
                val accountName = cursor.getStringValue(RawContacts.ACCOUNT_NAME) ?: ""
                val accountType = cursor.getStringValue(RawContacts.ACCOUNT_TYPE) ?: ""

                // Load phone storage and SIM card contacts
                if (!isSimOrPhoneStorage(accountName, accountType)) {
                    return@queryCursor
                }
                // Optimize: Cache account identifier to avoid repeated string concatenation
                val accountIdentifier = if (accountName.isEmpty() && accountType.isEmpty()) {
                    ":"
                } else {
                    "$accountName:$accountType"
                }
                if (ignoredSourcesToUse.contains(accountIdentifier)) {
                    return@queryCursor
                }

                val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
                var prefix = ""
                var name = ""
                var middleName = ""
                var surname = ""
                var suffix = ""

                // ignore names at Organization type contacts
                if (mimetype == CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) {
                    prefix = cursor.getStringValue(CommonDataKinds.StructuredName.PREFIX) ?: ""
                    val givenName = cursor.getStringValue(CommonDataKinds.StructuredName.GIVEN_NAME) ?: ""
                    middleName = cursor.getStringValue(CommonDataKinds.StructuredName.MIDDLE_NAME) ?: ""
                    val familyName = cursor.getStringValue(CommonDataKinds.StructuredName.FAMILY_NAME) ?: ""
                    suffix = cursor.getStringValue(CommonDataKinds.StructuredName.SUFFIX) ?: ""
                    
                    name = composeStructuredRowDisplayName(cursor, prefix, givenName, middleName, familyName, suffix)
                    // Clear other name fields since we're using single name
                    prefix = ""
                    middleName = ""
                    surname = ""
                    suffix = ""
                }

                var photoUri = ""
                var starred = 0
                var contactId = 0
                var thumbnailUri = ""
                var ringtone: String? = null

                photoUri = cursor.getStringValue(CommonDataKinds.StructuredName.PHOTO_URI) ?: ""
                starred = cursor.getIntValue(CommonDataKinds.StructuredName.STARRED)
                contactId = cursor.getIntValue(Data.CONTACT_ID)
                thumbnailUri = cursor.getStringValue(CommonDataKinds.StructuredName.PHOTO_THUMBNAIL_URI) ?: ""
                ringtone = cursor.getStringValue(CommonDataKinds.StructuredName.CUSTOM_RINGTONE)

                val nickname = ""
                val numbers = ArrayList<PhoneNumber>()          // proper value is obtained below
                val emails = ArrayList<Email>()
                val addresses = ArrayList<Address>()
                val events = ArrayList<Event>()
                val notes = ""
                val groups = ArrayList<Group>()
                val organization = Organization("", "")
                val ims = ArrayList<IM>()
                val contact = Contact(
                    id, prefix, name, middleName, surname, suffix, nickname,
                    photoUri, numbers, emails, addresses, events, accountName,
                    starred, contactId, thumbnailUri, null, notes, groups, organization,
                    ims, mimetype, ringtone
                )

                contacts.put(id, contact)
            }
        }

        // Helper function to apply SparseArray values to contacts
        fun <T> applySparseArrayToContacts(sparseArray: SparseArray<T>, apply: (Contact, T) -> Unit) {
            val size = sparseArray.size
            for (i in 0 until size) {
                val key = sparseArray.keyAt(i)
                contacts[key]?.let { apply(it, sparseArray.valueAt(i)) }
            }
        }

        val phoneNumbers = getPhoneNumbers(null)
        val phoneSize = phoneNumbers.size
        for (i in 0 until phoneSize) {
            val key = phoneNumbers.keyAt(i)
            contacts[key]?.let { it.phoneNumbers = phoneNumbers.valueAt(i) }
        }

        // Nickname removed - always set to empty
        val contactsSize = contacts.size
        for (i in 0 until contactsSize) {
            contacts.valueAt(i).nickname = ""
        }
    }

    /**
     * Prefer [CommonDataKinds.StructuredName.DISPLAY_NAME] when the provider stored it (SIM/USIM
     * sync often keeps the full ADN string there while splitting [GIVEN_NAME]/[FAMILY_NAME] and
     * dropping spaces).
     */
    private fun composeStructuredRowDisplayName(
        cursor: Cursor,
        prefix: String,
        givenName: String,
        middleName: String,
        familyName: String,
        suffix: String,
    ): String {
        val idx = cursor.getColumnIndex(CommonDataKinds.StructuredName.DISPLAY_NAME)
        if (idx >= 0) {
            val direct = cursor.getString(idx)?.trim().orEmpty()
            if (direct.isNotEmpty()) return direct
        }
        return buildDisplayName(prefix, givenName, middleName, familyName, suffix)
    }

    /**
     * Builds a display name string from structured name components, using Eastern order
     * (family+given, no space) for Korean/CJK scripts and Western order otherwise.
     */
    private fun buildDisplayName(
        prefix: String, givenName: String, middleName: String,
        familyName: String, suffix: String
    ): String {
        val eastern = familyName.isNotBlank() && givenName.isNotBlank() &&
            (isEasternScript(familyName) || isEasternScript(givenName))
        return if (eastern) {
            listOf(prefix, familyName + givenName, middleName, suffix)
                .filter { it.isNotBlank() }.joinToString(" ").trim()
        } else {
            val sb = StringBuilder()
            if (prefix.isNotEmpty()) sb.append(prefix).append(" ")
            if (givenName.isNotEmpty()) sb.append(givenName).append(" ")
            if (middleName.isNotEmpty()) sb.append(middleName).append(" ")
            if (familyName.isNotEmpty()) sb.append(familyName).append(" ")
            if (suffix.isNotEmpty()) sb.append(suffix).append(" ")
            sb.trim().toString()
        }
    }

    private fun isEasternScript(s: String): Boolean = s.any { c ->
        c in '\uAC00'..'\uD7AF' ||  // Hangul syllables
        c in '\u1100'..'\u11FF' ||  // Hangul Jamo
        c in '\u3130'..'\u318F' ||  // Hangul Compatibility Jamo (ㄱ–ㅣ)
        c in '\uA960'..'\uA97F' ||  // Hangul Jamo Extended-A
        c in '\uD7B0'..'\uD7FF' ||  // Hangul Jamo Extended-B
        c in '\u4E00'..'\u9FFF' ||  // CJK unified ideographs
        c in '\u3400'..'\u4DBF'     // CJK extension A
    }
}
