package com.goodwy.commons.securebox

import android.content.Context

/**
 * Helper class for managing Secure Box operations.
 * This class handles adding/removing calls and contacts from the secure box,
 * and provides methods to check if items are secured.
 */
class SecureBoxHelper(private val context: Context) {

    private val database: SecureBoxDatabase
        get() = SecureBoxDatabase.getInstance(context)

    companion object {
        // Session flag - stored in memory only, cleared on app background/close
        @Volatile
        private var isUnlocked: Boolean = false

        @Volatile
        private var unlockTimestamp: Long = 0

        private const val UNLOCK_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
        
        // Cache for secure box IDs to avoid repeated database queries
        @Volatile
        private var cachedSecureBoxContactIds: Set<Int>? = null
        
        @Volatile
        private var cachedSecureBoxCallIds: Set<Int>? = null
        
        @Volatile
        private var cacheTimestamp: Long = 0
        
        private const val CACHE_TIMEOUT_MS = 30 * 1000L // 30 seconds cache

        /**
         * Check if secure box is currently unlocked
         */
        fun isSecureBoxUnlocked(): Boolean {
            if (!isUnlocked) return false
            
            // Check timeout
            val now = System.currentTimeMillis()
            if (now - unlockTimestamp > UNLOCK_TIMEOUT_MS) {
                lockSecureBox()
                return false
            }
            
            return true
        }

        /**
         * Unlock secure box (call after biometric authentication)
         */
        fun unlockSecureBox() {
            isUnlocked = true
            unlockTimestamp = System.currentTimeMillis()
        }

        /**
         * Lock secure box
         */
        fun lockSecureBox() {
            isUnlocked = false
            unlockTimestamp = 0
        }

        /**
         * Reset unlock state (call on app background/close)
         */
        fun resetUnlockState() {
            lockSecureBox()
        }
    }

    // ========== Call Operations ==========

    /**
     * Check if a call is in secure box
     */
    fun isCallInSecureBox(callId: Int): Boolean {
        return if (isSecureBoxUnlocked()) {
            database.SecureBoxCallDao().isCallInSecureBox(callId)
        } else {
            // If locked, always return true to hide all secure calls
            database.SecureBoxCallDao().isCallInSecureBox(callId)
        }
    }

    /**
     * Check if multiple calls are in secure box
     */
    fun areCallsInSecureBox(callIds: List<Int>): Boolean {
        if (callIds.isEmpty()) return false
        return database.SecureBoxCallDao().areCallsInSecureBox(callIds)
    }

    /**
     * Get all secure box call IDs (with caching for performance)
     */
    fun getSecureBoxCallIds(): Set<Int> {
        val now = System.currentTimeMillis()
        
        // Return cached IDs if cache is still valid
        if (cachedSecureBoxCallIds != null && (now - cacheTimestamp) < CACHE_TIMEOUT_MS) {
            return cachedSecureBoxCallIds!!
        }
        
        // Load from database and cache
        return try {
            val ids = database.SecureBoxCallDao().getAllSecureBoxCallIds().toSet()
            cachedSecureBoxCallIds = ids
            cacheTimestamp = now
            ids
        } catch (e: Exception) {
            android.util.Log.e("SecureBoxHelper", "Failed to get secure box call IDs", e)
            emptySet() // Return empty set on error to prevent crashes
        }
    }

    /**
     * Add call to secure box with specified cipherNumber
     * cipherNumber identifies which secure box this call belongs to
     */
    fun addCallToSecureBox(callId: Int, cipherNumber: Int) {
        val secureBoxCall = SecureBoxCall(
            callId = callId,
            addedAt = System.currentTimeMillis(),
            cipherNumber = cipherNumber
        )
        database.SecureBoxCallDao().insertSecureBoxCall(secureBoxCall)
        // Invalidate cache
        cachedSecureBoxCallIds = null
    }

    /**
     * Add multiple calls to secure box with specified cipherNumber
     * cipherNumber identifies which secure box these calls belong to
     */
    fun addCallsToSecureBox(callIds: List<Int>, cipherNumber: Int) {
        val calls = callIds.map { callId ->
            SecureBoxCall(
                callId = callId,
                addedAt = System.currentTimeMillis(),
                cipherNumber = cipherNumber
            )
        }
        database.SecureBoxCallDao().insertSecureBoxCalls(calls)
        // Invalidate cache
        cachedSecureBoxCallIds = null
    }

