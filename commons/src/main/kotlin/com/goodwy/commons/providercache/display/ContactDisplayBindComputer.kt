package com.goodwy.commons.providercache.display

import android.content.Context
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.formatPhoneNumber
import com.goodwy.commons.extensions.getAvatarColorForName
import com.goodwy.commons.extensions.getAvatarDrawableIndexForName
import com.goodwy.commons.helpers.AvatarBindComputer
import com.goodwy.commons.helpers.AvatarSource
import com.goodwy.commons.helpers.AvatarSourceFactory
import com.goodwy.commons.helpers.ContactAvatarInvalidUriTracker
import com.goodwy.commons.helpers.ContactListPhotoUriPolicy
import com.goodwy.commons.helpers.MonogramGenerator
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.models.contacts.ContactDisplayBind
import com.goodwy.commons.providercache.entities.ContactDisplayCacheEntity

/**
 * Computes UI-ready list fields once at [contact_display_cache] rebuild time.
 * [ContactDisplayLoadHelper] only copies these fields when loading the list.
 */
object ContactDisplayBindComputer {

    data class UiFields(
        val firstPhoneFormatted: String,
        val showPhoneNumber: Boolean,
        val avatarInitials: String,
        val avatarDrawableIndex: Int,
        val avatarColor: Int,
        val avatarGradientColors: List<Int>,
        val photoThumbUri: String,
        val usePhotoAvatar: Boolean,
        val hasValidPhotoUri: Boolean,
    )

    fun computeUiFields(
        context: Context,
        displayName: String,
        rawPhone: String,
        thumbnailUri: String,
        photoUri: String,
        rawId: Int = 0,
    ): UiFields {
        val formatNumbers = context.baseConfig.formatPhoneNumbers
        val formattedPhone = if (formatNumbers && rawPhone.isNotEmpty()) {
            rawPhone.formatPhoneNumber()
        } else {
            rawPhone
        }
        val photoThumbUri = ContactListPhotoUriPolicy.resolveListPhotoThumbUri(thumbnailUri, photoUri)
        val uriValid = photoThumbUri.isNotEmpty() &&
            !ContactAvatarInvalidUriTracker.isInvalid(rawId, photoThumbUri)
        val monogram = AvatarBindComputer.computeMonogramFields(context, displayName)
        return UiFields(
            firstPhoneFormatted = formattedPhone,
            showPhoneNumber = formattedPhone.isNotEmpty(),
            avatarInitials = monogram.initials,
            avatarDrawableIndex = monogram.drawableIndex ?: -1,
            avatarColor = monogram.avatarColor,
            avatarGradientColors = monogram.gradientColors,
            photoThumbUri = photoThumbUri,
            usePhotoAvatar = uriValid,
            hasValidPhotoUri = uriValid,
        )
    }

    /** Attaches [Contact.displayBind] for cold-start / summary rows (no Room read). */
    fun attachDisplayBind(context: Context, contact: Contact): Contact {
        val displayName = contact.getNameToDisplay()
        val rawPhone = contact.phoneNumbers.firstOrNull()?.normalizedNumber?.trim().orEmpty()
        val ui = computeUiFields(
            context = context,
            displayName = displayName,
            rawPhone = rawPhone,
            thumbnailUri = contact.thumbnailUri,
            photoUri = contact.photoUri,
            rawId = contact.id,
        )
        contact.displayBind = toDisplayBind(
            ui = ui,
            displayName = displayName,
            sectionLetter = contact.resolvedFastScrollBucket(),
        )
        return contact
    }

    fun toDisplayBind(
        ui: UiFields,
        displayName: String,
        sectionLetter: String,
    ): ContactDisplayBind = ContactDisplayBind(
        displayName = displayName,
        formattedPhone = ui.firstPhoneFormatted,
        showPhoneNumber = ui.showPhoneNumber,
        sectionLetter = sectionLetter,
        avatarInitials = ui.avatarInitials,
        avatarDrawableIndex = ui.avatarDrawableIndex,
        avatarColor = ui.avatarColor,
        avatarGradientColors = ui.avatarGradientColors,
        photoThumbUri = ui.photoThumbUri,
        usePhotoAvatar = ui.usePhotoAvatar,
        hasValidPhotoUri = ui.hasValidPhotoUri,
    )

    fun monogramSource(bind: ContactDisplayBind): AvatarSource.Monogram {
        val displayName = bind.displayName
        val initials = bind.avatarInitials.ifEmpty { MonogramGenerator.generateInitials(displayName) }
        val drawableIndex = bind.avatarDrawableIndex.takeIf { it >= 0 }
        val gradientColors = bind.avatarGradientColors.ifEmpty {
            AvatarBindComputer.resolveGradientColors(displayName, drawableIndex, bind.avatarColor)
        }
        return AvatarSource.Monogram(
            initials = initials,
            gradientColors = gradientColors,
            drawableIndex = drawableIndex,
        )
    }

