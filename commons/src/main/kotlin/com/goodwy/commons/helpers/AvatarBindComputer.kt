package com.goodwy.commons.helpers

import android.content.Context
import com.goodwy.commons.extensions.getAvatarColorForName
import com.goodwy.commons.extensions.getAvatarDrawableIndexForName
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.models.contacts.ContactDisplayBind
import com.goodwy.commons.providercache.display.ContactDisplayBindComputer
import com.goodwy.commons.providercache.entities.ContactDisplayCacheEntity

/**
 * Builds [AvatarBindData] from precomputed display-bind fields.
 * Monogram/color rules are applied here (cache build / load), never in RecyclerView bind.
 */
object AvatarBindComputer {

    /** Matches [com.goodwy.commons.R.drawable.placeholder_contact] (top → bottom). */
    private val newContactPlaceholderGradientColors = listOf(
        0xFF878B94.toInt(),
        0xFFA4A8B5.toInt(),
    )

    data class MonogramFields(
        val initials: String,
        val drawableIndex: Int?,
        val avatarColor: Int,
        val gradientColors: List<Int>,
    )

    fun computeMonogramFields(context: Context, displayName: String): MonogramFields {
        val initials = MonogramGenerator.generateInitials(displayName)
        val drawableIndex = context.getAvatarDrawableIndexForName(displayName).takeIf { it >= 0 }
        val avatarColor = context.getAvatarColorForName(displayName)
        val gradientColors = resolveGradientColors(displayName, drawableIndex, avatarColor)
        return MonogramFields(initials, drawableIndex, avatarColor, gradientColors)
    }

    fun resolveGradientColors(
        displayName: String,
        drawableIndex: Int?,
        avatarColor: Int,
    ): List<Int> = when {
        drawableIndex != null -> emptyList()
        avatarColor != 0 -> listOf(avatarColor)
        else -> MonogramGenerator.generateGradientColors(displayName)
    }

    fun avatarVersionFor(rawContactId: Int): Long =
        ContactAvatarPhotoVersionTracker.getSignature(rawContactId)

    fun fromContactDisplayBind(
        bind: ContactDisplayBind,
        contactId: Int,
        rawContactId: Int,
        avatarVersion: Long = avatarVersionFor(rawContactId),
        safeFullPhotoUri: String = "",
    ): AvatarBindData {
        val drawableIndex = bind.avatarDrawableIndex.takeIf { it >= 0 }
        val gradientColors = if (drawableIndex != null) {
            emptyList()
        } else {
            bind.avatarGradientColors.ifEmpty {
                resolveGradientColors(bind.displayName, drawableIndex, bind.avatarColor)
            }
        }
        val safeUri = ContactListPhotoUriPolicy.sanitizeCachedListUri(bind.photoThumbUri)
        val safeFull = ContactListPhotoUriPolicy.sanitizeCachedListUri(safeFullPhotoUri)
        val uriValid = safeUri.isNotEmpty() &&
            bind.usePhotoAvatar &&
            bind.hasValidPhotoUri &&
            !ContactAvatarInvalidUriTracker.isInvalid(rawContactId, safeUri)
        return AvatarBindData(
            contactId = contactId,
            rawContactId = rawContactId,
            displayName = bind.displayName,
            safePhotoThumbUri = safeUri,
            safeFullPhotoUri = safeFull,
            usePhotoAvatar = uriValid,
            initials = bind.avatarInitials,
            drawableIndex = drawableIndex,
            avatarColor = bind.avatarColor,
            gradientColors = gradientColors,
            showProfileIcon = false,
            avatarVersion = avatarVersion,
        )
    }

