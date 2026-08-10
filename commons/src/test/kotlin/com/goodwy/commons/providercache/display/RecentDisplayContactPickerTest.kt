package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.entities.ContactDisplayCacheEntity
import com.goodwy.commons.providercache.entities.ContactPhoneIndexEntity
import com.goodwy.commons.providercache.entities.ContactSummaryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentDisplayContactPickerTest {

    private fun summary(id: Int, name: String, account: String = "Google") = ContactSummaryEntity(
        contactId = id,
        lookupKey = "k$id",
        displayName = name,
        photoThumbnailUri = "",
        hasPhoneNumber = true,
        lastUpdatedTimestamp = 0L,
        accountName = account,
    )

    private fun display(id: Int, starred: Int = 0, photo: String = "", name: String = "") =
        ContactDisplayCacheEntity(
            rawId = id,
            contactId = id,
            displayName = name,
            thumbnailUri = photo,
            photoUri = photo,
            source = "Google",
            accountType = "",
            firstPhone = "5551234",
            firstEmail = "",
            sectionLetter = "A",
            sortKey = name,
            searchName = name,
            t9Key = name,
            phoneDigits = "5551234",
            starred = starred,
            hasValidPhotoUri = if (photo.isNotEmpty()) 1 else 0,
            photoThumbUri = photo,
        )

    private fun index(contactId: Int) = ContactPhoneIndexEntity(
        contactId = contactId,
        normalizedNumber = "5551234",
        digits = "5551234",
        phoneDigits = "5551234",
    )

    @Test
    fun pickBest_prefersVisibleOverHidden() {
        val summaries = mapOf(
            1 to summary(1, "Hidden", account = "hidden"),
            2 to summary(2, "Visible"),
        )
        val displays = mapOf(
            1 to display(1, name = "Hidden"),
            2 to display(2, name = "Visible"),
        )
        val best = RecentDisplayContactPicker.pickBest(listOf(index(1), index(2)), summaries, displays)
        assertEquals(2, best?.contactId)
    }

    @Test
    fun pickBest_prefersStarredWhenBothVisible() {
        val summaries = mapOf(1 to summary(1, "A"), 2 to summary(2, "B"))
        val displays = mapOf(
            1 to display(1, starred = 0),
            2 to display(2, starred = 1),
        )
        val best = RecentDisplayContactPicker.pickBest(listOf(index(1), index(2)), summaries, displays)
        assertEquals(2, best?.contactId)
    }

    @Test
    fun pickBest_prefersPhotoWhenStarredEqual() {
        val summaries = mapOf(1 to summary(1, "A"), 2 to summary(2, "B"))
        val displays = mapOf(
            1 to display(1),
            2 to display(2, photo = "content://photo/2"),
        )
        val best = RecentDisplayContactPicker.pickBest(listOf(index(1), index(2)), summaries, displays)
        assertEquals(2, best?.contactId)
    }

    @Test
    fun pickBest_prefersNameWhenPhotoEqual() {
        val summaries = mapOf(1 to summary(1, ""), 2 to summary(2, "Named"))
        val displays = mapOf(1 to display(1), 2 to display(2, name = "Named"))
        val best = RecentDisplayContactPicker.pickBest(listOf(index(1), index(2)), summaries, displays)
        assertEquals(2, best?.contactId)
    }

    @Test
    fun pickBest_lowestContactIdOnFullTie() {
        val summaries = mapOf(1 to summary(1, "Same"), 2 to summary(2, "Same"))
        val displays = mapOf(1 to display(1, name = "Same"), 2 to display(2, name = "Same"))
        val best = RecentDisplayContactPicker.pickBest(listOf(index(1), index(2)), summaries, displays)
        assertEquals(1, best?.contactId)
    }
}
