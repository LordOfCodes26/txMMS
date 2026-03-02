package com.goodwy.commons.helpers

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import androidx.core.os.bundleOf

/**
 * Result of [unlockAllWithPin]: count of unlocked raw contacts and their IDs (when available).
 * Used so callers can ensure the contact list refresh reflects the newly unlocked contacts.
 */
data class UnlockResult(
    val count: Int,
    val rawContactIds: LongArray?
) {
    override fun equals(other: Any?): Boolean =
        (other as? UnlockResult)?.let { count == it.count && rawContactIds.contentEquals(it.rawContactIds) } ?: false

    override fun hashCode(): Int = 31 * count + (rawContactIds?.contentHashCode() ?: 0)
}

/**
 * Helper for ContactProvider protection API (set_protected / unprotect / unlock_all_with_pin).
 * Tracks protected raw contact IDs in SharedPreferences for UI state, and keeps in-memory
 * session unlock state so background threads can re-establish unlock for their Binder connection.
 */
object ContactProtectionHelper {
    private const val TAG = "ContactProtection"

    private const val PREFS_NAME = "contact_protection_test"
    private const val KEY_PROTECTED_RAW_IDS = "protected_raw_ids"
    private const val TEST_PIN = "1080"

    @Volatile
    private var unlockedInSession: Boolean = false

    @Volatile
    private var sessionPin: String? = null

    @Volatile
    private var unlockedRawContactIds: LongArray? = null

    fun getUnlockedRawContactIds(): LongArray? = unlockedRawContactIds

    private fun getProtectedIds(context: Context): MutableSet<Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_PROTECTED_RAW_IDS, null) ?: emptySet()
        return set.mapNotNull { it.toIntOrNull() }.toMutableSet()
    }

    private fun setProtectedIds(context: Context, ids: Set<Int>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_PROTECTED_RAW_IDS, ids.map { it.toString() }.toSet())
            .apply()
    }

    fun unprotectContact(context: Context, rawContactId: Int): Boolean {
        val protected = getProtectedIds(context)
        if (!protected.contains(rawContactId)) return false
        Log.d(TAG, "unprotect from list: rawContactId=$rawContactId")
        unprotect(context, rawContactId)
        protected.remove(rawContactId)
        setProtectedIds(context, protected)
        val currentIds = unlockedRawContactIds
        if (currentIds != null) {
            unlockedRawContactIds = currentIds.filter { it != rawContactId.toLong() }.toLongArray()
        }
        return true
    }

    fun protectContact(context: Context, rawContactId: Int, pin: String) {
        setProtected(context, rawContactId, pin)
        val protected = getProtectedIds(context)
        protected.add(rawContactId)
        setProtectedIds(context, protected)
    }

    fun protectMany(context: Context, rawContactIds: List<Int>, pin: String) {
        val trimmedPin = pin.trim()
        if (trimmedPin.isEmpty() || rawContactIds.isEmpty()) return
        val extras = bundleOf(
            "raw_contact_ids" to rawContactIds.map { it.toLong() }.toLongArray(),
            "pin" to trimmedPin
        )
        context.contentResolver.call(
            ContactsContract.AUTHORITY_URI,
            "set_protected_many",
            null,
            extras
        )
        val protected = getProtectedIds(context)
        protected.addAll(rawContactIds)
        setProtectedIds(context, protected)
    }

    fun unprotectMany(context: Context, rawContactIds: List<Int>) {
        if (rawContactIds.isEmpty()) return
        val extras = bundleOf(
            "raw_contact_ids" to rawContactIds.map { it.toLong() }.toLongArray()
        )
        context.contentResolver.call(
            ContactsContract.AUTHORITY_URI,
            "unprotect_many",
            null,
            extras
        )
        val protected = getProtectedIds(context)
        rawContactIds.forEach { protected.remove(it) }
        setProtectedIds(context, protected)
        val currentIds = unlockedRawContactIds
        if (currentIds != null) {
            val removeSet = rawContactIds.map { it.toLong() }.toSet()
            unlockedRawContactIds = currentIds.filter { it !in removeSet }.toLongArray()
        }
    }

    fun setProtected(context: Context, rawContactId: Int, pin: String) {
        val extras = bundleOf("pin" to pin)
        context.contentResolver.call(
            ContactsContract.AUTHORITY_URI,
            "set_protected",
            rawContactId.toString(),
            extras
        )
    }

    fun unprotect(context: Context, rawContactId: Int) {
        val extras = bundleOf("raw_contact_id" to rawContactId.toLong())
        context.contentResolver.call(
            ContactsContract.AUTHORITY_URI,
            "unprotect",
            rawContactId.toString(),
            extras
        )
    }

    fun isProtected(context: Context, rawContactId: Int): Boolean {
        return getProtectedIds(context).contains(rawContactId)
    }

    fun removeTrackingIdsThatAppearInList(context: Context, rawContactIdsInList: List<Int>) {
        if (unlockedInSession) return
        val protected = getProtectedIds(context)
        val idsToRemove = rawContactIdsInList.filter { protected.contains(it) }
        if (idsToRemove.isEmpty()) return
        idsToRemove.forEach { protected.remove(it) }
        setProtectedIds(context, protected)
    }

    fun isUnlockedInSession(): Boolean = unlockedInSession

    fun getTestPin(): String = TEST_PIN

    fun unlockAllWithPin(context: Context, pin: String): UnlockResult {
        val trimmedPin = pin.trim()
        if (trimmedPin.isEmpty()) {
            Log.w(TAG, "unlock_all_with_pin: pin is empty")
            return UnlockResult(0, null)
        }
        unlockedInSession = true
        sessionPin = trimmedPin
        unlockedRawContactIds = null

        val extras = bundleOf("pin" to trimmedPin)
        val result = context.contentResolver.call(
            ContactsContract.AUTHORITY_URI,
            "unlock_all_with_pin",
            null,
            extras
        )
        val bundleCount = result?.getInt("unlocked_count", -1) ?: -1
        val rawIds = result?.getLongArray("raw_contact_ids")
        val idsSize = rawIds?.size ?: 0
        val effectiveCount = when {
            bundleCount > 0 -> bundleCount
            idsSize > 0 -> idsSize
            else -> 0
        }
        unlockedRawContactIds = rawIds
        return UnlockResult(effectiveCount, rawIds)
    }

    fun ensureUnlockedForThread(context: Context) {
        val pin = sessionPin ?: return
        if (!unlockedInSession) return
        try {
            val extras = bundleOf("pin" to pin)
            context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "unlock_all_with_pin",
                null,
                extras
            )
        } catch (e: Exception) {
            Log.e(TAG, "ensureUnlockedForThread failed", e)
        }
    }

    fun lock(context: Context) {
        unlockedInSession = false
        sessionPin = null
        unlockedRawContactIds = null
        context.contentResolver.call(
            ContactsContract.AUTHORITY_URI,
            "lock",
            null,
            null
        )
    }
}
