package com.goodwy.commons.helpers

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.Log
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.util.LinkedHashSet
import java.util.Locale

private const val LOG_TAG_SIM_CONTACT_SAVE = "SimContactSave"

/**
 * RawContacts account types that are SIM/USIM (Icc ADN). Matches list/load logic elsewhere
 * (`contains("sim")` / `icc`); do not require the `".sim"` substring — some OEMs use `/sim` only.
 */
fun isSimAccountTypeForPersistence(accountType: String): Boolean {
    val typeLower = accountType.lowercase(Locale.getDefault())
    return typeLower.contains("sim") || typeLower.contains("icc")
}

private fun Context.loadActiveSubscriptionInfosSorted(): List<SubscriptionInfo> {
    val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        ?: return emptyList()
    return try {
        @SuppressLint("MissingPermission")
        sm.activeSubscriptionInfoList?.sortedBy { it.simSlotIndex } ?: emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }
}

private val defaultIccAdnUri: Uri = Uri.parse("content://icc/adn")

/** Same path shape as MediaTek `SubInfoUtils`: USIM uses PBR, classic SIM uses ADN. */
private fun buildIccAdnSubIdUri(subId: Int): Uri =
    Uri.parse("content://icc/adn").buildUpon()
        .appendPath("subId")
        .appendPath(subId.toString())
        .build()

private fun buildIccPbrSubIdUri(subId: Int): Uri =
    Uri.parse("content://icc/pbr").buildUpon()
        .appendPath("subId")
        .appendPath(subId.toString())
        .build()

/**
 * DialContact / MediaTek saves USIM contacts via [content://icc/pbr/subId/…], GSM via [content://icc/adn/subId/…].
 * We infer USIM from account strings when the platform does not expose MTK helpers.
 */
private fun accountHintsUsim(accountName: String, accountType: String): Boolean {
    val haystack = "$accountName $accountType".lowercase(Locale.getDefault())
    return haystack.contains("usim") || haystack.contains("uim") || haystack.contains("csim")
}

/**
 * Preferred Icc insert URI and an alternate (ADN ↔ PBR) for the same subscription when known.
 */
private fun Context.resolveIccInsertAttemptPair(accountName: String, accountType: String): Pair<Uri, Uri?> {
    val base = defaultIccAdnUri
    if (accountName.isBlank()) {
        Log.d(LOG_TAG_SIM_CONTACT_SAVE, "resolveIccInsertAttemptPair: blank accountName, using defaultUri=$base")
        return base to null
    }
    val subInfo = findSubscriptionInfoForSimAccount(accountName, accountType)
    if (subInfo == null) {
        Log.d(
            LOG_TAG_SIM_CONTACT_SAVE,
            "resolveIccInsertAttemptPair: no SubscriptionInfo for accountName=$accountName accountType=$accountType activeSubs=${
                loadActiveSubscriptionInfosSorted().map { "${it.subscriptionId}/slot${it.simSlotIndex}" }
            } defaultUri=$base",
        )
        return base to null
    }
    val subId = subInfo.subscriptionId
    if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
        Log.w(LOG_TAG_SIM_CONTACT_SAVE, "resolveIccInsertAttemptPair: INVALID_SUBSCRIPTION_ID for accountName=$accountName")
        return base to null
    }
    val adnScoped = buildIccAdnSubIdUri(subId)
    val pbrScoped = buildIccPbrSubIdUri(subId)
    val preferPbr = accountHintsUsim(accountName, accountType)
    val preferred = if (preferPbr) pbrScoped else adnScoped
    val alternate = if (preferPbr) adnScoped else pbrScoped
    Log.d(
        LOG_TAG_SIM_CONTACT_SAVE,
        "resolveIccInsertAttemptPair: preferred=$preferred alternate=$alternate subscriptionId=$subId simSlotIndex=${subInfo.simSlotIndex} preferPbr=$preferPbr accountName=$accountName accountType=$accountType",
    )
    return preferred to alternate
}

/**
 * ICC URIs to scan for SIM phonebook row counts (same bases as insert/update paths).
 */
