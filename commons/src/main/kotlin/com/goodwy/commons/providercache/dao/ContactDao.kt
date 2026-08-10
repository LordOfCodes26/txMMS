package com.goodwy.commons.providercache.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.goodwy.commons.providercache.entities.ContactDetailEntity
import com.goodwy.commons.providercache.entities.ContactEmailEntity
import com.goodwy.commons.providercache.entities.ContactPhoneEntity
import com.goodwy.commons.providercache.entities.ContactSummaryEntity

@Dao
interface ContactDao {

    @Query("SELECT COUNT(*) FROM contact_summaries")
    suspend fun getSummaryCount(): Int

    @Query("SELECT * FROM contact_summaries ORDER BY display_name COLLATE LOCALIZED ASC LIMIT :limit")
    suspend fun getFirstSummaries(limit: Int): List<ContactSummaryEntity>

    @Query("SELECT * FROM contact_summaries ORDER BY display_name COLLATE LOCALIZED ASC LIMIT :limit OFFSET :offset")
    suspend fun getSummariesChunk(limit: Int, offset: Int): List<ContactSummaryEntity>

    @Query("SELECT * FROM contact_summaries ORDER BY display_name COLLATE LOCALIZED ASC")
    suspend fun getAllSummaries(): List<ContactSummaryEntity>

    @Query(
        """
        SELECT * FROM contact_summaries cs
        WHERE (:applyAccountFilter = 0 OR cs.account_name IN (:visibleAccountNames))
          AND (:applySecureExclude = 0 OR (
              CASE WHEN cs.primary_raw_id != 0 THEN cs.primary_raw_id ELSE cs.contact_id END
          ) NOT IN (:excludeRawIds))
          AND (:applySecureIncludeOnly = 0 OR (
              CASE WHEN cs.primary_raw_id != 0 THEN cs.primary_raw_id ELSE cs.contact_id END
          ) IN (:includeOnlyRawIds))
        ORDER BY cs.display_name COLLATE LOCALIZED ASC
        """,
    )
    fun summaryPagingSource(
        visibleAccountNames: List<String>,
        applyAccountFilter: Int,
        excludeRawIds: List<Int>,
        applySecureExclude: Int,
        includeOnlyRawIds: List<Int>,
        applySecureIncludeOnly: Int,
    ): PagingSource<Int, ContactSummaryEntity>

    @Query(
        """
        SELECT DISTINCT cs.* FROM contact_summaries cs
        WHERE (
            cs.display_name LIKE :queryPrefix ESCAPE '\'
            OR EXISTS (
                SELECT 1 FROM contact_search_index csi
                WHERE csi.contact_id = cs.contact_id
                  AND (
                      csi.display_name_lower LIKE :queryLowerPrefix ESCAPE '\'
                      OR csi.name_t9_key LIKE :t9Prefix ESCAPE '\'
                  )
            )
            OR EXISTS (
                SELECT 1 FROM contact_phone_index cpi
                WHERE cpi.contact_id = cs.contact_id
                  AND (
                      cpi.normalized_number LIKE :normalizedPrefix ESCAPE '\'
                      OR cpi.digits LIKE :digitsPrefix ESCAPE '\'
                      OR cpi.phone_digits LIKE :digitsPrefix ESCAPE '\'
                  )
            )
        )
          AND (:applyAccountFilter = 0 OR cs.account_name IN (:visibleAccountNames))
          AND (:applySecureExclude = 0 OR (
              CASE WHEN cs.primary_raw_id != 0 THEN cs.primary_raw_id ELSE cs.contact_id END
          ) NOT IN (:excludeRawIds))
          AND (:applySecureIncludeOnly = 0 OR (
              CASE WHEN cs.primary_raw_id != 0 THEN cs.primary_raw_id ELSE cs.contact_id END
          ) IN (:includeOnlyRawIds))
        ORDER BY cs.display_name COLLATE LOCALIZED ASC
        """,
    )
    fun searchSummaryPagingSource(
        queryPrefix: String,
        queryLowerPrefix: String,
        normalizedPrefix: String,
        digitsPrefix: String,
        t9Prefix: String,
        visibleAccountNames: List<String>,
        applyAccountFilter: Int,
        excludeRawIds: List<Int>,
        applySecureExclude: Int,
        includeOnlyRawIds: List<Int>,
        applySecureIncludeOnly: Int,
    ): PagingSource<Int, ContactSummaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSummaries(summaries: List<ContactSummaryEntity>)

