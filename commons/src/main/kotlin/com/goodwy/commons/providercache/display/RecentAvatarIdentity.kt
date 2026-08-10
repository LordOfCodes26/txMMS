package com.goodwy.commons.providercache.display

/**
 * Stable avatar identity for Recents groups — independent of mutable display name.
 */
data class RecentAvatarIdentity(
    val seedType: SeedType,
    val seedValue: String,
    val displayContactId: Long?,
    val avatarVersion: Long,
) {
    enum class SeedType {
        CONTACT,
        NUMBER,
    }

    companion object {
        fun fromGroupKey(
            groupKey: String,
            displayContactId: Long?,
            photoThumbUri: String,
            usePhotoAvatar: Boolean,
            previousVersion: Long = 0L,
            previousPhotoUri: String = "",
            previousContactId: Long? = null,
        ): RecentAvatarIdentity {
            val contactId = displayContactId?.takeIf { it > 0L }
            val (seedType, seedValue) = when {
                groupKey.startsWith("contact:") -> {
                    val id = groupKey.removePrefix("contact:")
                    SeedType.CONTACT to id
                }
                groupKey.startsWith("number:") -> {
                    SeedType.NUMBER to groupKey.removePrefix("number:")
                }
                contactId != null -> SeedType.CONTACT to contactId.toString()
                else -> SeedType.NUMBER to groupKey.removePrefix("number:")
            }
            val version = computeAvatarVersion(
                previousVersion = previousVersion,
                photoThumbUri = photoThumbUri,
                usePhotoAvatar = usePhotoAvatar,
                displayContactId = contactId,
                previousPhotoUri = previousPhotoUri,
                previousContactId = previousContactId,
            )
            return RecentAvatarIdentity(
                seedType = seedType,
                seedValue = seedValue,
                displayContactId = contactId,
                avatarVersion = version,
            )
        }

        fun computeAvatarVersion(
            previousVersion: Long,
            photoThumbUri: String,
            usePhotoAvatar: Boolean,
            displayContactId: Long?,
            previousPhotoUri: String = "",
            previousContactId: Long? = null,
        ): Long {
            var version = previousVersion
            if (photoThumbUri != previousPhotoUri) {
                version = version xor photoThumbUri.hashCode().toLong()
            }
            if (displayContactId != previousContactId) {
                version = version xor (displayContactId ?: 0L) * 31L
            }
            if (!usePhotoAvatar && previousPhotoUri.isNotEmpty()) {
                version = version xor 17L
            }
            if (version == 0L && (photoThumbUri.isNotEmpty() || displayContactId != null)) {
                version = (photoThumbUri.hashCode().toLong() shl 32) xor (displayContactId ?: 0L)
            }
            return version
        }

        fun maskedSeed(seedValue: String): String =
            if (seedValue.length <= 4) "****" else "****${seedValue.takeLast(4)}"
    }
}
