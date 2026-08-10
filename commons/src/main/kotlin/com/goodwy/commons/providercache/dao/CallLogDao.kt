package com.goodwy.commons.providercache.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.goodwy.commons.providercache.entities.CallLogEntity

@Dao
interface CallLogDao {

    @Query("SELECT COUNT(*) FROM call_log_entries")
    suspend fun getCount(): Int

    @Query("SELECT * FROM call_log_entries ORDER BY start_ts DESC LIMIT :limit")
    suspend fun getFirstEntries(limit: Int): List<CallLogEntity>

    /**
     * Newest calls of one [android.provider.CallLog.Calls] type.
     *
     * The recents call-type filter groups *these* rows rather than filtering built groups — a
     * group key never includes the call type, so a group mixes types and its representative row
     * would answer for all of them. Filtering in SQL (not after [getFirstEntries]) matters:
     * `limit` must bound the rows that survive the filter, or a rare type returns nothing from a
     * viewport-sized page.
     */
    @Query("SELECT * FROM call_log_entries WHERE type = :type ORDER BY start_ts DESC LIMIT :limit")
    suspend fun getFirstEntriesOfType(type: Int, limit: Int): List<CallLogEntity>

    /**
     * [getFirstEntries] without the calls in [excludedCallIds] — the protected ones.
     *
     * Excluding here rather than dropping built rows afterwards is what makes the surviving group
     * correct: its head, its timestamp, its count and therefore its date section are all computed
     * from the calls that remain. A filter applied after grouping cannot fix any of those — the row
     * has already been built, sectioned and ordered around a call that must not be shown.
     *
     * Ids rather than numbers because the provider decides protection with PHONE_NUMBERS_EQUAL
     * under its own strictness settings. Approximating that comparison in SQL would be a second
     * source of truth; resolving to ids first inherits it exactly.
     */
    @Query(
        """
        SELECT * FROM call_log_entries
        WHERE call_id NOT IN (:excludedCallIds)
        ORDER BY start_ts DESC LIMIT :limit
        """,
    )
    suspend fun getFirstEntriesExcluding(
        excludedCallIds: List<Int>,
        limit: Int,
    ): List<CallLogEntity>

    /** [getFirstEntriesOfType] combined with [getFirstEntriesExcluding]. */
    @Query(
        """
        SELECT * FROM call_log_entries
        WHERE type = :type AND call_id NOT IN (:excludedCallIds)
        ORDER BY start_ts DESC LIMIT :limit
        """,
    )
    suspend fun getFirstEntriesOfTypeExcluding(
        type: Int,
        excludedCallIds: List<Int>,
        limit: Int,
    ): List<CallLogEntity>

    @Query("SELECT * FROM call_log_entries WHERE call_id IN (:ids)")
    suspend fun getByCallIds(ids: List<Int>): List<CallLogEntity>

    @Query("SELECT call_id FROM call_log_entries WHERE call_id IN (:ids)")
    suspend fun getCallIdsPresent(ids: List<Int>): List<Int>

    @Query("SELECT call_id FROM call_log_entries WHERE call_id IN (:ids) AND type = :type")
    suspend fun getCallIdsOfTypeIn(ids: List<Int>, type: Int): List<Int>

    @Query("SELECT call_id FROM call_log_entries WHERE contact_id = :contactId")
    suspend fun getCallIdsByContactId(contactId: Int): List<Int>

    @Query(
        """
        SELECT call_id FROM call_log_entries
        WHERE ${CallLogGroupKeySql.EXPR} IN (:numbers)
        """,
    )
    suspend fun getCallIdsByPhoneNumbers(numbers: List<String>): List<Int>

