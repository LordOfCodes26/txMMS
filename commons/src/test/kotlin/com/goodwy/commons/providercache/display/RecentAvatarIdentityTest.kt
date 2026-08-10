package com.goodwy.commons.providercache.display

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentAvatarIdentityTest {

    @Test
    fun contactGroup_usesContactIdSeed() {
        val identity = RecentAvatarIdentity.fromGroupKey(
            groupKey = "contact:42",
            displayContactId = 42L,
            photoThumbUri = "",
            usePhotoAvatar = false,
        )
        assertEquals(RecentAvatarIdentity.SeedType.CONTACT, identity.seedType)
        assertEquals("42", identity.seedValue)
    }

    @Test
    fun numberGroup_usesCanonicalNumberSeed() {
        val identity = RecentAvatarIdentity.fromGroupKey(
            groupKey = "number:5551000",
            displayContactId = null,
            photoThumbUri = "",
            usePhotoAvatar = false,
        )
        assertEquals(RecentAvatarIdentity.SeedType.NUMBER, identity.seedType)
        assertEquals("5551000", identity.seedValue)
    }

    @Test
    fun rename_doesNotChangeSeed() {
        val before = RecentAvatarIdentity.fromGroupKey(
            groupKey = "contact:42",
            displayContactId = 42L,
            photoThumbUri = "",
            usePhotoAvatar = false,
        )
        val after = RecentAvatarIdentity.fromGroupKey(
            groupKey = "contact:42",
            displayContactId = 42L,
            photoThumbUri = "",
            usePhotoAvatar = false,
        )
        assertEquals(before.seedType, after.seedType)
        assertEquals(before.seedValue, after.seedValue)
    }

    @Test
    fun photoChange_bumpsAvatarVersion() {
        val before = RecentAvatarIdentity.fromGroupKey(
            groupKey = "contact:42",
            displayContactId = 42L,
            photoThumbUri = "content://a",
            usePhotoAvatar = true,
            previousVersion = 100L,
            previousPhotoUri = "content://a",
        )
        val after = RecentAvatarIdentity.fromGroupKey(
            groupKey = "contact:42",
            displayContactId = 42L,
            photoThumbUri = "content://b",
            usePhotoAvatar = true,
            previousVersion = before.avatarVersion,
            previousPhotoUri = "content://a",
        )
        assertEquals(before.seedValue, after.seedValue)
        assert(after.avatarVersion != before.avatarVersion || after.avatarVersion != 0L)
    }
}
