package com.goodwy.commons.providercache.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.goodwy.commons.providercache.entities.RecentDisplayCacheEntity
import com.goodwy.commons.providercache.display.RecentDisplayListRow

@Dao
interface RecentDisplayCacheDao {

    @Query("SELECT COUNT(*) FROM recent_display_cache WHERE group_by_contact = :groupByContact")
    suspend fun getCount(groupByContact: Int): Int

    @Query("SELECT call_id FROM recent_display_cache WHERE group_by_contact = :groupByContact")
    suspend fun getAllCallIds(groupByContact: Int): List<Int>

    @Query("SELECT * FROM recent_display_cache WHERE contact_id = :contactId")
    suspend fun getByContactId(contactId: Int): List<RecentDisplayCacheEntity>

    @Query(
        """
        SELECT r.* FROM recent_display_cache r
        INNER JOIN contact_summaries cs ON r.contact_id = cs.contact_id
        WHERE cs.lookup_key = :lookupKey
        """,
    )
    suspend fun getByLookupKey(lookupKey: String): List<RecentDisplayCacheEntity>

    @Query(
        """
        SELECT * FROM recent_display_cache
        WHERE normalized_number IN (:normalizedNumbers)
           OR phone_number IN (:phoneNumbers)
        """,
    )
    suspend fun getByPhoneNumbers(
        normalizedNumbers: List<String>,
        phoneNumbers: List<String>,
    ): List<RecentDisplayCacheEntity>

    @Query(
        """
        SELECT * FROM recent_display_cache
        WHERE contact_id IS NULL
          AND cached_name = :cachedName
          AND cached_name != ''
        """,
    )
    suspend fun getUnlinkedByCachedName(cachedName: String): List<RecentDisplayCacheEntity>

    @Query("SELECT * FROM recent_display_cache WHERE call_id IN (:callIds)")
    suspend fun getByCallIds(callIds: List<Int>): List<RecentDisplayCacheEntity>

    @Query(
        """
        SELECT * FROM recent_display_cache
        WHERE group_by_contact = :groupByContact
        ORDER BY start_ts DESC
        LIMIT :limit
        """,
    )
    suspend fun getOrdered(groupByContact: Int, limit: Int): List<RecentDisplayCacheEntity>

    /**
     * Full current-mode Recents list in final UI order.
     * Schema mapping: grouping_mode=group_by_contact, latest_timestamp=start_ts, latest_call_id=call_id.
     */
    @Query(
        """
        SELECT group_by_contact, group_key, call_id, start_ts, call_count,
               display_name, display_number, contact_id, phone_number, cached_name,
               photo_thumb_uri, photo_uri,
               avatar_initials, avatar_drawable_index, avatar_color, avatar_version,
               avatar_show_profile_icon, use_photo_avatar,
               call_type_icon_key, sim_color_resolved, sim_label, sim_visible,
               sim_id, sim_type_id, sim_color, type, duration,
               is_unknown_number, is_voice_mail, block_reason, features,
               name_is_missed_color, section_day_code, section_header_text,
               group_count_text, formatted_date_time, display_order, normalized_number
        FROM recent_display_cache
        WHERE group_by_contact = :groupByContact
        ORDER BY start_ts DESC, call_id DESC, group_key ASC
        """,
    )
    suspend fun getAllOrderedForList(groupByContact: Int): List<RecentDisplayListRow>

    /** First-paint viewport of [getAllOrderedForList] — same columns/order with LIMIT. */
    @Query(
        """
        SELECT group_by_contact, group_key, call_id, start_ts, call_count,
               display_name, display_number, contact_id, phone_number, cached_name,
               photo_thumb_uri, photo_uri,
               avatar_initials, avatar_drawable_index, avatar_color, avatar_version,
               avatar_show_profile_icon, use_photo_avatar,
               call_type_icon_key, sim_color_resolved, sim_label, sim_visible,
               sim_id, sim_type_id, sim_color, type, duration,
               is_unknown_number, is_voice_mail, block_reason, features,
               name_is_missed_color, section_day_code, section_header_text,
               group_count_text, formatted_date_time, display_order, normalized_number
        FROM recent_display_cache
        WHERE group_by_contact = :groupByContact
        ORDER BY start_ts DESC, call_id DESC, group_key ASC
        LIMIT :limit
        """,
    )
    suspend fun getOrderedForList(groupByContact: Int, limit: Int): List<RecentDisplayListRow>