    @Query("SELECT MAX(last_updated_timestamp) FROM contact_summaries")
    suspend fun getMaxSummaryTimestamp(): Long?

    @Query("SELECT contact_id FROM contact_summaries")
    suspend fun getAllSummaryIds(): List<Int>

    @Query("SELECT * FROM contact_summaries WHERE contact_id IN (:ids)")
    suspend fun getSummariesByIds(ids: List<Int>): List<ContactSummaryEntity>

    @Query("SELECT * FROM contact_summaries WHERE contact_id = :contactId LIMIT 1")
    suspend fun getSummaryById(contactId: Int): ContactSummaryEntity?

    @Query("SELECT * FROM contact_summaries WHERE lookup_key = :lookupKey LIMIT 1")
    suspend fun getSummaryByLookupKey(lookupKey: String): ContactSummaryEntity?

    @Query("SELECT * FROM contact_summaries WHERE primary_raw_id IN (:rawIds)")
    suspend fun getSummariesByPrimaryRawIds(rawIds: List<Int>): List<ContactSummaryEntity>

    @Query("SELECT contact_id FROM contact_summaries WHERE photo_thumbnail_uri = ''")
    suspend fun getContactIdsMissingPhotoThumbnail(): List<Int>

    @Query("SELECT contact_id FROM contact_summaries WHERE photo_thumbnail_uri = '' LIMIT :limit")
    suspend fun getContactIdsMissingPhotoThumbnail(limit: Int): List<Int>

    @Query(
        """
        SELECT contact_id FROM contact_summaries
        WHERE photo_thumbnail_uri = '' AND contact_id > :afterId
        ORDER BY contact_id ASC
        LIMIT :limit
        """,
    )
    suspend fun getContactIdsMissingPhotoThumbnailAfter(afterId: Int, limit: Int): List<Int>

    @Query("DELETE FROM contact_summaries WHERE contact_id IN (:ids)")
    suspend fun deleteSummariesByIds(ids: List<Int>)

    @Query("DELETE FROM contact_summaries WHERE primary_raw_id IN (:rawIds)")
    suspend fun deleteSummariesByRawIds(rawIds: List<Int>)

    @Query("DELETE FROM contact_summaries")
    suspend fun clearSummaries()

    @Query("DELETE FROM contact_details WHERE contact_id IN (:ids)")
    suspend fun deleteDetailsByContactIds(ids: List<Int>)

    @Query("DELETE FROM contact_details")
    suspend fun clearDetails()

    @Query("SELECT * FROM contact_details WHERE contact_id = :contactId LIMIT 1")
    suspend fun getDetail(contactId: Int): ContactDetailEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDetail(detail: ContactDetailEntity)

    @Query("SELECT * FROM contact_phones WHERE contact_id = :contactId")
    suspend fun getPhones(contactId: Int): List<ContactPhoneEntity>

    @Query("SELECT * FROM contact_emails WHERE contact_id = :contactId")
    suspend fun getEmails(contactId: Int): List<ContactEmailEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhones(phones: List<ContactPhoneEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmails(emails: List<ContactEmailEntity>)

    @Query("DELETE FROM contact_phones WHERE contact_id = :contactId")
    suspend fun deletePhonesForContact(contactId: Int)

    @Query("DELETE FROM contact_emails WHERE contact_id = :contactId")
    suspend fun deleteEmailsForContact(contactId: Int)

    @Transaction
    suspend fun replaceContactDetail(
        detail: ContactDetailEntity,
        phones: List<ContactPhoneEntity>,
        emails: List<ContactEmailEntity>,
    ) {
        insertDetail(detail)
        deletePhonesForContact(detail.contactId)
        deleteEmailsForContact(detail.contactId)
        if (phones.isNotEmpty()) insertPhones(phones)
        if (emails.isNotEmpty()) insertEmails(emails)
    }
}
