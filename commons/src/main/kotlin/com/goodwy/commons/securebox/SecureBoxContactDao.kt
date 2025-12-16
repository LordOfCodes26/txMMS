package com.goodwy.commons.securebox

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.goodwy.commons.securebox.SecureBoxContact

@Dao
interface SecureBoxContactDao {

    @Query("SELECT * FROM secure_box_contacts")
    fun getAllSecureBoxContacts(): List<SecureBoxContact>

    @Query("SELECT * FROM secure_box_contacts WHERE cipherNumber=:cipherNumber")
    fun getSecureBoxContactsByCipherNumber(cipherNumber: Int): List<SecureBoxContact>

    @Query("SELECT contactId FROM secure_box_contacts")
    fun getAllSecureBoxContactIds(): List<Int>

    @Query("SELECT contactId FROM secure_box_contacts WHERE cipherNumber=:cipherNumber")
    fun getSecureBoxContactIdsByCipherNumber(cipherNumber: Int): List<Int>

    @Query("SELECT DISTINCT cipherNumber FROM secure_box_contacts")
    fun getAllCipherNumbers(): List<Int>

    @Query("SELECT * FROM secure_box_contacts WHERE contactId=:contactId")
    fun getSecureBoxContact(contactId: Int): SecureBoxContact?

    @Query("SELECT EXISTS(SELECT 1 FROM secure_box_contacts WHERE contactId=:contactId)")
    fun isContactInSecureBox(contactId: Int): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM secure_box_contacts WHERE contactId=:contactId AND cipherNumber=:cipherNumber)")
    fun isContactInSecureBoxWithCipher(contactId: Int, cipherNumber: Int): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM secure_box_contacts WHERE contactId IN (:contactIds))")
    fun areContactsInSecureBox(contactIds: List<Int>): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSecureBoxContact(secureBoxContact: SecureBoxContact): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSecureBoxContacts(contacts: List<SecureBoxContact>): List<Long>

    @Query("DELETE FROM secure_box_contacts WHERE contactId=:contactId")
    fun deleteSecureBoxContact(contactId: Int)

    @Query("DELETE FROM secure_box_contacts WHERE contactId IN (:contactIds)")
    fun deleteSecureBoxContacts(contactIds: List<Int>)

    @Query("DELETE FROM secure_box_contacts")
    fun deleteAllSecureBoxContacts()
}


