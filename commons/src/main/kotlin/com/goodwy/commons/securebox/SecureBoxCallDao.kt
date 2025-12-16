package com.goodwy.commons.securebox

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.goodwy.commons.securebox.SecureBoxCall

@Dao
interface SecureBoxCallDao {

    @Query("SELECT * FROM secure_box_calls")
    fun getAllSecureBoxCalls(): List<SecureBoxCall>

    @Query("SELECT * FROM secure_box_calls WHERE cipherNumber=:cipherNumber")
    fun getSecureBoxCallsByCipherNumber(cipherNumber: Int): List<SecureBoxCall>

    @Query("SELECT callId FROM secure_box_calls")
    fun getAllSecureBoxCallIds(): List<Int>

    @Query("SELECT callId FROM secure_box_calls WHERE cipherNumber=:cipherNumber")
    fun getSecureBoxCallIdsByCipherNumber(cipherNumber: Int): List<Int>

    @Query("SELECT DISTINCT cipherNumber FROM secure_box_calls")
    fun getAllCipherNumbers(): List<Int>

    @Query("SELECT * FROM secure_box_calls WHERE callId=:callId")
    fun getSecureBoxCall(callId: Int): SecureBoxCall?

    @Query("SELECT EXISTS(SELECT 1 FROM secure_box_calls WHERE callId=:callId)")
    fun isCallInSecureBox(callId: Int): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM secure_box_calls WHERE callId=:callId AND cipherNumber=:cipherNumber)")
    fun isCallInSecureBoxWithCipher(callId: Int, cipherNumber: Int): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM secure_box_calls WHERE callId IN (:callIds))")
    fun areCallsInSecureBox(callIds: List<Int>): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSecureBoxCall(secureBoxCall: SecureBoxCall): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSecureBoxCalls(calls: List<SecureBoxCall>): List<Long>

    @Query("DELETE FROM secure_box_calls WHERE callId=:callId")
    fun deleteSecureBoxCall(callId: Int)

    @Query("DELETE FROM secure_box_calls WHERE callId IN (:callIds)")
    fun deleteSecureBoxCalls(callIds: List<Int>)

    @Query("DELETE FROM secure_box_calls")
    fun deleteAllSecureBoxCalls()
}


