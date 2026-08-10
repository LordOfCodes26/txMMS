package com.goodwy.commons.providercache.display

/**
 * Compares legacy vs relational Recents grouping.
 *
 * Authority correctness (affects [Result.valid] / dual-write mismatch):
 * call ids, group membership, contact identity, phone numbers, timestamps, counts.
 *
 * Display cosmetics (logged separately, never fail authority):
 * DISPLAY_ORDER, section headers, relative-time-derived text, avatars, SIM chrome, etc.
 */
object RecentAuthorityComparator {

    data class Result(
        val valid: Boolean,
        val legacyGroupCount: Int,
        val relationalGroupCount: Int,
        /** Authority mismatches only. */
        val mismatches: List<ComparableRecentGroupMismatch>,
        /** Cosmetic / bind-time display diffs — informational; do not fail [valid]. */
        val displayMismatches: List<ComparableDisplayMismatch> = emptyList(),
    ) {
        val authorityMismatchCount: Int get() = mismatches.size
        val cosmeticMismatchCount: Int get() = displayMismatches.size
        /** Prefer [authorityMismatchCount]; kept for log compatibility. */
        val allMismatches: Int get() = authorityMismatchCount
    }

    private val AUTHORITY_SEMANTIC_FIELDS = setOf(
        ComparableRecentGroupField.CALL_IDS,
        ComparableRecentGroupField.CONTACT_ID,
        ComparableRecentGroupField.NUMBERS,
        ComparableRecentGroupField.COUNT,
        ComparableRecentGroupField.LATEST_CALL,
        ComparableRecentGroupField.LATEST_TS,
        ComparableRecentGroupField.PRIMARY_NUMBER,
        ComparableRecentGroupField.GROUP_EXISTENCE,
    )

    fun compareSemanticGroups(
        mode: RecentGroupingMode,
        legacy: Map<String, ComparableRecentGroup>,
        relational: Map<String, ComparableRecentGroup>,
    ): Result {
        val mismatches = mutableListOf<ComparableRecentGroupMismatch>()
        val allKeys = (legacy.keys + relational.keys).toSortedSet()

        allKeys.forEach { key ->
            val old = legacy[key]
            val new = relational[key]
            if (old == null || new == null) {
                mismatches += ComparableRecentGroupMismatch(
                    mode = mode,
                    semanticKey = key,
                    field = ComparableRecentGroupField.GROUP_EXISTENCE,
                    oldValue = old?.callIds?.joinToString(",").orEmpty().ifEmpty { "missing" },
                    newValue = new?.callIds?.joinToString(",").orEmpty().ifEmpty { "missing" },
                )
                return@forEach
            }
            compareAuthorityField(mode, key, ComparableRecentGroupField.CALL_IDS, old.callIds, new.callIds, mismatches)
            compareAuthorityField(
                mode, key, ComparableRecentGroupField.CONTACT_ID,
                old.displayContactId, new.displayContactId, mismatches,
            )
            compareAuthorityField(
                mode, key, ComparableRecentGroupField.NUMBERS,
                old.normalizedNumbers, new.normalizedNumbers, mismatches,
            )
            compareAuthorityField(mode, key, ComparableRecentGroupField.COUNT, old.callCount, new.callCount, mismatches)
            compareAuthorityField(
                mode, key, ComparableRecentGroupField.LATEST_CALL,
                old.latestCallId, new.latestCallId, mismatches,
            )
            compareAuthorityField(
                mode, key, ComparableRecentGroupField.LATEST_TS,
                old.latestTimestamp, new.latestTimestamp, mismatches,
            )
            compareAuthorityField(
                mode, key, ComparableRecentGroupField.PRIMARY_NUMBER,
                old.primaryNumber, new.primaryNumber, mismatches,
            )
            // DISPLAY_ORDER is cosmetic — intentionally not compared for authority.
        }

        return Result(
            valid = mismatches.isEmpty(),
            legacyGroupCount = legacy.size,
            relationalGroupCount = relational.size,
            mismatches = mismatches,
        )
    }

    /**
     * Cosmetic fields that survive a comparison against a *relational projection*.
     *
     * `RelationalRecentDisplaySnapshotBuilder.buildDisplayRows` assembles its rows straight from
     * `recent_groups` + `call_log_cache`; it never runs the enrichment pass, so every
     * enrichment-derived field on that side sits at its type default — `""`, `-1`, `0`, `false`.
     * Comparing those against fully enriched legacy rows cannot pass: it reported a *constant*
     * 2227 mismatches (304 rows × the ~7.3 enriched fields each one populates), identical in every
     * log, three times per startup, and always waved through as `valid=true`.
     *
     * That constant is not drift, it is "the projection carries no enrichment" restated 2227 times,
     * and it buries any cosmetic drift that would actually matter. Restricting the comparison to
     * fields both sides populate keeps the drift signal and drops the noise floor to zero.
     */
    internal val PROJECTION_COMPARABLE_COSMETIC_FIELDS = setOf(
        ComparableDisplayField.DISPLAY_ORDER,
        ComparableDisplayField.PHOTO_THUMB_URI,
        ComparableDisplayField.SIM_COLOR,
    )

