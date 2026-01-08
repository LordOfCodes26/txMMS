package com.goodwy.commons.interfaces

import androidx.room.*
import com.goodwy.commons.models.PhoneDistrict

@Dao
interface PhoneDistrictDao {

    @Query("SELECT * FROM phone_districts WHERE prefix = :prefix AND districtCode = :districtCode LIMIT 1")
    fun getDistrictByPrefixAndCode(prefix: String, districtCode: String): PhoneDistrict?

    @Query("SELECT districtName FROM phone_districts WHERE prefix = :prefix AND districtCode = :districtCode LIMIT 1")
    fun getDistrictNameByPrefixAndCode(prefix: String, districtCode: String): String?

    @Query("SELECT * FROM phone_districts WHERE prefix = :prefix")
    fun getDistrictsByPrefix(prefix: String): List<PhoneDistrict>

    @Query("SELECT * FROM phone_districts")
    fun getAllDistricts(): List<PhoneDistrict>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdateDistrict(district: PhoneDistrict): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdateDistricts(districts: List<PhoneDistrict>): List<Long>

    @Query("DELETE FROM phone_districts WHERE prefix = :prefix AND districtCode = :districtCode")
    fun deleteDistrict(prefix: String, districtCode: String)

    @Query("DELETE FROM phone_districts WHERE prefix = :prefix")
    fun deleteDistrictsByPrefix(prefix: String)

    @Query("DELETE FROM phone_districts")
    fun deleteAllDistricts()
}