fun Context.iccPhonebookQueryUrisForSimAccount(accountName: String, accountType: String): List<Uri> {
    val bases = LinkedHashSet<Uri>()
    val (preferred, alternate) = resolveIccInsertAttemptPair(accountName, accountType)
    bases.add(preferred)
    if (alternate != null && alternate != preferred) {
        bases.add(alternate)
    }
    if (!bases.contains(defaultIccAdnUri)) {
        bases.add(defaultIccAdnUri)
    }
    return bases.toList()
}

/**
 * Best-effort count of rows visible through the Icc ADN/PBR provider for this SIM account
 * (may not match [SimPhonebookContract] exactly; used as fallback / cross-check).
 */
fun Context.countIccPhonebookRowsForSimAccount(accountName: String, accountType: String): Int {
    var maxSeen = 0
    for (uri in iccPhonebookQueryUrisForSimAccount(accountName, accountType)) {
        try {
            contentResolver.query(uri, arrayOf("_id"), null, null, null)?.use { c ->
                maxSeen = maxOf(maxSeen, c.count)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG_SIM_CONTACT_SAVE, "countIccPhonebookRowsForSimAccount: query threw uri=$uri", e)
        }
    }
    return maxSeen
}

/** GSM SIM ADN entries use a short dialable number; strip formatting for provider compatibility. */
fun normalizeIccDialableNumber(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    if (digits.length > 40) return digits.takeLast(40)
    return digits
}

internal fun normalizeIccDisplayName(raw: String): String {
    val t = raw.trim()
    if (t.length <= 60) return t
    return t.take(60)
}

/**
 * Primary Icc insert URI (ADN for GSM SIM, PBR for USIM-style accounts when inferred from
 * [accountName]/[accountType]). Matches DialContact `SubInfoUtils.getIccProviderUri` behavior in spirit.
 * For inserts, [tryInsertIccAdnForSimContact] also tries the alternate ADN/PBR URI before legacy [defaultIccAdnUri].
 */
fun Context.resolveIccAdnInsertUri(accountName: String, accountType: String): Uri =
    resolveIccInsertAttemptPair(accountName, accountType).first

/**
 * Best-effort Icc insert after a SIM RawContact was written via [ContactsContract].
 * Tries subscription-scoped **PBR** (USIM-style) and **ADN** (GSM) URIs like DialContact
 * `SimEditProcessor.insertSIMContact` / `SubInfoUtils.getIccProviderUri`, then legacy [defaultIccAdnUri].
 *
 * @param logContext optional caller label (e.g. `insertContact rawId=42`) for logcat correlation.
 * @return true if the Icc provider returned a non-null result URI for any attempt.
 */
fun Context.tryInsertIccAdnForSimContact(
    accountName: String,
    accountType: String,
    number: String,
    displayName: String,
    logContext: String = "",
): Boolean {
    val prefix = if (logContext.isNotEmpty()) "[$logContext] " else ""
    val numStored = normalizeIccDialableNumber(number)
    val tagStored = normalizeIccDisplayName(displayName)
    Log.d(
        LOG_TAG_SIM_CONTACT_SAVE,
        "${prefix}Icc insert start accountName=$accountName accountType=$accountType numberLen=${numStored.length} tagLen=${tagStored.length} tag=${tagStored.take(80)}",
    )
    val cv = ContentValues().apply {
        put("number", numStored)
        put("tag", tagStored)
        put("name", tagStored)
    }
    val (primaryUri, alternateUri) = resolveIccInsertAttemptPair(accountName, accountType)
    val attemptUris = LinkedHashSet<Uri>()
    attemptUris.add(primaryUri)
    if (alternateUri != null && alternateUri != primaryUri) {
        attemptUris.add(alternateUri)
    }
    attemptUris.add(defaultIccAdnUri)

    for (uri in attemptUris) {
        try {
            val inserted = contentResolver.insert(uri, cv)
            if (inserted != null) {
                Log.d(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}Icc insert OK uri=$uri resultUri=$inserted")
                return true
            }
            Log.w(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}Icc insert returned null uri=$uri")
        } catch (e: Exception) {
            Log.e(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}Icc insert threw uri=$uri", e)
        }
    }
    return false
}

