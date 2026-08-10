package com.goodwy.commons.providercache.display

import android.content.Context
import com.goodwy.commons.helpers.ContactsHelper
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.providercache.entities.ContactDisplayCacheEntity
import com.goodwy.commons.providercache.filter.T9Mapper
import java.util.Locale

object ContactDisplayCacheMapper {

    fun fromContact(
        context: Context,
        contact: com.goodwy.commons.models.contacts.Contact,
        displayOrder: Int = 0,
    ): ContactDisplayCacheEntity {
        val displayName = contact.getNameToDisplay()
        val firstPhone = contact.phoneNumbers.firstOrNull()?.normalizedNumber?.trim().orEmpty()
        val phoneDigits = T9Mapper.extractDigits(firstPhone)
        val thumbUri = contact.thumbnailUri
        val photoUri = contact.photoUri.ifEmpty { contact.thumbnailUri }
        val ui = ContactDisplayBindComputer.computeUiFields(
            context = context,
            displayName = displayName,
            rawPhone = firstPhone,
            thumbnailUri = thumbUri,
            photoUri = photoUri,
            rawId = contact.id,
        )
        val accountType = ContactsHelper(context).getContactSourceType(contact.source)
        return ContactDisplayCacheEntity(
            rawId = contact.id,
            contactId = contact.contactId,
            displayName = displayName,
            thumbnailUri = thumbUri,
            photoUri = photoUri,
            source = contact.source,
            accountType = accountType,
            firstPhone = firstPhone,
            firstEmail = contact.emails.firstOrNull()?.value?.trim().orEmpty(),
            starred = contact.starred,
            sectionLetter = contact.getFastScrollBucket(),
            sortKey = displayName,
            searchName = displayName.lowercase(Locale.getDefault()),
            t9Key = T9Mapper.toT9Digits(displayName),
            phoneDigits = phoneDigits,
            firstPhoneFormatted = ui.firstPhoneFormatted,
            showPhoneNumber = if (ui.showPhoneNumber) 1 else 0,
            avatarInitials = ui.avatarInitials,
            avatarDrawableIndex = ui.avatarDrawableIndex,
            avatarColor = ui.avatarColor,
            photoThumbUri = ui.photoThumbUri,
            usePhotoAvatar = if (ui.usePhotoAvatar) 1 else 0,
            hasValidPhotoUri = if (ui.hasValidPhotoUri) 1 else 0,
            displayOrder = displayOrder,
        )
    }

    /** Legacy full-entity path — only used when UI columns are already populated. */
    fun toContact(entity: ContactDisplayCacheEntity): Contact {
        val contact = ContactDisplayBindComputer.toContact(entity.toListRow())
        contact.displayBind = ContactDisplayBindComputer.toDisplayBind(entity)
        return contact
    }

    private fun ContactDisplayCacheEntity.toListRow(): ContactDisplayListRow = ContactDisplayListRow(
        rawId = rawId,
        contactId = contactId,
        displayName = displayName,
        source = source,
        starred = starred,
        sectionLetter = sectionLetter,
        firstPhoneFormatted = firstPhoneFormatted,
        showPhoneNumber = showPhoneNumber,
        avatarInitials = avatarInitials,
        avatarDrawableIndex = avatarDrawableIndex,
        avatarColor = avatarColor,
        photoThumbUri = photoThumbUri,
        usePhotoAvatar = usePhotoAvatar,
        hasValidPhotoUri = hasValidPhotoUri,
        displayOrder = displayOrder,
    )
}
