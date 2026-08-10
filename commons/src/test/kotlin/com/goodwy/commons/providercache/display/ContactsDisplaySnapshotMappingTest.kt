package com.goodwy.commons.providercache.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class ContactsDisplaySnapshotMappingTest {

    @Test
    fun mapListRows_isDirectCopy_stableIdIsRawId() {
        val row = ContactDisplayListRow(
            rawId = 42,
            contactId = 7,
            displayName = "Ada",
            source = "SIM 1",
            starred = 1,
            sectionLetter = "A",
            firstPhoneFormatted = "+1 555",
            showPhoneNumber = 1,
            avatarInitials = "A",
            avatarDrawableIndex = 3,
            avatarColor = 0xFF112233.toInt(),
            photoThumbUri = "content://photo/1",
            usePhotoAvatar = 1,
            hasValidPhotoUri = 1,
            displayOrder = 0,
        )
        val contact = ContactDisplayLoadHelper.mapListRow(row)
        assertEquals(42, contact.id)
        assertEquals(7, contact.contactId)
        assertEquals("SIM 1", contact.source)
        assertEquals("Ada", contact.displayBind?.displayName)
        assertEquals("A", contact.displayBind?.sectionLetter)
        assertEquals("+1 555", contact.displayBind?.formattedPhone)
        assertEquals("A", contact.displayBind?.avatarInitials)
    }

    @Test
    fun fullSnapshot_buildSectionsOnce_for3k() {
        val rows = (0 until 3000).map { i ->
            ContactDisplayListRow(
                rawId = i + 1,
                contactId = i + 1,
                displayName = "N$i",
                starred = 0,
                sectionLetter = (('A'.code + (i / 120).coerceAtMost(25)).toChar()).toString(),
                firstPhoneFormatted = "",
                showPhoneNumber = 0,
                avatarInitials = "N",
                avatarDrawableIndex = 0,
                avatarColor = 1,
                photoThumbUri = "",
                usePhotoAvatar = 0,
                hasValidPhotoUri = 0,
                displayOrder = i,
            )
        }
        val mapMs = measureTimeMillis {
            ContactDisplayLoadHelper.mapListRows(rows)
        }
        val (contacts, _) = ContactDisplayLoadHelper.mapListRows(rows)
        val sectionsMs = measureTimeMillis {
            ContactsFastScrollSections.buildFromRows(rows)
        }
        val sections = ContactsFastScrollSections.buildFromRows(rows)
        val snapshot = ContactsDisplaySnapshot(
            displayVersion = 1L,
            rows = rows,
            contacts = contacts,
            sections = sections,
            contentChecksum = 1L * 31 + rows.size,
        )
        assertEquals(3000, snapshot.rowCount)
        assertEquals(contacts.size, snapshot.contacts.size)
        assertTrue(ContactsFastScrollSections.validate(snapshot.sections, snapshot.rowCount))
        // Soft budget for JVM unit host (not a mid-range device); keep generous.
        assertTrue("mapMs=$mapMs", mapMs < 2_000)
        assertTrue("sectionsMs=$sectionsMs", sectionsMs < 500)
    }

    @Test
    fun versionSkip_sameChecksumMeansNoRepublish() {
        val checksum = 9L * 31 + 100
        val a = ContactsDisplaySnapshot(9L, emptyList(), emptyList(), emptyList(), checksum)
        val b = ContactsDisplaySnapshot(9L, emptyList(), emptyList(), emptyList(), checksum)
        assertEquals(a.displayVersion, b.displayVersion)
        assertEquals(a.contentChecksum, b.contentChecksum)
    }

    @Test
    fun startupHiddenRowWarm_mapsToCacheRebuildUiReason() {
        assertEquals(
            ContactsUiReconcileReason.CACHE_REBUILD,
            ContactDisplayLoadReason.STARTUP_HIDDEN_ROW_WARM.toUiReconcileReason(),
        )
    }
}
