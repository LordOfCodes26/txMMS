package com.goodwy.commons.interfaces

import androidx.room.*
import com.goodwy.commons.models.PhonePrefixLocation

@Dao
interface PhonePrefixLocationDao {

    @Query("SELECT * FROM phone_prefix_locations WHERE prefix = :prefix LIMIT 1")
    fun getLocationByPrefix(prefix: String): PhonePrefixLocation?

    @Query("SELECT location FROM phone_prefix_locations WHERE prefix = :prefix LIMIT 1")
    fun getLocationStringByPrefix(prefix: String): String?

    @Query("SELECT * FROM phone_prefix_locations")
    fun getAllPrefixLocations(): List<PhonePrefixLocation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdatePrefixLocation(prefixLocation: PhonePrefixLocation): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdatePrefixLocations(prefixLocations: List<PhonePrefixLocation>): List<Long>

    @Query("DELETE FROM phone_prefix_locations WHERE prefix = :prefix")
    fun deletePrefixLocation(prefix: String)

    @Query("DELETE FROM phone_prefix_locations")
    fun deleteAllPrefixLocations()
}

