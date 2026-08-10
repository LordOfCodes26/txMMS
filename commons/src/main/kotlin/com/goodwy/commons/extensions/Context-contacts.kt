package com.goodwy.commons.extensions

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import com.goodwy.commons.R
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.databases.ContactsDatabase
import com.goodwy.commons.dialogs.CallConfirmationDialog
import com.goodwy.commons.dialogs.RadioGroupDialog
import eightbitlab.com.blurview.BlurTarget
import com.goodwy.commons.helpers.*
import com.goodwy.commons.interfaces.ContactsDao
import com.goodwy.commons.interfaces.GroupsDao
import com.goodwy.commons.models.RadioItem
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.models.contacts.ContactSource
import com.goodwy.commons.models.contacts.Organization
import java.io.File
import java.util.ArrayList
import androidx.core.net.toUri
import java.util.Locale

val Context.contactsDB: ContactsDao get() = ContactsDatabase.getInstance(applicationContext).ContactsDao()

val Context.groupsDB: GroupsDao get() = ContactsDatabase.getInstance(applicationContext).GroupsDao()

fun Context.getEmptyContact(): Contact {
    val originalContactSource = if (hasContactPermissions()) baseConfig.lastUsedContactSource else SMT_PRIVATE
    val organization = Organization("", "")
    return Contact(
        0, "", "", "", "", "", "", "", ArrayList(), ArrayList(), ArrayList(), ArrayList(), originalContactSource, 0, 0, "",
        null, "", ArrayList(), organization, ArrayList(), DEFAULT_MIMETYPE, null
    )
}

fun Context.sendAddressIntent(address: String) {
    val location = Uri.encode(address)
    val uri = "geo:0,0?q=$location".toUri()

    Intent(Intent.ACTION_VIEW, uri).apply {
        launchActivityIntent(this)
    }
}

fun Context.getLookupUriRawId(dataUri: Uri): Int {
    val lookupKey = getLookupKeyFromUri(dataUri)
    if (lookupKey != null) {
        val uri = lookupContactUri(lookupKey, this)
        if (uri != null) {
            return getContactUriRawId(uri)
        }
    }
    return -1
}

fun Context.getContactUriRawId(uri: Uri): Int {
    val projection = arrayOf(ContactsContract.Contacts.NAME_RAW_CONTACT_ID)
    var cursor: Cursor? = null
    try {
        cursor = contentResolver.query(uri, projection, null, null, null)
        if (cursor!!.moveToFirst()) {
            return cursor.getIntValue(ContactsContract.Contacts.NAME_RAW_CONTACT_ID)
        }
    } catch (_: Exception) {
    } finally {
        cursor?.close()
    }
    return -1
}

// from https://android.googlesource.com/platform/packages/apps/Dialer/+/68038172793ee0e2ab3e2e56ddfbeb82879d1f58/java/com/android/contacts/common/util/UriUtils.java
fun getLookupKeyFromUri(lookupUri: Uri): String? {
    return if (!isEncodedContactUri(lookupUri)) {
        val segments = lookupUri.pathSegments
        if (segments.size < 3) null else Uri.encode(segments[2])
    } else {
        null
    }
}

fun isEncodedContactUri(uri: Uri?): Boolean {
    if (uri == null) {
        return false
    }
    val lastPathSegment = uri.lastPathSegment ?: return false
    return lastPathSegment == "encoded"
}

fun lookupContactUri(lookup: String, context: Context): Uri? {
    val lookupUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookup)
    return try {
        ContactsContract.Contacts.lookupContact(context.contentResolver, lookupUri)
    } catch (_: Exception) {
        null
    }
}

fun Context.getCachePhoto(): File {
    val imagesFolder = File(cacheDir, "my_cache")
    if (!imagesFolder.exists()) {
        imagesFolder.mkdirs()
    }

    val file = File(imagesFolder, "Photo_${System.currentTimeMillis()}.jpg")
    file.createNewFile()
    return file
}

fun Context.getPhotoThumbnailSize(): Int {
    val uri = ContactsContract.DisplayPhoto.CONTENT_MAX_DIMENSIONS_URI
    val projection = arrayOf(ContactsContract.DisplayPhoto.THUMBNAIL_MAX_DIM)
    var cursor: Cursor? = null
    try {
        cursor = contentResolver.query(uri, projection, null, null, null)
        if (cursor?.moveToFirst() == true) {
            return cursor.getIntValue(ContactsContract.DisplayPhoto.THUMBNAIL_MAX_DIM)
        }
    } catch (_: Exception) {
    } finally {
        cursor?.close()
    }
    return 0
}