    /**
     * Remove call from secure box
     */
    fun removeCallFromSecureBox(callId: Int) {
        database.SecureBoxCallDao().deleteSecureBoxCall(callId)
        // Invalidate cache
        cachedSecureBoxCallIds = null
    }

    /**
     * Remove multiple calls from secure box
     */
    fun removeCallsFromSecureBox(callIds: List<Int>) {
        database.SecureBoxCallDao().deleteSecureBoxCalls(callIds)
    }

    // ========== Contact Operations ==========

    /**
     * Check if a contact is in secure box
     */
    fun isContactInSecureBox(contactId: Int): Boolean {
        return database.SecureBoxContactDao().isContactInSecureBox(contactId)
    }

    /**
     * Check if multiple contacts are in secure box
     */
    fun areContactsInSecureBox(contactIds: List<Int>): Boolean {
        if (contactIds.isEmpty()) return false
        return database.SecureBoxContactDao().areContactsInSecureBox(contactIds)
    }

    /**
     * Get all secure box contact IDs (with caching for performance)
     */
    fun getSecureBoxContactIds(): Set<Int> {
        val now = System.currentTimeMillis()
        
        // Return cached IDs if cache is still valid
        if (cachedSecureBoxContactIds != null && (now - cacheTimestamp) < CACHE_TIMEOUT_MS) {
            return cachedSecureBoxContactIds!!
        }
        
        // Load from database and cache
        return try {
            val ids = database.SecureBoxContactDao().getAllSecureBoxContactIds().toSet()
            cachedSecureBoxContactIds = ids
            cacheTimestamp = now
            ids
        } catch (e: Exception) {
            android.util.Log.e("SecureBoxHelper", "Failed to get secure box contact IDs", e)
            emptySet() // Return empty set on error to prevent crashes
        }
    }

    /**
     * Add contact to secure box with specified cipherNumber
     * cipherNumber identifies which secure box this contact belongs to
     */
    fun addContactToSecureBox(contactId: Int, cipherNumber: Int) {
        val secureBoxContact = SecureBoxContact(
            contactId = contactId,
            addedAt = System.currentTimeMillis(),
            cipherNumber = cipherNumber
        )
        database.SecureBoxContactDao().insertSecureBoxContact(secureBoxContact)
        // Invalidate cache
        cachedSecureBoxContactIds = null
    }

    /**
     * Add multiple contacts to secure box with specified cipherNumber
     * cipherNumber identifies which secure box these contacts belong to
     */
    fun addContactsToSecureBox(contactIds: List<Int>, cipherNumber: Int) {
        val contacts = contactIds.map { contactId ->
            SecureBoxContact(
                contactId = contactId,
                addedAt = System.currentTimeMillis(),
                cipherNumber = cipherNumber
            )
        }
        database.SecureBoxContactDao().insertSecureBoxContacts(contacts)
        // Invalidate cache
        cachedSecureBoxContactIds = null
    }

    /**
     * Remove contact from secure box
     */
    fun removeContactFromSecureBox(contactId: Int) {
        database.SecureBoxContactDao().deleteSecureBoxContact(contactId)
        // Invalidate cache
        cachedSecureBoxContactIds = null
    }

    /**
     * Remove multiple contacts from secure box
     */
    fun removeContactsFromSecureBox(contactIds: List<Int>) {
        database.SecureBoxContactDao().deleteSecureBoxContacts(contactIds)
    }

    /**
     * Get count of secure box calls
     */
    fun getSecureBoxCallCount(): Int {
        return if (isSecureBoxUnlocked()) {
            database.SecureBoxCallDao().getAllSecureBoxCalls().size
        } else {
            0 // Don't reveal count when locked
        }
    }

    /**
     * Get count of secure box contacts
     */
    fun getSecureBoxContactCount(): Int {
        return if (isSecureBoxUnlocked()) {
            database.SecureBoxContactDao().getAllSecureBoxContacts().size
        } else {
            0 // Don't reveal count when locked
        }
    }

