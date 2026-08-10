package com.goodwy.commons.providercache.display

import com.goodwy.commons.extensions.toFastScrollBucket
import com.goodwy.commons.models.contacts.Contact

/**
 * Builds FastScroll section metadata from an already-sorted contacts list in O(n).
 * Uses persisted [ContactDisplayBind.sectionLetter] / row section letter — no locale collation.
 */
object ContactsFastScrollSections {

    const val FALLBACK_LABEL = "#"

    fun buildFromRows(rows: List<ContactDisplayListRow>): List<FastScrollSection> {
        if (rows.isEmpty()) return emptyList()
        val out = ArrayList<FastScrollSection>(32)
        var lastLabel: String? = null
        for (i in rows.indices) {
            val label = normalizeLabel(rows[i].sectionLetter)
            if (label != lastLabel) {
                out += FastScrollSection(label = label, firstPosition = i)
                lastLabel = label
            }
        }
        return out
    }

    fun buildFromContacts(contacts: List<Contact>): List<FastScrollSection> {
        if (contacts.isEmpty()) return emptyList()
        val out = ArrayList<FastScrollSection>(32)
        var lastLabel: String? = null
        for (i in contacts.indices) {
            val label = normalizeLabel(
                contacts[i].displayBind?.sectionLetter
                    ?: contacts[i].resolvedFastScrollBucket(),
            )
            if (label != lastLabel) {
                out += FastScrollSection(label = label, firstPosition = i)
                lastLabel = label
            }
        }
        return out
    }

    /** contactId/rawId → section letter for Reddit FastScrollerView bucket maps. */
    fun bucketMapFromContacts(contacts: List<Contact>): Map<Int, String> {
        if (contacts.isEmpty()) return emptyMap()
        val map = HashMap<Int, String>(contacts.size * 2)
        for (contact in contacts) {
            val label = normalizeLabel(
                contact.displayBind?.sectionLetter ?: contact.resolvedFastScrollBucket(),
            )
            if (label.isNotEmpty()) {
                map[contact.id] = label
            }
        }
        return map
    }

    /**
     * Normalizes a persisted or live section letter into a rail bucket.
     * Preserves Korean choseong (ㄱㄴㄷ…) and other [toFastScrollBucket] labels;
     * does not collapse non-Latin to [FALLBACK_LABEL].
     */
    fun normalizeLabel(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return FALLBACK_LABEL
        val bucket = trimmed.first().toString().toFastScrollBucket()
        return bucket.ifEmpty { FALLBACK_LABEL }
    }

    fun validate(sections: List<FastScrollSection>, rowCount: Int): Boolean {
        if (rowCount == 0) return sections.isEmpty()
        if (sections.isEmpty()) return false
        var prevPos = -1
        var prevLabel: String? = null
        for (section in sections) {
            if (section.firstPosition < 0 || section.firstPosition >= rowCount) return false
            if (section.firstPosition <= prevPos) return false
            if (section.label == prevLabel) return false
            prevPos = section.firstPosition
            prevLabel = section.label
        }
        return sections.first().firstPosition == 0
    }
}
