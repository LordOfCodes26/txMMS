package com.goodwy.commons.providercache.filter

import android.content.Context
import com.goodwy.commons.extensions.getVisibleContactSources

/**
 * Parameters pushed into Room list/search queries (account visibility + secure mode).
 */
data class ContactRoomQueryFilters(
    val visibleAccountNames: List<String>,
    val applyAccountFilter: Int,
    val excludeRawIds: List<Int>,
    val applySecureExclude: Int,
    val includeOnlyRawIds: List<Int>,
    val applySecureIncludeOnly: Int,
) {
    val sqlAccountFilterApplied: Boolean get() = applyAccountFilter != 0
    val sqlSecureFilterApplied: Boolean get() = applySecureExclude != 0 || applySecureIncludeOnly != 0

    companion object {
        private const val FLAG_OFF = 0
        private const val FLAG_ON = 1
        private val NO_MATCH_ID = listOf(-1)

        fun build(context: Context, secureParams: ContactSecureSqlParams?): ContactRoomQueryFilters {
            val visibleSources = context.getVisibleContactSources()
            val applyAccount = if (visibleSources.isEmpty()) FLAG_OFF else FLAG_ON
            val accountNames = if (applyAccount == FLAG_ON) visibleSources else listOf("")

            val secure = secureParams ?: ContactSecureSqlParams()
            val applyExclude = if (secure.excludeRawIds.isNotEmpty()) FLAG_ON else FLAG_OFF
            val excludeIds = if (applyExclude == FLAG_ON) secure.excludeRawIds else NO_MATCH_ID

            val includeOnly = secure.includeOnlyRawIds
            val applyIncludeOnly = if (includeOnly != null) FLAG_ON else FLAG_OFF
            val includeIds = when {
                includeOnly == null -> NO_MATCH_ID
                includeOnly.isEmpty() -> NO_MATCH_ID
                else -> includeOnly
            }

            return ContactRoomQueryFilters(
                visibleAccountNames = accountNames,
                applyAccountFilter = applyAccount,
                excludeRawIds = excludeIds,
                applySecureExclude = applyExclude,
                includeOnlyRawIds = includeIds,
                applySecureIncludeOnly = applyIncludeOnly,
            )
        }
    }
}

/**
 * Secure-mode raw-contact IDs for SQL filtering. Set from the app layer.
 */
data class ContactSecureSqlParams(
    val excludeRawIds: List<Int> = emptyList(),
    val includeOnlyRawIds: List<Int>? = null,
) {
    /** In-memory apply of include/exclude rules (dialpad/toolbar search safety net). */
    fun filterRawIds(rawIds: Sequence<Int>): Sequence<Int> {
        val includeOnly = includeOnlyRawIds
        if (includeOnly != null) {
            val allowed = includeOnly.toSet()
            return rawIds.filter { it in allowed }
        }
        if (excludeRawIds.isEmpty()) return rawIds
        val blocked = excludeRawIds.toSet()
        return rawIds.filter { it !in blocked }
    }

    fun <T> filterByRawId(items: List<T>, rawIdOf: (T) -> Int): List<T> {
        val includeOnly = includeOnlyRawIds
        if (includeOnly != null) {
            val allowed = includeOnly.toSet()
            return items.filter { rawIdOf(it) in allowed }
        }
        if (excludeRawIds.isEmpty()) return items
        val blocked = excludeRawIds.toSet()
        return items.filter { rawIdOf(it) !in blocked }
    }
}
