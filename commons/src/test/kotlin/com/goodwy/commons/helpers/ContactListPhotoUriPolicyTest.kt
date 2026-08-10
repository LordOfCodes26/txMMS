package com.goodwy.commons.helpers

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ContactListPhotoUriPolicyTest {

    @Before
    fun clearInvalidTracker() {
        ContactAvatarInvalidUriTracker.clearAll()
    }

    @Test
    fun resolveListPhotoThumbUri_prefersThumbnailOverPhotoUri() {
        val resolved = ContactListPhotoUriPolicy.resolveListPhotoThumbUri(
            thumbnailUri = "content://com.android.contacts/contacts/1/photo_thumb",
            photoUri = "content://com.android.contacts/contacts/1/photo",
        )
        assertEquals("content://com.android.contacts/contacts/1/photo_thumb", resolved)
    }

    @Test
    fun resolveListPhotoThumbUri_fallsBackToPhotoUriWhenThumbEmpty() {
        val resolved = ContactListPhotoUriPolicy.resolveListPhotoThumbUri(
            thumbnailUri = "",
            photoUri = "content://com.android.contacts/contacts/2/photo",
        )
        assertEquals("content://com.android.contacts/contacts/2/photo", resolved)
    }

    @Test
    fun resolveListPhotoThumbUri_returnsEmptyWhenBothMissing() {
        val resolved = ContactListPhotoUriPolicy.resolveListPhotoThumbUri(
            thumbnailUri = "  ",
            photoUri = "",
        )
        assertEquals("", resolved)
    }
}
