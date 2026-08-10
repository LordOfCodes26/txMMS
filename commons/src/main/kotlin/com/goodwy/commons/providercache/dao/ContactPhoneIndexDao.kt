package com.goodwy.commons.providercache.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.goodwy.commons.providercache.entities.ContactPhoneIndexEntity

@Dao
interface ContactPhoneIndexDao {

    @Query("SELECT COUNT(*) FROM contact_phone_index")
    suspend fun getCount(): Int

    @Query("SELECT * FROM contact_phone_index")
    suspend fun getAll(): List<ContactPhoneIndexEntity>

    @Query("SELECT * FROM contact_phone_index WHERE contact_id IN (:contactIds)")
    suspend fun getByContactIds(contactIds: List<Int>): List<ContactPhoneIndexEntity>

    @Query(
        """
        SELECT COALESCE(NULLIF(phone_digits, ''), digits) FROM contact_phone_index
        WHERE contact_id = :contactId AND COALESCE(NULLIF(phone_digits, ''), digits) != ''
        """,
    )
    suspend fun getDigitsForContactId(contactId: Int): List<String>

    @Query(
        """
        SELECT contact_id FROM contact_phone_index
        WHERE phone_digits = :digits OR digits = :digits
        LIMIT 1
        """,
    )
    suspend fun getContactIdForDigits(digits: String): Int?

    @Query("""
        SELECT contact_id FROM contact_phone_index
        WHERE normalized_number = :normalizedNumber
           OR digits LIKE :digitPattern
        LIMIT 1
    """)
    suspend fun findContactId(normalizedNumber: String, digitPattern: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<ContactPhoneIndexEntity>)

    @Query(
        """
        SELECT * FROM contact_phone_index
        WHERE contact_id NOT IN (:excludeContactIds)
          AND (
              (:hasNormalized = 0 OR normalized_number IN (:normalizedNumbers))
              OR (:hasDigits = 0 OR phone_digits IN (:phoneDigits) OR digits IN (:phoneDigits))
          )
        """,
    )
    suspend fun findByPhoneNumbersExcluding(
        excludeContactIds: List<Int>,
        normalizedNumbers: List<String>,
        phoneDigits: List<String>,
        hasNormalized: Int,
        hasDigits: Int,
    ): List<ContactPhoneIndexEntity>

    @Query(
        """
        SELECT * FROM contact_phone_index
        WHERE phone_digits IN (:phoneDigits)
           OR digits IN (:phoneDigits)
           OR REPLACE(COALESCE(NULLIF(normalized_number, ''), ''), '+', '') IN (:phoneDigits)
        """,
    )
    suspend fun findByPhoneDigits(phoneDigits: List<String>): List<ContactPhoneIndexEntity>

    @Query("DELETE FROM contact_phone_index WHERE contact_id IN (:contactIds)")
    suspend fun deleteForContacts(contactIds: List<Int>)

    @Query("DELETE FROM contact_phone_index")
    suspend fun clearAll()
}
