package com.goodwy.commons.helpers

import android.content.Context
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import androidx.core.os.bundleOf
import com.goodwy.commons.extensions.hasPermission
import java.util.concurrent.atomic.AtomicLong

/**
 * Result of [unlockAllWithPin] / `query_by_pin`: count of unlocked items and associated data.
 *
 * [rawContactIds] and [phoneNumbers] are stored in-session for the supplementary contact load.
 * [callIds], [callDates], [callNumbers] are the PIN-matched recents rows — available for
 * pre-caching so callers don't need a separate call-log query.
 */
data class UnlockResult(
    val count: Int,
    val rawContactIds: LongArray?,
    val phoneNumbers: Array<String>? = null,
    val contactIds: LongArray? = null,
    val callIds: LongArray? = null,
    val callDates: LongArray? = null,
    val callNumbers: Array<String>? = null,
) {
    override fun equals(other: Any?): Boolean =
        (other as? UnlockResult)?.let { count == it.count && rawContactIds.contentEquals(it.rawContactIds) } ?: false
    override fun hashCode(): Int = 31 * count + (rawContactIds?.contentHashCode() ?: 0)
}

/**
 * Test helper for ContactProvider protection API (set_protected / unprotect).
 * Uses PIN "1080" for testing. Tracks protected raw contact IDs in SharedPreferences
 * so we can toggle unprotect after the contact is hidden from queries.
 */
object ContactProtectionHelper {

    private const val TAG = "ContactProtection"
    private const val FLOW_TAG = "ProtectionFlow"

    private const val PREFS_NAME = "contact_protection_test"
    private const val KEY_PROTECTED_RAW_IDS = "protected_raw_ids"

    /**
     * Protected `rawContactId:aggregateContactId` pairs.
     *
     * [KEY_PROTECTED_RAW_IDS] holds RAW contact ids. Recents rows carry an AGGREGATE contact id
     * ([com.goodwy.commons.models.contacts.Contact.contactId], not `.id`), so comparing one against
     * the other silently hid unprotected calls whenever the two id spaces happened to collide.
     * This parallel set records the aggregate id captured at protect time — before the provider
     * hides the row, at which point it can no longer be resolved by a normal query.
     *
     * Absent for contacts protected by older builds; callers must treat a miss as "unknown", not
     * "not protected", and fall through to the authoritative provider check.
     */
    private const val KEY_PROTECTED_RAW_TO_CONTACT = "protected_raw_to_contact"
    private const val TEST_PIN = "1080"

    @Volatile
    private var stalePrefsPruned: Boolean = false

    /**
     * Bumped whenever the unlock session identity changes (unlock, failed unlock, lock). Threads
     * record the generation they last unlocked their Binder connection for, so a stale memo can
     * never outlive the session it belongs to. See [ensureUnlockedForThread].
     */
    private val sessionGeneration = AtomicLong(0L)

    private val threadUnlockGeneration = ThreadLocal<Long>()

    /** True after [unlockAllWithPin], false after [lock] or app start. Used to avoid showing "Unprotect" for reused raw contact IDs. */
    @Volatile
    private var unlockedInSession: Boolean = false

    /**
     * The PIN used for the most recent successful [unlockAllWithPin] call, or null when locked.
     * Kept so background threads can re-call unlock_all_with_pin on their own Binder connection
     * if that turns out to be necessary.
     */
    @Volatile
    private var sessionPin: String? = null

    /**
     * Raw contact IDs returned by the most recent [unlockAllWithPin] call.
     * Used by [ContactsHelper.getDeviceContacts] to load unlocked contacts directly from
     * [ContactsContract.Contacts.CONTENT_URI], which respects the unlock state, when
     * [ContactsContract.Data.CONTENT_URI] does not (provider limitation).
     */
    @Volatile
    private var unlockedRawContactIds: LongArray? = null

    /**
     * Phone numbers returned by the most recent [unlockAllWithPin] call (protected recents).
     */
    @Volatile
    private var unlockedPhoneNumbers: Array<String>? = null

    fun getUnlockedRawContactIds(): LongArray? = unlockedRawContactIds

    fun getUnlockedPhoneNumbers(): Array<String>? = unlockedPhoneNumbers

