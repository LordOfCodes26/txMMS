package com.goodwy.commons.helpers

import android.content.Context
import android.os.Build
import android.provider.SimPhonebookContract
import android.telephony.SubscriptionManager
import android.util.Log
import com.goodwy.commons.R

private const val TAG = "SimAdnCapacity"

/**
 * When [SimPhonebookContract] does not report capacity, assume at most this many total ADN-style
 * entries for overflow heuristics (many classic SIMs are 200–250; USIMs are often higher — we only warn here).
 */
private const val HEURISTIC_MAX_SIM_PHONEBOOK_ENTRIES = 250

data class SimAdnCapacityEstimate(
    /** From [SimPhonebookContract.ElementaryFiles.MAX_RECORDS], if available. */
    val maxSlots: Int?,
    /** From [SimPhonebookContract.ElementaryFiles.RECORD_COUNT], if available. */
    val usedSlotsReported: Int?,
    /** Row count from Icc ADN/PBR queries ([countIccPhonebookRowsForSimAccount]). */
    val usedSlotsIccCount: Int,
)

private data class SimPhonebookAdnRow(val maxRecords: Int, val recordCount: Int)

fun Context.estimateSimAdnCapacityForSimAccount(accountName: String, accountType: String): SimAdnCapacityEstimate {
    val iccUsed = countIccPhonebookRowsForSimAccount(accountName, accountType)
    if (accountName.isBlank()) {
        return SimAdnCapacityEstimate(maxSlots = null, usedSlotsReported = null, usedSlotsIccCount = iccUsed)
    }
    val subInfo = findSubscriptionInfoForSimAccount(accountName, accountType)
    val subId = subInfo?.subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
    if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
        return SimAdnCapacityEstimate(maxSlots = null, usedSlotsReported = null, usedSlotsIccCount = iccUsed)
    }
    val row = readAdnElementaryFileFromSimPhonebookContract(subId)
    return SimAdnCapacityEstimate(
        maxSlots = row?.maxRecords,
        usedSlotsReported = row?.recordCount,
        usedSlotsIccCount = iccUsed,
    )
}

private fun Context.readAdnElementaryFileFromSimPhonebookContract(subId: Int): SimPhonebookAdnRow? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return null
    }
    return try {
        val uri = SimPhonebookContract.ElementaryFiles.CONTENT_URI
        val subCol = SimPhonebookContract.ElementaryFiles.SUBSCRIPTION_ID
        val maxCol = SimPhonebookContract.ElementaryFiles.MAX_RECORDS
        val recCol = SimPhonebookContract.ElementaryFiles.RECORD_COUNT
        val efCol = SimPhonebookContract.ElementaryFiles.EF_TYPE
        val projection = arrayOf(maxCol, recCol, efCol)
        contentResolver.query(
            uri,
            projection,
            "$subCol = ?",
            arrayOf(subId.toString()),
            null,
        )?.use { c ->
            val ixMax = c.getColumnIndex(maxCol)
            val ixRec = c.getColumnIndex(recCol)
            val ixEf = c.getColumnIndex(efCol)
            if (ixMax < 0 || ixRec < 0) {
                return@use null
            }
            val hasEf = ixEf >= 0
            // IccConstants.EF_ADN — elementary file for phonebook names/numbers.
            val efAdn = 0x6F3A
            var best: SimPhonebookAdnRow? = null
            while (c.moveToNext()) {
                if (hasEf) {
                    val ef = c.getInt(ixEf)
                    if (ef != efAdn) {
                        continue
                    }
                }
                val maxR = c.getInt(ixMax)
                if (maxR <= 0) {
                    continue
                }
                val rec = c.getInt(ixRec).coerceIn(0, maxR)
                val row = SimPhonebookAdnRow(maxR, rec)
                if (best == null || row.maxRecords > best.maxRecords) {
                    best = row
                }
            }
            // If EF column existed but no ADN row matched, fall back to the largest max_records row.
            if (best == null && hasEf && c.moveToFirst()) {
                do {
                    val maxR = c.getInt(ixMax)
                    if (maxR <= 0) {
                        continue
                    }
                    val rec = c.getInt(ixRec).coerceIn(0, maxR)
                    val row = SimPhonebookAdnRow(maxR, rec)
                    if (best == null || row.maxRecords > best.maxRecords) {
                        best = row
                    }
                } while (c.moveToNext())
            }
            best
        }
    } catch (e: Exception) {
        Log.w(TAG, "readAdnElementaryFileFromSimPhonebookContract failed subId=$subId", e)
        null
    }
}

/**
 * Returns a localized warning when [movingCount] is larger than estimated free ADN slots; null if no warning.
 */
fun Context.simSlotCapacityOverflowMessage(
    destinationAccountName: String,
    destinationAccountType: String,
    movingCount: Int,
): String? {
    if (movingCount <= 0) return null
    if (!isSimAccountTypeForPersistence(destinationAccountType)) return null
    val est = estimateSimAdnCapacityForSimAccount(destinationAccountName, destinationAccountType)
    val usedReported = est.usedSlotsReported
    val used = when {
        usedReported != null && est.usedSlotsIccCount > 0 ->
            maxOf(usedReported, est.usedSlotsIccCount)
        usedReported != null -> usedReported
        else -> est.usedSlotsIccCount
    }
    val max = est.maxSlots
    val free = max?.let { (it - used).coerceAtLeast(0) }
    return when {
        free != null && movingCount > free ->
            getString(R.string.sim_migration_exceeds_free_slots, free, used, max, movingCount)
        free == null && movingCount > (HEURISTIC_MAX_SIM_PHONEBOOK_ENTRIES - used).coerceAtLeast(0) ->
            getString(
                R.string.sim_migration_may_exceed_heuristic_capacity,
                used,
                HEURISTIC_MAX_SIM_PHONEBOOK_ENTRIES,
                movingCount,
            )
        else -> null
    }
}