    // ========== Methods for retrieving secure box items by cipherNumber ==========
    // These are used when displaying secure box contents after unlocking

    /**
     * Get all available cipherNumbers (secure box names)
     * Returns list of all unique cipherNumbers that have items
     */
    fun getAllCipherNumbers(): List<Int> {
        return if (isSecureBoxUnlocked()) {
            try {
                val callCipherNumbers = database.SecureBoxCallDao().getAllCipherNumbers()
                val contactCipherNumbers = database.SecureBoxContactDao().getAllCipherNumbers()
                (callCipherNumbers + contactCipherNumbers).distinct().sorted()
            } catch (e: Exception) {
                android.util.Log.e("SecureBoxHelper", "Failed to get cipher numbers", e)
                emptyList()
            }
        } else {
            emptyList() // Return empty if locked
        }
    }

    /**
     * Get all secure box calls for a specific cipherNumber (secure box)
     * Only returns data if secure box is unlocked
     */
    fun getSecureBoxCallsByCipherNumber(cipherNumber: Int): List<SecureBoxCall> {
        return if (isSecureBoxUnlocked()) {
            try {
                database.SecureBoxCallDao().getSecureBoxCallsByCipherNumber(cipherNumber)
            } catch (e: Exception) {
                android.util.Log.e("SecureBoxHelper", "Failed to get secure box calls for cipherNumber: $cipherNumber", e)
                emptyList()
            }
        } else {
            emptyList() // Return empty if locked
        }
    }

    /**
     * Get all secure box contacts for a specific cipherNumber (secure box)
     * Only returns data if secure box is unlocked
     */
    fun getSecureBoxContactsByCipherNumber(cipherNumber: Int): List<SecureBoxContact> {
        return if (isSecureBoxUnlocked()) {
            try {
                database.SecureBoxContactDao().getSecureBoxContactsByCipherNumber(cipherNumber)
            } catch (e: Exception) {
                android.util.Log.e("SecureBoxHelper", "Failed to get secure box contacts for cipherNumber: $cipherNumber", e)
                emptyList()
            }
        } else {
            emptyList() // Return empty if locked
        }
    }

    /**
     * Get all secure box calls (for all cipherNumbers)
     * Only returns data if secure box is unlocked
     */
    fun getAllSecureBoxCalls(): List<SecureBoxCall> {
        return if (isSecureBoxUnlocked()) {
            try {
                database.SecureBoxCallDao().getAllSecureBoxCalls()
            } catch (e: Exception) {
                android.util.Log.e("SecureBoxHelper", "Failed to get secure box calls", e)
                emptyList()
            }
        } else {
            emptyList() // Return empty if locked
        }
    }

    /**
     * Get all secure box contacts (for all cipherNumbers)
     * Only returns data if secure box is unlocked
     */
    fun getAllSecureBoxContacts(): List<SecureBoxContact> {
        return if (isSecureBoxUnlocked()) {
            try {
                database.SecureBoxContactDao().getAllSecureBoxContacts()
            } catch (e: Exception) {
                android.util.Log.e("SecureBoxHelper", "Failed to get secure box contacts", e)
                emptyList()
            }
        } else {
            emptyList() // Return empty if locked
        }
    }

    /**
     * Get a specific secure box call with cipherNumber by callId
     * Only returns data if secure box is unlocked
     */
    fun getSecureBoxCall(callId: Int): SecureBoxCall? {
        return if (isSecureBoxUnlocked()) {
            try {
                database.SecureBoxCallDao().getSecureBoxCall(callId)
            } catch (e: Exception) {
                android.util.Log.e("SecureBoxHelper", "Failed to get secure box call", e)
                null
            }
        } else {
            null // Return null if locked
        }
    }

    /**
     * Get a specific secure box contact with cipherNumber by contactId
     * Only returns data if secure box is unlocked
     */
    fun getSecureBoxContact(contactId: Int): SecureBoxContact? {
        return if (isSecureBoxUnlocked()) {
            try {
                database.SecureBoxContactDao().getSecureBoxContact(contactId)
            } catch (e: Exception) {
                android.util.Log.e("SecureBoxHelper", "Failed to get secure box contact", e)
                null
            }
        } else {
            null // Return null if locked
        }
    }
}