/**
 * After editing a SIM-backed raw contact in [ContactsContract], update the matching row in Icc ADN
 * when possible; otherwise insert a new ADN row (e.g. previous save never reached the SIM).
 */
fun Context.tryUpdateIccAdnAfterSimContactEdit(
    accountName: String,
    accountType: String,
    previousPrimaryNumber: String,
    previousDisplayName: String,
    newPrimaryNumber: String,
    newDisplayName: String,
    logContext: String = "",
): Boolean {
    val newNum = normalizeIccDialableNumber(newPrimaryNumber)
    val newTag = normalizeIccDisplayName(newDisplayName)
    if (newNum.isEmpty()) {
        Log.w(LOG_TAG_SIM_CONTACT_SAVE, "tryUpdateIccAdnAfterSimContactEdit: empty new number, skip")
        return false
    }
    val prefix = if (logContext.isNotEmpty()) "[$logContext] " else ""
    val oldNorm = normalizeIccDialableNumber(previousPrimaryNumber)
    val (preferredUri, alternateUri) = resolveIccInsertAttemptPair(accountName, accountType)
    val queryBases = LinkedHashSet<Uri>()
    queryBases.add(preferredUri)
    if (alternateUri != null && alternateUri != preferredUri) {
        queryBases.add(alternateUri)
    }
    if (!queryBases.contains(defaultIccAdnUri)) {
        queryBases.add(defaultIccAdnUri)
    }
    for (baseUri in queryBases) {
        try {
            contentResolver.query(baseUri, null, null, null, null)?.use { c ->
                val idCol = c.getColumnIndex("_id")
                val numCol = c.getColumnIndex("number").takeIf { it >= 0 }
                    ?: c.getColumnIndex("data1").takeIf { it >= 0 } ?: -1
                if (idCol < 0 || numCol < 0) {
                    Log.w(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}Icc query: missing columns id=$idCol num=$numCol uri=$baseUri")
                    return@use
                }
                while (c.moveToNext()) {
                    val rowNum = c.getString(numCol)?.let { normalizeIccDialableNumber(it) } ?: ""
                    if (oldNorm.isNotEmpty() && rowNum == oldNorm) {
                        val rowId = c.getLong(idCol)
                        val rowUri = ContentUris.withAppendedId(baseUri, rowId)
                        val cv = ContentValues().apply {
                            put("number", newNum)
                            put("tag", newTag)
                            put("name", newTag)
                        }
                        val n = try {
                            contentResolver.update(rowUri, cv, null, null)
                        } catch (e: Exception) {
                            Log.e(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}Icc update failed uri=$rowUri", e)
                            0
                        }
                        if (n > 0) {
                            Log.d(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}Icc update OK rows=$n uri=$rowUri")
                            return true
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}Icc query/update threw uri=$baseUri", e)
        }
    }
    Log.d(
        LOG_TAG_SIM_CONTACT_SAVE,
        "${prefix}ADN no matching row; inserting (oldNorm=$oldNorm prevName=${previousDisplayName.take(40)})",
    )
    return tryInsertIccAdnForSimContact(
        accountName,
        accountType,
        newPrimaryNumber,
        newDisplayName,
        logContext = "${logContext}_fallbackInsert",
    )
}

/**
 * Deletes the SIM ADN row at [indexInSim] for the subscription [mtkSubId]. Mirrors
 * DialContact's `SimDeleteProcessor`: `delete(simUri, "index = $indexInSim", null)` on the
 * subscription-scoped PBR/ADN URI.
 *
 * This is the **most reliable** SIM ADN delete path: it relies on the
 * `MtkContactsContract.RawContactsColumns.INDEX_IN_SIM` value the platform stamps onto the
 * `RawContacts` row when a SIM contact is loaded (no fuzzy phone-number matching needed).
 *
 * Returns true when the provider reports at least one row deleted.
 */
fun Context.tryDeleteIccAdnByMtkSimIndex(
    mtkSubId: Int,
    indexInSim: Int,
    accountName: String,
    accountType: String,
    logContext: String = "",
): Boolean {
    if (mtkSubId <= 0 || indexInSim <= 0) return false
    val prefix = if (logContext.isNotEmpty()) "[$logContext] " else ""

    val preferUsim = accountHintsUsim(accountName, accountType)
    val attemptUris = LinkedHashSet<Uri>()
    if (preferUsim) {
        attemptUris.add(buildIccPbrSubIdUri(mtkSubId))
        attemptUris.add(buildIccAdnSubIdUri(mtkSubId))
    } else {
        attemptUris.add(buildIccAdnSubIdUri(mtkSubId))
        attemptUris.add(buildIccPbrSubIdUri(mtkSubId))
    }

    for (uri in attemptUris) {
        try {
            val n = contentResolver.delete(uri, "index = $indexInSim", null)
            Log.i(
                LOG_TAG_SIM_CONTACT_SAVE,
                "${prefix}MTK Icc ADN delete by index=$indexInSim n=$n uri=$uri subId=$mtkSubId preferUsim=$preferUsim",
            )
            if (n > 0) return true
        } catch (e: Exception) {
            Log.w(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}MTK Icc ADN delete threw uri=$uri index=$indexInSim", e)
        }
    }
    Log.w(
        LOG_TAG_SIM_CONTACT_SAVE,
        "${prefix}MTK Icc ADN delete: no rows removed for subId=$mtkSubId index=$indexInSim accountName=$accountName",
    )
    return false
}

/**
 * Deletes exactly ONE SIM ADN entry matching [phoneNumber] (and optionally [displayName]).
 *
 * Matching: every URI is scanned and rows are scored by specificity (tag+number > number-only
 * > digit-suffix overlap). The highest-scoring row wins, so when the SIM has duplicates that
 * share a number but differ in tag (a common Korean / Cyrillic CJK SIM quirk), only the row
 * whose tag also matches the deleted contact is removed.
 *
 * Delete strategies are detailed on [deleteIccAdnRowByCursorValues]; in short the AOSP
 * `IccProvider` standard `"tag='X' AND number='Y'"` selection is preferred, with MediaTek's
 * `"index = N"` as fallback. The URI-append form is intentionally skipped — the AOSP URI
 * matcher rejects it.
 *
 * Returns true if at least one ADN row was deleted.
 */
fun Context.tryDeleteIccAdnForSimContact(
    accountName: String,
    accountType: String,
    phoneNumber: String,
    displayName: String = "",
    logContext: String = "",
): Boolean {
    val numNorm = normalizeIccDialableNumber(phoneNumber)
    val tagNorm = normalizeIccDisplayName(displayName)
    // Need at least one matching criterion.
    if (numNorm.isEmpty() && tagNorm.isEmpty()) {
        Log.w(LOG_TAG_SIM_CONTACT_SAVE, "tryDeleteIccAdnForSimContact: both number and name empty, skip")
        return false
    }
    val prefix = if (logContext.isNotEmpty()) "[$logContext] " else ""
    val (preferredUri, alternateUri) = resolveIccInsertAttemptPair(accountName, accountType)
    val queryBases = LinkedHashSet<Uri>()
    queryBases.add(preferredUri)
    if (alternateUri != null && alternateUri != preferredUri) queryBases.add(alternateUri)
    if (!queryBases.contains(defaultIccAdnUri)) queryBases.add(defaultIccAdnUri)

    // For each URI, score every cursor row and try the highest-scoring match first. Stops on
    // the first success — picks the *most specific* duplicate (e.g. when the SIM has two rows
    // sharing a number but with different tags, the row whose tag also matches wins, so we
    // don't kill the wrong duplicate). We only try candidates *tied at the top score*: if the
    // best match's delete fails, we don't silently fall through to a strictly less-specific
    // row (which would target a different SIM contact with the same number).
    for (baseUri in queryBases) {
        val candidates = collectIccDeleteCandidates(baseUri, numNorm, tagNorm, prefix)
        if (candidates.isEmpty()) continue
        val topScore = candidates.maxOf { it.score }
        for (cand in candidates.filter { it.score == topScore }) {
            if (deleteIccAdnRowByCursorValues(baseUri, cand, prefix)) return true
        }
    }
    Log.w(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}Icc ADN delete: no match num=$numNorm name=${displayName.take(40)}")
    return false
}

private data class IccDeleteCandidate(
    val rowId: Long,
    val tagRaw: String,
    val numRaw: String,
    /** Higher = more specific match. tag+number > number-only > suffix; see [collectIccDeleteCandidates]. */
    val score: Int,
)

/**
 * Scans [baseUri], returning every row that could plausibly be the SIM ADN entry for the
 * deleted RawContact, scored so the caller can try the most specific match first.
 *
 * Score guide:
 *  - 4: exact normalised number AND tag match (most specific — wins over duplicates).
 *  - 3: exact normalised number, no usable tag.
 *  - 2: digit-suffix overlap (≥ 7) AND tag match.
 *  - 1: digit-suffix overlap only.
 *  - 2: tag match when no number is known.
 */
private fun Context.collectIccDeleteCandidates(
    baseUri: Uri,
    numNorm: String,
    tagNorm: String,
    prefix: String,
): List<IccDeleteCandidate> {
    val out = mutableListOf<IccDeleteCandidate>()
    try {
        contentResolver.query(baseUri, null, null, null, null)?.use { c ->
            val idCol = c.getColumnIndex("_id")
            val numCol = c.getColumnIndex("number").takeIf { it >= 0 }
                ?: c.getColumnIndex("data1").takeIf { it >= 0 } ?: -1
            val tagCol = c.getColumnIndex("tag").takeIf { it >= 0 }
                ?: c.getColumnIndex("name").takeIf { it >= 0 } ?: -1
            if (idCol < 0) {
                Log.w(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}Icc query: no _id col uri=$baseUri")
                return@use
            }
            while (c.moveToNext()) {
                val rowNumRaw = if (numCol >= 0) c.getString(numCol) ?: "" else ""
                val rowTagRaw = if (tagCol >= 0) c.getString(tagCol)?.trim() ?: "" else ""
                val rowNumNorm = normalizeIccDialableNumber(rowNumRaw)
                val tagMatch = tagNorm.isNotEmpty() && rowTagRaw.equals(tagNorm, ignoreCase = true)
                val numMatchExact = numNorm.isNotEmpty() && rowNumNorm == numNorm
                val numMatchSuffix = !numMatchExact && numNorm.length >= 7 && rowNumNorm.length >= 7 &&
                    (rowNumNorm.endsWith(numNorm) || numNorm.endsWith(rowNumNorm))
                val score = when {
                    numMatchExact && tagMatch -> 4
                    numMatchExact -> 3
                    numMatchSuffix && tagMatch -> 2
                    numMatchSuffix -> 1
                    numNorm.isEmpty() && tagMatch -> 2
                    else -> 0
                }
                if (score > 0) {
                    out.add(IccDeleteCandidate(c.getLong(idCol), rowTagRaw, rowNumRaw, score))
                }
            }
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}Icc ADN query threw uri=$baseUri", e)
    }
    return out
}

/**
 * Tries every known ADN-delete strategy on [cand], in order of compatibility:
 *
 *  1. **AOSP IccProvider** standard — `delete(uri, "tag='X' AND number='Y'", null)`. The
 *     provider parses the WHERE clause literally (no `?` parameter binding!), splits on
 *     uppercase `"AND"`, then on `"="`, and strips one pair of single quotes from each value.
 *     Used by Samsung / AOSP / most stock USIM / GSM SIM stacks. Any single quote in the tag
 *     is stripped beforehand because the parser doesn't handle SQL-style escaping.
 *  2. **MediaTek-style** — `delete(uri, "index = N", null)`. Some MTK derivatives accept this
 *     even though stock AOSP rejects unknown columns with `UnsupportedOperationException`.
 *
 * The URI-append form (`content://icc/.../<id>`) is intentionally NOT tried — Samsung's
 * URI matcher rejects it with `UnsupportedOperationException("Cannot insert into URL ...")`,
 * which only generates noise (see `contact_sim_log.txt`).
 *
 * Negative return values from `delete()` are vendor error codes (Samsung uses `-10` for "ADN
 * record not found / busy") and are treated as failure — only `n > 0` counts as success.
 */
private fun Context.deleteIccAdnRowByCursorValues(
    baseUri: Uri,
    cand: IccDeleteCandidate,
    prefix: String,
): Boolean {
    Log.d(
        LOG_TAG_SIM_CONTACT_SAVE,
        "${prefix}Icc ADN deleting rowId=${cand.rowId} tag=${cand.tagRaw} num=${cand.numRaw} score=${cand.score} uri=$baseUri",
    )

    val safeTag = cand.tagRaw.replace("'", "")
    val safeNum = cand.numRaw.replace("'", "")
    val whereTagAndNumber = when {
        safeTag.isNotEmpty() && safeNum.isNotEmpty() -> "tag='$safeTag' AND number='$safeNum'"
        safeNum.isNotEmpty() -> "number='$safeNum'"
        safeTag.isNotEmpty() -> "tag='$safeTag'"
        else -> null
    }
    if (whereTagAndNumber != null) {
        try {
            val n = contentResolver.delete(baseUri, whereTagAndNumber, null)
            Log.i(
                LOG_TAG_SIM_CONTACT_SAVE,
                "${prefix}Icc ADN delete by tag/number n=$n where=\"$whereTagAndNumber\" uri=$baseUri",
            )
            if (n > 0) return true
        } catch (e: Exception) {
            Log.w(
                LOG_TAG_SIM_CONTACT_SAVE,
                "${prefix}Icc ADN delete by tag/number threw where=\"$whereTagAndNumber\" uri=$baseUri",
                e,
            )
        }
    }

    try {
        val n = contentResolver.delete(baseUri, "index = ${cand.rowId}", null)
        Log.i(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}Icc ADN delete by index=${cand.rowId} n=$n uri=$baseUri")
        if (n > 0) return true
    } catch (e: Exception) {
        Log.w(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}Icc ADN delete by index threw rowId=${cand.rowId}", e)
    }

    return false
}

/**
 * Ensures exactly one ADN entry exists in the SIM phonebook for [phoneNumber]/[displayName]
 * after a merge — removes all duplicate entries, keeping one.
 *
 * Strategy:
 * 1. Collect all row IDs matching [phoneNumber].
 * 2. If ≤ 1 match, nothing to do.
 * 3. Delete extras by row ID (precise, deletes N-1 entries).
 * 4. If row-ID delete yields n==0 for all candidates (provider quirk), fall back to
 *    selection-based "delete ALL + re-insert ONE", which is safe here because we
 *    explicitly re-insert the keeper's data immediately after.
 *
 * Returns the number of duplicate ADN entries removed.
 */
fun Context.deduplicateIccAdnForMerge(
    accountName: String,
    accountType: String,
    phoneNumber: String,
    displayName: String,
    logContext: String = "",
): Int {
    val numNorm = normalizeIccDialableNumber(phoneNumber)
    val tagNorm = normalizeIccDisplayName(displayName)
    if (numNorm.isEmpty()) return 0

    val prefix = if (logContext.isNotEmpty()) "[$logContext] " else ""
    val (preferredUri, alternateUri) = resolveIccInsertAttemptPair(accountName, accountType)
    val queryBases = LinkedHashSet<Uri>()
    queryBases.add(preferredUri)
    if (alternateUri != null && alternateUri != preferredUri) queryBases.add(alternateUri)
    if (!queryBases.contains(defaultIccAdnUri)) queryBases.add(defaultIccAdnUri)

    for (baseUri in queryBases) {
        // Collect all row IDs with a matching normalised number.
        val matchingIds = mutableListOf<Long>()
        try {
            contentResolver.query(baseUri, null, null, null, null)?.use { c ->
                val idCol = c.getColumnIndex("_id")
                val numCol = c.getColumnIndex("number").takeIf { it >= 0 }
                    ?: c.getColumnIndex("data1").takeIf { it >= 0 } ?: -1
                if (idCol < 0 || numCol < 0) return@use
                while (c.moveToNext()) {
                    val rowNum = c.getString(numCol)?.let { normalizeIccDialableNumber(it) } ?: ""
                    if (rowNum == numNorm) matchingIds.add(c.getLong(idCol))
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}dedup query threw uri=$baseUri", e)
        }

        Log.d(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}dedup found ${matchingIds.size} ADN entries for num=$numNorm uri=$baseUri")
        if (matchingIds.size <= 1) return 0

        // Strategy 1: delete extras by index selection (mirrors DialContact's "index = X" approach).
        val toDelete = matchingIds.drop(1)
        var deletedByIndex = 0
        for (rowId in toDelete) {
            try {
                val n = contentResolver.delete(baseUri, "index = $rowId", null)
                if (n > 0) { deletedByIndex++; continue }
            } catch (e: Exception) {
                Log.e(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}dedup delete by index threw rowId=$rowId", e)
            }
            // Sub-fallback: URI-append.
            try {
                val rowUri = ContentUris.withAppendedId(baseUri, rowId)
                val n = contentResolver.delete(rowUri, null, null)
                if (n > 0) deletedByIndex++
                Log.i(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}dedup delete by uri rowId=$rowId n=$n")
            } catch (e: Exception) {
                Log.e(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}dedup delete by uri threw rowId=$rowId", e)
            }
        }
        if (deletedByIndex > 0) {
            Log.i(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}dedup removed $deletedByIndex duplicate(s) for num=$numNorm")
            return deletedByIndex
        }

        // Strategy 2: selection-based — delete ALL matching, then re-insert exactly one.
        // Used when the provider ignores row-ID deletes (some USIM/OEM implementations).
        try {
            val deletedAll = contentResolver.delete(baseUri, "tag=? AND number=?", arrayOf(tagNorm, numNorm))
            if (deletedAll > 0) {
                Log.i(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}dedup deleted $deletedAll by selection, re-inserting keeper")
                tryInsertIccAdnForSimContact(accountName, accountType, phoneNumber, displayName,
                    logContext = "${logContext}_dedupReinsert")
                return (deletedAll - 1).coerceAtLeast(0)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG_SIM_CONTACT_SAVE, "${prefix}dedup selection-delete threw uri=$baseUri", e)
        }
    }
    return 0
}

/**
 * Resolves the active [SubscriptionInfo] for a SIM RawContacts account (e.g. numeric sub id or "USIM3").
 * Returns null if not a SIM account or no matching subscription.
 *
 * When [accountType] is empty (e.g. cache not yet rebuilt), matching still proceeds using subscription id /
 * slot heuristics so telephony-backed label/tint stay in sync with [SubscriptionManager].
 */
fun Context.findSubscriptionInfoForSimAccount(accountName: String, accountType: String): SubscriptionInfo? {
    if (accountName.isBlank()) {
        return null
    }
    if (accountType.isNotEmpty() && !isSimAccountTypeForPersistence(accountType)) {
        return null
    }
    val infos = loadActiveSubscriptionInfosSorted()
    if (infos.isEmpty()) {
        return null
    }

    val an = accountName.trim()

    accountName.trim().toIntOrNull()?.let { subId ->
        infos.find { it.subscriptionId == subId }?.let { return it }
    }

    infos.forEach { info ->
        if (an == info.subscriptionId.toString()) {
            return info
        }
    }

    Regex("(\\d+)$").find(an)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { n ->
        infos.find { it.subscriptionId == n }?.let { return it }
        infos.find { it.simSlotIndex == n }?.let { return it }
        infos.getOrNull(n - 1)?.let { return it }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        infos.forEach { info ->
            val icc = info.iccId?.trim().orEmpty()
            if (icc.isNotEmpty() && (an.contains(icc, ignoreCase = true) || icc.contains(an, ignoreCase = true))) {
                return info
            }
        }
    }

    infos.forEach { info ->
        val disp = info.displayName?.toString()?.trim().orEmpty()
        val carrier = info.carrierName?.toString()?.trim().orEmpty()
        if (disp.isNotEmpty() && an.equals(disp, ignoreCase = true)) {
            return info
        }
        if (carrier.isNotEmpty() && an.equals(carrier, ignoreCase = true)) {
            return info
        }
    }

    if (infos.size == 1) {
        return infos.first()
    }
    return null
}

/**
 * 1-based SIM slot number for UI (1 = first SIM slot, 2 = second, …) from
 * [SubscriptionInfo.getSimSlotIndex] (0-based hardware index) after matching this SIM
 * [android.provider.ContactsContract.RawContacts] account to an active subscription.
 *
 * Uses [SubscriptionManager] / telephony — not the ordering of entries in [ContactsHelper.getDeviceContactSources].
 * Returns 0 if the subscription cannot be resolved.
 */
fun Context.resolveSimSlotNumber1BasedFromTelephony(accountName: String, accountType: String): Int {
    val info = findSubscriptionInfoForSimAccount(accountName, accountType) ?: return 0
    val idx = info.simSlotIndex
    if (idx < 0) {
        return 0
    }
    return idx + 1
}

/**
 * Maps a SIM RawContacts account (e.g. "USIM3") to a human-readable label: subscription display
 * name (user-set in system SIM settings), then carrier / operator name, then the original account name.
 */
fun Context.resolveSimAccountDisplayName(accountName: String, accountType: String): String {
    if (accountName.isBlank()) {
        return accountName
    }
    if (accountType.isNotEmpty() && !isSimAccountTypeForPersistence(accountType)) {
        return accountName
    }
    val info = findSubscriptionInfoForSimAccount(accountName, accountType)
        ?: return accountName
    return subscriptionInfoDisplayLabel(info, accountName)
}

private fun iconTintFromSubscriptionInfo(info: SubscriptionInfo): Int? {
    val tint = info.iconTint
    if (tint == 0 || Color.alpha(tint) == 0) {
        return null
    }
    return tint
}

/**
 * Subscription highlight color from [SubscriptionInfo.getIconTint] (system telephony / SIM UI color).
 * Returns null if unknown, transparent, or not applicable.
 */
fun Context.resolveSimAccountIconTint(accountName: String, accountType: String): Int? {
    val info = findSubscriptionInfoForSimAccount(accountName, accountType) ?: return null
    return iconTintFromSubscriptionInfo(info)
}

/**
 * Live [SubscriptionInfo.getIconTint] for a 1-based SIM index (1 = first slot), using the current
 * [SubscriptionManager] list. Use when account-name mapping fails but [simCardIndex] is known.
 */
fun Context.resolveSimAccountIconTintForSimCardIndex(simCardIndex: Int): Int? {
    if (simCardIndex !in 1..2) return null
    val infos = loadActiveSubscriptionInfosSorted()
    if (infos.isEmpty()) return null
    val wantSlot = simCardIndex - 1
    val info = infos.firstOrNull { it.simSlotIndex == wantSlot }
        ?: infos.getOrNull(wantSlot)
    return info?.let { iconTintFromSubscriptionInfo(it) }
}

private fun Context.subscriptionInfoDisplayLabel(info: SubscriptionInfo, fallback: String): String {
    val display = info.displayName?.toString()?.trim().orEmpty()
    if (display.isNotEmpty()) {
        return display
    }
    val carrier = info.carrierName?.toString()?.trim().orEmpty()
    if (carrier.isNotEmpty()) {
        return carrier
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        @SuppressLint("MissingPermission")
        val tm = getSystemService(TelephonyManager::class.java)
            ?.createForSubscriptionId(info.subscriptionId)
        val op = tm?.simOperatorName?.trim().orEmpty()
        if (op.isNotEmpty()) {
            return op
        }
    }
    return fallback
}
