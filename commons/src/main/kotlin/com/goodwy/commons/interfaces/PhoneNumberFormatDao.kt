package com.goodwy.commons.interfaces

import androidx.room.*
import com.goodwy.commons.models.PhoneNumberFormat

@Dao
interface PhoneNumberFormatDao {

    @Query("SELECT * FROM phone_number_formats WHERE prefix = :prefix")
    fun getFormatsByPrefixForMatching(prefix: String): List<PhoneNumberFormat>
    
    @Query("SELECT * FROM phone_number_formats WHERE prefix = 'all'")
    fun getGlobalFormatsForMatching(): List<PhoneNumberFormat>

    @Query("SELECT * FROM phone_number_formats WHERE prefix = :prefix ORDER BY districtCodeLength DESC")
    fun getFormatsByPrefix(prefix: String): List<PhoneNumberFormat>

    @Query("SELECT * FROM phone_number_formats WHERE prefix = 'all' ORDER BY districtCodeLength DESC")
    fun getGlobalFormats(): List<PhoneNumberFormat>

    @Query("SELECT * FROM phone_number_formats")
    fun getAllFormats(): List<PhoneNumberFormat>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdateFormat(format: PhoneNumberFormat): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdateFormats(formats: List<PhoneNumberFormat>): List<Long>

    @Query("DELETE FROM phone_number_formats WHERE prefix = :prefix AND districtCodePattern = :districtCodePattern")
    fun deleteFormat(prefix: String, districtCodePattern: String)

    @Query("DELETE FROM phone_number_formats")
    fun deleteAllFormats()
}