fun Context.hasContactPermissions() = hasPermission(PERMISSION_READ_CONTACTS) && hasPermission(PERMISSION_WRITE_CONTACTS)

fun Context.getPublicContactSource(source: String, callback: (String) -> Unit) {
    when (source) {
        SMT_PRIVATE -> callback(getString(R.string.phone_storage_hidden))
        else -> {
            ContactsHelper(this).getContactSources {
                var newSource = source
                for (contactSource in it) {
                    if (contactSource.name != source) {
                        continue
                    }
                    newSource = contactSource.publicName.ifBlank { source }
                    break
                }
                Handler(Looper.getMainLooper()).post {
                    callback(newSource)
                }
            }
        }
    }
}

fun Context.getPublicContactSourceSync(source: String, contactSources: ArrayList<ContactSource>): String {
    return when (source) {
        SMT_PRIVATE -> getString(R.string.phone_storage_hidden)
        else -> {
            for (contactSource in contactSources) {
                if (contactSource.name != source) {
                    continue
                }
                return contactSource.publicName.ifBlank { source }
            }
            return source
        }
    }
}

fun Context.sendSMSToContacts(contacts: ArrayList<Contact>) {
    val numbers = StringBuilder()
    contacts.forEach {
        val number = it.phoneNumbers.firstOrNull { it.type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE }
            ?: it.phoneNumbers.firstOrNull()
        if (number != null) {
            numbers.append("${Uri.encode(number.value)};")
        }
    }

    val uriString = "smsto:${numbers.toString().trimEnd(';')}"
    Intent(Intent.ACTION_SENDTO, uriString.toUri()).apply {
        launchActivityIntent(this)
    }
}

fun Context.sendEmailToContacts(contacts: ArrayList<Contact>) {
    val emails = ArrayList<String>()
    contacts.forEach {
        it.emails.forEach {
            if (it.value.isNotEmpty()) {
                emails.add(it.value)
            }
        }
    }

    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "message/rfc822"
        putExtra(Intent.EXTRA_EMAIL, emails.toTypedArray())
        launchActivityIntent(this)
    }
}

fun Context.getTempFile(filename: String = DEFAULT_FILE_NAME): File? {
    val folder = File(cacheDir, "contacts")
    if (!folder.exists()) {
        if (!folder.mkdir()) {
            toast(R.string.unknown_error_occurred)
            return null
        }
    }

    return File(folder, filename)
}

fun Context.addContactsToGroup(contacts: ArrayList<Contact>, groupId: Long) {
    val publicContacts = contacts.filter { !it.isPrivate() }.toMutableList() as ArrayList<Contact>
    val privateContacts = contacts.filter { it.isPrivate() }.toMutableList() as ArrayList<Contact>
    if (publicContacts.isNotEmpty()) {
        ContactsHelper(this).addContactsToGroup(publicContacts, groupId)
    }

    if (privateContacts.isNotEmpty()) {
        LocalContactsHelper(this).addContactsToGroup(privateContacts, groupId)
    }
}

fun Context.removeContactsFromGroup(contacts: ArrayList<Contact>, groupId: Long) {
    val publicContacts = contacts.filter { !it.isPrivate() }.toMutableList() as ArrayList<Contact>
    val privateContacts = contacts.filter { it.isPrivate() }.toMutableList() as ArrayList<Contact>
    if (publicContacts.isNotEmpty() && hasContactPermissions()) {
        ContactsHelper(this).removeContactsFromGroup(publicContacts, groupId)
    }

    if (privateContacts.isNotEmpty()) {
        LocalContactsHelper(this).removeContactsFromGroup(privateContacts, groupId)
    }
}

fun Context.getContactPublicUri(contact: Contact): Uri {
    val lookupKey = if (contact.isPrivate()) {
        "local_${contact.id}"
    } else {
        SimpleContactsHelper(this).getContactLookupKey(contact.id.toString())
    }
    return Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)
}

fun Context.getVisibleContactSources(): ArrayList<String> {
    val sources = getAllContactSources()
    val ignoredContactSources = baseConfig.ignoredContactSources

    // We allocate memory for the result in advance.
    val result = ArrayList<String>(sources.size)

    // Direct cycle instead of filter/map chain
    for (source in sources) {
        val fullIdentifier = source.getFullIdentifier()
        if (!ignoredContactSources.contains(fullIdentifier)) {
            result.add(source.name)
        }
    }
    return result
}