    /**
     * Compares display-cache cosmetics only. Results never affect authority [Result.valid]
     * or dual-write mismatch counters.
     *
     * [fields] defaults to every cosmetic field — pass
     * [PROJECTION_COMPARABLE_COSMETIC_FIELDS] when the relational side is an un-enriched
     * projection.
     */
    fun compareDisplayGroups(
        mode: RecentGroupingMode,
        legacy: Map<String, ComparableDisplayGroup>,
        relational: Map<String, ComparableDisplayGroup>,
        fields: Set<ComparableDisplayField> =
            ComparableDisplayField.entries.filter { it.isCosmetic }.toSet(),
    ): List<ComparableDisplayMismatch> {
        val mismatches = mutableListOf<ComparableDisplayMismatch>()
        val allKeys = (legacy.keys + relational.keys).toSortedSet()
        allKeys.forEach { key ->
            val old = legacy[key]
            val new = relational[key]
            if (old == null || new == null) {
                // Existence is already covered by semantic GROUP_EXISTENCE; skip as cosmetic noise.
                return@forEach
            }
            ComparableDisplayField.entries
                .filter { it.isCosmetic && it in fields }
                .forEach { field ->
                    val (oldVal, newVal) = fieldValues(field, old, new)
                    if (oldVal != newVal) {
                        mismatches += ComparableDisplayMismatch(
                            mode = mode,
                            semanticKey = key,
                            field = field,
                            oldValue = oldVal,
                            newValue = newVal,
                        )
                    }
                }
        }
        return mismatches
    }

    private fun fieldValues(
        field: ComparableDisplayField,
        old: ComparableDisplayGroup,
        new: ComparableDisplayGroup,
    ): Pair<String, String> = when (field) {
        ComparableDisplayField.DISPLAY_ORDER -> old.displayOrder.toString() to new.displayOrder.toString()
        ComparableDisplayField.DISPLAY_NAME -> old.displayName to new.displayName
        ComparableDisplayField.PHOTO_THUMB_URI -> old.photoThumbUri to new.photoThumbUri
        ComparableDisplayField.AVATAR_INITIALS -> old.avatarInitials to new.avatarInitials
        ComparableDisplayField.AVATAR_DRAWABLE_INDEX ->
            old.avatarDrawableIndex.toString() to new.avatarDrawableIndex.toString()
        ComparableDisplayField.USE_PHOTO_AVATAR -> old.usePhotoAvatar.toString() to new.usePhotoAvatar.toString()
        ComparableDisplayField.AVATAR_SHOW_PROFILE_ICON ->
            old.avatarShowProfileIcon.toString() to new.avatarShowProfileIcon.toString()
        ComparableDisplayField.AVATAR_COLOR -> old.avatarColor.toString() to new.avatarColor.toString()
        ComparableDisplayField.SIM_LABEL -> old.simLabel to new.simLabel
        ComparableDisplayField.SIM_COLOR -> old.simColorResolved.toString() to new.simColorResolved.toString()
        ComparableDisplayField.SIM_VISIBLE -> old.simVisible.toString() to new.simVisible.toString()
        ComparableDisplayField.CALL_TYPE_ICON -> old.callTypeIconKey to new.callTypeIconKey
        ComparableDisplayField.GROUP_COUNT_TEXT -> old.groupCountText to new.groupCountText
        ComparableDisplayField.NAME_IS_MISSED_COLOR ->
            old.nameIsMissedColor.toString() to new.nameIsMissedColor.toString()
        ComparableDisplayField.SECTION_DAY_CODE -> old.sectionDayCode to new.sectionDayCode
        ComparableDisplayField.SECTION_HEADER_TEXT -> old.sectionHeaderText to new.sectionHeaderText
        // Authority-overlapping display fields are compared semantically, not here.
        ComparableDisplayField.DISPLAY_CONTACT_ID,
        ComparableDisplayField.DISPLAY_NUMBER,
        ComparableDisplayField.GROUP_EXISTENCE,
        -> "" to ""
    }

    private fun <T> compareAuthorityField(
        mode: RecentGroupingMode,
        semanticKey: String,
        field: ComparableRecentGroupField,
        old: T,
        new: T,
        mismatches: MutableList<ComparableRecentGroupMismatch>,
    ) {
        check(field in AUTHORITY_SEMANTIC_FIELDS) {
            "Non-authority field $field passed to compareAuthorityField"
        }
        if (old != new) {
            mismatches += ComparableRecentGroupMismatch(
                mode = mode,
                semanticKey = semanticKey,
                field = field,
                oldValue = old.toString(),
                newValue = new.toString(),
            )
        }
    }
}