    fun toAvatarSource(bind: ContactDisplayBind): AvatarSource =
        AvatarSourceFactory.create(
            AvatarBindComputer.fromContactDisplayBind(
                bind = AvatarBindComputer.enrichContactDisplayBind(bind),
                contactId = 0,
                rawContactId = 0,
            ),
        )

    /** Copy-only path for [contact_display_cache] list load — no URI parsing or recomputation. */
    fun copyDisplayBindFromListRow(row: ContactDisplayListRow): ContactDisplayBind = ContactDisplayBind(
        displayName = row.displayName,
        formattedPhone = row.firstPhoneFormatted,
        showPhoneNumber = row.showPhoneNumber != 0,
        sectionLetter = row.sectionLetter,
        avatarInitials = row.avatarInitials,
        avatarDrawableIndex = row.avatarDrawableIndex,
        avatarColor = row.avatarColor,
        photoThumbUri = row.photoThumbUri,
        usePhotoAvatar = row.usePhotoAvatar != 0,
        hasValidPhotoUri = row.hasValidPhotoUri != 0,
    )

    /**
     * Copy-only path for [contact_display_cache] list load — no filtering, formatting, or
     * monogram recomputation. Gradients are derived from already-persisted avatar fields.
     */
    fun copyContactFromListRow(row: ContactDisplayListRow): Contact {
        val contact = Contact(
            id = row.rawId,
            contactId = row.contactId,
            firstName = row.displayName,
            source = row.source,
            starred = row.starred,
        )
        val bind = copyDisplayBindFromListRow(row)
        val drawableIndex = bind.avatarDrawableIndex.takeIf { it >= 0 }
        val photoUri = ContactListPhotoUriPolicy.sanitizeCachedListUri(bind.photoThumbUri)
        // List rows only carry displayBind; mirror photo onto Contact so live-URI overlays work.
        if (photoUri.isNotEmpty()) {
            contact.thumbnailUri = photoUri
            contact.photoUri = photoUri
        }
        contact.displayBind = bind.copy(
            avatarGradientColors = when {
                drawableIndex != null -> emptyList()
                bind.avatarColor != 0 -> listOf(bind.avatarColor)
                else -> emptyList()
            },
        )
        return contact
    }

    fun toDisplayBind(row: ContactDisplayListRow): ContactDisplayBind =
        AvatarBindComputer.enrichContactDisplayBind(copyDisplayBindFromListRow(row))

    fun toAvatarSource(row: ContactDisplayListRow): AvatarSource = toAvatarSource(copyDisplayBindFromListRow(row))

    fun toContact(row: ContactDisplayListRow): Contact = copyContactFromListRow(row)

    fun uiFieldsFromEntity(entity: ContactDisplayCacheEntity): UiFields {
        val photoThumbUri = cachedListPhotoThumbUri(entity.photoThumbUri, entity.thumbnailUri, entity.photoUri)
        return UiFields(
            firstPhoneFormatted = entity.firstPhoneFormatted,
            showPhoneNumber = entity.showPhoneNumber != 0,
            avatarInitials = entity.avatarInitials,
            avatarDrawableIndex = entity.avatarDrawableIndex,
            avatarColor = entity.avatarColor,
            avatarGradientColors = AvatarBindComputer.resolveGradientColors(
                entity.displayName,
                entity.avatarDrawableIndex.takeIf { it >= 0 },
                entity.avatarColor,
            ),
            photoThumbUri = photoThumbUri,
            usePhotoAvatar = entity.usePhotoAvatar != 0 && photoThumbUri.isNotEmpty(),
            hasValidPhotoUri = entity.hasValidPhotoUri != 0 && photoThumbUri.isNotEmpty(),
        )
    }

    fun toDisplayBind(entity: ContactDisplayCacheEntity): ContactDisplayBind {
        val photoThumbUri = cachedListPhotoThumbUri(entity.photoThumbUri, entity.thumbnailUri, entity.photoUri)
        return AvatarBindComputer.enrichContactDisplayBind(
            ContactDisplayBind(
                displayName = entity.displayName,
                formattedPhone = entity.firstPhoneFormatted,
                showPhoneNumber = entity.showPhoneNumber != 0,
                sectionLetter = entity.sectionLetter,
                avatarInitials = entity.avatarInitials,
                avatarDrawableIndex = entity.avatarDrawableIndex,
                avatarColor = entity.avatarColor,
                photoThumbUri = photoThumbUri,
                usePhotoAvatar = entity.usePhotoAvatar != 0 && photoThumbUri.isNotEmpty(),
                hasValidPhotoUri = entity.hasValidPhotoUri != 0 && photoThumbUri.isNotEmpty() &&
                    !ContactAvatarInvalidUriTracker.isInvalid(entity.rawId, photoThumbUri),
            ),
        )
    }

    fun toAvatarSource(entity: ContactDisplayCacheEntity): AvatarSource = toAvatarSource(toDisplayBind(entity))

    private fun cachedListPhotoThumbUri(photoThumbUri: String, thumbnailUri: String, photoUri: String): String =
        ContactListPhotoUriPolicy.resolveListPhotoThumbUri(
            photoThumbUri.ifEmpty { thumbnailUri },
            photoUri,
        )
}
