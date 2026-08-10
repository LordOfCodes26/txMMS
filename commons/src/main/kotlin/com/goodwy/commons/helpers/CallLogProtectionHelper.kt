package com.goodwy.commons.helpers

import android.content.ContentUris
import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.PhoneLookup
import android.util.Log
import androidx.core.os.bundleOf
import com.goodwy.commons.extensions.getIntValue
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.extensions.normalizePhoneNumber

/**
 * Recents protection backed by the Contacts Provider database.
 *
 * PINs are stored in [ContactsContract.AUTHORITY] via `set_protected_phone` /
 * `protected_phone_pins` (and linked saved contacts via [ContactProtectionHelper]).
 * No PIN strings are stored in app SharedPreferences.
 */
object CallLogProtectionHelper {

    private const val TAG = "CallLogProtection"
    private const val FLOW_TAG = "ProtectionFlow"

    /** Keeps `_id IN (…)` under SQLite's variable/expression limits on large recents lists. */
    private const val CALL_LOG_ID_CHUNK = 400

    /**
     * Memoized "is this number protected" answers, keyed by normalized number.
     *
     * [protectedCallLogIds] already asks once per *distinct* number within a single call, but a
     * recents list of 304 distinct numbers still costs ~600 provider IPCs — one
     * `is_phone_protected` plus one raw-contact resolution each — and that whole pass reruns on
     * every publish. In a log of a 304-row list it took ~3.5s between the section work finishing
     * and the adapter submit, and answered `protected=0` every time. Startup pays it, and so does
     * every call-type filter change, which is why filtering felt like a hang.
     *
     * Protection is a property of the number, not of the publish, so it only has to be recomputed
     * when protection actually changes. Every in-process mutation must call
     * [invalidateProtectedNumberCache] — that means the contact-level writes in
     * [ContactProtectionHelper] (`setProtectedIds` / `setProtectedIdPairs`, session generation)
     * *and* the phone-level writes in this file (`set_protected_phones_many`,
     * `unprotect_phones_many`, `unprotect_phone`).
     *
     * The phone-level ones were missed when this cache was introduced, on the assumption that
     * everything funnelled through [ContactProtectionHelper]. It does not: moving a recents row to
     * the secure box writes the phone number straight to the provider and never touches the
     * app-side raw-contact id set. The moved rows stayed visible in normal recents until the TTL
     * expired, because the memo kept answering with the pre-move state. Add the invalidation call
     * alongside any new provider write here.
     *
     * [PROTECTED_NUMBER_CACHE_TTL_MS] is the backstop for
     * changes made outside this process (secure-space UI, another app writing the provider), so a
     * missed notification costs at most that long rather than hiding a protected row for the
     * lifetime of the process.
     */
    private val protectedNumberCache = HashMap<String, Boolean>()

    /** Elapsed-time stamp of the oldest live cache entry; `0L` when the cache is empty. */
    private var protectedNumberCacheStampMs = 0L

    /**
     * Backstop only — in-process protect/unprotect and lock/unlock invalidate explicitly.
     *
     * This started at 30s and that was far too short to be useful. The stamp tracks the *oldest*
     * live entry and expiry wipes the whole map, so any pass more than 30s after the cache was
     * first populated paid full price again. A captured session showed exactly that: a filter
     * change 50s after population spent 4.7s recomputing, while the next one 16s later — inside
     * the window — took 296ms. Same work, same answer, 16x apart purely on TTL luck.
     *
     * Protection changes are rare and user-initiated, and the only ones this window has to catch
     * are those made outside this process (secure-space UI, another app writing the provider).
     * Ten minutes still bounds that staleness while letting the cache survive normal use.
     */
    private const val PROTECTED_NUMBER_CACHE_TTL_MS = 10 * 60_000L

