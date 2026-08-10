package com.goodwy.commons.providercache.display

import com.goodwy.commons.providercache.debug.CompareOnlySoakCounters
import com.goodwy.commons.providercache.validation.RecentDisplayRelationalConsistencyValidator
import com.goodwy.commons.helpers.ContactAvatarInvalidUriTracker
import com.goodwy.commons.helpers.ContactListPhotoUriPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecentRelationalValidationDeferredStateTest {

    @Before
    fun setUp() {
        RecentRelationalValidationDeferredState.resetForDebug()
        StartupDomainOwner.reset()
        CompareOnlySoakCounters.reset()
        ContactAvatarInvalidUriTracker.clearAll()
    }

    @After
    fun tearDown() {
        RecentRelationalValidationDeferredState.resetForDebug()
        StartupDomainOwner.reset()
        CompareOnlySoakCounters.reset()
        ContactAvatarInvalidUriTracker.clearAll()
    }

    @Test
    fun repairStates_skipAlignmentUntilCommitted() {
        assertFalse(RecentRelationalValidationDeferredState.shouldSkipAlignmentValidation())

        RecentRelationalValidationDeferredState.markRelationalWritten(
            RecentGroupingMode.BY_NUMBER,
            "test_write",
        )
        assertEquals(
            RecentRelationalRepairState.RELATIONAL_WRITTEN,
            RecentRelationalValidationDeferredState.phase(),
        )
        assertTrue(RecentRelationalValidationDeferredState.shouldSkipAlignmentValidation())

        RecentRelationalValidationDeferredState.markDisplayWritten(
            RecentGroupingMode.BY_NUMBER,
            "display_rows",
        )
        assertEquals(
            RecentRelationalRepairState.DISPLAY_WRITTEN,
            RecentRelationalValidationDeferredState.phase(),
        )
        assertTrue(RecentRelationalValidationDeferredState.shouldSkipAlignmentValidation())

        RecentRelationalValidationDeferredState.markDisplayCommitted(
            RecentGroupingMode.BY_NUMBER,
            "commit",
        )
        assertEquals(
            RecentRelationalRepairState.COMMITTED,
            RecentRelationalValidationDeferredState.phase(),
        )
        assertFalse(RecentRelationalValidationDeferredState.shouldSkipAlignmentValidation())

        RecentRelationalValidationDeferredState.markValidating(RecentGroupingMode.BY_NUMBER)
        RecentRelationalValidationDeferredState.markComplete(RecentGroupingMode.BY_NUMBER)
        assertEquals(
            RecentRelationalRepairState.COMPLETE,
            RecentRelationalValidationDeferredState.phase(),
        )
        RecentRelationalValidationDeferredState.markIdle()
        assertEquals(RecentRelationalRepairState.IDLE, RecentRelationalValidationDeferredState.phase())
    }

    @Test
    fun consistencyResult_deferredIsNotFailure() {
        val deferred = RecentDisplayRelationalConsistencyValidator.Result(
            mode = RecentGroupingMode.BY_NUMBER,
            issues = emptyList(),
            groupCount = 2,
            displayRowCount = 0,
            deferred = true,
        )
        assertTrue(deferred.valid)
        assertFalse(deferred.readyAndValid)
    }

    @Test
    fun oneRecentsStartupOwner_acrossReasons() {
        StartupDomainOwner.nextStartupGeneration()
        assertTrue(
            StartupDomainOwner.tryAcquire(
                CacheDomain.RECENTS,
                StartupDomainOwnerKind.METADATA_REPAIR,
                "METADATA_REPAIR",
            ),
        )
        assertEquals("RECENTS:${StartupDomainOwner.currentStartupGeneration()}", StartupDomainOwner.mutationKey(CacheDomain.RECENTS))
        assertFalse(
            StartupDomainOwner.tryAcquire(
                CacheDomain.RECENTS,
                StartupDomainOwnerKind.COLD_BOOTSTRAP,
                "COLD_EMPTY_CACHE",
            ),
        )
        assertFalse(
            StartupDomainOwner.tryAcquire(
                CacheDomain.RECENTS,
                StartupDomainOwnerKind.CACHE_REPAIR,
                "STARTUP_REPAIR",
            ),
        )
        // Incremental CALL_DELETED must not steal/commit ownership without acquire.
        StartupDomainOwner.markCommitted(CacheDomain.RECENTS)
        assertTrue(StartupDomainOwner.hasCommitted(CacheDomain.RECENTS))
        assertFalse(
            StartupDomainOwner.tryAcquire(
                CacheDomain.RECENTS,
                StartupDomainOwnerKind.COLD_BOOTSTRAP,
                "COLD_EMPTY_CACHE",
            ),
        )
    }

    @Test
    fun markCommittedWithoutAcquire_isNoOp_allowsLaterAcquire() {
        StartupDomainOwner.nextStartupGeneration()
        StartupDomainOwner.markCommitted(CacheDomain.RECENTS) // CALL_DELETED-style
        assertFalse(StartupDomainOwner.hasCommitted(CacheDomain.RECENTS))
        assertTrue(
            StartupDomainOwner.tryAcquire(
                CacheDomain.RECENTS,
                StartupDomainOwnerKind.METADATA_REPAIR,
                "METADATA_REPAIR",
            ),
        )
    }

    @Test
    fun authorityCompareSoak_incrementsCompareTotal_underLegacyOnly() {
        assertEquals(RelationalRecentsReadMode.LEGACY_ONLY, RelationalRecentsGroupingFlags.readMode)
        assertTrue(RelationalRecentsGroupingFlags.shouldCompareAuthority())
        CompareOnlySoakCounters.recordAuthorityCompare(valid = true, displayMismatchCount = 0)
        assertTrue(CompareOnlySoakCounters.snapshot().compareTotal > 0L)
    }

    @Test
    fun avatarInvalidUri_sanitizedBySharedPolicy() {
        val aggregate = "content://com.android.contacts/contacts/42/photo"
        assertTrue(ContactListPhotoUriPolicy.isAggregateContactsFullPhotoUri(aggregate))
        // Aggregate PHOTO_URI is list-safe (Call UI loads it); only invalid-tracker rejects.
        assertEquals(aggregate, ContactListPhotoUriPolicy.sanitizeCachedListUri(aggregate))
        assertEquals(
            aggregate,
            ContactListPhotoUriPolicy.resolveListPhotoThumbUri("", aggregate),
        )

        val thumb = "content://com.android.contacts/contacts/42/photo/thumbnail"
        ContactAvatarInvalidUriTracker.markInvalid(42, thumb)
        assertEquals("", ContactListPhotoUriPolicy.sanitizeCachedListUri(thumb))
        assertTrue(ContactAvatarInvalidUriTracker.isInvalid(42, thumb))
    }
}
