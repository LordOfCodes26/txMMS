package com.goodwy.commons.providercache.validation.legacy

import com.goodwy.commons.helpers.SMT_PRIVATE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateContactsCachePolicyTest {

    @Test
    fun visiblePrivateSource_includedWhenAllowed() {
        assertTrue(
            PrivateContactsCachePolicy.shouldIncludeInDisplayCache(
                SMT_PRIVATE,
                setOf(SMT_PRIVATE, "Google"),
            ),
        )
    }

    @Test
    fun hiddenPrivateSource_excludedWhenNotInVisibleSources() {
        assertFalse(
            PrivateContactsCachePolicy.shouldIncludeInDisplayCache(
                SMT_PRIVATE,
                setOf("Google"),
            ),
        )
    }

    @Test
    fun lockedMode_neverIncludesPrivateWithoutExplicitSource() {
        assertFalse(
            PrivateContactsCachePolicy.shouldIncludeInDisplayCache(
                SMT_PRIVATE,
                emptySet(),
            ),
        )
    }

    @Test
    fun nonPrivateSource_followsVisibleSources() {
        assertTrue(
            PrivateContactsCachePolicy.shouldIncludeInDisplayCache(
                "Google",
                setOf("Google"),
            ),
        )
        assertFalse(
            PrivateContactsCachePolicy.shouldIncludeInDisplayCache(
                "Exchange",
                setOf("Google"),
            ),
        )
    }

    /**
     * Recents/search SQL push-down and secure-mode filters are enforced in repository/SQL layers.
     * Full end-to-end private-contact leak tests require instrumented provider fixtures — see QA checklist.
     */
    @Test
    fun policyDocumentedAtDisplayCacheBuild_only() {
        assertTrue(PrivateContactsCachePolicy.legacyDiskCacheMayContainPrivateContacts())
    }
}
