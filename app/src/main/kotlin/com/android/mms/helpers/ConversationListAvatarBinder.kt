package com.android.mms.helpers

import com.android.mms.models.Conversation
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.getAvatarDrawableIndexForName
import com.goodwy.commons.helpers.AvatarSource
import com.goodwy.commons.helpers.GROUP
import com.goodwy.commons.helpers.MonogramGenerator
import com.goodwy.commons.views.ContactAvatarView

/**
 * Binds [avatarView] with the same rules as the main conversation list
 * ([com.android.mms.adapters.BaseConversationsAdapter] row avatars).
 */
fun ContactAvatarView.bindConversationListAvatar(
    activity: BaseSimpleActivity,
    threadId: Long,
    title: String,
    phoneNumber: String,
    photoUri: String,
    isGroupConversation: Boolean,
) {
    val isUnsavedMessage = threadId <= 0 || phoneNumber == title
    if (isUnsavedMessage) {
        bind(
            AvatarSource.Monogram(
                initials = "",
                gradientColors = MonogramGenerator.generateGradientColors(phoneNumber),
                drawableIndex = activity.getAvatarDrawableIndexForName(phoneNumber).takeIf { it >= 0 },
                showProfileIcon = true,
            ),
        )
        return
    }

    val avatarSeed = title.ifEmpty { phoneNumber }
    val drawableIndex = activity.getAvatarDrawableIndexForName(avatarSeed).takeIf { it >= 0 }
    val shouldUsePhoto = !activity.isDestroyed &&
        !activity.isFinishing &&
        photoUri.isNotBlank() &&
        !isGroupConversation &&
        phoneNumber != title

    bind(
        if (shouldUsePhoto) {
            AvatarSource.Photo(photoUri)
        } else if (isGroupConversation) {
            AvatarSource.Monogram(
                initials = GROUP,
                gradientColors = MonogramGenerator.generateGradientColors(phoneNumber),
                drawableIndex = activity.getAvatarDrawableIndexForName(phoneNumber).takeIf { it >= 0 },
            )
        } else {
            AvatarSource.Monogram(
                initials = MonogramGenerator.generateInitials(avatarSeed),
                gradientColors = MonogramGenerator.generateGradientColors(avatarSeed),
                drawableIndex = drawableIndex,
            )
        },
    )
}

fun ContactAvatarView.bindConversationListAvatar(
    activity: BaseSimpleActivity,
    conversation: Conversation,
) {
    bindConversationListAvatar(
        activity = activity,
        threadId = conversation.threadId,
        title = conversation.title,
        phoneNumber = conversation.phoneNumber,
        photoUri = conversation.photoUri,
        isGroupConversation = conversation.isGroupConversation,
    )
}