    /** Drops memoized protection answers. Safe to call from any thread. */
    fun invalidateProtectedNumberCache(reason: String) {
        val dropped = synchronized(protectedNumberCache) {
            val size = protectedNumberCache.size
            protectedNumberCache.clear()
            protectedNumberCacheStampMs = 0L
            size
        }
        if (dropped > 0) {
            Log.d(TAG, "protectedNumberCache invalidated reason=$reason entries=$dropped")
        }
    }

    /**
     * [queryProviderIsPhoneProtected] + raw-contact fallback, memoized.
     *
     * The provider calls happen outside the lock: two threads racing the same number both get the
     * authoritative answer and write the same value, which is cheaper than serializing every
     * caller behind hundreds of IPCs.
     */
    /**
     * The provider's protected-phone answer for many numbers in one `call()`, or `null` when the
     * installed provider has no `are_phones_protected` method.
     *
     * `null` means **unknown**, never "none protected". A caller that treated an absent method as
     * an empty result would display protected call-log rows, so every failure path here returns
     * null and the caller falls back to asking per number.
     *
     * This replaces one Binder crossing per number. It does not replace the raw-contact fallback
     * in [isPhoneProtectedCached]: a number the provider reports as unprotected can still belong
     * to a protected contact, and that check stays per number.
     */
    private fun queryProviderProtectedPhonesBatch(
        context: Context,
        phoneNumbers: Collection<String>,
    ): Set<String>? {
        if (phoneNumbers.isEmpty()) return emptySet()
        return try {
            val extras = bundleOf("phone_numbers" to ArrayList(phoneNumbers))
            val result = context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "are_phones_protected",
                null,
                extras,
            ) ?: return null
            // Key absent means the provider answered something else; treat as unknown, not empty.
            result.getStringArrayList("protected_phone_numbers")?.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "are_phones_protected unavailable; falling back to per-number queries", e)
            null
        }
    }

    /**
     * @param providerAlreadySaidUnprotected true when a batch answer has already covered the
     *  provider half for this number, so only the raw-contact fallback still has to run.
     */
    private fun isPhoneProtectedCached(
        context: Context,
        phoneNumber: String,
        providerAlreadySaidUnprotected: Boolean = false,
    ): Boolean {
        val key = phoneNumber.normalizePhoneNumber().ifEmpty { phoneNumber }
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(protectedNumberCache) {
            if (protectedNumberCacheStampMs != 0L &&
                now - protectedNumberCacheStampMs > PROTECTED_NUMBER_CACHE_TTL_MS
            ) {
                protectedNumberCache.clear()
                protectedNumberCacheStampMs = 0L
            } else {
                protectedNumberCache[key]?.let { return it }
            }
        }
        val isProtected = (
            !providerAlreadySaidUnprotected && queryProviderIsPhoneProtected(context, phoneNumber)
            ) || resolveRawContactIdsForPhoneNumber(context, phoneNumber)
            .any { ContactProtectionHelper.isProtected(context, it) }
        synchronized(protectedNumberCache) {
            if (protectedNumberCacheStampMs == 0L) protectedNumberCacheStampMs = now
            protectedNumberCache[key] = isProtected
        }
        return isProtected
    }

    fun isUnlockedInSession(): Boolean = ContactProtectionHelper.isUnlockedInSession()

    fun getSessionPin(): String? = ContactProtectionHelper.getSessionPin()

    /** @deprecated Recents unlock is phone/contact based; use [isVisibleInCurrentSession]. */
    fun getUnlockedCallLogIds(): LongArray? = null

    fun isProtected(context: Context, callLogId: Int): Boolean {
        val phone = getPhoneNumberForCallLogId(context, callLogId) ?: return false
        val phoneProtected = queryProviderIsPhoneProtected(context, phone)
        val contactProtected = resolveRawContactIdsForPhoneNumber(context, phone)
            .any { ContactProtectionHelper.isProtected(context, it) }
        val result = phoneProtected || contactProtected
        Log.d(TAG, "isProtected callLogId=$callLogId phone=${ProtectionLogRedaction.phone(phone)}"
                + " phoneProtected=$phoneProtected"
                + " contactProtected=$contactProtected result=$result")
        return result
    }

    /**
     * Bulk form of [isProtected], for filtering a whole recents list.
     *
     * [isProtected] costs one call-log query plus a provider check per row, and recents repeats
     * numbers heavily -- a 1157-row log over 305 numbers asked the provider the same question
     * hundreds of times. This resolves every id in one call-log query and asks once per distinct
     * number. The provider check itself is unchanged and still authoritative; only the repetition
     * is removed.
     */
    fun protectedCallLogIds(context: Context, callLogIds: Collection<Int>): Set<Int> {
        val ids = callLogIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return emptySet()
        val numbersById = getPhoneNumbersForCallLogIds(context, ids)
        if (numbersById.isEmpty()) return emptySet()
        val protectedNumbers = protectedPhoneNumbers(context, numbersById.values)
        val protectedIds = HashSet<Int>()
        for ((id, phone) in numbersById) {
            if (phone in protectedNumbers) protectedIds.add(id)
        }
        Log.d(TAG, "protectedCallLogIds ids=${ids.size} numbers=${numbersById.values.distinct().size}"
                + " protected=${protectedIds.size}")
        return protectedIds
    }

    /**
     * The protected subset of [phoneNumbers].
     *
     * Split out of [protectedCallLogIds] because protection is a property of the number, so a
     * caller that already has numbers should not have to go through call-log ids to ask. The
     * recents protected-number index uses this to build the exclusion list that keeps protected
     * calls out of grouping entirely, rather than hiding built rows afterwards.
     *
     * One provider crossing for the whole set instead of one per number; a null batch result means
     * the installed provider has no `are_phones_protected` method, and every number falls back to
     * asking individually. Never treat null as "none protected".
     */
    fun protectedPhoneNumbers(context: Context, phoneNumbers: Collection<String>): Set<String> {
        val numbers = phoneNumbers.filter { it.isNotBlank() }.distinct()
        if (numbers.isEmpty()) return emptySet()
        val batchProtected = queryProviderProtectedPhonesBatch(context, numbers.toSet())
        val protectedNumbers = HashSet<String>()
        for (phone in numbers) {
            val isProtected = when {
                // Provider already said protected; the raw-contact fallback cannot change it.
                batchProtected?.contains(phone) == true -> true
                // Provider half answered "no" for this number, so only the raw-contact fallback
                // still has to run. A null batch skips this and asks in full.
                batchProtected != null ->
                    isPhoneProtectedCached(context, phone, providerAlreadySaidUnprotected = true)
                else -> isPhoneProtectedCached(context, phone)
            }
            if (isProtected) protectedNumbers.add(phone)
        }
        Log.d(TAG, "protectedPhoneNumbers numbers=${numbers.size} protected=${protectedNumbers.size}"
                + " batch=${if (batchProtected == null) "unavailable" else batchProtected.size.toString()}")
        return protectedNumbers
    }

    /**
     * Bulk form of [isProtectedForSessionPin], for filtering a whole recents list in secure mode.
     *
     * Same repetition problem as [protectedCallLogIds], only worse: the per-row form resolves
     * protection twice (once directly, once inside [isVisibleInCurrentSession]), so a call log with
     * hundreds of rows asked the provider the same question about the same number four times over.
     * Protection and session visibility are both properties of the number, so each is decided once
     * per distinct number here. The decisions themselves are unchanged.
     */
    fun sessionPinVisibleCallLogIds(context: Context, callLogIds: Collection<Int>): Set<Int> {
        val ids = callLogIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return emptySet()
        val numbersById = getPhoneNumbersForCallLogIds(context, ids)
        if (numbersById.isEmpty()) return emptySet()

        val unlockedInSession = ContactProtectionHelper.isUnlockedInSession()
        val rawIdsByNumber = HashMap<String, List<Int>>()
        fun rawIdsFor(phone: String): List<Int> =
            rawIdsByNumber.getOrPut(phone) { resolveRawContactIdsForPhoneNumber(context, phone) }

        val keepByNumber = HashMap<String, Boolean>()
        val kept = LinkedHashSet<Int>()
        for ((id, phone) in numbersById) {
            val keepPhone = keepByNumber.getOrPut(phone) {
                val phoneProtected = isPhoneProtectedCached(context, phone)
                when {
                    // Not protected at all: never part of the secure box.
                    !phoneProtected -> false
                    // Locked out of the session, so no cipher narrows the box down.
                    !unlockedInSession -> true
                    else -> isPhoneVisibleInSession(context, phone, rawIdsFor(phone))
                }
            }
            if (keepPhone) kept.add(id)
        }
        Log.d(TAG, "sessionPinVisibleCallLogIds ids=${ids.size} numbers=${keepByNumber.size}"
                + " kept=${kept.size} unlockedInSession=$unlockedInSession"
                + " space=${ProtectionLogRedaction.space(ContactProtectionHelper.getSessionPin())}")
        return kept
    }

    /** Visibility half of [isVisibleInCurrentSession], for a number already known to be protected. */
    private fun isPhoneVisibleInSession(context: Context, phone: String, rawIds: List<Int>): Boolean {
        ContactProtectionHelper.getUnlockedPhoneNumbers()?.let { unlocked ->
            if (unlocked.any { numbersEqual(phone, it) }) return true
        }
        val unlockedContacts = ContactProtectionHelper.getUnlockedRawContactIds() ?: return false
        return rawIds.any { unlockedContacts.contains(it.toLong()) }
    }

    /** One `_id IN (…)` query per chunk instead of [getPhoneNumberForCallLogId] per row. */
    private fun getPhoneNumbersForCallLogIds(context: Context, ids: List<Int>): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        ids.chunked(CALL_LOG_ID_CHUNK).forEach { chunk ->
            val selection = "${CallLog.Calls._ID} IN (${chunk.joinToString(",")})"
            try {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER),
                    selection,
                    null,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getInt(0)
                        val number = cursor.getString(1)?.trim().orEmpty()
                        if (id > 0 && number.isNotEmpty()) out[id] = number
                    }
                }
            } catch (_: Exception) {
            }
        }
        return out
    }

    fun getProtectionPin(context: Context, callLogId: Int): String? {
        val phone = getPhoneNumberForCallLogId(context, callLogId) ?: return null
        return queryProviderPhonePin(context, phone)
    }

    fun isVisibleInCurrentSession(context: Context, callLogId: Int): Boolean {
        if (!isProtected(context, callLogId)) return true
        if (!ContactProtectionHelper.isUnlockedInSession()) return false
        val phone = getPhoneNumberForCallLogId(context, callLogId) ?: return false
        ContactProtectionHelper.getUnlockedPhoneNumbers()?.let { unlocked ->
            if (unlocked.any { numbersEqual(phone, it) }) {
                Log.d(TAG, "isVisibleInCurrentSession callLogId=$callLogId"
                        + " phone=${ProtectionLogRedaction.phone(phone)}"
                        + " space=${ProtectionLogRedaction.space(ContactProtectionHelper.getSessionPin())}"
                        + " visible=true (phone unlocked)")
                return true
            }
        }
        val unlockedContacts = ContactProtectionHelper.getUnlockedRawContactIds() ?: return false
        val visible = resolveRawContactIdsForPhoneNumber(context, phone)
            .any { unlockedContacts.contains(it.toLong()) }
        Log.d(TAG, "isVisibleInCurrentSession callLogId=$callLogId"
                + " phone=${ProtectionLogRedaction.phone(phone)}"
                + " space=${ProtectionLogRedaction.space(ContactProtectionHelper.getSessionPin())}"
                + " visible=$visible")
        return visible
    }

    /**
     * True when this call-log row is protected with the active session PIN (secure box filter).
     * Single-row form; filtering a list goes through [sessionPinVisibleCallLogIds].
     */
    fun isProtectedForSessionPin(context: Context, callLogId: Int): Boolean {
        if (!ContactProtectionHelper.isUnlockedInSession()) return isProtected(context, callLogId)
        val protected = isProtected(context, callLogId)
        val visible = isVisibleInCurrentSession(context, callLogId)
        val keep = protected && visible
        Log.d(TAG, "isProtectedForSessionPin callLogId=$callLogId protected=$protected"
                + " visible=$visible keep=$keep"
                + " space=${ProtectionLogRedaction.space(ContactProtectionHelper.getSessionPin())}")
        return keep
    }

    fun protectCallLog(context: Context, callLogId: Int, pin: String) {
        protectMany(context, listOf(callLogId), pin)
    }

    fun protectMany(context: Context, callLogIds: List<Int>, pin: String) {
        val trimmedPin = pin.trim()
        if (trimmedPin.isEmpty() || callLogIds.isEmpty()) return
        val phoneNumbers = callLogIds.mapNotNull { getPhoneNumberForCallLogId(context, it) }
            .distinct()
        if (phoneNumbers.isNotEmpty()) {
            val maskedPhones = ProtectionLogRedaction.phones(phoneNumbers)
            val space = ProtectionLogRedaction.space(trimmedPin)
            Log.i(FLOW_TAG, "recentProtectMany callLogIds=$callLogIds phones=$maskedPhones space=$space")
            Log.d(TAG, "set_protected_phones_many: phones=$maskedPhones space=$space")
            try {
                val extras = bundleOf(
                    "phone_numbers" to phoneNumbers.toTypedArray(),
                    "pin" to trimmedPin,
                )
                val result = context.contentResolver.call(
                    ContactsContract.AUTHORITY_URI,
                    "set_protected_phones_many",
                    null,
                    extras,
                )
                val success = result?.getBoolean("success", false) ?: false
                val updated = result?.getInt("updated_count", 0) ?: 0
                Log.i(FLOW_TAG, "recentProtectMany provider success=$success updated=$updated")
                // These numbers just became protected in the provider, so every memoized answer
                // about them is now wrong. Without this the moved rows stay visible in the normal
                // recents list until the TTL expires -- the memo answers "not protected" from
                // before the move, and recents filters on exactly that answer.
                //
                // ContactProtectionHelper.setProtectedIds does not run for this path: protecting a
                // recents row writes the phone number straight to the provider and never touches
                // the app-side raw-contact id set, so the invalidation hooked there is not reached.
                invalidateProtectedNumberCache("set_protected_phones_many")
                phoneNumbers.forEach { phone ->
                    val protected = queryProviderIsPhoneProtected(context, phone)
                    Log.i(FLOW_TAG, "recentPinVerify phone=${ProtectionLogRedaction.phone(phone)}"
                            + " expectedSpace=$space providerIsProtected=$protected")
                }
            } catch (e: Exception) {
                Log.e(TAG, "set_protected_phones_many failed", e)
                throw e
            }
        } else {
            Log.w(FLOW_TAG, "recentProtectMany: no phone numbers for callLogIds=$callLogIds")
        }
        val rawContactIds = resolveRawContactIdsForCallLogIds(context, callLogIds)
        if (rawContactIds.isNotEmpty()) {
            Log.d(TAG, "protectMany linked rawContactIds=$rawContactIds"
                    + " space=${ProtectionLogRedaction.space(trimmedPin)}")
            ContactProtectionHelper.protectMany(context, rawContactIds, trimmedPin)
        }
        ContactProtectionHelper.logProviderPinSnapshot(context, "after recentProtectMany")
    }

    fun protectManyWithLinkedContacts(context: Context, callLogIds: List<Int>, pin: String) {
        protectMany(context, callLogIds, pin)
    }

    fun unprotectCallLog(context: Context, callLogId: Int): Boolean {
        if (!isProtected(context, callLogId)) return false
        val phone = getPhoneNumberForCallLogId(context, callLogId)
        if (phone != null) {
            unprotectPhone(context, phone)
        }
        return true
    }

    fun unprotectCallLogWithLinkedContacts(context: Context, callLogId: Int): Boolean {
        if (!isProtected(context, callLogId)) return false
        val rawContactIds = resolveRawContactIdsForCallLogIds(context, listOf(callLogId))
        val removed = unprotectCallLog(context, callLogId)
        rawContactIds.filter { ContactProtectionHelper.isProtected(context, it) }.forEach {
            ContactProtectionHelper.unprotectContact(context, it)
        }
        return removed
    }

    fun unprotectMany(context: Context, callLogIds: List<Int>) {
        if (callLogIds.isEmpty()) return
        val phoneNumbers = callLogIds.mapNotNull { getPhoneNumberForCallLogId(context, it) }
            .distinct()
        if (phoneNumbers.isNotEmpty()) {
            Log.d(TAG, "unprotect_phones_many: phones=${ProtectionLogRedaction.phones(phoneNumbers)}")
            try {
                val extras = bundleOf("phone_numbers" to phoneNumbers.toTypedArray())
                context.contentResolver.call(
                    ContactsContract.AUTHORITY_URI,
                    "unprotect_phones_many",
                    null,
                    extras,
                )
                // Mirror of the protect path: the memo now holds "protected" for numbers that no
                // longer are, which keeps rows hidden from normal recents after they were moved back.
                invalidateProtectedNumberCache("unprotect_phones_many")
            } catch (e: Exception) {
                Log.e(TAG, "unprotect_phones_many failed", e)
                throw e
            }
        }
    }

    fun unprotectManyWithLinkedContacts(context: Context, callLogIds: List<Int>) {
        val rawContactIds = resolveRawContactIdsForCallLogIds(context, callLogIds)
        unprotectMany(context, callLogIds)
        val toUnprotect = rawContactIds.filter { ContactProtectionHelper.isProtected(context, it) }
        if (toUnprotect.isNotEmpty()) {
            ContactProtectionHelper.unprotectMany(context, toUnprotect)
        }
    }

    fun unlockAllWithPin(context: Context, pin: String): UnlockResult {
        val result = ContactProtectionHelper.unlockAllWithPin(context, pin)
        // Also unlock the call_log authority on this thread's Binder connection. The contacts
        // authority and call_log authority may be hosted by different providers and each tracks
        // unlock state per Binder. Without this, the call_log provider remains locked on this
        // thread even after the contacts authority is unlocked.
        val trimmedPin = pin.trim()
        if (trimmedPin.isNotEmpty()) {
            try {
                val extras = androidx.core.os.bundleOf("pin" to trimmedPin)
                context.contentResolver.call(
                    CallLog.Calls.CONTENT_URI,
                    "unlock_all_with_pin",
                    null,
                    extras
                )
                Log.d(TAG, "unlockAllWithPin: call_log authority unlocked"
                        + " space=${ProtectionLogRedaction.space(trimmedPin)}")
            } catch (e: Exception) {
                Log.d(TAG, "unlockAllWithPin: call_log authority unlock skipped (${e.message})")
            }
        }
        return result
    }

    fun ensureUnlockedForThread(context: Context) {
        ContactProtectionHelper.ensureUnlockedForThread(context)
        // The Call Log provider (authority "call_log") may maintain a separate per-Binder
        // unlock state from the Contacts provider ("com.android.contacts"). Re-establish
        // the unlock on this thread's call_log connection so Calls.CONTENT_URI queries
        // return protected entries in secure mode.
        val pin = ContactProtectionHelper.getSessionPin() ?: return
        if (!ContactProtectionHelper.isUnlockedInSession()) return
        try {
            val extras = androidx.core.os.bundleOf("pin" to pin)
            context.contentResolver.call(
                CallLog.Calls.CONTENT_URI,
                "unlock_all_with_pin",
                null,
                extras
            )
            Log.d(TAG, "ensureUnlockedForThread: call_log authority unlock done")
        } catch (e: Exception) {
            Log.d(TAG, "ensureUnlockedForThread: call_log authority unlock skipped (${e.message})")
        }
    }

    fun lock(context: Context) {
        ContactProtectionHelper.lock(context)
        // Call log may be a separate provider with its own per-Binder unlock session.
        try {
            context.contentResolver.call(
                CallLog.Calls.CONTENT_URI,
                "lock",
                null,
                null,
            )
            Log.d(TAG, "lock: call_log authority locked")
        } catch (e: Exception) {
            Log.d(TAG, "lock: call_log authority lock skipped (${e.message})")
        }
    }

    private fun unprotectPhone(context: Context, phoneNumber: String) {
        try {
            val extras = bundleOf("phone_number" to phoneNumber)
            context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "unprotect_phone",
                null,
                extras,
            )
            invalidateProtectedNumberCache("unprotect_phone")
        } catch (e: Exception) {
            Log.e(TAG, "unprotect_phone failed phoneNumber=${ProtectionLogRedaction.phone(phoneNumber)}", e)
            throw e
        }
    }

    private fun queryProviderIsPhoneProtected(context: Context, phoneNumber: String): Boolean {
        return try {
            val extras = bundleOf("phone_number" to phoneNumber)
            val result = context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "is_phone_protected",
                null,
                extras,
            )
            result?.getBoolean("is_protected", false) ?: false
        } catch (e: Exception) {
            Log.w(TAG, "is_phone_protected failed phoneNumber=${ProtectionLogRedaction.phone(phoneNumber)}", e)
            false
        }
    }

    private fun queryProviderPhonePin(context: Context, phoneNumber: String): String? {
        // PIN is not exposed via a public query API; only used internally by unlock_all_with_pin.
        return null
    }

    private fun numbersEqual(a: String, b: String): Boolean {
        if (a == b) return true
        return a.normalizePhoneNumber() == b.normalizePhoneNumber()
    }

    private fun getPhoneNumberForCallLogId(context: Context, callLogId: Int): String? {
        if (callLogId <= 0) return null
        val uri = ContentUris.withAppendedId(CallLog.Calls.CONTENT_URI, callLogId.toLong())
        context.contentResolver.query(uri, arrayOf(CallLog.Calls.NUMBER), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                return c.getString(0)?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    fun resolveRawContactIdsForCallLogIds(context: Context, callLogIds: List<Int>): List<Int> {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) return emptyList()
        val rawIds = LinkedHashSet<Int>()
        for (callLogId in callLogIds) {
            val number = getPhoneNumberForCallLogId(context, callLogId) ?: continue
            rawIds.addAll(resolveRawContactIdsForPhoneNumber(context, number))
        }
        return rawIds.toList()
    }

    private fun resolveRawContactIdsForPhoneNumber(context: Context, phoneNumber: String): List<Int> {
        val normalized = phoneNumber.normalizePhoneNumber()
        val lookupUri = android.net.Uri.withAppendedPath(
            PhoneLookup.CONTENT_FILTER_URI,
            android.net.Uri.encode(phoneNumber),
        )
        context.contentResolver.query(lookupUri, arrayOf(PhoneLookup._ID), null, null, null)?.use { lookup ->
            if (!lookup.moveToFirst()) return emptyList()
        } ?: return emptyList()

        val ids = LinkedHashSet<Int>()
        val selection = "${Phone.NUMBER} = ? OR ${Phone.NORMALIZED_NUMBER} = ?"
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Data.RAW_CONTACT_ID),
            selection,
            arrayOf(phoneNumber, normalized),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val rawId = cursor.getIntValue(Data.RAW_CONTACT_ID)
                if (rawId > 0) ids.add(rawId)
            }
        }
        return ids.toList()
    }
}
