package com.goodwy.commons.providercache.validation.legacy

import android.util.Log
import com.goodwy.commons.providercache.ProviderCache
import com.goodwy.commons.providercache.display.DisplayCacheReadiness
import com.goodwy.commons.providercache.display.DisplayCacheReadinessTracker

/**
 * Central policy for legacy disk/memory caches (Phase 5 / L).
 */
object LegacyCacheGate {

    private const val TAG = "LegacyCacheGate"

    data class AuthoritySnapshot(
        val contactsRoomAuthoritative: Boolean,
        val recentsRoomAuthoritative: Boolean,
    )

    fun isProviderCacheReady(): Boolean =
        runCatching { ProviderCache.isInitialized() }.getOrDefault(false)

    fun contactsRoomCacheAuthoritative(): Boolean {
        if (!isProviderCacheReady()) return false
        return evaluateContactsAuthority(
            readiness = DisplayCacheReadinessTracker.contactsReadiness(),
            displayVersion = ProviderCache.contactsRepository.peekDisplayCacheVersion(),
            dirty = ProviderCache.cacheMetadataStore.peekContactsDisplayDirty(),
            repairRequired = ProviderCache.cacheMetadataStore.peekContactsDisplayRepairRequired(),
            providerFallbackActive = DisplayCacheReadinessTracker.isContactsProviderFallbackActive(),
        )
    }

    fun recentsRoomCacheAuthoritative(): Boolean {
        if (!isProviderCacheReady()) return false
        return evaluateRecentsAuthority(
            readiness = DisplayCacheReadinessTracker.recentsReadiness(),
            displayVersion = ProviderCache.callLogRepository.recentsCacheVersion(),
            dirty = ProviderCache.cacheMetadataStore.peekRecentsDisplayDirty(),
            repairRequired = ProviderCache.cacheMetadataStore.peekRecentsDisplayRepairRequired(),
            providerFallbackActive = DisplayCacheReadinessTracker.isRecentsProviderFallbackActive(),
        )
    }

    fun snapshot(): AuthoritySnapshot = AuthoritySnapshot(
        contactsRoomAuthoritative = contactsRoomCacheAuthoritative(),
        recentsRoomAuthoritative = recentsRoomCacheAuthoritative(),
    )

    fun logAuthority(domain: String) {
        val readiness = when (domain) {
            "CONTACTS" -> DisplayCacheReadinessTracker.contactsReadiness()
            else -> DisplayCacheReadinessTracker.recentsReadiness()
        }
        val fallback = when (domain) {
            "CONTACTS" -> DisplayCacheReadinessTracker.isContactsProviderFallbackActive()
            else -> DisplayCacheReadinessTracker.isRecentsProviderFallbackActive()
        }
        val (displayVersion, dirty, repairRequired) = if (isProviderCacheReady()) {
            when (domain) {
                "CONTACTS" -> Triple(
                    ProviderCache.contactsRepository.peekDisplayCacheVersion(),
                    ProviderCache.cacheMetadataStore.peekContactsDisplayDirty(),
                    ProviderCache.cacheMetadataStore.peekContactsDisplayRepairRequired(),
                )
                else -> Triple(
                    ProviderCache.callLogRepository.recentsCacheVersion(),
                    ProviderCache.cacheMetadataStore.peekRecentsDisplayDirty(),
                    ProviderCache.cacheMetadataStore.peekRecentsDisplayRepairRequired(),
                )
            }
        } else {
            Triple(0L, true, true)
        }
        val allowed = when (domain) {
            "CONTACTS" -> contactsRoomCacheAuthoritative()
            else -> recentsRoomCacheAuthoritative()
        }
        com.goodwy.commons.providercache.debug.CacheReadinessAssertions.assertRoomAuthoritativeInvariants(
            domain = domain,
            allowed = allowed,
            readiness = readiness,
            fallbackActive = fallback,
            repairRequired = repairRequired,
        )
        Log.d(
            TAG,
            "legacyAuthority domain=$domain allowed=$allowed readiness=$readiness " +
                "fallbackActive=$fallback dirty=$dirty repairRequired=$repairRequired version=$displayVersion",
        )
    }

    /**
     * True while a Private space / Secure box is open.
     *
     * While set, the recents lists in flight hold **only protected calls**, so none of the legacy
     * caches may be written: the disk/prefs caches would persist protected numbers, names and
     * timestamps in plaintext outside the provider's protection, and all of them would be read
     * back into the *normal* list after leaving the box.
     *
     * Lives here rather than being checked at each call site so a future writer cannot miss it.
     * Set from the app's secure-mode enter/exit path.
     */
    @Volatile
    private var secureBoxActive: Boolean = false

    fun setSecureBoxActive(active: Boolean) {
        if (secureBoxActive != active) {
            Log.d(TAG, "legacyCacheGate secureBoxActive=$active")
        }
        secureBoxActive = active
    }

    fun isSecureBoxActive(): Boolean = secureBoxActive

    fun shouldWriteContactsDiskCache(): Boolean = !contactsRoomCacheAuthoritative()

    fun shouldWriteRecentsDiskCache(): Boolean =
        !secureBoxActive && !recentsRoomCacheAuthoritative()

    fun shouldWriteRecentsMemoryCache(): Boolean =
        !secureBoxActive && !recentsRoomCacheAuthoritative()

    fun shouldReadContactsDiskCacheForHint(): Boolean = !contactsRoomCacheAuthoritative()

    fun shouldReadRecentsLegacyCache(): Boolean = !recentsRoomCacheAuthoritative()

    fun evaluateContactsAuthority(
        readiness: DisplayCacheReadiness,
        displayVersion: Long,
        dirty: Boolean,
        repairRequired: Boolean,
        providerFallbackActive: Boolean,
    ): Boolean = DisplayCacheReadinessTracker.evaluateContactsAuthoritative(
        readiness = readiness,
        displayVersion = displayVersion,
        dirty = dirty,
        repairRequired = repairRequired,
        providerFallbackActive = providerFallbackActive,
    )

    fun evaluateRecentsAuthority(
        readiness: DisplayCacheReadiness,
        displayVersion: Long,
        dirty: Boolean,
        repairRequired: Boolean,
        providerFallbackActive: Boolean,
    ): Boolean = evaluateContactsAuthority(
        readiness,
        displayVersion,
        dirty,
        repairRequired,
        providerFallbackActive,
    )

    /** Pure evaluation for tests. */
    fun evaluateAuthority(
        contactsReadiness: DisplayCacheReadiness,
        contactsDisplayVersion: Long,
        contactsDirty: Boolean,
        contactsRepairRequired: Boolean,
        contactsFallbackActive: Boolean,
        recentsReadiness: DisplayCacheReadiness,
        recentsDisplayVersion: Long,
        recentsDirty: Boolean,
        recentsRepairRequired: Boolean,
        recentsFallbackActive: Boolean,
    ): AuthoritySnapshot = AuthoritySnapshot(
        contactsRoomAuthoritative = evaluateContactsAuthority(
            contactsReadiness,
            contactsDisplayVersion,
            contactsDirty,
            contactsRepairRequired,
            contactsFallbackActive,
        ),
        recentsRoomAuthoritative = evaluateRecentsAuthority(
            recentsReadiness,
            recentsDisplayVersion,
            recentsDirty,
            recentsRepairRequired,
            recentsFallbackActive,
        ),
    )
}