    @Query(
        """
        SELECT c.*, g.call_count, g.grouped_ids,
            c.cached_photo_uri AS resolved_photo_uri,
            COALESCE(c.contact_id, 0) AS resolved_contact_id
        FROM call_log_entries c
        JOIN (
            SELECT
                ${CallLogGroupKeySql.EXPR} AS group_key,
                MAX(start_ts) AS max_ts,
                COUNT(*) AS call_count,
                GROUP_CONCAT(call_id) AS grouped_ids
            FROM call_log_entries
            GROUP BY group_key
        ) g ON (
            ${CallLogGroupKeySql.EXPR_C} = g.group_key
            AND c.start_ts = g.max_ts
            AND c.call_id = ${CallLogGroupKeySql.HEAD_CALL_ID_AT_MAX_TS}
        )
        ORDER BY c.start_ts DESC
        """,
    )
    suspend fun getGroupedEntries(): List<GroupedCallLogEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<CallLogEntity>)

    @Query("DELETE FROM call_log_entries")
    suspend fun clearAll()

    @Query(
        """
        DELETE FROM call_log_entries
        WHERE call_id NOT IN (
            SELECT call_id FROM call_log_entries ORDER BY start_ts DESC LIMIT :keepCount
        )
        """,
    )
    suspend fun trimToMostRecent(keepCount: Int)

    @Query(
        """
        SELECT c.*, g.call_count, g.grouped_ids,
            c.cached_photo_uri AS resolved_photo_uri,
            COALESCE(c.contact_id, 0) AS resolved_contact_id
        FROM call_log_entries c
        JOIN (
            SELECT
                ${CallLogGroupKeySql.EXPR} AS group_key,
                MAX(start_ts) AS max_ts,
                COUNT(*) AS call_count,
                GROUP_CONCAT(call_id) AS grouped_ids
            FROM call_log_entries
            GROUP BY group_key
        ) g ON (
            ${CallLogGroupKeySql.EXPR_C} = g.group_key
            AND c.start_ts = g.max_ts
            AND c.call_id = ${CallLogGroupKeySql.HEAD_CALL_ID_AT_MAX_TS}
        )
        ORDER BY c.start_ts DESC
        """,
    )
    suspend fun getGroupedEntriesByNumber(): List<GroupedCallLogEntry>

    @Query(
        """
        UPDATE call_log_entries
        SET
            cached_name = CASE
                WHEN cached_name = '' THEN COALESCE((
                    SELECT cs.display_name
                    FROM contact_phone_index cpi
                    JOIN contact_summaries cs ON cs.contact_id = cpi.contact_id
                    WHERE cpi.normalized_number = call_log_entries.normalized_number
                       OR (LENGTH(call_log_entries.normalized_number) >= 7
                           AND cpi.digits LIKE '%' || LTRIM(call_log_entries.normalized_number, '0'))
                    LIMIT 1
                ), '')
                ELSE cached_name
            END,
            cached_photo_uri = CASE
                WHEN cached_photo_uri = '' THEN COALESCE((
                    SELECT cs.photo_thumbnail_uri
                    FROM contact_phone_index cpi
                    JOIN contact_summaries cs ON cs.contact_id = cpi.contact_id
                    WHERE cpi.normalized_number = call_log_entries.normalized_number
                       OR (LENGTH(call_log_entries.normalized_number) >= 7
                           AND cpi.digits LIKE '%' || LTRIM(call_log_entries.normalized_number, '0'))
                    LIMIT 1
                ), '')
                ELSE cached_photo_uri
            END,
            contact_id = CASE
                WHEN contact_id IS NULL THEN (
                    SELECT cpi.contact_id
                    FROM contact_phone_index cpi
                    WHERE cpi.normalized_number = call_log_entries.normalized_number
                       OR (LENGTH(call_log_entries.normalized_number) >= 7
                           AND cpi.digits LIKE '%' || LTRIM(call_log_entries.normalized_number, '0'))
                    LIMIT 1
                )
                ELSE contact_id
            END
        """,
    )
    suspend fun backfillContactInfo()

    @Query(
        """
        UPDATE call_log_entries
        SET
            cached_name = COALESCE((
                SELECT cs.display_name
                FROM contact_phone_index cpi
                JOIN contact_summaries cs ON cs.contact_id = cpi.contact_id
                WHERE cpi.contact_id IN (:contactIds)
                  AND (cpi.normalized_number = call_log_entries.normalized_number
                       OR (LENGTH(call_log_entries.normalized_number) >= 7
                           AND cpi.digits LIKE '%' || LTRIM(call_log_entries.normalized_number, '0')))
                LIMIT 1
            ), cached_name),
            cached_photo_uri = COALESCE((
                SELECT cs.photo_thumbnail_uri
                FROM contact_phone_index cpi
                JOIN contact_summaries cs ON cs.contact_id = cpi.contact_id
                WHERE cpi.contact_id IN (:contactIds)
                  AND (cpi.normalized_number = call_log_entries.normalized_number
                       OR (LENGTH(call_log_entries.normalized_number) >= 7
                           AND cpi.digits LIKE '%' || LTRIM(call_log_entries.normalized_number, '0')))
                LIMIT 1
            ), cached_photo_uri),
            contact_id = COALESCE((
                SELECT cpi.contact_id
                FROM contact_phone_index cpi
                WHERE cpi.contact_id IN (:contactIds)
                  AND (cpi.normalized_number = call_log_entries.normalized_number
                       OR (LENGTH(call_log_entries.normalized_number) >= 7
                           AND cpi.digits LIKE '%' || LTRIM(call_log_entries.normalized_number, '0')))
                LIMIT 1
            ), contact_id)
        WHERE EXISTS (
            SELECT 1 FROM contact_phone_index cpi
            WHERE cpi.contact_id IN (:contactIds)
              AND (cpi.normalized_number = call_log_entries.normalized_number
                   OR (LENGTH(call_log_entries.normalized_number) >= 7
                       AND cpi.digits LIKE '%' || LTRIM(call_log_entries.normalized_number, '0')))
        )
        """,
    )
    suspend fun backfillContactInfoForIds(contactIds: List<Int>)

    /**
     * Detaches contact info from call-log rows that still point at [contactIds] but no longer match
     * any of those contacts' phone numbers.
     *
     * Every `backfillContactInfo*` query is fill-only — `WHEN cached_name = '' THEN <lookup> ELSE
     * cached_name`, `WHEN contact_id IS NULL THEN <lookup> ELSE contact_id`. That makes attaching a
     * contact to a call a one-way door: remove a number from a contact and the call-log row keeps
     * the old `cached_name` and `contact_id` forever, so every downstream rebuild faithfully
     * reproduces the stale name in Recents.
     *
     * `backfillContactInfoForIds` cannot cover this either: its `WHERE EXISTS` requires a matching
     * `contact_phone_index` row, which is precisely what a removed number no longer has, so the
     * affected row is never visited.
     *
     * The match predicate here is a deliberate copy of the one in those queries. It has to be — if
     * it were looser, this would detach rows that legitimately still match.
     *
     * Run this *before* the backfill so a number that has genuinely moved to another contact is
     * re-attached to the correct one in the same pass.
     */
    @Query(
        """
        UPDATE call_log_entries
        SET
            cached_name = '',
            cached_photo_uri = '',
            contact_id = NULL
        WHERE contact_id IN (:contactIds)
          AND NOT EXISTS (
              SELECT 1
              FROM contact_phone_index cpi
              WHERE cpi.contact_id = call_log_entries.contact_id
                AND (cpi.normalized_number = call_log_entries.normalized_number
                     OR (LENGTH(call_log_entries.normalized_number) >= 7
                         AND cpi.digits LIKE '%' || LTRIM(call_log_entries.normalized_number, '0')))
          )
        """,
    )
    suspend fun clearStaleContactInfoForIds(contactIds: List<Int>): Int

    /**
     * Unscoped form of [clearStaleContactInfoForIds], for the repair paths.
     *
     * Needed because the scoped version only fires while handling a contact change. A row that went
     * stale before this existed — or through any path that did not emit a change event — would stay
     * stale forever, since nothing else ever detaches contact info. This makes a full repair
     * self-healing instead.
     *
     * The `EXISTS (SELECT 1 FROM contact_phone_index)` guard is load-bearing, not defensive noise.
     * Repairs run at startup, and if the phone index has not populated yet then every row looks
     * unmatched — without the guard this would strip the cached name from the entire call log and
     * show raw numbers until a later sync refilled them. Scoping by contact does not need the same
     * guard: there, an empty result genuinely means that contact has no phones left.
     */
    @Query(
        """
        UPDATE call_log_entries
        SET
            cached_name = '',
            cached_photo_uri = '',
            contact_id = NULL
        WHERE contact_id IS NOT NULL
          AND EXISTS (SELECT 1 FROM contact_phone_index)
          AND NOT EXISTS (
              SELECT 1
              FROM contact_phone_index cpi
              WHERE cpi.contact_id = call_log_entries.contact_id
                AND (cpi.normalized_number = call_log_entries.normalized_number
                     OR (LENGTH(call_log_entries.normalized_number) >= 7
                         AND cpi.digits LIKE '%' || LTRIM(call_log_entries.normalized_number, '0')))
          )
        """,
    )
    suspend fun clearStaleContactInfo(): Int

    @Query(
        """
        UPDATE call_log_entries
        SET contact_id = NULL,
            cached_name = phone_number,
            cached_photo_uri = ''
        WHERE contact_id IN (:contactIds)
        """,
    )
    suspend fun clearContactInfoForContactIds(contactIds: List<Int>)

    @Query(
        """
        UPDATE call_log_entries
        SET contact_id = NULL,
            cached_name = phone_number,
            cached_photo_uri = ''
        WHERE ${CallLogGroupKeySql.EXPR} IN (:phoneNumbers)
        """,
    )
    suspend fun clearContactInfoForPhoneNumbers(phoneNumbers: List<String>)

    @Query(
        """
        UPDATE call_log_entries
        SET contact_id = NULL,
            cached_name = phone_number,
            cached_photo_uri = ''
        """,
    )
    suspend fun clearAllContactInfo()

    @Query(
        """
        UPDATE call_log_entries
        SET contact_id = :contactId,
            cached_name = :displayName,
            cached_photo_uri = :photoUri
        WHERE contact_id IS NULL
          AND ${CallLogGroupKeySql.EXPR} IN (:phoneNumbers)
        """,
    )
    suspend fun reassignContactInfoForPhoneNumber(
        phoneNumbers: List<String>,
        contactId: Int,
        displayName: String,
        photoUri: String,
    )

    @Query(
        """
        SELECT COUNT(*) FROM call_log_entries
        WHERE ${CallLogGroupKeySql.EXPR} = :groupKey
        """,
    )
    suspend fun getCallCountForGroupKey(groupKey: String): Int

    @Query(
        """
        SELECT GROUP_CONCAT(call_id) FROM call_log_entries
        WHERE ${CallLogGroupKeySql.EXPR} = :groupKey
        """,
    )
    suspend fun getGroupedCallIdsForGroupKey(groupKey: String): String?

    @Query(
        """
        SELECT COUNT(*) FROM call_log_entries
        WHERE normalized_number = :normalizedNumber
           OR phone_number = :phoneNumber
        """,
    )
    suspend fun getCallCountForPhone(normalizedNumber: String, phoneNumber: String): Int

    @Query(
        """
        SELECT GROUP_CONCAT(call_id) FROM call_log_entries
        WHERE normalized_number = :normalizedNumber
           OR phone_number = :phoneNumber
        """,
    )
    suspend fun getGroupedCallIdsForPhone(normalizedNumber: String, phoneNumber: String): String?

    @Query("SELECT MAX(start_ts) FROM call_log_entries")
    suspend fun getMaxStartTimestamp(): Long?

    @Query("SELECT MAX(call_id) FROM call_log_entries")
    suspend fun getMaxCallId(): Int?

    @Query("SELECT call_id FROM call_log_entries")
    suspend fun getAllCallIds(): List<Int>

    @Query("DELETE FROM call_log_entries WHERE call_id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)

    @Query(
        """
        SELECT DISTINCT ${CallLogGroupKeySql.EXPR} AS group_key
        FROM call_log_entries
        WHERE call_id IN (:ids)
        """,
    )
    suspend fun getGroupKeysForCallIds(ids: List<Int>): List<String>

    @Query(
        """
        SELECT c.*, g.call_count, g.grouped_ids,
            c.cached_photo_uri AS resolved_photo_uri,
            COALESCE(c.contact_id, 0) AS resolved_contact_id
        FROM call_log_entries c
        JOIN (
            SELECT
                ${CallLogGroupKeySql.EXPR} AS group_key,
                MAX(start_ts) AS max_ts,
                COUNT(*) AS call_count,
                GROUP_CONCAT(call_id) AS grouped_ids
            FROM call_log_entries
            GROUP BY group_key
            HAVING group_key IN (:groupKeys)
        ) g ON (
            ${CallLogGroupKeySql.EXPR_C} = g.group_key
            AND c.start_ts = g.max_ts
            AND c.call_id = ${CallLogGroupKeySql.HEAD_CALL_ID_AT_MAX_TS}
        )
        ORDER BY c.start_ts DESC
        """,
    )
    suspend fun getGroupedEntriesForGroupKeys(groupKeys: List<String>): List<GroupedCallLogEntry>

    @Query(
        """
        SELECT c.*, g.call_count, g.grouped_ids,
            c.cached_photo_uri AS resolved_photo_uri,
            COALESCE(c.contact_id, 0) AS resolved_contact_id
        FROM call_log_entries c
        JOIN (
            SELECT
                ${CallLogGroupKeySql.EXPR} AS group_key,
                MAX(start_ts) AS max_ts,
                COUNT(*) AS call_count,
                GROUP_CONCAT(call_id) AS grouped_ids
            FROM call_log_entries
            GROUP BY group_key
            HAVING group_key IN (:groupKeys)
        ) g ON (
            ${CallLogGroupKeySql.EXPR_C} = g.group_key
            AND c.start_ts = g.max_ts
            AND c.call_id = ${CallLogGroupKeySql.HEAD_CALL_ID_AT_MAX_TS}
        )
        ORDER BY c.start_ts DESC
        """,
    )
    suspend fun getGroupedEntriesByNumberForKeys(groupKeys: List<String>): List<GroupedCallLogEntry>

    @Query(
        """
        UPDATE call_log_entries
        SET
            cached_name = CASE
                WHEN cached_name = '' THEN COALESCE((
                    SELECT cs.display_name
                    FROM contact_phone_index cpi
                    JOIN contact_summaries cs ON cs.contact_id = cpi.contact_id
                    WHERE cpi.normalized_number = call_log_entries.normalized_number
                       OR (LENGTH(call_log_entries.normalized_number) >= 7
                           AND cpi.digits LIKE '%' || LTRIM(call_log_entries.normalized_number, '0'))
                    LIMIT 1
                ), '')
                ELSE cached_name
            END,
            cached_photo_uri = CASE
                WHEN cached_photo_uri = '' THEN COALESCE((
                    SELECT cs.photo_thumbnail_uri
                    FROM contact_phone_index cpi
                    JOIN contact_summaries cs ON cs.contact_id = cpi.contact_id
                    WHERE cpi.normalized_number = call_log_entries.normalized_number
                       OR (LENGTH(call_log_entries.normalized_number) >= 7
                           AND cpi.digits LIKE '%' || LTRIM(call_log_entries.normalized_number, '0'))
                    LIMIT 1
                ), '')
                ELSE cached_photo_uri
            END,
            contact_id = CASE
                WHEN contact_id IS NULL THEN (
                    SELECT cpi.contact_id
                    FROM contact_phone_index cpi
                    WHERE cpi.normalized_number = call_log_entries.normalized_number
                       OR (LENGTH(call_log_entries.normalized_number) >= 7
                           AND cpi.digits LIKE '%' || LTRIM(call_log_entries.normalized_number, '0'))
                    LIMIT 1
                )
                ELSE contact_id
            END
        WHERE call_id IN (:callIds)
        """,
    )
    suspend fun backfillContactInfoForCallIds(callIds: List<Int>)

    /** Aligns stored [normalized_number] with digit-only [CallLogGroupKeySql] after upgrades. */
    @Query(
        """
        UPDATE call_log_entries
        SET normalized_number = ${CallLogGroupKeySql.EXPR}
        WHERE normalized_number != ${CallLogGroupKeySql.EXPR}
           OR normalized_number IS NULL
        """,
    )
    suspend fun backfillDigitNormalizedNumbers()

    @Query(
        """
        SELECT ${CallLogGroupKeySql.EXPR} AS groupKey,
               COUNT(*) AS count
        FROM call_log_entries
        WHERE ${CallLogGroupKeySql.EXPR} IN (:groupKeys)
        GROUP BY groupKey
        """,
    )
    suspend fun getCallCountsByGroupKeys(groupKeys: List<String>): List<GroupKeyCallCountRow>
}

data class GroupKeyCallCountRow(
    val groupKey: String,
    val count: Int,
)

data class GroupedCallLogEntry(
    @Embedded val entry: CallLogEntity,
    @ColumnInfo(name = "call_count") val callCount: Int = 1,
    @ColumnInfo(name = "grouped_ids") val groupedIds: String = "",
    @ColumnInfo(name = "resolved_photo_uri") val resolvedPhotoUri: String = "",
    @ColumnInfo(name = "resolved_contact_id") val resolvedContactId: Int = 0,
)
