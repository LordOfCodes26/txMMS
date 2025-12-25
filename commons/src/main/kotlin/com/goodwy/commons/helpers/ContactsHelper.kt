package com.goodwy.commons.helpers

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.*
import android.provider.MediaStore
import android.text.TextUtils
import android.util.SparseArray
import com.goodwy.commons.R
import com.goodwy.commons.extensions.*
import com.goodwy.commons.models.PhoneNumber
import com.goodwy.commons.models.contacts.*
import com.goodwy.commons.overloads.times
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.text.Collator
import java.util.Locale
import androidx.core.util.size
import androidx.core.net.toUri
import androidx.core.graphics.scale
import java.util.LinkedHashSet
import com.goodwy.commons.helpers.getQuestionMarks

class ContactsHelper(val context: Context) {
    companion object {
        private val contactSourcesCache = mutableMapOf<String, LinkedHashSet<ContactSource>>()
    }

    private val BATCH_SIZE = 50
    private var displayContactSources = ArrayList<String>()

    fun getContacts(
        getAll: Boolean = false,
        gettingDuplicates: Boolean = false,
        ignoredContactSources: HashSet<String> = HashSet(),
        showOnlyContactsWithNumbers: Boolean = context.baseConfig.showOnlyContactsWithNumbers,
        loadExtendedFields: Boolean = true, // Set to false for list views to skip addresses, events, notes, websites, relations, IMs
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

            if (context.baseConfig.mergeDuplicateContacts && ignoredContactSources.isEmpty() && !getAll) {
                // Optimize duplicate merging: filter and group in single pass
                val contactsToMerge = ArrayList<Contact>()
                val contactsToAdd = ArrayList<Contact>()
                for (contact in tempContacts) {
                    if (displayContactSources.contains(contact.source)) {
                        contactsToMerge.add(contact)
                    } else {
                        contactsToAdd.add(contact)
                    }
                }
                
                // Group by name (lowercase) for merging
                val groupedByName = HashMap<String, ArrayList<Contact>>()
                for (contact in contactsToMerge) {
                    val key = contact.getNameToDisplay().lowercase(Locale.getDefault())
                    groupedByName.getOrPut(key) { ArrayList() }.add(contact)
                }
                
                // Process grouped contacts
                for (group in groupedByName.values) {
                    if (group.size == 1) {
                        resultContacts.add(group.first())
                    } else {
                        // Sort once and find best match
                        group.sortByDescending { it.getStringToCompare().length }
                        val bestMatch = group.firstOrNull { it.phoneNumbers.isNotEmpty() } ?: group.first()
                        resultContacts.add(bestMatch)
                    }
                }
                
                resultContacts.addAll(contactsToAdd)
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

            Contact.sorting = context.baseConfig.sorting
            Contact.startWithSurname = context.baseConfig.startNameWithSurname
            Contact.showNicknameInsteadNames = context.baseConfig.showNicknameInsteadNames
            Contact.sortingSymbolsFirst = context.baseConfig.sortingSymbolsFirst
            Contact.collator = Collator.getInstance(context.sysLocale())
            System.setProperty("java.util.Arrays.useLegacyMergeSort", "true") //https://stackoverflow.com/questions/11441666/java-error-comparison-method-violates-its-general-contract
            resultContacts.sort()

            Handler(Looper.getMainLooper()).post {
                callback(resultContacts)
            }
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
            if (type == TELEGRAM_PACKAGE) {
                publicName = context.getString(R.string.telegram)
            }

            val source = ContactSource(name, type, publicName)
            sources.add(source)
        }
    }

