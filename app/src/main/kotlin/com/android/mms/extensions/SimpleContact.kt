package com.android.mms.extensions

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.provider.ContactsContract
import android.text.TextUtils
import androidx.core.app.Person
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.models.SimpleContact
import androidx.core.net.toUri
import com.goodwy.commons.extensions.applyColorFilter
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.convertToBitmap
import com.goodwy.commons.extensions.getLetterBackgroundColors
import com.goodwy.commons.extensions.toast
import com.android.mms.R
import com.android.mms.helpers.Config
import kotlin.math.abs

/**
 * Returns a localized thread title. For multiple participants returns "first name/number" + localized "and N others".
 * Pass [context] for proper localization; when null and size > 1, a language-neutral numeric suffix is used.
 */
fun ArrayList<SimpleContact>.getThreadTitle(context: Context? = null): String {
    if (isEmpty()) return ""
    if (size == 1) return first().name.orEmpty()
    val firstContact = first()
    val firstDisplayName = firstContact.name.takeIf { it.isNotEmpty() }
        ?: firstContact.phoneNumbers.firstOrNull()?.normalizedNumber
        ?: ""
    val othersCount = size - 1
    if (context != null) {
        val othersSuffix = context.resources.getQuantityString(R.plurals.and_other_contacts, othersCount, othersCount)
        return context.resources.getString(R.string.thread_title_multiple_format, firstDisplayName, othersSuffix).trim()
    }
    // Language-neutral fallback when context not available (no localized "other(s)")
    return "$firstDisplayName (+$othersCount)".trim()
}

fun ArrayList<SimpleContact>.getAddresses(): List<String> {
    return flatMap { it.phoneNumbers }.map { it.normalizedNumber }
}

fun ArrayList<SimpleContact>.getThreadSubtitle(context: Context? = null): String {
    val config = context?.let { Config.newInstance(it) }
    val showPhoneNumber = config?.showPhoneNumber ?: true
    
    return TextUtils.join(", ", map { contact ->
        val phoneNumber = contact.phoneNumbers.first().normalizedNumber
        // If showPhoneNumber is off, only show phone number if contact doesn't have a name (name == phone number)
        // If showPhoneNumber is on, always show phone number
        // If phone number is not defined in contact address, always show phone number
        if (!showPhoneNumber && contact.name != phoneNumber && contact.name.isNotEmpty()) {
            "" // Don't show phone number if contact has a name and setting is off
        } else {
            phoneNumber
        }
    }.filter { it.isNotEmpty() }.toTypedArray())
}

fun SimpleContact.toPerson(context: Context? = null): Person {
    val uri =
        Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, contactId.toString())
    val iconCompat = if (context != null) {
        loadIcon(context)
    } else {
        IconCompat.createWithContentUri(photoUri)
    }

    return Person.Builder()
        .setName(name)
        .setUri(uri.toString())
        .setIcon(iconCompat)
        .setKey(uri.toString())
        .build()
}

fun SimpleContact.loadIcon(context: Context): IconCompat {
    try {
        val stream = context.contentResolver.openInputStream(photoUri.toUri())
        val bitmap = BitmapFactory.decodeStream(stream)
        stream?.close()
        val iconCompat = IconCompat.createWithAdaptiveBitmap(bitmap)
        return iconCompat
    } catch (_: Exception) {
        return if (isABusinessContact()) {
            IconCompat.createWithBitmap(
                SimpleContactsHelper(context).getColoredCompanyIcon(name).toBitmap()
            )
        } else {
            IconCompat.createWithBitmap(
                SimpleContactsHelper(context).getContactLetterIcon(name)
            )
        }
    }
}