fun Context.getAllContactSources(): ArrayList<ContactSource> {
    val allSources = ContactsHelper(this).getDeviceContactSources()
    // Return phone storage and SIM card contacts.
    // Use isBlank() instead of isEmpty() because the protection mechanism writes a single
    // space (' ') for account name/type instead of an empty string.
    val phoneAndSimSources = allSources.filter { source ->
        val nameLower = source.name.lowercase(Locale.getDefault())
        val typeLower = source.type.lowercase(Locale.getDefault())
        
        val isPhoneStorage = (source.name.isBlank() && source.type.isBlank()) ||
            (nameLower.trim() == "phone" && source.type.isBlank())
        
        val isSimCard = typeLower.contains("sim") || typeLower.contains("icc")

        isPhoneStorage || isSimCard
    }
    // A SIM's contacts must stop being visible once the SIM is out. They cannot be reconciled
    // away: the provider keeps its SIM raw contacts after an eject (nothing deletes them), so as
    // far as the cache is concerned they still exist. Presence is a telephony question, and this
    // is the one place that decides which sources exist -- it feeds getVisibleContactSources (the
    // per-page ContactSourceAccountFilter) and the source-picker UI alike, so gating here hides
    // the contacts and stops offering an absent SIM as a destination in one move.
    //
    // Non-destructive by design: nothing is deleted, so reinserting the SIM brings the rows
    // straight back with no re-import. isSimAccountPresentOrNull returns null when telephony
    // cannot answer, and null keeps the source -- never hide contacts on a permission failure.
    val presenceFiltered = phoneAndSimSources.filter { source ->
        isSimAccountPresentOrNull(source.name, source.type) != false
    }
    if (presenceFiltered.size != phoneAndSimSources.size) {
        val dropped = phoneAndSimSources.filter { it !in presenceFiltered }.map { it.getFullIdentifier() }
        android.util.Log.d("SimSourceRefresh", "getAllContactSources dropped absent SIM sources=$dropped")
    }
    return presenceFiltered.toMutableList() as ArrayList<ContactSource>
}

fun BaseSimpleActivity.initiateCall(contact: Contact, onStartCallIntent: (phoneNumber: String) -> Unit) {
    val numbers = contact.phoneNumbers
    if (numbers.size == 1) {
        onStartCallIntent(numbers.first().value)
    } else if (numbers.size > 1) {
        val primaryNumber = contact.phoneNumbers.find { it.isPrimary }
        if (primaryNumber != null) {
            onStartCallIntent(primaryNumber.value)
        } else {
            val items = ArrayList<RadioItem>()
            numbers.forEachIndexed { index, phoneNumber ->
                items.add(RadioItem(index, "${phoneNumber.value} (${getPhoneNumberTypeText(phoneNumber.type, phoneNumber.label)})", phoneNumber.value))
            }

            if (this is BaseSimpleActivity) {
                val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
                    ?: throw IllegalStateException("mainBlurTarget not found")
                RadioGroupDialog(this, items, blurTarget = blurTarget) {
                    onStartCallIntent(it as String)
                }
            }
        }
    }
}

fun BaseSimpleActivity.tryInitiateCall(contact: Contact, onStartCallIntent: (phoneNumber: String) -> Unit) {
    if (baseConfig.showCallConfirmation) {
        val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        CallConfirmationDialog(this, contact.getNameToDisplay(), blurTarget = blurTarget) {
            initiateCall(contact, onStartCallIntent)
        }
    } else {
        initiateCall(contact, onStartCallIntent)
    }
}

fun Context.isContactBlocked(contact: Contact, callback: (Boolean) -> Unit) {
    val phoneNumbers = contact.phoneNumbers.map { PhoneNumberUtils.stripSeparators(it.value) }
    getBlockedNumbersWithContact { blockedNumbersWithContact ->
        val blockedNumbers = blockedNumbersWithContact.map { it.number }
        val allNumbersBlocked = phoneNumbers.all { it in blockedNumbers }
        callback(allNumbersBlocked)
    }
}

fun Context.blockContact(contact: Contact): Boolean {
    if (!isDefaultDialer()) return false
    return contact.phoneNumbers.all {
        addBlockedNumber(PhoneNumberUtils.stripSeparators(it.value))
    }
}

fun Context.unblockContact(contact: Contact): Boolean {
    if (!isDefaultDialer()) return false
    return contact.phoneNumbers.all {
        deleteBlockedNumber(PhoneNumberUtils.stripSeparators(it.value))
    }
}
