package com.goodwy.commons.providercache.debug

import com.goodwy.commons.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CacheFailureInjectorTest {

    @After
    fun tearDown() {
        CacheFailureInjector.clear()
    }

    @Test
    fun releaseBuild_hooksDisabled() {
        if (BuildConfig.CACHE_FAILURE_HOOKS_ENABLED) return
        CacheFailureInjector.arm(CacheFailureDomain.CONTACTS, CacheFailurePoint.BEFORE_VERSION_COMMIT)
        assertEquals("none", CacheFailureInjector.peekPending())
        CacheFailureInjector.maybeThrow(CacheFailureDomain.CONTACTS, CacheFailurePoint.BEFORE_VERSION_COMMIT)
    }

    @Test
    fun debugBuild_oneShotInjection() {
        assumeTrue(BuildConfig.CACHE_FAILURE_HOOKS_ENABLED)
        CacheFailureInjector.arm(CacheFailureDomain.RECENTS, CacheFailurePoint.AFTER_RAW_WRITE)
        assertTrue(CacheFailureInjector.peekPending().contains("RECENTS"))
        try {
            CacheFailureInjector.maybeThrow(CacheFailureDomain.RECENTS, CacheFailurePoint.AFTER_RAW_WRITE)
            assertFalse("expected injection", true)
        } catch (e: CacheFailureInjectionException) {
            assertEquals(CacheFailureDomain.RECENTS, e.domain)
            assertEquals(CacheFailurePoint.AFTER_RAW_WRITE, e.point)
        }
        assertEquals("none", CacheFailureInjector.peekPending())
    }
}