    @SuppressLint("UseKtx")
    private fun getDeviceContacts(contacts: SparseArray<Contact>, ignoredContactSources: HashSet<String>?, gettingDuplicates: Boolean, loadExtendedFields: Boolean = true) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return
        }

        val ignoredSources = ignoredContactSources ?: context.baseConfig.ignoredContactSources
        val uri = Data.CONTENT_URI
        val projection = getContactProjection()

        arrayOf(CommonDataKinds.Organization.CONTENT_ITEM_TYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE).forEach { mimetype ->
            val selection = "${Data.MIMETYPE} = ?"
            val selectionArgs = arrayOf(mimetype)
            val sortOrder = getSortString()

            context.queryCursor(uri, projection, selection, selectionArgs, sortOrder, true) { cursor ->
                val accountName = cursor.getStringValue(RawContacts.ACCOUNT_NAME) ?: ""
                val accountType = cursor.getStringValue(RawContacts.ACCOUNT_TYPE) ?: ""

                // Only load system contacts (empty account name/type or "Phone" account)
                val isSystemContact = (accountName.isEmpty() && accountType.isEmpty()) ||
                    (accountName.lowercase(Locale.getDefault()) == "phone" && accountType.isEmpty())
                
                if (!isSystemContact || ignoredSources.contains("$accountName:$accountType")) {
                    return@queryCursor
                }

                val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
                var prefix = ""
                var firstName = ""
                var middleName = ""
                var surname = ""
                var suffix = ""

                // ignore names at Organization type contacts
                if (mimetype == CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) {
                    prefix = cursor.getStringValue(CommonDataKinds.StructuredName.PREFIX) ?: ""
                    firstName = cursor.getStringValue(CommonDataKinds.StructuredName.GIVEN_NAME) ?: ""
                    middleName = cursor.getStringValue(CommonDataKinds.StructuredName.MIDDLE_NAME) ?: ""
                    surname = cursor.getStringValue(CommonDataKinds.StructuredName.FAMILY_NAME) ?: ""
                    suffix = cursor.getStringValue(CommonDataKinds.StructuredName.SUFFIX) ?: ""
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
                val websites = ArrayList<String>()
                val relations = ArrayList<ContactRelation>()
                val ims = ArrayList<IM>()
                val contact = Contact(
                    id, prefix, firstName, middleName, surname, suffix, nickname,
                    photoUri, numbers, emails, addresses, events, accountName,
                    starred, contactId, thumbnailUri, null, notes, groups, organization,
                    websites, relations, ims, mimetype, ringtone
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

        applySparseArrayToContacts(getEmails()) { contact, emails -> contact.emails = emails }
        applySparseArrayToContacts(getOrganizations()) { contact, org -> contact.organization = org }

        // no need to fetch some fields if we are only getting duplicates of the current contact
        if (gettingDuplicates) {
            return
        }

        val phoneNumbers = getPhoneNumbers(null)
        val phoneSize = phoneNumbers.size
        for (i in 0 until phoneSize) {
            val key = phoneNumbers.keyAt(i)
            contacts[key]?.let { it.phoneNumbers = phoneNumbers.valueAt(i) }
        }

        applySparseArrayToContacts(getNicknames()) { contact, nickname -> contact.nickname = nickname }
        
        // Only load extended fields if requested (skip for list views to improve performance)
        if (loadExtendedFields) {
            applySparseArrayToContacts(getAddresses()) { contact, addresses -> contact.addresses = addresses }
            applySparseArrayToContacts(getIMs()) { contact, ims -> contact.IMs = ims }
            applySparseArrayToContacts(getEvents()) { contact, events -> contact.events = events }
            applySparseArrayToContacts(getNotes()) { contact, note -> contact.notes = note }
            applySparseArrayToContacts(getWebsites()) { contact, websites -> contact.websites = websites }
            applySparseArrayToContacts(getRelations()) { contact, relations -> contact.relations = relations }
        }
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
            val type = cursor.getIntValue(CommonDataKinds.Phone.TYPE)
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

    private fun getIMs(contactId: Int? = null): SparseArray<ArrayList<IM>> {
        val IMs = SparseArray<ArrayList<IM>>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            CommonDataKinds.Im.DATA,
            CommonDataKinds.Im.PROTOCOL,
            CommonDataKinds.Im.CUSTOM_PROTOCOL
        )

        val selection = getSourcesSelection(true, contactId != null)
        val selectionArgs = getSourcesSelectionArgs(CommonDataKinds.Im.CONTENT_ITEM_TYPE, contactId)

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val IM = cursor.getStringValue(CommonDataKinds.Im.DATA) ?: return@queryCursor
            val type = cursor.getIntValue(CommonDataKinds.Im.PROTOCOL)
            val label = cursor.getStringValue(CommonDataKinds.Im.CUSTOM_PROTOCOL) ?: ""

            if (IMs[id] == null) {
                IMs.put(id, ArrayList())
            }

            IMs[id]!!.add(IM(IM, type, label))
        }

        return IMs
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
            val label = cursor.getStringValue(CommonDataKinds.Event.LABEL) ?: ""

            if (events[id] == null) {
                events.put(id, ArrayList())
            }

            events[id]!!.add(Event(startDate, type, label))
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

    private fun getWebsites(contactId: Int? = null): SparseArray<ArrayList<String>> {
        val websites = SparseArray<ArrayList<String>>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            CommonDataKinds.Website.URL
        )

        val selection = getSourcesSelection(true, contactId != null)
        val selectionArgs = getSourcesSelectionArgs(CommonDataKinds.Website.CONTENT_ITEM_TYPE, contactId)

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val url = cursor.getStringValue(CommonDataKinds.Website.URL) ?: return@queryCursor

            if (websites[id] == null) {
                websites.put(id, ArrayList())
            }

            websites[id]!!.add(url)
        }

        return websites
    }

    private fun getRelations(contactId: Int? = null): SparseArray<ArrayList<ContactRelation>> {
        val relations = SparseArray<ArrayList<ContactRelation>>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            CommonDataKinds.Relation.NAME,
            CommonDataKinds.Relation.TYPE,
            CommonDataKinds.Relation.LABEL
        )

        val selection = getSourcesSelection(true, contactId != null)
        val selectionArgs = getSourcesSelectionArgs(CommonDataKinds.Relation.CONTENT_ITEM_TYPE, contactId)

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id: Int = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val name: String = cursor.getStringValue(CommonDataKinds.Relation.NAME) ?: return@queryCursor
            val type: Int = cursor.getIntValue(CommonDataKinds.Relation.TYPE)
            val label: String = cursor.getStringValue(CommonDataKinds.Relation.LABEL) ?: ""

            val (editType, editLabel) = getRelationEditTypeLabelFromAndroidTypeLabel(type, label)

            if (relations[id] == null) {
                relations.put(id, ArrayList())
            }

            relations[id]!!.add(ContactRelation(name.trim(), editType, editLabel.trim()))
        }
        return relations
    }

    private fun getRelationEditTypeLabelFromAndroidTypeLabel(type: Int, label: String): Pair<Int, String> {
        if (type != ContactRelation.TYPE_CUSTOM) {
            return Pair(type, "")
        } else {
            val detectType = when (label.trim().lowercase()) {
                "" -> ContactRelation.TYPE_CUSTOM
                context.getString(R.string.relation_label_assistant) -> ContactRelation.TYPE_ASSISTANT
                context.getString(R.string.relation_label_brother) -> ContactRelation.TYPE_BROTHER
                context.getString(R.string.relation_label_child) -> ContactRelation.TYPE_CHILD
                context.getString(R.string.relation_label_domestic_partner) -> ContactRelation.TYPE_DOMESTIC_PARTNER
                context.getString(R.string.relation_label_father) -> ContactRelation.TYPE_FATHER
                context.getString(R.string.relation_label_friend) -> ContactRelation.TYPE_FRIEND
                context.getString(R.string.relation_label_manager) -> ContactRelation.TYPE_MANAGER
                context.getString(R.string.relation_label_mother) -> ContactRelation.TYPE_MOTHER
                context.getString(R.string.relation_label_parent) -> ContactRelation.TYPE_PARENT
                context.getString(R.string.relation_label_partner) -> ContactRelation.TYPE_PARTNER
                context.getString(R.string.relation_label_referred_by) -> ContactRelation.TYPE_REFERRED_BY
                context.getString(R.string.relation_label_relative) -> ContactRelation.TYPE_RELATIVE
                context.getString(R.string.relation_label_sister) -> ContactRelation.TYPE_SISTER
                context.getString(R.string.relation_label_spouse) -> ContactRelation.TYPE_SPOUSE
                context.getString(R.string.relation_label_contact) -> ContactRelation.TYPE_CONTACT
                context.getString(R.string.relation_label_acquaintance) -> ContactRelation.TYPE_ACQUAINTANCE
                context.getString(R.string.relation_label_met) -> ContactRelation.TYPE_MET
                context.getString(R.string.relation_label_co_worker) -> ContactRelation.TYPE_CO_WORKER
                context.getString(R.string.relation_label_colleague) -> ContactRelation.TYPE_COLLEAGUE
                context.getString(R.string.relation_label_co_resident) -> ContactRelation.TYPE_CO_RESIDENT
                context.getString(R.string.relation_label_neighbor) -> ContactRelation.TYPE_NEIGHBOR
                context.getString(R.string.relation_label_sibling) -> ContactRelation.TYPE_SIBLING
                context.getString(R.string.relation_label_kin) -> ContactRelation.TYPE_KIN
                context.getString(R.string.relation_label_kin_alt) -> ContactRelation.TYPE_KIN
                context.getString(R.string.relation_label_muse) -> ContactRelation.TYPE_MUSE
                context.getString(R.string.relation_label_crush) -> ContactRelation.TYPE_CRUSH
                context.getString(R.string.relation_label_date) -> ContactRelation.TYPE_DATE
                context.getString(R.string.relation_label_sweetheart) -> ContactRelation.TYPE_SWEETHEART
                context.getString(R.string.relation_label_agent) -> ContactRelation.TYPE_AGENT
                context.getString(R.string.relation_label_emergency) -> ContactRelation.TYPE_EMERGENCY
                context.getString(R.string.relation_label_me) -> ContactRelation.TYPE_ME
                context.getString(R.string.relation_label_superior) -> ContactRelation.TYPE_SUPERIOR
                context.getString(R.string.relation_label_subordinate) -> ContactRelation.TYPE_SUBORDINATE
                context.getString(R.string.relation_label_husband) -> ContactRelation.TYPE_HUSBAND
                context.getString(R.string.relation_label_wife) -> ContactRelation.TYPE_WIFE
                context.getString(R.string.relation_label_son) -> ContactRelation.TYPE_SON
                context.getString(R.string.relation_label_daughter) -> ContactRelation.TYPE_DAUGHTER
                context.getString(R.string.relation_label_grandparent) -> ContactRelation.TYPE_GRANDPARENT
                context.getString(R.string.relation_label_grandfather) -> ContactRelation.TYPE_GRANDFATHER
                context.getString(R.string.relation_label_grandmother) -> ContactRelation.TYPE_GRANDMOTHER
                context.getString(R.string.relation_label_grandchild) -> ContactRelation.TYPE_GRANDCHILD
                context.getString(R.string.relation_label_grandson) -> ContactRelation.TYPE_GRANDSON
                context.getString(R.string.relation_label_granddaughter) -> ContactRelation.TYPE_GRANDDAUGHTER
                context.getString(R.string.relation_label_uncle) -> ContactRelation.TYPE_UNCLE
                context.getString(R.string.relation_label_aunt) -> ContactRelation.TYPE_AUNT
                context.getString(R.string.relation_label_nephew) -> ContactRelation.TYPE_NEPHEW
                context.getString(R.string.relation_label_niece) -> ContactRelation.TYPE_NIECE
                context.getString(R.string.relation_label_father_in_law) -> ContactRelation.TYPE_FATHER_IN_LAW
                context.getString(R.string.relation_label_mother_in_law) -> ContactRelation.TYPE_MOTHER_IN_LAW
                context.getString(R.string.relation_label_son_in_law) -> ContactRelation.TYPE_SON_IN_LAW
                context.getString(R.string.relation_label_daughter_in_law) -> ContactRelation.TYPE_DAUGHTER_IN_LAW
                context.getString(R.string.relation_label_brother_in_law) -> ContactRelation.TYPE_BROTHER_IN_LAW
                context.getString(R.string.relation_label_sister_in_law) -> ContactRelation.TYPE_SISTER_IN_LAW
                else -> ContactRelation.TYPE_CUSTOM
            }
            return if (detectType == ContactRelation.TYPE_CUSTOM)
                Pair(detectType, label)
            else
                Pair(detectType, "")
        }
    }

    private fun getRelationAndroidTypeLabelFromEditTypeLabel(type: Int, label: String): Pair<Int, String> {
        return when (type) {
            ContactRelation.TYPE_CUSTOM -> Pair(ContactRelation.TYPE_CUSTOM, label.trim())
            ContactRelation.TYPE_ASSISTANT -> Pair(ContactRelation.TYPE_ASSISTANT, "")
            ContactRelation.TYPE_BROTHER -> Pair(ContactRelation.TYPE_BROTHER, "")
            ContactRelation.TYPE_CHILD -> Pair(ContactRelation.TYPE_CHILD, "")
            ContactRelation.TYPE_DOMESTIC_PARTNER -> Pair(ContactRelation.TYPE_DOMESTIC_PARTNER, "")
            ContactRelation.TYPE_FATHER -> Pair(ContactRelation.TYPE_FATHER, "")
            ContactRelation.TYPE_FRIEND -> Pair(ContactRelation.TYPE_FRIEND, "")
            ContactRelation.TYPE_MANAGER -> Pair(ContactRelation.TYPE_MANAGER, "")
            ContactRelation.TYPE_MOTHER -> Pair(ContactRelation.TYPE_MOTHER, "")
            ContactRelation.TYPE_PARENT -> Pair(ContactRelation.TYPE_PARENT, "")
            ContactRelation.TYPE_PARTNER -> Pair(ContactRelation.TYPE_PARTNER, "")
            ContactRelation.TYPE_REFERRED_BY -> Pair(ContactRelation.TYPE_REFERRED_BY, "")
            ContactRelation.TYPE_RELATIVE -> Pair(ContactRelation.TYPE_RELATIVE, "")
            ContactRelation.TYPE_SISTER -> Pair(ContactRelation.TYPE_SISTER, "")
            ContactRelation.TYPE_SPOUSE -> Pair(ContactRelation.TYPE_SPOUSE, "")

            // Relation types defined in vCard 4.0
            ContactRelation.TYPE_CONTACT -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_contact))
            ContactRelation.TYPE_ACQUAINTANCE -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_acquaintance))
            // ContactRelation.TYPE_FRIEND -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_friend))
            ContactRelation.TYPE_MET -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_met))
            ContactRelation.TYPE_CO_WORKER -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_co_worker))
            ContactRelation.TYPE_COLLEAGUE -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_colleague))
            ContactRelation.TYPE_CO_RESIDENT -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_co_resident))
            ContactRelation.TYPE_NEIGHBOR -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_neighbor))
            // ContactRelation.TYPE_CHILD -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_child))
            // ContactRelation.TYPE_PARENT -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_parent))
            ContactRelation.TYPE_SIBLING -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_sibling))
            // ContactRelation.TYPE_SPOUSE -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_spouse))
            ContactRelation.TYPE_KIN -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_kin))
            ContactRelation.TYPE_MUSE -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_muse))
            ContactRelation.TYPE_CRUSH -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_crush))
            ContactRelation.TYPE_DATE -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_date))
            ContactRelation.TYPE_SWEETHEART -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_sweetheart))
            ContactRelation.TYPE_ME -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_me))
            ContactRelation.TYPE_AGENT -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_agent))
            ContactRelation.TYPE_EMERGENCY -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_emergency))

            ContactRelation.TYPE_SUPERIOR -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_superior))
            ContactRelation.TYPE_SUBORDINATE -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_subordinate))
            ContactRelation.TYPE_HUSBAND -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_husband))
            ContactRelation.TYPE_WIFE -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_wife))
            ContactRelation.TYPE_SON -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_son))
            ContactRelation.TYPE_DAUGHTER -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_daughter))
            ContactRelation.TYPE_GRANDPARENT -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_grandparent))
            ContactRelation.TYPE_GRANDFATHER -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_grandfather))
            ContactRelation.TYPE_GRANDMOTHER -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_grandmother))
            ContactRelation.TYPE_GRANDCHILD -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_grandchild))
            ContactRelation.TYPE_GRANDSON -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_grandson))
            ContactRelation.TYPE_GRANDDAUGHTER -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_granddaughter))
            ContactRelation.TYPE_UNCLE -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_uncle))
            ContactRelation.TYPE_AUNT -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_aunt))
            ContactRelation.TYPE_NEPHEW -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_nephew))
            ContactRelation.TYPE_NIECE -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_niece))
            ContactRelation.TYPE_FATHER_IN_LAW -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_father_in_law))
            ContactRelation.TYPE_MOTHER_IN_LAW -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_mother_in_law))
            ContactRelation.TYPE_SON_IN_LAW -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_son_in_law))
            ContactRelation.TYPE_DAUGHTER_IN_LAW -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_daughter_in_law))
            ContactRelation.TYPE_BROTHER_IN_LAW -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_brother_in_law))
            ContactRelation.TYPE_SISTER_IN_LAW -> Pair(ContactRelation.TYPE_CUSTOM, context.getString(R.string.relation_label_sister_in_law))

            else -> Pair(ContactRelation.TYPE_CUSTOM, label.trim())
        }
    } // getRelationAndroidTypeLabelFromEditTypeLabel()

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

    private fun getQuestionMarks() = ("?," * displayContactSources.filter { it.isNotEmpty() }.size).trimEnd(',')

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
            accountnameString.append("${RawContacts.ACCOUNT_NAME} IN (${getQuestionMarks()})")
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
            Groups.SYSTEM_ID
        )

        val selection = "${Groups.AUTO_ADD} = ? AND ${Groups.FAVORITES} = ?"
        val selectionArgs = arrayOf("0", "0")

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getLongValue(Groups._ID)
            val title = cursor.getStringValue(Groups.TITLE) ?: return@queryCursor

            val systemId = cursor.getStringValue(Groups.SYSTEM_ID)
            if (groups.map { it.title }.contains(title) && systemId != null) {
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
        return parseContactCursor(selection, selectionArgs)
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
                var firstName = ""
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
                    firstName = cursor.getStringValue(CommonDataKinds.StructuredName.GIVEN_NAME) ?: ""
                    middleName = cursor.getStringValue(CommonDataKinds.StructuredName.MIDDLE_NAME) ?: ""
                    surname = cursor.getStringValue(CommonDataKinds.StructuredName.FAMILY_NAME) ?: ""
                    suffix = cursor.getStringValue(CommonDataKinds.StructuredName.SUFFIX) ?: ""
                }

                val nickname = getNicknames(id)[id] ?: ""
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
                val websites = getWebsites(id)[id] ?: ArrayList()
                val relations = getRelations(id)[id] ?: ArrayList<ContactRelation>()
                val ims = getIMs(id)[id] ?: ArrayList()
                return Contact(
                    id, prefix, firstName, middleName, surname, suffix, nickname,
                    photoUri, number, emails, addresses, events, accountName, starred,
                    contactId, thumbnailUri, null, notes, groups, organization,
                    websites, relations, ims, mimetype, ringtone
                )
            }
        }

        return null
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
                SIGNAL_PACKAGE,
                TELEGRAM_PACKAGE,
                WHATSAPP_PACKAGE,
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
        
        // Helper function to check if account is SIM card or phone storage
        fun isSimOrPhoneStorage(accountName: String, accountType: String): Boolean {
            val nameLower = accountName.lowercase(Locale.getDefault())
            val typeLower = accountType.lowercase(Locale.getDefault())
            
            // Phone storage: empty account name/type or "phone" account
            val isPhoneStorage = (accountName.isEmpty() && accountType.isEmpty()) ||
                (nameLower == "phone" && accountType.isEmpty())
            
            // SIM card: account type contains "sim" or "icc"
            val isSimCard = typeLower.contains("sim") || typeLower.contains("icc")
            
            return isPhoneStorage || isSimCard
        }
        
        for (account in accounts) {
            if (ContentResolver.getIsSyncable(account, AUTHORITY) >= 0) {
                // Only include SIM card and phone storage accounts
                if (isSimOrPhoneStorage(account.name, account.type)) {
                    val accountKey = "${account.name}|${account.type}"
                    if (seenAccountKeys.add(accountKey)) {
                        sources.add(ContactSource(account.name, account.type, account.name))
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
                            sources.add(account)
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

    private fun getContactSourceType(accountName: String) = getDeviceContactSources().firstOrNull { it.name == accountName }?.type ?: ""

    private fun getContactProjection() = arrayOf(
        Data.MIMETYPE,
        Data.CONTACT_ID,
        Data.RAW_CONTACT_ID,
        CommonDataKinds.StructuredName.PREFIX,
        CommonDataKinds.StructuredName.GIVEN_NAME,
        CommonDataKinds.StructuredName.MIDDLE_NAME,
        CommonDataKinds.StructuredName.FAMILY_NAME,
        CommonDataKinds.StructuredName.SUFFIX,
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

    fun updateContact(contact: Contact, photoUpdateStatus: Int): Boolean {
        context.toast(R.string.updating)

        try {
            val operations = ArrayList<ContentProviderOperation>()
            ContentProviderOperation.newUpdate(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?"
                val selectionArgs = arrayOf(contact.id.toString(), contact.mimetype)
                withSelection(selection, selectionArgs)
                withValue(CommonDataKinds.StructuredName.PREFIX, contact.prefix)
                withValue(CommonDataKinds.StructuredName.GIVEN_NAME, contact.firstName)
                withValue(CommonDataKinds.StructuredName.MIDDLE_NAME, contact.middleName)
                withValue(CommonDataKinds.StructuredName.FAMILY_NAME, contact.surname)
                withValue(CommonDataKinds.StructuredName.SUFFIX, contact.suffix)
                operations.add(build())
            }

            // delete nickname
            ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
                val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Nickname.CONTENT_ITEM_TYPE)
                withSelection(selection, selectionArgs)
                operations.add(build())
            }

            // add nickname
            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValue(Data.RAW_CONTACT_ID, contact.id)
                withValue(Data.MIMETYPE, CommonDataKinds.Nickname.CONTENT_ITEM_TYPE)
                withValue(CommonDataKinds.Nickname.NAME, contact.nickname)
                operations.add(build())
            }

            // delete phone numbers
            ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
                val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                withSelection(selection, selectionArgs)
                operations.add(build())
            }

            // add phone numbers
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

            // delete emails
            ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
                val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                withSelection(selection, selectionArgs)
                operations.add(build())
            }

            // add emails
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

            // delete addresses
            ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
                val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                withSelection(selection, selectionArgs)
                operations.add(build())
            }

            // add addresses
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

            // delete IMs
            ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
                val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Im.CONTENT_ITEM_TYPE)
                withSelection(selection, selectionArgs)
                operations.add(build())
            }

            // add IMs
            contact.IMs.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValue(Data.RAW_CONTACT_ID, contact.id)
                    withValue(Data.MIMETYPE, CommonDataKinds.Im.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.Im.DATA, it.value)
                    withValue(CommonDataKinds.Im.PROTOCOL, it.type)
                    withValue(CommonDataKinds.Im.CUSTOM_PROTOCOL, it.label)
                    operations.add(build())
                }
            }

            // delete events
            ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
                val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                withSelection(selection, selectionArgs)
                operations.add(build())
            }

            // add events
            contact.events.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValue(Data.RAW_CONTACT_ID, contact.id)
                    withValue(Data.MIMETYPE, CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.Event.START_DATE, it.value)
                    withValue(CommonDataKinds.Event.TYPE, it.type)
                    withValue(CommonDataKinds.Event.LABEL, it.label)
                    operations.add(build())
                }
            }

            // delete notes
            ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
                val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                withSelection(selection, selectionArgs)
                operations.add(build())
            }

            // add notes
            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValue(Data.RAW_CONTACT_ID, contact.id)
                withValue(Data.MIMETYPE, CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                withValue(CommonDataKinds.Note.NOTE, contact.notes)
                operations.add(build())
            }

            // delete organization
            ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
                val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                withSelection(selection, selectionArgs)
                operations.add(build())
            }

            // add organization
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

            // delete websites
            ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
                val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                withSelection(selection, selectionArgs)
                operations.add(build())
            }

            // add websites
            contact.websites.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValue(Data.RAW_CONTACT_ID, contact.id)
                    withValue(Data.MIMETYPE, CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.Website.URL, it)
                    withValue(CommonDataKinds.Website.TYPE, DEFAULT_WEBSITE_TYPE)
                    operations.add(build())
                }
            }

            // delete relations
            ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? "
                val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Relation.CONTENT_ITEM_TYPE)
                withSelection(selection, selectionArgs)
                operations.add(build())
            }

            // add relations
            contact.relations.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValue(Data.RAW_CONTACT_ID, contact.id)
                    withValue(Data.MIMETYPE, CommonDataKinds.Relation.CONTENT_ITEM_TYPE)
                    val (type, label) = getRelationAndroidTypeLabelFromEditTypeLabel(it.type, it.label)
                    withValue(CommonDataKinds.Relation.NAME, it.name)
                    withValue(CommonDataKinds.Relation.TYPE, type)
                    withValue(CommonDataKinds.Relation.LABEL, label)
                    operations.add(build())
                }
            }

            // delete groups
            val relevantGroupIDs = getStoredGroupsSync().map { it.id }
            if (relevantGroupIDs.isNotEmpty()) {
                val IDsString = TextUtils.join(",", relevantGroupIDs)
                ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                    val selection = "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${Data.DATA1} IN ($IDsString)"
                    val selectionArgs = arrayOf(contact.contactId.toString(), CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                    withSelection(selection, selectionArgs)
                    operations.add(build())
                }
            }

            // add groups
            contact.groups.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValue(Data.RAW_CONTACT_ID, contact.id)
                    withValue(Data.MIMETYPE, CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.GroupMembership.GROUP_ROW_ID, it.id)
                    operations.add(build())
                }
            }

            // favorite, ringtone
            try {
                val uri = Uri.withAppendedPath(Contacts.CONTENT_URI, contact.contactId.toString())
                val contentValues = ContentValues(2)
                contentValues.put(Contacts.STARRED, contact.starred)
                contentValues.put(Contacts.CUSTOM_RINGTONE, contact.ringtone)
                context.contentResolver.update(uri, contentValues, null, null)
            } catch (e: Exception) {
                context.showErrorToast(e)
            }

            // photo
            when (photoUpdateStatus) {
                PHOTO_ADDED, PHOTO_CHANGED -> addPhoto(contact, operations)
                PHOTO_REMOVED -> removePhoto(contact, operations)
            }

            context.contentResolver.applyBatch(AUTHORITY, operations)
            return true
        } catch (e: Exception) {
            context.showErrorToast(e)
            return false
        }
    }

    private fun addPhoto(contact: Contact, operations: ArrayList<ContentProviderOperation>): ArrayList<ContentProviderOperation> {
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
        }
        return operations
    }

    private fun removePhoto(contact: Contact, operations: ArrayList<ContentProviderOperation>): ArrayList<ContentProviderOperation> {
        ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
            val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?"
            val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
            withSelection(selection, selectionArgs)
            operations.add(build())
        }

        return operations
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

    fun insertContact(contact: Contact): Boolean {
        try {
            val operations = ArrayList<ContentProviderOperation>()
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI).apply {
                withValue(RawContacts.ACCOUNT_NAME, contact.source)
                withValue(RawContacts.ACCOUNT_TYPE, getContactSourceType(contact.source))
                operations.add(build())
            }

            // names
            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValueBackReference(Data.RAW_CONTACT_ID, 0)
                withValue(Data.MIMETYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                withValue(CommonDataKinds.StructuredName.PREFIX, contact.prefix)
                withValue(CommonDataKinds.StructuredName.GIVEN_NAME, contact.firstName)
                withValue(CommonDataKinds.StructuredName.MIDDLE_NAME, contact.middleName)
                withValue(CommonDataKinds.StructuredName.FAMILY_NAME, contact.surname)
                withValue(CommonDataKinds.StructuredName.SUFFIX, contact.suffix)
                operations.add(build())
            }

            // nickname
            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValueBackReference(Data.RAW_CONTACT_ID, 0)
                withValue(Data.MIMETYPE, CommonDataKinds.Nickname.CONTENT_ITEM_TYPE)
                withValue(CommonDataKinds.Nickname.NAME, contact.nickname)
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

            // IMs
            contact.IMs.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    withValue(Data.MIMETYPE, CommonDataKinds.Im.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.Im.DATA, it.value)
                    withValue(CommonDataKinds.Im.PROTOCOL, it.type)
                    withValue(CommonDataKinds.Im.CUSTOM_PROTOCOL, it.label)
                    operations.add(build())
                }
            }

            // events
            contact.events.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    withValue(Data.MIMETYPE, CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.Event.START_DATE, it.value)
                    withValue(CommonDataKinds.Event.TYPE, it.type)
                    withValue(CommonDataKinds.Event.LABEL, it.label)
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

            // websites
            contact.websites.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    withValue(Data.MIMETYPE, CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.Website.URL, it)
                    withValue(CommonDataKinds.Website.TYPE, DEFAULT_WEBSITE_TYPE)
                    operations.add(build())
                }
            }

            // relations
            contact.relations.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    withValue(Data.MIMETYPE, CommonDataKinds.Relation.CONTENT_ITEM_TYPE)
                    val (type, label) = getRelationAndroidTypeLabelFromEditTypeLabel(it.type, it.label)
                    withValue(CommonDataKinds.Relation.NAME, it.name)
                    withValue(CommonDataKinds.Relation.TYPE, type)
                    withValue(CommonDataKinds.Relation.LABEL, label)
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

            // photo (inspired by https://gist.github.com/slightfoot/5985900)
            var fullSizePhotoData: ByteArray? = null
            if (contact.photoUri.isNotEmpty()) {
                val photoUri = contact.photoUri.toUri()
                fullSizePhotoData = context.contentResolver.openInputStream(photoUri)?.readBytes()
            }

            val results = context.contentResolver.applyBatch(AUTHORITY, operations)

            // storing contacts on some devices seems to be messed up and they move on Phone instead, or disappear completely
            // try storing a lighter contact version with this oldschool version too just so it wont disappear, future edits work well
            if (getContactSourceType(contact.source).contains(".sim")) {
                val simUri = "content://icc/adn".toUri()
                ContentValues().apply {
                    put("number", contact.phoneNumbers.firstOrNull()?.value ?: "")
                    put("tag", contact.getNameToDisplay())
                    context.contentResolver.insert(simUri, this)
                }
            }

            // fullsize photo
            val rawId = ContentUris.parseId(results[0].uri!!)
            if (contact.photoUri.isNotEmpty() && fullSizePhotoData != null) {
                addFullSizePhoto(rawId, fullSizePhotoData)
            }

            // favorite, ringtone
            val userId = getRealContactId(rawId)
            if (userId != 0) {
                val uri = Uri.withAppendedPath(Contacts.CONTENT_URI, userId.toString())
                val contentValues = ContentValues(2)
                contentValues.put(Contacts.STARRED, contact.starred)
                contentValues.put(Contacts.CUSTOM_RINGTONE, contact.ringtone)
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

    fun addFavorites(contacts: ArrayList<Contact>) {
        ensureBackgroundThread {
            if (context.hasContactPermissions()) {
                toggleFavorites(contacts, true)
            }
        }
    }

    fun removeFavorites(contacts: ArrayList<Contact>) {
        ensureBackgroundThread {
            if (context.hasContactPermissions()) {
                toggleFavorites(contacts, false)
            }
        }
    }

    private fun toggleFavorites(contacts: ArrayList<Contact>, addToFavorites: Boolean) {
        try {
            val operations = ArrayList<ContentProviderOperation>()
            contacts.map { it.contactId.toString() }.forEach {
                val uri = Uri.withAppendedPath(Contacts.CONTENT_URI, it)
                ContentProviderOperation.newUpdate(uri).apply {
                    withValue(Contacts.STARRED, if (addToFavorites) 1 else 0)
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

    fun deleteContacts(contacts: ArrayList<Contact>): Boolean {
        if (!context.hasPermission(PERMISSION_WRITE_CONTACTS)) {
            return false
        }

        return try {
            val resolver = context.contentResolver
            val contactIds = contacts.map { it.id.toLong() }.filter { it > 0 }
            
            if (contactIds.isEmpty()) {
                return true
            }

            // Use bulk delete with IN clause - much faster than individual ContentProviderOperations
            // Process in chunks of 500 for optimal performance
            val uri = RawContacts.CONTENT_URI
            contactIds.chunked(500).forEach { chunk ->
                val selection = "${RawContacts._ID} IN (${getQuestionMarks(chunk.size)})"
                val selectionArgs = chunk.map { it.toString() }.toTypedArray()
                resolver.delete(uri, selection, selectionArgs)
            }
            
            true
        } catch (e: Exception) {
            context.showErrorToast(e)
            false
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

    fun getContactsToExport(selectedContactSources: Set<String>, callback: (List<Contact>) -> Unit) {
        getContacts(getAll = true) { receivedContacts ->
            val contacts = receivedContacts.filter { it.source in selectedContactSources }
            callback(contacts)
        }
    }

    fun exportContacts(contacts: List<Contact>, outputStream: OutputStream): ExportResult {
        return try {
            val jsonString = Json.encodeToString(contacts)
            outputStream.use {
                it.write(jsonString.toByteArray())
            }
            ExportResult.EXPORT_OK
        } catch (_: Error) {
            ExportResult.EXPORT_FAIL
        }
    }

    fun getContactsForRecents(
        callback: (ArrayList<Contact>) -> Unit
    ) {
        ensureBackgroundThread {
            val contacts = SparseArray<Contact>()
            displayContactSources = context.getVisibleContactSources()

            getDeviceContactsForRecents(contacts)

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
                tempContacts.filter { displayContactSources.contains(it.source) }.groupBy { it.getNameToDisplay().lowercase() }.values.forEach { it ->
                    if (it.size == 1) {
                        resultContacts.add(it.first())
                    } else {
                        val sorted = it.sortedByDescending { it.getStringToCompare().length }
                        resultContacts.add(sorted.first())
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

            Contact.sorting = context.baseConfig.sorting
            Contact.startWithSurname = context.baseConfig.startNameWithSurname
            Contact.showNicknameInsteadNames = context.baseConfig.showNicknameInsteadNames
            Contact.sortingSymbolsFirst = context.baseConfig.sortingSymbolsFirst
            Contact.collator = Collator.getInstance(context.sysLocale())

            callback(resultContacts)
        }
    }

    private fun getDeviceContactsForRecents(contacts: SparseArray<Contact>) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return
        }

        val ignoredSources = context.baseConfig.ignoredContactSources
        val uri = Data.CONTENT_URI
        val projection = getContactProjection()

        arrayOf(CommonDataKinds.Organization.CONTENT_ITEM_TYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE).forEach { mimetype ->
            val selection = "${Data.MIMETYPE} = ?"
            val selectionArgs = arrayOf(mimetype)
            val sortOrder = getSortString()

            context.queryCursor(uri, projection, selection, selectionArgs, sortOrder, true) { cursor ->
                val accountName = cursor.getStringValue(RawContacts.ACCOUNT_NAME) ?: ""
                val accountType = cursor.getStringValue(RawContacts.ACCOUNT_TYPE) ?: ""

                // Only load system contacts (empty account name/type or "Phone" account)
                val isSystemContact = (accountName.isEmpty() && accountType.isEmpty()) ||
                    (accountName.lowercase(Locale.getDefault()) == "phone" && accountType.isEmpty())
                
                if (!isSystemContact || ignoredSources.contains("$accountName:$accountType")) {
                    return@queryCursor
                }

                val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
                var prefix = ""
                var firstName = ""
                var middleName = ""
                var surname = ""
                var suffix = ""

                // ignore names at Organization type contacts
                if (mimetype == CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) {
                    prefix = cursor.getStringValue(CommonDataKinds.StructuredName.PREFIX) ?: ""
                    firstName = cursor.getStringValue(CommonDataKinds.StructuredName.GIVEN_NAME) ?: ""
                    middleName = cursor.getStringValue(CommonDataKinds.StructuredName.MIDDLE_NAME) ?: ""
                    surname = cursor.getStringValue(CommonDataKinds.StructuredName.FAMILY_NAME) ?: ""
                    suffix = cursor.getStringValue(CommonDataKinds.StructuredName.SUFFIX) ?: ""
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
                val websites = ArrayList<String>()
                val relations = ArrayList<ContactRelation>()
                val ims = ArrayList<IM>()
                val contact = Contact(
                    id, prefix, firstName, middleName, surname, suffix, nickname,
                    photoUri, numbers, emails, addresses, events, accountName,
                    starred, contactId, thumbnailUri, null, notes, groups, organization,
                    websites, relations, ims, mimetype, ringtone
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

        applySparseArrayToContacts(getNicknames()) { contact, nickname -> contact.nickname = nickname }
    }
}