    /**
     * Paged form of [getAllOrderedForList] — identical columns and ORDER BY, windowed by Paging.
     *
     * First step of the recents Paging migration (`docs/recents-remediation-plan.md`, Stage 3).
     * Room invalidates this source on any write to `recent_display_cache`, which is what eventually
     * removes the need for the manual invalidation the pipeline coordinator performs today.
     *
     * Additive: nothing consumes it yet. The contacts tab already works this way — see
     * [ContactDao.summaryPagingSource] and `MainTabContactsPagingAdapter`.
     */
    @Query(
        """
        SELECT group_by_contact, group_key, call_id, start_ts, call_count,
               display_name, display_number, contact_id, phone_number, cached_name,
               photo_thumb_uri, photo_uri,
               avatar_initials, avatar_drawable_index, avatar_color, avatar_version,
               avatar_show_profile_icon, use_photo_avatar,
               call_type_icon_key, sim_color_resolved, sim_label, sim_visible,
               sim_id, sim_type_id, sim_color, type, duration,
               is_unknown_number, is_voice_mail, block_reason, features,
               name_is_missed_color, section_day_code, section_header_text,
               group_count_text, formatted_date_time, display_order, normalized_number
        FROM recent_display_cache
        WHERE group_by_contact = :groupByContact
        ORDER BY start_ts DESC, call_id DESC, group_key ASC
        """,
    )
    fun orderedForListPagingSource(groupByContact: Int): PagingSource<Int, RecentDisplayListRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<RecentDisplayCacheEntity>)

    @Query("DELETE FROM recent_display_cache WHERE group_by_contact = :groupByContact")
    suspend fun clearForMode(groupByContact: Int)

    @Query("DELETE FROM recent_display_cache WHERE call_id IN (:ids)")
    suspend fun deleteByCallIds(ids: List<Int>)

    @Query(
        """
        UPDATE recent_display_cache
        SET use_photo_avatar = 0, photo_thumb_uri = ''
        WHERE call_id IN (:callIds)
        """,
    )
    suspend fun markPhotoUriInvalid(callIds: List<Int>)

    @Query(
        """
        UPDATE recent_display_cache
        SET use_photo_avatar = 0, photo_thumb_uri = ''
        WHERE contact_id = :contactId
        """,
    )
    suspend fun markPhotoUriInvalidByContactId(contactId: Int)

    @Query(
        """
        DELETE FROM recent_display_cache
        WHERE group_by_contact = :groupByContact
          AND contact_id = :contactId
        """,
    )
    suspend fun deleteByContactIdForMode(contactId: Int, groupByContact: Int)

    @Query(
        """
        DELETE FROM recent_display_cache
        WHERE group_by_contact = :groupByContact
          AND group_key = :groupKey
        """,
    )
    suspend fun deleteByGroupKeyForMode(groupKey: String, groupByContact: Int)

    @Query(
        """
        DELETE FROM recent_display_cache
        WHERE group_by_contact = :groupByContact
          AND contact_id IS NULL
          AND normalized_number = :normalizedNumber
        """,
    )
    suspend fun deleteByNormalizedNumberForMode(normalizedNumber: String, groupByContact: Int)

    @Query(
        """
        SELECT * FROM recent_display_cache
        WHERE group_by_contact = :groupByContact
          AND group_key = :groupKey
        """,
    )
    suspend fun getByGroupKey(groupKey: String, groupByContact: Int): List<RecentDisplayCacheEntity>

    @Query(
        """
        SELECT group_key AS groupKey, COUNT(*) AS count
        FROM recent_display_cache
        WHERE group_by_contact = :groupByContact
        GROUP BY group_key
        HAVING COUNT(*) > 1
        """,
    )
    suspend fun getDuplicateGroupKeys(groupByContact: Int): List<DuplicateGroupKeyRow>

    @Query(
        """
        SELECT DISTINCT group_key FROM recent_display_cache
        WHERE group_by_contact = :groupByContact
          AND contact_id IS NOT NULL
          AND contact_id > 0
          AND NOT EXISTS (
              SELECT 1 FROM contact_display_cache c
              WHERE c.contact_id = recent_display_cache.contact_id
          )
        """,
    )
    suspend fun getGroupKeysWithMissingDisplayContact(groupByContact: Int): List<String>

    @Query(
        """
        DELETE FROM recent_display_cache
        WHERE group_by_contact = :groupByContact
          AND call_id NOT IN (:keepIds)
        """,
    )
    suspend fun deleteNotInCallIds(groupByContact: Int, keepIds: List<Int>)

    @Transaction
    suspend fun upsertAndPrune(
        rows: List<RecentDisplayCacheEntity>,
        groupByContact: Int,
        limit: Int,
    ) {
        if (rows.isEmpty()) return
        insertAll(rows)
        val keepIds = rows.map { it.callId }
        if (keepIds.isEmpty()) return
        deleteNotInCallIds(groupByContact, keepIds)
        trimToLimit(groupByContact, limit)
    }

    @Query(
        """
        DELETE FROM recent_display_cache
        WHERE rowid IN (
            SELECT rowid FROM recent_display_cache
            WHERE group_by_contact = :groupByContact
            ORDER BY start_ts ASC
            LIMIT :excess
        )
        """,
    )
    suspend fun deleteOldest(groupByContact: Int, excess: Int)

    suspend fun trimToLimit(groupByContact: Int, limit: Int) {
        val count = getCount(groupByContact)
        val excess = count - limit
        if (excess > 0) {
            deleteOldest(groupByContact, excess)
        }
    }

    @Transaction
    suspend fun replaceAllCold(rows: List<RecentDisplayCacheEntity>, groupByContact: Int) {
        clearForMode(groupByContact)
        if (rows.isNotEmpty()) insertAll(rows)
    }
}

data class DuplicateGroupKeyRow(
    val groupKey: String,
    val count: Int,
)