    fun fromContact(
        context: Context,
        contact: Contact,
        photoUriOverride: String? = null,
        displayNameOverride: String? = null,
        avatarVersion: Long = avatarVersionFor(contact.id),
    ): AvatarBindData {
        val displayName = displayNameOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: contact.getNameToDisplay()
        val photoCandidate = (photoUriOverride ?: contact.photoUri).trim()
        val safeUri = ContactListPhotoUriPolicy.resolveListPhotoThumbUri(
            contact.thumbnailUri,
            photoCandidate,
        )
        val safeFull = ContactListPhotoUriPolicy.sanitizeCachedListUri(photoCandidate)
        val baseBind = contact.displayBind?.copy(
            displayName = displayName,
            photoThumbUri = safeUri,
            usePhotoAvatar = safeUri.isNotEmpty(),
            hasValidPhotoUri = safeUri.isNotEmpty(),
        ) ?: ContactDisplayBind(
            displayName = displayName,
            formattedPhone = "",
            showPhoneNumber = false,
            photoThumbUri = safeUri,
            usePhotoAvatar = safeUri.isNotEmpty(),
            hasValidPhotoUri = safeUri.isNotEmpty(),
        )
        return fromContactDisplayBind(
            bind = enrichContactDisplayBind(context, baseBind),
            contactId = contact.contactId,
            rawContactId = contact.id,
            avatarVersion = avatarVersion,
            safeFullPhotoUri = safeFull,
        )
    }

    /**
     * Edit-contact header: new contacts without a photo show the default person icon
     * (gray placeholder gradient), not a letter monogram.
     */
    fun forEditContact(
        context: Context,
        contact: Contact,
        displayNameOverride: String? = null,
        avatarVersion: Long = avatarVersionFor(contact.id),
    ): AvatarBindData {
        val data = fromContact(
            context = context,
            contact = contact,
            displayNameOverride = displayNameOverride,
            avatarVersion = avatarVersion,
        )
        if (contact.id != 0 || data.usePhotoAvatar) return data
        return data.copy(
            showProfileIcon = true,
            drawableIndex = null,
            gradientColors = newContactPlaceholderGradientColors,
        )
    }

    /**
     * Normalizes monogram fields using the same rules as the contacts list cache.
     * Requires [Context] so drawable-index avatars match [computeMonogramFields].
     */
    fun enrichContactDisplayBind(context: Context, bind: ContactDisplayBind): ContactDisplayBind {
        val monogram = computeMonogramFields(context, bind.displayName)
        val drawableIndex = bind.avatarDrawableIndex.takeIf { it >= 0 } ?: monogram.drawableIndex
        val avatarColor = if (bind.avatarColor != 0) bind.avatarColor else monogram.avatarColor
        val gradientColors = resolveGradientColors(bind.displayName, drawableIndex, avatarColor)
        return bind.copy(
            avatarInitials = bind.avatarInitials.ifEmpty { monogram.initials },
            avatarDrawableIndex = drawableIndex ?: -1,
            avatarColor = avatarColor,
            avatarGradientColors = gradientColors,
        )
    }

    /** Cache-only enrich when monogram fields were already computed with [Context] at build time. */
    fun enrichContactDisplayBind(bind: ContactDisplayBind): ContactDisplayBind {
        val drawableIndex = bind.avatarDrawableIndex.takeIf { it >= 0 }
        val expectedGradients = resolveGradientColors(bind.displayName, drawableIndex, bind.avatarColor)
        if (bind.avatarGradientColors == expectedGradients &&
            bind.avatarInitials.isNotEmpty() &&
            (drawableIndex != null || bind.avatarColor != 0)
        ) {
            return bind
        }
        return bind.copy(avatarGradientColors = expectedGradients)
    }

    fun fromContactEntity(
        entity: ContactDisplayCacheEntity,
        avatarVersion: Long = avatarVersionFor(entity.rawId),
    ): AvatarBindData = fromContactDisplayBind(
        enrichContactDisplayBind(ContactDisplayBindComputer.toDisplayBind(entity)),
        contactId = entity.contactId,
        rawContactId = entity.rawId,
        avatarVersion = avatarVersion,
    )
}

