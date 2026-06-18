package com.android.mms.helpers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import com.android.mms.extensions.getNameAndPhotoFromPhoneNumber
import com.android.mms.models.Contact
import com.android.mms.models.ContactPickerListRow
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.helpers.ensureBackgroundThread
import java.util.Calendar

object RecipientSelectCallLogLoader {
    private const val CALL_LOG_LIMIT = 500

    private data class RawCallLogRow(
        val number: String,
        val normalized: String,
        val type: Int,
        val dateMillis: Long,
        val cachedName: String?,
    )

    private data class CallLogEntryMeta(
        val type: Int,
        val timestamp: Long,
        val groupedCount: Int,
    )

    private data class IndexedCallLogEntry(
        val contactIndex: Int,
        val type: Int,
        val date: Long,
        val groupedCount: Int,
    )

    private enum class CallLogDateSection { TODAY, YESTERDAY, BEFORE }

    fun load(
        context: Context,
        alreadySelectedNormalized: Set<String>,
        onResult: (contacts: List<Contact>, rows: List<ContactPickerListRow>, selectedIndices: Set<Int>) -> Unit,
        onEmpty: () -> Unit,
        onPermissionDenied: () -> Unit,
    ) {
        ensureBackgroundThread {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
                onPermissionDenied()
                return@ensureBackgroundThread
            }
            try {
                val projection = arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.DATE,
                    CallLog.Calls.TYPE,
                )
                val cursor = try {
                    context.contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        projection,
                        null,
                        null,
                        "${CallLog.Calls.DATE} DESC",
                    )
                } catch (_: SecurityException) {
                    onPermissionDenied()
                    return@ensureBackgroundThread
                }
                val list = ArrayList<Contact>()
                val meta = ArrayList<CallLogEntryMeta>()
                val rawRows = ArrayList<RawCallLogRow>()
                cursor?.use { c ->
                    val nameCol = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    val numberCol = c.getColumnIndex(CallLog.Calls.NUMBER)
                    val dateCol = c.getColumnIndex(CallLog.Calls.DATE)
                    val typeCol = c.getColumnIndex(CallLog.Calls.TYPE)
                    if (numberCol < 0 || dateCol < 0) {
                        onEmpty()
                        return@ensureBackgroundThread
                    }
                    var count = 0
                    while (c.moveToNext() && count < CALL_LOG_LIMIT) {
                        val number = c.getString(numberCol) ?: continue
                        if (number.isBlank()) continue
                        val normalized = normalizePhoneNumber(number)
                        val dateMillis = c.getLong(dateCol)
                        val callType = if (typeCol >= 0) c.getInt(typeCol) else CallLog.Calls.INCOMING_TYPE
                        val cached = if (nameCol >= 0) c.getString(nameCol) else null
                        rawRows.add(RawCallLogRow(number, normalized, callType, dateMillis, cached))
                        count++
                    }
                }
                val countByNormalized = rawRows.groupingBy { it.normalized }.eachCount()
                val seenNumbers = HashSet<String>()
                for (raw in rawRows) {
                    if (!seenNumbers.add(raw.normalized)) continue
                    var name = raw.number
                    if (!raw.cachedName.isNullOrBlank()) name = raw.cachedName
                    if (name == raw.number &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                    ) {
                        SimpleContactsHelper(context).getNameFromPhoneNumber(raw.number).takeIf { it != raw.number }?.let {
                            name = it
                        }
                    }
                    val photoUri = context.getNameAndPhotoFromPhoneNumber(raw.number).photoUri.orEmpty()
                    list.add(
                        Contact(
                            name = name,
                            contactId = "",
                            phoneNumber = raw.number,
                            photoUri = photoUri,
                        ),
                    )
                    meta.add(
                        CallLogEntryMeta(
                            type = raw.type,
                            timestamp = raw.dateMillis,
                            groupedCount = countByNormalized[raw.normalized] ?: 1,
                        ),
                    )
                }
                if (list.isEmpty()) {
                    onEmpty()
                    return@ensureBackgroundThread
                }
                val selected = HashSet<Int>()
                list.forEachIndexed { index, contact ->
                    if (alreadySelectedNormalized.contains(normalizePhoneNumber(contact.phoneNumber))) {
                        selected.add(index)
                    }
                }
                val entries = ArrayList<IndexedCallLogEntry>()
                list.forEachIndexed { i, _ ->
                    if (i in meta.indices) {
                        val m = meta[i]
                        entries.add(IndexedCallLogEntry(i, m.type, m.timestamp, m.groupedCount))
                    }
                }
                val rows = groupCallsByDateSections(entries)
                onResult(list, rows, selected)
            } catch (_: Exception) {
                onEmpty()
            }
        }
    }

    private fun groupCallsByDateSections(entries: List<IndexedCallLogEntry>): List<ContactPickerListRow> {
        if (entries.isEmpty()) return emptyList()
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val yesterdayStart = (todayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val todayStartMillis = todayStart.timeInMillis
        val yesterdayStartMillis = yesterdayStart.timeInMillis
        val result = ArrayList<ContactPickerListRow>()
        var lastSection: CallLogDateSection? = null
        for (entry in entries) {
            val currentSection = when {
                entry.date >= todayStartMillis -> CallLogDateSection.TODAY
                entry.date >= yesterdayStartMillis -> CallLogDateSection.YESTERDAY
                else -> CallLogDateSection.BEFORE
            }
            if (currentSection != lastSection) {
                val dayCode = when (currentSection) {
                    CallLogDateSection.TODAY -> ContactPickerListRow.DateSection.SECTION_TODAY
                    CallLogDateSection.YESTERDAY -> ContactPickerListRow.DateSection.SECTION_YESTERDAY
                    CallLogDateSection.BEFORE -> ContactPickerListRow.DateSection.SECTION_BEFORE
                }
                result.add(ContactPickerListRow.DateSection(dayCode))
                lastSection = currentSection
            }
            result.add(
                ContactPickerListRow.ContactRow(
                    contactIndex = entry.contactIndex,
                    callType = entry.type,
                    callTimestamp = entry.date,
                    groupedCallCount = entry.groupedCount,
                ),
            )
        }
        return result
    }

    private fun normalizePhoneNumber(phone: String): String = phone.filter { it.isDigit() }
}
