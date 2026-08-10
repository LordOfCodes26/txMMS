package com.goodwy.commons.providercache.debug

import com.goodwy.commons.BuildConfig

/**
 * DEBUG-only one-shot failure injection for cache mutation paths (Phase R).
 * Release builds compile [CACHE_FAILURE_HOOKS_ENABLED]=false — hooks are no-ops.
 */
enum class CacheFailurePoint {
    AFTER_RAW_WRITE,
    BEFORE_DISPLAY_WRITE,
    AFTER_DISPLAY_WRITE,
    BEFORE_VERSION_COMMIT,
    AFTER_VERSION_COMMIT_BEFORE_NOTIFY,
}

enum class CacheFailureDomain {
    CONTACTS,
    RECENTS,
}

class CacheFailureInjectionException(
    val domain: CacheFailureDomain,
    val point: CacheFailurePoint,
) : RuntimeException("Cache failure injection: domain=$domain point=$point")

object CacheFailureInjector {

    @Volatile
    private var pendingDomain: CacheFailureDomain? = null

    @Volatile
    private var pendingPoint: CacheFailurePoint? = null

    fun isEnabled(): Boolean = BuildConfig.CACHE_FAILURE_HOOKS_ENABLED

    /** Arm a single failure for [domain] at [point]; consumed on first match. */
    fun arm(domain: CacheFailureDomain, point: CacheFailurePoint) {
        if (!BuildConfig.CACHE_FAILURE_HOOKS_ENABLED) return
        pendingDomain = domain
        pendingPoint = point
    }

    fun clear() {
        pendingDomain = null
        pendingPoint = null
    }

    fun peekPending(): String {
        val domain = pendingDomain ?: return "none"
        return "domain=$domain point=${pendingPoint ?: "?"}"
    }

    fun maybeThrow(domain: CacheFailureDomain, point: CacheFailurePoint) {
        if (!BuildConfig.CACHE_FAILURE_HOOKS_ENABLED) return
        if (pendingDomain == domain && pendingPoint == point) {
            pendingDomain = null
            pendingPoint = null
            throw CacheFailureInjectionException(domain, point)
        }
    }
}
