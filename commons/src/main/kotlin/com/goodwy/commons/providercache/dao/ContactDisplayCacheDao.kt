package com.goodwy.commons.providercache.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.goodwy.commons.providercache.display.ContactDisplayListRow
import com.goodwy.commons.providercache.entities.ContactDisplayCacheEntity

@Dao
interface ContactDisplayCacheDao {

    @Query("SELECT COUNT(*) FROM contact_display_cache")
    suspend fun getCount(): Int

    @Query("SELECT raw_id FROM contact_display_cache")
    suspend fun getAllRawIds(): List<Int>

    @Query("SELECT * FROM contact_display_cache ORDER BY sort_key COLLATE LOCALIZED ASC")
    suspend fun getAllOrdered(): List<ContactDisplayCacheEntity>

    @Query(
        """
        SELECT raw_id, contact_id, display_name, source, starred,
               section_letter, first_phone_formatted, show_phone_number,
               avatar_initials, avatar_drawable_index, avatar_color, photo_thumb_uri,
               use_photo_avatar, has_valid_photo_uri, display_order
        FROM contact_display_cache
        ORDER BY display_order ASC, raw_id ASC
        """,
    )
    suspend fun getAllOrderedForList(): List<ContactDisplayListRow>

    @Query(
        """
        SELECT raw_id, contact_id, display_name, source, starred,
               section_letter, first_phone_formatted, show_phone_number,
               avatar_initials, avatar_drawable_index, avatar_color, photo_thumb_uri,
               use_photo_avatar, has_valid_photo_uri, display_order
        FROM contact_display_cache
        ORDER BY display_order ASC, raw_id ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun getOrderedForListChunk(limit: Int, offset: Int): List<ContactDisplayListRow>

    @Query(
        """
        SELECT raw_id, contact_id, display_name, source, starred,
               section_letter, first_phone_formatted, show_phone_number,
               avatar_initials, avatar_drawable_index, avatar_color, photo_thumb_uri,
               use_photo_avatar, has_valid_photo_uri, display_order
        FROM contact_display_cache cs
        WHERE cs.phone_digits LIKE :digitsPrefix ESCAPE '\'
           OR EXISTS (
               SELECT 1 FROM contact_phone_index cpi
               WHERE cpi.contact_id = cs.contact_id
                 AND (
                   cpi.phone_digits LIKE :digitsPrefix ESCAPE '\'
                   OR cpi.digits LIKE :digitsPrefix ESCAPE '\'
                 )
           )
        ORDER BY sort_key ASC
        LIMIT :limit
        """,
    )
    suspend fun searchToolbarNumericPrefix(
        digitsPrefix: String,
        limit: Int,
    ): List<ContactDisplayListRow>

    @Query(
        """
        SELECT raw_id, contact_id, display_name, source, starred,
               section_letter, first_phone_formatted, show_phone_number,
               avatar_initials, avatar_drawable_index, avatar_color, photo_thumb_uri,
               use_photo_avatar, has_valid_photo_uri, display_order
        FROM contact_display_cache cs
        WHERE cs.contact_id IN (
            SELECT contact_id FROM contact_phone_index
            WHERE phone_digits LIKE :digitsContains ESCAPE '\'
               OR digits LIKE :digitsContains ESCAPE '\'
            LIMIT :limit
        )
           OR cs.phone_digits LIKE :digitsContains ESCAPE '\'
        ORDER BY sort_key ASC
        LIMIT :limit
        """,
    )
    suspend fun searchToolbarNumericViaPhoneIndex(
        digitsContains: String,
        limit: Int,
    ): List<ContactDisplayListRow>

    @Query(
        """
        SELECT raw_id, contact_id, display_name, source, starred,
               section_letter, first_phone_formatted, show_phone_number,
               avatar_initials, avatar_drawable_index, avatar_color, photo_thumb_uri,
               use_photo_avatar, has_valid_photo_uri, display_order
        FROM contact_display_cache cs
        WHERE cs.phone_digits LIKE :digitsContains ESCAPE '\'
           OR EXISTS (
               SELECT 1 FROM contact_phone_index cpi
               WHERE cpi.contact_id = cs.contact_id
                 AND (
                   cpi.phone_digits LIKE :digitsContains ESCAPE '\'
                   OR cpi.digits LIKE :digitsContains ESCAPE '\'
                 )
           )
        ORDER BY sort_key ASC
        LIMIT :limit
        """,
    )
    suspend fun searchToolbarNumericDigitsOnly(
        digitsContains: String,
        limit: Int,
    ): List<ContactDisplayListRow>

    @Query(
        """
        SELECT raw_id, contact_id, display_name, source, starred,
               section_letter, first_phone_formatted, show_phone_number,
               avatar_initials, avatar_drawable_index, avatar_color, photo_thumb_uri,
               use_photo_avatar, has_valid_photo_uri, display_order
        FROM contact_display_cache
        WHERE search_name LIKE :searchPrefix ESCAPE '\'
        ORDER BY sort_key ASC
        LIMIT :limit
        """,
    )
    suspend fun searchToolbarByName(
        searchPrefix: String,
        limit: Int,
    ): List<ContactDisplayListRow>

    @Query(
        """
        SELECT raw_id, contact_id, display_name, source, starred,
               section_letter, first_phone_formatted, show_phone_number,
               avatar_initials, avatar_drawable_index, avatar_color, photo_thumb_uri,
               use_photo_avatar, has_valid_photo_uri, display_order
        FROM contact_display_cache
        WHERE t9_key LIKE :t9Prefix ESCAPE '\'
        ORDER BY sort_key ASC
        LIMIT :limit
        """,
    )
    suspend fun searchDialpadT9Prefix(
        t9Prefix: String,
        limit: Int,
    ): List<ContactDisplayListRow>

    @Query(
        """
        SELECT raw_id, contact_id, display_name, source, starred,
               section_letter, first_phone_formatted, show_phone_number,
               avatar_initials, avatar_drawable_index, avatar_color, photo_thumb_uri,
               use_photo_avatar, has_valid_photo_uri, display_order
        FROM contact_display_cache
        WHERE search_name LIKE :searchPrefix ESCAPE '\'
        ORDER BY sort_key ASC
        LIMIT :limit
        """,
    )
    suspend fun searchDialpadByName(
        searchPrefix: String,
        limit: Int,
    ): List<ContactDisplayListRow>

    @Query(
        """
        SELECT raw_id, contact_id, display_name, source, starred,
               section_letter, first_phone_formatted, show_phone_number,
               avatar_initials, avatar_drawable_index, avatar_color, photo_thumb_uri,
               use_photo_avatar, has_valid_photo_uri, display_order
        FROM contact_display_cache cs
        WHERE cs.phone_digits LIKE :digitsPrefix ESCAPE '\'
           OR EXISTS (
               SELECT 1 FROM contact_phone_index cpi
               WHERE cpi.contact_id = cs.contact_id
                 AND (
                   cpi.phone_digits LIKE :digitsPrefix ESCAPE '\'
                   OR cpi.digits LIKE :digitsPrefix ESCAPE '\'
                 )
           )
        ORDER BY display_order ASC, sort_key COLLATE LOCALIZED ASC,
                 display_name COLLATE LOCALIZED ASC, contact_id ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun searchToolbarNumericPrefixPage(
        digitsPrefix: String,
        limit: Int,
        offset: Int,
    ): List<ContactDisplayListRow>

    @Query(
        """
        SELECT raw_id, contact_id, display_name, source, starred,
               section_letter, first_phone_formatted, show_phone_number,
               avatar_initials, avatar_drawable_index, avatar_color, photo_thumb_uri,
               use_photo_avatar, has_valid_photo_uri, display_order
        FROM contact_display_cache cs
        WHERE cs.phone_digits LIKE :digitsContains ESCAPE '\'
           OR EXISTS (
               SELECT 1 FROM contact_phone_index cpi
               WHERE cpi.contact_id = cs.contact_id
                 AND (
                   cpi.phone_digits LIKE :digitsContains ESCAPE '\'
                   OR cpi.digits LIKE :digitsContains ESCAPE '\'
                 )
           )
        ORDER BY display_order ASC, sort_key COLLATE LOCALIZED ASC,
                 display_name COLLATE LOCALIZED ASC, contact_id ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun searchToolbarNumericViaPhoneIndexPage(
        digitsContains: String,
        limit: Int,
        offset: Int,
    ): List<ContactDisplayListRow>

    @Query(
        """
        SELECT raw_id, contact_id, display_name, source, starred,
               section_letter, first_phone_formatted, show_phone_number,
               avatar_initials, avatar_drawable_index, avatar_color, photo_thumb_uri,
               use_photo_avatar, has_valid_photo_uri, display_order
        FROM contact_display_cache
        WHERE search_name LIKE :searchPrefix ESCAPE '\'
        ORDER BY display_order ASC, sort_key COLLATE LOCALIZED ASC,
                 display_name COLLATE LOCALIZED ASC, contact_id ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun searchToolbarByNamePage(
        searchPrefix: String,
        limit: Int,
        offset: Int,
    ): List<ContactDisplayListRow>

    /**
     * Contacts-tab / toolbar text search: display name contains [namePattern] OR any phone
     * contains [digitsPattern]. Pass empty [digitsPattern] to skip phone matching (letter-only).
     * No T9 name matching.
     */
    @Query(
        """
        SELECT raw_id, contact_id, display_name, source, starred,
               section_letter, first_phone_formatted, show_phone_number,
               avatar_initials, avatar_drawable_index, avatar_color, photo_thumb_uri,
               use_photo_avatar, has_valid_photo_uri, display_order
        FROM contact_display_cache cs
        WHERE (
            :namePattern != '' AND search_name LIKE :namePattern ESCAPE '\'
        ) OR (
            :digitsPattern != '' AND (
                cs.phone_digits LIKE :digitsPattern ESCAPE '\'
                OR EXISTS (
                    SELECT 1 FROM contact_phone_index cpi
                    WHERE cpi.contact_id = cs.contact_id
                      AND (
                        cpi.phone_digits LIKE :digitsPattern ESCAPE '\'
                        OR cpi.digits LIKE :digitsPattern ESCAPE '\'
                      )
                )
            )
        )
        ORDER BY display_order ASC, sort_key COLLATE LOCALIZED ASC,
                 display_name COLLATE LOCALIZED ASC, contact_id ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun searchToolbarNameOrPhonePage(
        namePattern: String,
        digitsPattern: String,
        limit: Int,
        offset: Int,
    ): List<ContactDisplayListRow>

    @Query(
        """
        SELECT raw_id, contact_id, display_name, source, starred,
               section_letter, first_phone_formatted, show_phone_number,
               avatar_initials, avatar_drawable_index, avatar_color, photo_thumb_uri,
               use_photo_avatar, has_valid_photo_uri, display_order
        FROM contact_display_cache
        WHERE t9_key LIKE :t9Prefix ESCAPE '\'
        ORDER BY display_order ASC, sort_key COLLATE LOCALIZED ASC,
                 display_name COLLATE LOCALIZED ASC, contact_id ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun searchDialpadT9PrefixPage(
        t9Prefix: String,
        limit: Int,
        offset: Int,
    ): List<ContactDisplayListRow>

    @Query(
        """
        SELECT raw_id, contact_id, display_name, source, starred,
               section_letter, first_phone_formatted, show_phone_number,
               avatar_initials, avatar_drawable_index, avatar_color, photo_thumb_uri,
               use_photo_avatar, has_valid_photo_uri, display_order
        FROM contact_display_cache
        WHERE search_name LIKE :searchPrefix ESCAPE '\'
        ORDER BY display_order ASC, sort_key COLLATE LOCALIZED ASC,
                 display_name COLLATE LOCALIZED ASC, contact_id ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun searchDialpadByNamePage(
        searchPrefix: String,
        limit: Int,
        offset: Int,
    ): List<ContactDisplayListRow>

    @Query(
        """
        SELECT raw_id, contact_id, display_name, source, starred,
               section_letter, first_phone_formatted, show_phone_number,
               avatar_initials, avatar_drawable_index, avatar_color, photo_thumb_uri,
               use_photo_avatar, has_valid_photo_uri, display_order
        FROM contact_display_cache cs
        WHERE search_name LIKE :lettersContains ESCAPE '\'
        AND (
            cs.phone_digits LIKE :digitsContains ESCAPE '\'
            OR EXISTS (
                SELECT 1 FROM contact_phone_index cpi
                WHERE cpi.contact_id = cs.contact_id
                  AND (
                    cpi.phone_digits LIKE :digitsContains ESCAPE '\'
                    OR cpi.digits LIKE :digitsContains ESCAPE '\'
                  )
            )
        )
        ORDER BY display_order ASC, sort_key COLLATE LOCALIZED ASC,
                 display_name COLLATE LOCALIZED ASC, contact_id ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun searchToolbarMixedAndPage(
        lettersContains: String,
        digitsContains: String,
        limit: Int,
        offset: Int,
    ): List<ContactDisplayListRow>

    /**
     * Broad dialpad candidate scan with stable ordering for LIMIT/OFFSET paging.
     * Post-filter with [DialpadContactSearch] in the repository.
     */
    @Query(
        """
        SELECT raw_id, contact_id, display_name, source, starred,
               section_letter, first_phone_formatted, show_phone_number,
               avatar_initials, avatar_drawable_index, avatar_color, photo_thumb_uri,
               use_photo_avatar, has_valid_photo_uri, display_order
        FROM contact_display_cache cs
        WHERE (
            (:phonePattern != '' AND (
                cs.phone_digits LIKE :phonePattern ESCAPE '\'
                OR EXISTS (
                    SELECT 1 FROM contact_phone_index cpi
                    WHERE cpi.contact_id = cs.contact_id
                      AND (
                        cpi.phone_digits LIKE :phonePattern ESCAPE '\'
                        OR cpi.digits LIKE :phonePattern ESCAPE '\'
                      )
                )
            ))
            OR (:namePattern != '' AND cs.search_name LIKE :namePattern ESCAPE '\')
            OR (:t9Pattern != '' AND cs.t9_key LIKE :t9Pattern ESCAPE '\')
        )
        ORDER BY display_order ASC, sort_key COLLATE LOCALIZED ASC,
                 display_name COLLATE LOCALIZED ASC, contact_id ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun searchDialpadCandidatesPage(
        phonePattern: String,
        namePattern: String,
        t9Pattern: String,
        limit: Int,
        offset: Int,
    ): List<ContactDisplayListRow>

    @Query(
        """
        SELECT raw_id, contact_id, display_name, source, starred,
               section_letter, first_phone_formatted, show_phone_number,
               avatar_initials, avatar_drawable_index, avatar_color, photo_thumb_uri,
               use_photo_avatar, has_valid_photo_uri, display_order
        FROM contact_display_cache cs
        WHERE search_name LIKE :lettersContains ESCAPE '\'
        AND (
            cs.phone_digits LIKE :digitsContains ESCAPE '\'
            OR EXISTS (
                SELECT 1 FROM contact_phone_index cpi
                WHERE cpi.contact_id = cs.contact_id
                  AND (
                    cpi.phone_digits LIKE :digitsContains ESCAPE '\'
                    OR cpi.digits LIKE :digitsContains ESCAPE '\'
                  )
            )
        )
        ORDER BY sort_key ASC
        LIMIT :limit
        """,
    )
    suspend fun searchToolbarMixedAnd(
        lettersContains: String,
        digitsContains: String,
        limit: Int,
    ): List<ContactDisplayListRow>

    @Query(
        """
        SELECT raw_id, contact_id, display_name, source, starred,
               section_letter, first_phone_formatted, show_phone_number,
               avatar_initials, avatar_drawable_index, avatar_color, photo_thumb_uri,
               use_photo_avatar, has_valid_photo_uri, display_order
        FROM contact_display_cache
        WHERE search_name LIKE :searchPrefix ESCAPE '\'
           OR t9_key LIKE :t9Prefix ESCAPE '\'
           OR phone_digits LIKE :digitsPrefix ESCAPE '\'
        ORDER BY sort_key ASC
        """,
    )
    suspend fun searchOrderedForList(
        searchPrefix: String,
        t9Prefix: String,
        digitsPrefix: String,
    ): List<ContactDisplayListRow>

    @Query(
        """
        SELECT raw_id, contact_id, display_name, source, starred,
               section_letter, first_phone_formatted, show_phone_number,
               avatar_initials, avatar_drawable_index, avatar_color, photo_thumb_uri,
               use_photo_avatar, has_valid_photo_uri, display_order
        FROM contact_display_cache
        WHERE search_name LIKE :searchPrefix ESCAPE '\'
           OR t9_key LIKE :t9Prefix ESCAPE '\'
           OR phone_digits LIKE :digitsPrefix ESCAPE '\'
        ORDER BY sort_key ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun searchOrderedForListChunk(
        searchPrefix: String,
        t9Prefix: String,
        digitsPrefix: String,
        limit: Int,
        offset: Int,
    ): List<ContactDisplayListRow>

    @Query(
        """
        SELECT * FROM contact_display_cache
        WHERE search_name LIKE :searchPrefix ESCAPE '\'
           OR t9_key LIKE :t9Prefix ESCAPE '\'
           OR phone_digits LIKE :digitsPrefix ESCAPE '\'
        ORDER BY sort_key COLLATE LOCALIZED ASC
        """,
    )
    suspend fun searchOrdered(
        searchPrefix: String,
        t9Prefix: String,
        digitsPrefix: String,
    ): List<ContactDisplayCacheEntity>

    @Query("SELECT * FROM contact_display_cache WHERE contact_id IN (:ids)")
    suspend fun getByContactIds(ids: List<Int>): List<ContactDisplayCacheEntity>

    @Query("SELECT * FROM contact_display_cache WHERE raw_id = :rawId LIMIT 1")
    suspend fun getByRawId(rawId: Int): ContactDisplayCacheEntity?

    @Query(
        """
        SELECT raw_id, contact_id, display_name, source, starred,
               section_letter, first_phone_formatted, show_phone_number,
               avatar_initials, avatar_drawable_index, avatar_color, photo_thumb_uri,
               use_photo_avatar, has_valid_photo_uri, display_order
        FROM contact_display_cache WHERE contact_id IN (:ids)
        ORDER BY display_order ASC, raw_id ASC
        """,
    )
    suspend fun getListRowsByContactIds(ids: List<Int>): List<ContactDisplayListRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ContactDisplayCacheEntity>)

    @Query("DELETE FROM contact_display_cache")
    suspend fun clearAll()

    @Query("DELETE FROM contact_display_cache WHERE contact_id IN (:ids)")
    suspend fun deleteByContactIds(ids: List<Int>)

    @Query("DELETE FROM contact_display_cache WHERE raw_id IN (:ids)")
    suspend fun deleteByRawIds(ids: List<Int>)

    @Query(
        """
        UPDATE contact_display_cache
        SET has_valid_photo_uri = 0, use_photo_avatar = 0, photo_thumb_uri = ''
        WHERE raw_id = :rawId
        """,
    )
    suspend fun markPhotoUriInvalid(rawId: Int)

    @Query(
        """
        UPDATE contact_display_cache
        SET photo_thumb_uri = :photoThumbUri,
            thumbnail_uri = :thumbnailUri,
            photo_uri = :photoUri,
            use_photo_avatar = :usePhotoAvatar,
            has_valid_photo_uri = :hasValidPhotoUri
        WHERE contact_id = :contactId
        """,
    )
    suspend fun updatePhotoFieldsByContactId(
        contactId: Int,
        photoThumbUri: String,
        thumbnailUri: String,
        photoUri: String,
        usePhotoAvatar: Int,
        hasValidPhotoUri: Int,
    )

    @Query(
        """
        SELECT contact_id FROM contact_display_cache
        WHERE photo_thumb_uri = ''
        ORDER BY contact_id ASC
        LIMIT :limit
        """,
    )
    suspend fun getContactIdsMissingListPhoto(limit: Int): List<Int>

    /** Cheap checksum of avatar bind columns — used to skip unnecessary list reloads. */
    @Query(
        """
        SELECT COALESCE(SUM(
            raw_id * 31 + LENGTH(photo_thumb_uri) * 17 +
            use_photo_avatar * 13 + has_valid_photo_uri * 11 + avatar_color
        ), 0) FROM contact_display_cache
        """,
    )
    suspend fun getAvatarBindSignature(): Long

    /**
     * Upserts [rows] then removes orphans. Never clears the table first — UI keeps showing prior rows
     * until the transaction completes.
     *
     * An empty [rows] list means the filtered set is empty (e.g. Private space with no members):
     * clear the table so callers do not keep a stale pre-filter snapshot.
     */
    @Transaction
    suspend fun upsertAndPrune(rows: List<ContactDisplayCacheEntity>) {
        if (rows.isEmpty()) {
            clearAll()
            return
        }
        insertAll(rows)
        val keepIds = rows.map { it.rawId }.toHashSet()
        val orphanIds = getAllRawIds().filter { it !in keepIds }
        orphanIds.chunked(PRUNE_CHUNK).forEach { chunk ->
            if (chunk.isNotEmpty()) deleteByRawIds(chunk)
        }
    }

    /** Cold start only — table is already empty or will be replaced entirely. */
    @Transaction
    suspend fun replaceAllCold(rows: List<ContactDisplayCacheEntity>) {
        clearAll()
        if (rows.isNotEmpty()) insertAll(rows)
    }

    companion object {
        private const val PRUNE_CHUNK = 400
    }
}