    fun getSessionPin(): String? = sessionPin

    /**
     * Whether this raw contact should appear in the current session.
     * Unprotected rows are always visible; protected rows only when unlocked with a matching PIN.
     */
    fun isVisibleInCurrentSession(context: Context, rawContactId: Int): Boolean {
        if (!isProtected(context, rawContactId)) return true
        if (!unlockedInSession) {
            Log.d(TAG, "isVisibleInCurrentSession rawContactId=$rawContactId visible=false (locked)")
            return false
        }
        val unlocked = unlockedRawContactIds ?: return false
        val visible = unlocked.contains(rawContactId.toLong())
        if (!visible) {
            Log.d(TAG, "isVisibleInCurrentSession rawContactId=$rawContactId visible=false"
                    + " (protected, not in unlocked set)")
        }
        return visible
    }

    private fun getProtectedIds(context: Context): MutableSet<Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_PROTECTED_RAW_IDS, null) ?: emptySet()
        return set.mapNotNull { it.toIntOrNull() }.toMutableSet()
    }

    /**
     * Returns all app-tracked protected raw contact IDs in a single SharedPreferences read.
     * Use this to bulk-filter a list of contacts instead of calling [isProtected] per-contact
     * (which would do N SharedPreferences loads + N provider IPC calls ≈ 2s for 5000 contacts).
     */
    fun getProtectedIdSet(context: Context): Set<Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_PROTECTED_RAW_IDS, null)
            ?.mapNotNullTo(HashSet()) { it.toIntOrNull() }
            ?: emptySet()
    }

    private fun setProtectedIds(context: Context, ids: Set<Int>) {
        // commit() so getProtectedIdSet() sees the update before UI refresh on the next line.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_PROTECTED_RAW_IDS, ids.map { it.toString() }.toSet())
            .commit()
        // Every protect/unprotect path funnels through here, so this is where the recents
        // per-number protection memo has to be dropped.
        CallLogProtectionHelper.invalidateProtectedNumberCache("setProtectedIds")
    }

    private fun getProtectedIdPairs(context: Context): MutableSet<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return (prefs.getStringSet(KEY_PROTECTED_RAW_TO_CONTACT, null) ?: emptySet()).toMutableSet()
    }

    private fun setProtectedIdPairs(context: Context, pairs: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_PROTECTED_RAW_TO_CONTACT, pairs)
            .commit()
        CallLogProtectionHelper.invalidateProtectedNumberCache("setProtectedIdPairs")
    }

    /**
     * Aggregate contact ids of app-tracked protected contacts.
     *
     * Compare this against `RecentCall.contactID` / [com.goodwy.commons.models.contacts.Contact.contactId].
     * Use [getProtectedIdSet] for RAW contact ids ([com.goodwy.commons.models.contacts.Contact.id]).
     * Mixing the two hides the wrong rows.
     *
     * Incomplete by design: contacts protected before this set existed are missing from it, so a
     * miss means "don't know" — always fall through to a provider check rather than treating it as
     * unprotected.
     */
    fun getProtectedAggregateContactIdSet(context: Context): Set<Int> =
        getProtectedIdPairs(context).mapNotNullTo(HashSet()) { pair ->
            pair.substringAfter(':', "").toIntOrNull()?.takeIf { it > 0 }
        }

    /**
     * Resolves a raw contact's aggregate contact id.
     *
     * Must be called BEFORE the provider marks the contact protected — afterwards the row is
     * filtered out of queries and this returns null.
     */
    /**
     * Raw contact ids belonging to [aggregateContactId], newest-first order not guaranteed.
     *
     * The protect APIs take `RawContacts._ID`, but the ids the contact list carries are not
     * reliably raw ids: the display cache stores `primaryRawId`, and every producer of that field
     * falls back to the *aggregate* id when the raw lookup misses — see
     * `ContactsMetadataLoader.loadForSummaries` and the five `?: entity.contactId` sites in
     * `ContactsSyncManager`. Passing one of those straight to `set_protected` asks the provider to
     * find a `raw_contacts` row that does not exist, and it answers `success=false`; worse, when
     * the number happens to match an unrelated raw contact it protects the wrong person.
     *
     * Resolving through the aggregate id removes the guess. It also makes protect symmetric with
     * `unprotect`, which the provider already applies to every raw contact of the aggregate.
     */
    fun rawContactIdsForAggregate(context: Context, aggregateContactId: Int): List<Int> {
        if (aggregateContactId <= 0) return emptyList()
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) return emptyList()
        return try {
            val ids = LinkedHashSet<Int>()
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(aggregateContactId.toString()),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getInt(0).takeIf { it > 0 }?.let { ids.add(it) }
                }
            }
            ids.toList()
        } catch (e: Exception) {
            Log.w(TAG, "rawContactIdsForAggregate failed contactId=$aggregateContactId", e)
            emptyList()
        }
    }

    /** True when [rawContactId] really is a row in `raw_contacts`. */
    fun isRealRawContactId(context: Context, rawContactId: Int): Boolean {
        if (rawContactId <= 0) return false
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) return false
        return try {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(rawContactId.toString()),
                null,
            )?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "isRealRawContactId failed rawContactId=$rawContactId", e)
            false
        }
    }

    /**
     * Raw contact ids to protect for a list entry carrying [aggregateContactId] and [listId].
     *
     * Prefers the aggregate, because [listId] may be an aggregate id wearing a raw id's name (see
     * [rawContactIdsForAggregate]). Falls back to [listId] only once it is confirmed to be a real
     * raw contact row, so a wrong id yields nothing to protect rather than the wrong contact.
     */
    fun protectableRawContactIds(context: Context, aggregateContactId: Int, listId: Int): List<Int> {
        val fromAggregate = rawContactIdsForAggregate(context, aggregateContactId)
        if (fromAggregate.isNotEmpty()) return fromAggregate
        if (isRealRawContactId(context, listId)) return listOf(listId)
        Log.w(
            FLOW_TAG,
            "protectableRawContactIds: no raw contacts for contactId=$aggregateContactId"
                + " listId=$listId (neither resolves to a raw_contacts row)",
        )
        return emptyList()
    }

    private fun queryAggregateContactId(context: Context, rawContactId: Int): Int? {
        return try {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.CONTACT_ID),
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(rawContactId.toString()),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0).takeIf { it > 0 } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryAggregateContactId failed rawContactId=$rawContactId", e)
            null
        }
    }

    /** Records the raw -> aggregate pairing for [rawContactIds]. Call before protecting them. */
    private fun rememberAggregateContactIds(context: Context, rawContactIds: List<Int>) {
        if (rawContactIds.isEmpty()) return
        val pairs = getProtectedIdPairs(context)
        var changed = false
        rawContactIds.forEach { rawId ->
            val contactId = queryAggregateContactId(context, rawId) ?: return@forEach
            // Drop any previous pairing for this raw id (re-aggregation may have moved it).
            pairs.removeAll { it.substringBefore(':').toIntOrNull() == rawId }
            pairs.add("$rawId:$contactId")
            changed = true
        }
        if (changed) {
            setProtectedIdPairs(context, pairs)
        }
    }

    /** Drops the raw -> aggregate pairings for [rawContactIds]. */
    private fun forgetAggregateContactIds(context: Context, rawContactIds: List<Int>) {
        if (rawContactIds.isEmpty()) return
        val pairs = getProtectedIdPairs(context)
        val ids = rawContactIds.toSet()
        val removed = pairs.removeAll { pair ->
            val rawId = pair.substringBefore(':').toIntOrNull()
            rawId != null && ids.contains(rawId)
        }
        if (removed) {
            setProtectedIdPairs(context, pairs)
        }
    }

    /**
     * Unprotect the contact (when already protected). Use from list/unlock view long-press menu.
     * For "Protect", use [protectContact] with PIN from dialog.
     *
     * Proceeds when the provider or the app tracking set marks the raw contact protected, so
     * unprotect still works after app-data clear or other prefs/provider drift.
     */
    fun unprotectContact(context: Context, rawContactId: Int): Boolean {
        val protected = getProtectedIds(context)
        val trackedInPrefs = protected.contains(rawContactId)
        val protectedInProvider = queryProviderIsProtected(context, rawContactId)
        if (!trackedInPrefs && !protectedInProvider) return false
        Log.d(TAG, "unprotect from unlock view (list): rawContactId=$rawContactId"
                + " trackedInPrefs=$trackedInPrefs providerProtected=$protectedInProvider"
                + " -> calling provider unprotect")
        unprotect(context, rawContactId)
        protected.remove(rawContactId)
        setProtectedIds(context, protected)
        forgetAggregateContactIds(context, listOf(rawContactId))
        // Remove from the in-memory unlocked set so the supplementary Contacts.CONTENT_URI
        // load in getDeviceContacts doesn't re-add this contact on the next refresh.
        val currentIds = unlockedRawContactIds
        if (currentIds != null) {
            unlockedRawContactIds = currentIds.filter { it != rawContactId.toLong() }.toLongArray()
        }
        Log.d(TAG, "unprotect from unlock view: done, removed from tracking set and unlockedRawContactIds")
        return true
    }

    /**
     * Protect the raw contact with the given PIN. Call after user enters PIN in dialog.
     * Also adds to app's tracking set so "Unprotect" is available.
     */
    fun protectContact(context: Context, rawContactId: Int, pin: String) {
        val trimmedPin = pin.trim()
        if (trimmedPin.isEmpty()) return
        Log.d(TAG, "protectContact: rawContactId=$rawContactId"
                + " space=${ProtectionLogRedaction.space(trimmedPin)}")
        // Capture the aggregate id first: once protected, the provider hides the row from queries.
        rememberAggregateContactIds(context, listOf(rawContactId))
        val success = setProtected(context, rawContactId, trimmedPin)
        if (!success) {
            Log.w(TAG, "protectContact: provider set_protected failed rawContactId=$rawContactId")
            forgetAggregateContactIds(context, listOf(rawContactId))
            return
        }
        val protected = getProtectedIds(context)
        protected.add(rawContactId)
        setProtectedIds(context, protected)
        logContactPinVerify(context, rawContactId, trimmedPin, "protectContact")
    }

    private fun logContactPinVerify(context: Context, rawContactId: Int, expectedPin: String, reason: String) {
        val fromProvider = queryProviderIsProtected(context, rawContactId)
        val expectedSpace = ProtectionLogRedaction.space(expectedPin)
        Log.i(FLOW_TAG, "contactPinVerify reason=$reason rawContactId=$rawContactId"
                + " expectedSpace=$expectedSpace providerIsProtected=$fromProvider")
        Log.d(TAG, "contactPinVerify: rawContactId=$rawContactId expectedSpace=$expectedSpace"
                + " providerIsProtected=$fromProvider")
    }

    /** Ask the provider to log all saved contact/phone PIN rows (logcat tag ProtectedContacts). */
    fun logProviderPinSnapshot(context: Context, reason: String) {
        Log.i(FLOW_TAG, "logProviderPinSnapshot reason=$reason")
        try {
            val extras = bundleOf("reason" to reason)
            context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "dump_protected_pins",
                null,
                extras,
            )
        } catch (e: Exception) {
            Log.w(FLOW_TAG, "logProviderPinSnapshot failed reason=$reason", e)
        }
    }

    /**
     * Protect multiple raw contacts in a single provider call using `set_protected_many`.
     * Updates the app's tracking set for all IDs.
     */
    fun protectMany(context: Context, rawContactIds: List<Int>, pin: String) {
        val trimmedPin = pin.trim()
        if (trimmedPin.isEmpty() || rawContactIds.isEmpty()) return
        Log.d(TAG, "set_protected_many: rawContactIds=$rawContactIds"
                + " space=${ProtectionLogRedaction.space(trimmedPin)}")
        // Capture aggregate ids first: once protected, the provider hides these rows from queries.
        rememberAggregateContactIds(context, rawContactIds)
        try {
            val extras = bundleOf(
                "raw_contact_ids" to rawContactIds.map { it.toLong() }.toLongArray(),
                "pin" to trimmedPin
            )
            val result = context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "set_protected_many",
                null,
                extras
            )
            // The provider reports success/updated_count and this used to discard both, so a
            // refusal (blocked call, ids not found) still added every id to the tracking set and
            // only surfaced later as the caller's "unknown error" over an unprotected contact.
            // protectContact() has always checked its result; this is the batch path catching up.
            val success = result?.getBoolean("success", false) ?: false
            val updated = result?.getInt("updated_count", 0) ?: 0
            val error = result?.getString("error")
            Log.d(TAG, "set_protected_many returned success=$success updated=$updated"
                    + (error?.let { " error=$it" } ?: ""))
            if (!success) {
                Log.e(FLOW_TAG, "set_protected_many refused by provider:"
                        + " rawContactIds=$rawContactIds updated=$updated"
                        + (error?.let { " error=$it" } ?: "")
                        + " (ids must be RawContacts._ID, not Contacts._ID)")
                forgetAggregateContactIds(context, rawContactIds)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "set_protected_many failed", e)
            forgetAggregateContactIds(context, rawContactIds)
            throw e
        }
        val protected = getProtectedIds(context)
        protected.addAll(rawContactIds)
        setProtectedIds(context, protected)
        Log.i(FLOW_TAG, "contactProtectMany rawContactIds=$rawContactIds"
                + " space=${ProtectionLogRedaction.space(trimmedPin)}")
        logProviderPinSnapshot(context, "after set_protected_many")
    }

    /**
     * Unprotect multiple raw contacts in a single provider call using `unprotect_many`.
     * Updates the app's tracking set and in-memory unlocked list for all IDs.
     */
    fun unprotectMany(context: Context, rawContactIds: List<Int>) {
        if (rawContactIds.isEmpty()) return
        Log.d(TAG, "unprotect_many: rawContactIds=$rawContactIds")
        try {
            val extras = bundleOf(
                "raw_contact_ids" to rawContactIds.map { it.toLong() }.toLongArray()
            )
            context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "unprotect_many",
                null,
                extras
            )
            Log.d(TAG, "unprotect_many returned (no exception)")
        } catch (e: Exception) {
            Log.e(TAG, "unprotect_many failed", e)
            throw e
        }
        val protected = getProtectedIds(context)
        rawContactIds.forEach { protected.remove(it) }
        setProtectedIds(context, protected)
        forgetAggregateContactIds(context, rawContactIds)
        // Remove from the in-memory unlocked set so the supplementary load doesn't re-add them
        val currentIds = unlockedRawContactIds
        if (currentIds != null) {
            val removeSet = rawContactIds.map { it.toLong() }.toSet()
            unlockedRawContactIds = currentIds.filter { it !in removeSet }.toLongArray()
        }
        Log.d(TAG, "unprotect_many: tracking set and unlockedRawContactIds updated for ${rawContactIds.size} ids")
    }

    fun setProtected(context: Context, rawContactId: Int, pin: String): Boolean {
        val trimmedPin = pin.trim()
        if (trimmedPin.isEmpty()) return false
        val extras = bundleOf("pin" to trimmedPin)
        Log.d(TAG, "set_protected -> provider rawContactId=$rawContactId"
                + " space=${ProtectionLogRedaction.space(trimmedPin)}")
        return try {
            val result = context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "set_protected",
                rawContactId.toString(),
                extras
            )
            val success = result?.getBoolean("success", false) ?: false
            // Distinguish the three ways this comes back false, otherwise the caller can only say
            // "failed": a null Bundle means the provider does not implement the method at all, an
            // "error" key means it refused deliberately, and neither means it ran and found
            // nothing to protect.
            val reason = when {
                result == null -> "no response (provider does not implement set_protected)"
                success -> ""
                else -> result.getString("error") ?: "provider found no row for this id"
            }
            Log.d(TAG, "set_protected <- provider success=$success rawContactId=$rawContactId"
                    + " space=${ProtectionLogRedaction.space(trimmedPin)}"
                    + if (reason.isEmpty()) "" else " reason=$reason")
            success
        } catch (e: Exception) {
            Log.e(TAG, "set_protected failed rawContactId=$rawContactId", e)
            throw e
        }
    }

    fun unprotect(context: Context, rawContactId: Int) {
        Log.d(TAG, "unprotect calling provider rawContactId=$rawContactId authority=${ContactsContract.AUTHORITY_URI}")
        try {
            val extras = bundleOf("raw_contact_id" to rawContactId.toLong())
            context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "unprotect",
                rawContactId.toString(),
                extras
            )
            Log.d(TAG, "unprotect returned (no exception)")
        } catch (e: Exception) {
            Log.e(TAG, "unprotect failed rawContactId=$rawContactId", e)
            throw e
        }
    }

    /** Whether this raw contact is protected (app tracking and/or provider DB). */
    fun isProtected(context: Context, rawContactId: Int): Boolean {
        if (getProtectedIds(context).contains(rawContactId)) {
            Log.d(TAG, "isProtected: rawContactId=$rawContactId true (prefs)")
            return true
        }
        val fromProvider = queryProviderIsProtected(context, rawContactId)
        if (fromProvider) {
            Log.d(TAG, "isProtected: rawContactId=$rawContactId true (provider)")
        }
        return fromProvider
    }

    private fun queryProviderIsProtected(context: Context, rawContactId: Int): Boolean =
        queryProviderIsProtectedOrNull(context, rawContactId) ?: false

    /**
     * Provider protection state, or null when the provider could not be consulted.
     *
     * [pruneStaleProtectedIds] must not treat an unreachable provider as "not protected" — that
     * would wipe the whole tracking set and unhide every protected contact.
     */
    private fun queryProviderIsProtectedOrNull(context: Context, rawContactId: Int): Boolean? {
        return try {
            val extras = bundleOf("raw_contact_id" to rawContactId.toLong())
            val result = context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "is_raw_contact_protected",
                null,
                extras,
            ) ?: return null
            if (!result.containsKey("is_protected")) return null
            result.getBoolean("is_protected", false)
        } catch (e: Exception) {
            Log.w(TAG, "is_raw_contact_protected failed rawContactId=$rawContactId", e)
            null
        }
    }

    /**
     * Drops tracked ids the provider no longer reports as protected.
     *
     * Contacts deleted while protected (the stale-account sweep used to do exactly this) left ids
     * behind in prefs forever. Because [isProtected] answers from prefs before asking the provider,
     * those ghosts keep hiding contacts and recents that no longer exist. Runs once per process.
     *
     * Call off the main thread: it makes one provider IPC per tracked id.
     *
     * @return number of stale ids removed
     */
    fun pruneStaleProtectedIds(context: Context): Int {
        if (stalePrefsPruned) return 0
        // While unlocked the tracking set is intentionally in flux; wait for a clean session.
        if (unlockedInSession) return 0
        stalePrefsPruned = true

        val tracked = getProtectedIds(context)
        if (tracked.isEmpty()) return 0

        val stale = tracked.filter { rawId ->
            // null (provider unreachable) must not count as stale.
            queryProviderIsProtectedOrNull(context, rawId) == false
        }
        if (stale.isEmpty()) return 0

        val remaining = tracked.toMutableSet().apply { removeAll(stale.toSet()) }
        setProtectedIds(context, remaining)
        forgetAggregateContactIds(context, stale)
        Log.w(TAG, "pruneStaleProtectedIds: dropped ${stale.size} tracked id(s) the provider no"
                + " longer reports as protected: $stale")
        return stale.size
    }

    /** Call when contact list is received and we are locked. Removes any listed raw contact IDs from the tracking set so reused IDs (e.g. new contacts) don't show "Unprotect". */
    fun removeTrackingIdsThatAppearInList(context: Context, rawContactIdsInList: List<Int>) {
        if (unlockedInSession) return
        val protected = getProtectedIds(context)
        val idsToRemove = rawContactIdsInList.filter { protected.contains(it) }
        if (idsToRemove.isEmpty()) return
        idsToRemove.forEach { protected.remove(it) }
        setProtectedIds(context, protected)
        forgetAggregateContactIds(context, idsToRemove)
        Log.d(TAG, "removeTrackingIdsThatAppearInList (locked): removed stale ids $idsToRemove so new/reused contacts show Protect")
    }

    fun isUnlockedInSession(): Boolean = unlockedInSession

    /** Test PIN used for protect/unprotect (for display on detail screen). */
    fun getTestPin(): String = TEST_PIN

    /**
     * Unlock all protected raw contacts that use the given PIN for the calling UID.
     * After this, queries will include those contacts until [lock] is called or the process ends.
     * @return [UnlockResult] with count and unlocked raw_contact_ids (so UI can refresh and display them)
     */
    fun unlockAllWithPin(context: Context, pin: String): UnlockResult {
        val trimmedPin = pin.trim()
        if (trimmedPin.isEmpty()) {
            Log.w(TAG, "unlockAllWithPin: pin is empty")
            return UnlockResult(0, null)
        }
        unlockedInSession = true
        sessionPin = trimmedPin
        unlockedRawContactIds = null
        unlockedPhoneNumbers = null
        sessionGeneration.incrementAndGet()
        CallLogProtectionHelper.invalidateProtectedNumberCache("sessionGeneration")
        Log.d(TAG, "unlockAllWithPin -> provider query_by_pin"
                + " space=${ProtectionLogRedaction.space(trimmedPin)}")
        // Prefer query_by_pin(unlock=true): same session unlock as unlock_all_with_pin, plus
        // display names / lookup keys / call-log row metadata for callers that want to pre-cache.
        // Fall back to unlock_all_with_pin when query_by_pin fails (older provider builds, or
        // transient call-log merge errors after set_protected stamped rows).
        try {
            val result = context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "query_by_pin",
                null,
                bundleOf("pin" to trimmedPin, "unlock" to true),
            )
            return applyUnlockBundle(context, trimmedPin, result, source = "query_by_pin")
        } catch (e: Exception) {
            Log.e(
                TAG,
                "query_by_pin failed; falling back to unlock_all_with_pin"
                    + " space=${ProtectionLogRedaction.space(trimmedPin)}",
                e,
            )
        }
        return unlockAllWithPinLegacy(context, trimmedPin)
    }

    private fun unlockAllWithPinLegacy(context: Context, trimmedPin: String): UnlockResult {
        Log.d(TAG, "unlockAllWithPin -> provider unlock_all_with_pin"
                + " space=${ProtectionLogRedaction.space(trimmedPin)}")
        return try {
            val result = context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "unlock_all_with_pin",
                null,
                bundleOf("pin" to trimmedPin),
            )
            applyUnlockBundle(context, trimmedPin, result, source = "unlock_all_with_pin")
        } catch (e: Exception) {
            Log.e(TAG, "unlock_all_with_pin failed space=${ProtectionLogRedaction.space(trimmedPin)}", e)
            unlockedInSession = false
            sessionPin = null
            unlockedRawContactIds = null
            unlockedPhoneNumbers = null
            sessionGeneration.incrementAndGet()
            CallLogProtectionHelper.invalidateProtectedNumberCache("sessionGeneration")
            throw e
        }
    }

    private fun applyUnlockBundle(
        context: Context,
        trimmedPin: String,
        result: android.os.Bundle?,
        source: String,
    ): UnlockResult {
        val bundleCount = result?.getInt("unlocked_count", -1) ?: -1
        val rawIds = result?.getLongArray("raw_contact_ids")
        val phoneNumbers = result?.getStringArray("phone_numbers")
            ?.filterNot { it.isNullOrEmpty() }
            ?.toTypedArray()
        val contactIds = result?.getLongArray("contact_ids")
        val callIds = result?.getLongArray("call_ids")
        val callDates = result?.getLongArray("call_dates")
        val callNumbers = result?.getStringArray("call_numbers")
        val idsSize = rawIds?.size ?: 0
        val phonesSize = phoneNumbers?.size ?: 0
        val effectiveCount = when {
            bundleCount > 0 -> bundleCount
            idsSize + phonesSize > 0 -> idsSize + phonesSize
            else -> 0
        }
        Log.d(
            TAG,
            "unlockAllWithPin <- $source space=${ProtectionLogRedaction.space(trimmedPin)}"
                + " unlocked_count=$bundleCount"
                + " raw_contact_ids=$idsSize phone_numbers=$phonesSize"
                + " call_ids=${callIds?.size ?: 0} effectiveCount=$effectiveCount"
                + " ids=${rawIds?.contentToString()}"
                + " phones=${ProtectionLogRedaction.phones(phoneNumbers)}",
        )
        if (effectiveCount == 0) {
            // Not a warning: entering normal space unlocks the normal-space PIN, under which
            // nothing is protected by definition, so zero matches is the expected result and this
            // fired on every single normal-mode entry. Whether zero is suspicious depends on which
            // space the caller is entering, which this helper cannot see.
            Log.d(
                TAG,
                "unlockAllWithPin: no contacts or phones matched"
                    + " space=${ProtectionLogRedaction.space(trimmedPin)}"
                    + " (expected for the normal-space PIN; if this was a secure space, check"
                    + " protected_contact_pins / protected_phone_pins via"
                    + " logcat -s ProtectedContacts ProtectedContactManager)",
            )
        }
        unlockedRawContactIds = rawIds
        unlockedPhoneNumbers = phoneNumbers
        Log.i(
            FLOW_TAG,
            "unlockAllWithPin source=$source space=${ProtectionLogRedaction.space(trimmedPin)}"
                + " effectiveCount=$effectiveCount"
                + " rawContactIds=${rawIds?.contentToString()}"
                + " phoneNumbers=${ProtectionLogRedaction.phones(phoneNumbers)}",
        )
        logProviderPinSnapshot(
            context,
            "after unlockAllWithPin space=${ProtectionLogRedaction.space(trimmedPin)} source=$source",
        )
        return UnlockResult(
            count = effectiveCount,
            rawContactIds = rawIds,
            phoneNumbers = phoneNumbers,
            contactIds = contactIds,
            callIds = callIds,
            callDates = callDates,
            callNumbers = callNumbers,
        )
    }

    /**
     * Re-establishes the unlock state on the **current thread's** Binder connection.
     * The provider tracks unlock state per Binder connection rather than per UID, so a
     * background thread that was not the one to call [unlockAllWithPin] must call this
     * before querying the provider, otherwise protected contacts remain hidden.
     *
     * Safe to call even when not unlocked (no-op in that case).
     */
    fun ensureUnlockedForThread(context: Context) {
        val pin = sessionPin ?: return
        if (!unlockedInSession) return
        // The provider tracks unlock state per Binder connection, so this is per thread -- but it
        // is per thread *once* per session, not per call. Re-running the round trip on every call
        // cost hundreds of provider IPCs during a single recents enrichment pass. The generation
        // is bumped on every session change, so a thread that memoized an older one unlocks again.
        val generation = sessionGeneration.get()
        if (threadUnlockGeneration.get() == generation) return
        Log.d(TAG, "ensureUnlockedForThread: re-establishing unlock on background thread (pinLength=${pin.length})")
        try {
            val extras = bundleOf("pin" to pin)
            val result = context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "unlock_all_with_pin",
                null,
                extras
            )
            // Only update in-memory state when the provider returns non-null values.
            // If the provider returns null (e.g., called before the initial unlock has propagated),
            // preserve the existing values so the secure-mode query path is not incorrectly cleared.
            val rawIds = result?.getLongArray("raw_contact_ids")
            val phones = result?.getStringArray("phone_numbers")
            if (rawIds != null) unlockedRawContactIds = rawIds
            if (phones != null) unlockedPhoneNumbers = phones
            // Only after the provider call succeeds, so a failure retries on the next call.
            threadUnlockGeneration.set(generation)
            Log.d(TAG, "ensureUnlockedForThread: done rawIds=${rawIds?.size ?: "null"} phones=${phones?.size ?: "null"}")
        } catch (e: Exception) {
            Log.e(TAG, "ensureUnlockedForThread failed", e)
        }
    }

    /**
     * Clear the calling UID's unlock state. Protected contacts become invisible again
     * until [unlockAllWithPin] is called.
     */
    fun lock(context: Context) {
        unlockedInSession = false
        sessionPin = null
        unlockedRawContactIds = null
        unlockedPhoneNumbers = null
        // The provider drops this UID's unlock state, so every thread must unlock again even if
        // the next session uses the same PIN.
        sessionGeneration.incrementAndGet()
        CallLogProtectionHelper.invalidateProtectedNumberCache("sessionGeneration")
        Log.d(TAG, "lock -> provider (clear unlock session)")
        try {
            context.contentResolver.call(
                ContactsContract.AUTHORITY_URI,
                "lock",
                null,
                null
            )
            Log.d(TAG, "lock <- provider ok; protected contacts hidden until next unlockAllWithPin")
        } catch (e: Exception) {
            Log.e(TAG, "lock failed", e)
            throw e
        }
    }
}
