package com.goodwy.commons.providercache.debug

import android.util.Log
import com.goodwy.commons.providercache.display.ContactDisplayRebuildMode
import com.goodwy.commons.providercache.display.DisplayCacheRebuildMetrics
import com.goodwy.commons.providercache.display.DisplayCacheRebuildReason
import com.goodwy.commons.providercache.model.ProviderCacheLoadState

/**
 * Lightweight diagnostics for the provider-cache paging migration.
 * Disabled by default; enable only in debug builds from the app layer.
 */
object ProviderCacheDebugLogger {

    const val TAG = "ProviderCacheDebug"

    @Volatile
    var isEnabled: Boolean = false

    fun log(message: String) {
        if (!isEnabled) return
        runCatching { Log.i(TAG, message) }
    }

    fun logContactsSource(source: ProviderCacheDataSource, loadState: ProviderCacheLoadState) {
        if (!isEnabled) return
        log("contacts source=$source loadState=$loadState")
    }

    fun logRecentsSource(source: ProviderCacheDataSource, loadState: ProviderCacheLoadState) {
        if (!isEnabled) return
        log("recents source=$source loadState=$loadState")
    }

    fun logColdStartPath(
        phase: String,
        source: ProviderCacheDataSource,
        firstPageCount: Int,
        loadState: ProviderCacheLoadState,
    ) {
        if (!isEnabled) return
        log("coldStart phase=$phase source=$source firstPage=$firstPageCount loadState=$loadState")
    }

    fun logRoomSwitch(from: ProviderCacheDataSource, to: ProviderCacheDataSource) {
        if (!isEnabled) return
        log("sourceSwitch from=$from to=$to")
    }

    fun logSyncStart(kind: String, entity: String) {
        if (!isEnabled) return
        log("syncStart kind=$kind entity=$entity")
    }

    fun logSyncEnd(
        kind: String,
        entity: String,
        durationMs: Long,
        syncedCount: Int,
        extra: String = "",
    ) {
        if (!isEnabled) return
        val suffix = if (extra.isEmpty()) "" else " $extra"
        log("syncEnd kind=$kind entity=$entity durationMs=$durationMs synced=$syncedCount$suffix")
    }

    fun logIndexSync(entity: String, rowCount: Int) {
        if (!isEnabled) return
        log("indexSync entity=$entity rows=$rowCount")
    }

    fun logBulkDeleteStart(rawContacts: Int) {
        if (!isEnabled) return
        log("bulkDeleteStart rawContacts=$rawContacts")
    }

    fun logDeleteClickToUiClearMs(ms: Long) {
        if (!isEnabled) return
        log("deleteClickToUiClearMs=$ms")
        PagingProfileTracker.record("deleteClickToUiClear", ms)
    }

    fun logUiClearToProviderDeleteStartMs(ms: Long) {
        if (!isEnabled) return
        log("uiClearToProviderDeleteStartMs=$ms")
        PagingProfileTracker.record("uiClearToProviderDeleteStart", ms)
    }

    fun logBulkDeleteBatch(deleted: Int) {
        if (!isEnabled) return
        log("bulkDeleteBatch deleted=$deleted")
    }

    fun logBulkDeleteEnd(
        providerRawContacts: Int,
        providerContacts: Int,
        roomContacts: Int,
        displayRows: Int,
    ) {
        if (!isEnabled) return
        log(
            "bulkDeleteEnd providerRawContacts=$providerRawContacts providerContacts=$providerContacts " +
                "roomContacts=$roomContacts displayRows=$displayRows",
        )
    }

    fun logBulkDeleteRemainingRows(rows: List<com.goodwy.commons.providercache.sync.BulkDeleteRemainingRow>) {
        if (!isEnabled || rows.isEmpty()) return
        rows.forEach { row ->
            log(
                "bulkDeleteRemainder contactId=${row.contactId} rawContactId=${row.rawContactId} " +
                    "accountName=${row.accountName} accountType=${row.accountType} " +
                    "sourceId=${row.sourceId} deleted=${row.deleted}",
            )
        }
    }

    fun logBulkDeletePermissionDenied() {
        if (!isEnabled) return
        log("bulkDeleteEnd permissionDenied=true")
    }

    fun logBulkDeleteError(message: String) {
        if (!isEnabled) return
        log("bulkDeleteError $message")
    }

    fun logEmptyStateVisible(visible: Boolean) {
        if (!isEnabled) return
        log("emptyState visible=$visible")
    }

    fun logPagingInvalidation(
        target: String,
        reason: PagingInvalidationReason,
        generation: Int,
    ) {
        if (!isEnabled) return
        log("invalidate target=$target reason=$reason generation=$generation")
    }

    fun logFilterPage(
        inCount: Int,
        afterSecure: Int,
        afterSource: Int,
        afterDuplicate: Int,
    ) {
        if (!isEnabled) return
        val secureRemoved = inCount - afterSecure
        val sourceRemoved = afterSecure - afterSource
        val duplicateRemoved = afterSource - afterDuplicate
        log(
            "filterPage in=$inCount out=$afterDuplicate" +
                " secureRemoved=$secureRemoved sourceRemoved=$sourceRemoved duplicateRemoved=$duplicateRemoved",
        )
    }

    fun logProviderQuery(label: String, durationMs: Long, rowCount: Int) {
        if (!isEnabled) return
        log("providerQuery label=$label durationMs=$durationMs rows=$rowCount")
    }

    fun logRoomQuery(label: String, durationMs: Long, rowCount: Int) {
        if (!isEnabled) return
        log("roomQuery label=$label durationMs=$durationMs rows=$rowCount")
        PagingProfileTracker.record("roomQuery:$label", durationMs)
    }

    fun logContactDisplayLoad(
        reason: String,
        queryMs: Long,
        mapMs: Long,
        rowCount: Int,
        cacheVersion: Long,
        skipped: Boolean = false,
        mapEntityMs: Long = 0,
        mapTextMs: Long = 0,
        mapAvatarMs: Long = 0,
        mapSectionMs: Long = 0,
    ) {
        if (!isEnabled) return
        val skipTag = if (skipped) " SKIPPED" else ""
        val mapBreakdown = if (mapMs > 0 || mapEntityMs > 0) {
            " mapEntityMs=$mapEntityMs mapTextMs=$mapTextMs mapAvatarMs=$mapAvatarMs mapSectionMs=$mapSectionMs"
        } else {
            ""
        }
        log(
            "contact_display_load reason=$reason queryMs=$queryMs mapMs=$mapMs " +
                "rows=$rowCount cacheVersion=$cacheVersion$skipTag$mapBreakdown",
        )
        if (!skipped) {
            PagingProfileTracker.record("contact_display_load", queryMs + mapMs)
        }
    }

    fun logAdapterDiff(adapter: String, durationMs: Long, changed: Int, inserted: Int, removed: Int) {
        if (!isEnabled) return
        log("adapterDiff adapter=$adapter durationMs=$durationMs changed=$changed inserted=$inserted removed=$removed")
        PagingProfileTracker.record("adapterDiff:$adapter", durationMs)
    }

    fun logContactDisplayUpdate(
        kind: String,
        changedCount: Int,
        deletedCount: Int,
        reboundCount: Int,
    ) {
        if (!isEnabled) return
        log("contactDisplayUpdate kind=$kind changed=$changedCount deleted=$deletedCount rebound=$reboundCount")
    }

    fun logContactDisplayUpdateUpgraded(reason: String, changedCount: Int, deletedCount: Int) {
        if (!isEnabled) return
        log(
            "contactDisplayUpdate upgraded partial_to_full reason=$reason " +
                "changed=$changedCount deleted=$deletedCount",
        )
    }

    fun logContactsAdapterState(headers: Int, contactRows: Int, displayRows: Int) {
        if (!isEnabled) return
        log("contactsAdapterState headers=$headers contactRows=$contactRows displayRows=$displayRows")
    }

    fun logSearchHintCount(source: String, count: Int) {
        if (!isEnabled) return
        log("searchHintCount source=$source count=$count")
    }

    fun logAdapterRows(visibleRows: Int, totalContacts: Int) {
        if (!isEnabled) return
        log("adapterRows visibleRows=$visibleRows totalContacts=$totalContacts")
    }

    fun logContactsDisplayLoad(source: String, rows: Int) {
        if (!isEnabled) return
        log("contactsDisplayLoad source=$source rows=$rows")
    }

    fun logDisplayCacheEmpty(rawRows: Int, action: String) {
        if (!isEnabled) return
        log("displayCacheEmpty rawRows=$rawRows action=$action")
    }

    fun logDisplayCacheReady(version: Long) {
        if (!isEnabled) return
        log("displayCacheReady version=$version")
    }

    fun logContactsCacheValidation(
        providerCount: Int,
        roomCount: Int,
        displayCount: Int,
        providerMaxTs: Long,
        cachedMaxTs: Long,
        providerHash: Long,
        cachedHash: Long,
        result: String,
        reason: String,
        action: String,
    ) {
        if (!isEnabled) return
        log(
            "contactsCacheValidation providerCount=$providerCount roomCount=$roomCount " +
                "displayCount=$displayCount providerMaxTs=$providerMaxTs cachedMaxTs=$cachedMaxTs " +
                "providerHash=$providerHash cachedHash=$cachedHash result=$result reason=$reason action=$action",
        )
    }

    fun logStartupCacheRebuildStart(reason: String) {
        if (!isEnabled) return
        log("startupCacheRebuildStart reason=$reason")
    }

    fun logSkipStartupSync(reason: String) {
        if (!isEnabled) return
        log("skipStartupSync reason=$reason")
    }

    fun logSkipDisplayRebuild(reason: String) {
        if (!isEnabled) return
        log("skipDisplayRebuild reason=$reason")
    }

    fun logPagingStep(stage: String, durationMs: Long, detail: String = "") {
        if (!isEnabled) return
        val suffix = if (detail.isEmpty()) "" else " $detail"
        log("pagingStep stage=$stage durationMs=$durationMs$suffix")
        PagingProfileTracker.record(stage, durationMs)
    }

    fun logAdapterBind(adapter: String, durationMs: Long, position: Int) {
        if (!isEnabled) return
        if (durationMs < 2L) return
        log("adapterBind adapter=$adapter durationMs=$durationMs position=$position")
        PagingProfileTracker.record("adapterBind:$adapter", durationMs)
    }

    fun logAdapterSubmit(adapter: String, durationMs: Long, itemCount: Int) {
        if (!isEnabled) return
        log("adapterSubmit adapter=$adapter durationMs=$durationMs items=$itemCount")
        PagingProfileTracker.record("adapterSubmit:$adapter", durationMs)
    }

    fun logAdapterSubmitSkipped(
        adapter: String,
        reason: String,
        cacheVersion: Long = -1L,
        rowCount: Int = 0,
        contentHash: Long = 0L,
    ) {
        if (!isEnabled) return
        log(
            "adapterSubmitSkipped adapter=$adapter reason=$reason " +
                "cacheVersion=$cacheVersion rows=$rowCount contentHash=$contentHash",
        )
    }

    fun logSearchIndexBuildDeferred(contactCount: Int) {
        if (!isEnabled) return
        log("searchIndexBuildDeferred contacts=$contactCount")
    }

    fun logContactsSearchIndexBuild(contactCount: Int, durationMs: Long) {
        if (!isEnabled) return
        log("contactsSearchIndexBuild contacts=$contactCount durationMs=$durationMs")
        PagingProfileTracker.record("contactsSearchIndexBuild", durationMs)
    }

    fun logPostSubmitAllocProbe(stage: String, detail: String = "") {
        if (!isEnabled) return
        val suffix = if (detail.isEmpty()) "" else " $detail"
        log("postSubmitAllocProbe stage=$stage$suffix")
    }

    fun logAvatarDrawableCacheMiss(contactId: Int, reason: String) {
        if (!isEnabled) return
        log("avatarDrawableCacheMiss contactId=$contactId reason=$reason")
    }

    fun logSnapshotPublish(durationMs: Long, rawRows: Int, displayRows: Int) {
        if (!isEnabled) return
        log("snapshotPublish durationMs=$durationMs raw=$rawRows display=$displayRows")
        PagingProfileTracker.record("snapshotPublish", durationMs)
    }

    fun logFilterStep(step: String, durationMs: Long, inCount: Int, outCount: Int) {
        if (!isEnabled) return
        log("filterStep step=$step durationMs=$durationMs in=$inCount out=$outCount")
        PagingProfileTracker.record("filterStep:$step", durationMs)
    }

    fun logSearch(
        query: String,
        fromRoom: Boolean,
        phoneIndexReady: Boolean,
        firstPageCount: Int,
    ) {
        if (!isEnabled) return
        val path = if (fromRoom) "ROOM_INDEX" else "PROVIDER_FALLBACK"
        log(
            "search query=\"$query\" path=$path phoneIndexReady=$phoneIndexReady firstPage=$firstPageCount",
        )
    }

    fun logSecureMode(
        mode: String,
        activityAlive: Boolean,
        inCount: Int,
        outCount: Int,
    ) {
        if (!isEnabled) return
        log("secureMode mode=$mode activityAlive=$activityAlive in=$inCount out=$outCount hidden=${inCount - outCount}")
    }

    fun logRecentsSnapshot(
        source: ProviderCacheDataSource,
        rawRows: Int,
        groupedRows: Int,
        withPhoneAccountId: Int,
        withoutPhoneAccountId: Int,
        huaweiFallbackCount: Int,
    ) {
        if (!isEnabled) return
        log(
            "recents source=$source raw=$rawRows grouped=$groupedRows" +
                " phoneAccountId=$withPhoneAccountId missingPhoneAccountId=$withoutPhoneAccountId" +
                " huaweiFallback=$huaweiFallbackCount",
        )
    }

    fun logDisplayCacheRebuildStart(
        target: String,
        reason: DisplayCacheRebuildReason,
        changedIds: Int = 0,
        deletedIds: Int = 0,
        forceFull: Boolean = false,
        rebuildMode: ContactDisplayRebuildMode = ContactDisplayRebuildMode.FAST,
    ) {
        if (!isEnabled) return
        log(
            "displayCacheRebuildStart target=$target reason=$reason rebuildMode=$rebuildMode" +
                " changedIds=$changedIds deletedIds=$deletedIds forceFull=$forceFull",
        )
    }

    fun logDisplayCacheRebuildEnd(target: String, metrics: DisplayCacheRebuildMetrics) {
        if (!isEnabled) return
        log(
            "displayCacheRebuildEnd target=$target rows=${metrics.rowsUpdated} reason=${metrics.reason} mode=${metrics.mode}" +
                " rebuildMode=${metrics.rebuildMode}" +
                " rawQueryMs=${metrics.rawQueryMs} filterMs=${metrics.filterMs}" +
                " duplicateMergeMs=${metrics.duplicateMergeMs} sortMs=${metrics.sortMs}" +
                " dbWriteMs=${metrics.dbWriteMs} totalMs=${metrics.totalMs}" +
                " updated=${metrics.rowsUpdated} deleted=${metrics.rowsDeleted}" +
                " legacyMetadataBackfillNeeded=${metrics.legacyMetadataBackfillNeeded}",
        )
        PagingProfileTracker.record("displayCacheRebuild:$target", metrics.totalMs)
    }

    fun logDiagnostics(snapshot: ProviderCacheDiagnostics) {
        if (!isEnabled) return
        log(snapshot.toLogLine())
    }
}

data class ProviderCacheDiagnostics(
    val contactsLoadState: ProviderCacheLoadState,
    val callLogLoadState: ProviderCacheLoadState,
    val contactsSource: ProviderCacheDataSource,
    val callLogSource: ProviderCacheDataSource,
    val contactSummaryCount: Int,
    val phoneIndexCount: Int,
    val searchIndexCount: Int,
    val callLogCount: Int,
    val contactDisplayCount: Int = 0,
    val recentDisplayPerPhoneCount: Int = 0,
    val recentDisplayPerContactCount: Int = 0,
    val contactsDisplayVersion: Long = 0L,
    val recentsDisplayVersion: Long = 0L,
    val phoneIndexReady: Boolean,
    val activeSearchQuery: String,
    val pagingGeneration: Int,
    val contactsReadiness: String = "NOT_STARTED",
    val recentsReadiness: String = "NOT_STARTED",
    val recentsVisibleVersion: Long = 0L,
    val contactsRepairRequired: Boolean = false,
    val recentsRepairRequired: Boolean = false,
    val contactsFallbackActive: Boolean = false,
    val recentsFallbackActive: Boolean = false,
    val lastContactsMutationId: Long = 0L,
    val lastRecentsMutationId: Long = 0L,
    val compareTotal: Long = 0L,
    val compareMismatch: Long = 0L,
    val displayMismatch: Long = 0L,
    val dualWriteTotal: Long = 0L,
    val dualWriteMismatch: Long = 0L,
    val checksumCompareTotal: Long = 0L,
    val checksumMismatch: Long = 0L,
    val incrementalFallbackCount: Long = 0L,
    val noOpMutationCount: Long = 0L,
    val displayOnlyMutationCount: Long = 0L,
    val membershipChangedCount: Long = 0L,
    val authorityPathViolations: Long = 0L,
    val soakSessionPanel: String = "",
    val byContactAuthority: String = "",
    val byNumberAuthority: String = "",
    val databaseVersion: Int = 16,
    val dirtyRecents: Boolean = false,
    val relationalConsistency: String = "NOT_RUN",
    val lastMismatchAtMs: Long = 0L,
    val lastMismatchReason: String = "",
    val lastMismatchMode: String = "",
    val lastMismatchKey: String = "",
) {
    fun toLogLine(): String =
        "diagnostics contacts=$contactsSource($contactsLoadState) recents=$callLogSource($callLogLoadState)" +
            " summaries=$contactSummaryCount display=$contactDisplayCount v=$contactsDisplayVersion" +
            " phoneIndex=$phoneIndexCount searchIndex=$searchIndexCount" +
            " callLog=$callLogCount recentDisplay=$recentDisplayPerPhoneCount/$recentDisplayPerContactCount" +
            " recentsV=$recentsDisplayVersion phoneIndexReady=$phoneIndexReady search=\"$activeSearchQuery\"" +
            " pagingGen=$pagingGeneration"

    fun toQaPanel(): String = buildString {
        if (soakSessionPanel.isNotBlank()) {
            appendLine(soakSessionPanel.trimEnd())
            appendLine()
        }
        appendLine("=== Readiness ===")
        appendLine("Contacts readiness: $contactsReadiness")
        appendLine("Recents readiness: $recentsReadiness")
        appendLine("DB version: $databaseVersion")
        appendLine("displayVersion: contacts=$contactsDisplayVersion recents=$recentsDisplayVersion")
        appendLine("visibleVersion: $recentsVisibleVersion")
        appendLine("dirty(recents): $dirtyRecents")
        appendLine("repairRequired: contacts=$contactsRepairRequired recents=$recentsRepairRequired")
        appendLine("fallbackActive: contacts=$contactsFallbackActive recents=$recentsFallbackActive")
        appendLine("lastMutationId: contacts=$lastContactsMutationId recents=$lastRecentsMutationId")
        appendLine()
        appendLine("=== Authority ===")
        appendLine("BY_CONTACT: $byContactAuthority")
        appendLine("BY_NUMBER: $byNumberAuthority")
        appendLine("Relational consistency: $relationalConsistency")
        appendLine()
        appendLine("=== Counters ===")
        appendLine("semantic: total=$compareTotal mismatch=$compareMismatch")
        appendLine("displayMismatch=$displayMismatch")
        appendLine("checksum: total=$checksumCompareTotal mismatch=$checksumMismatch")
        appendLine("dualWrite: total=$dualWriteTotal mismatch=$dualWriteMismatch")
        appendLine("incrementalFallback=$incrementalFallbackCount")
        appendLine("noOp=$noOpMutationCount displayOnly=$displayOnlyMutationCount membershipChanged=$membershipChangedCount")
        appendLine("authorityPathViolations=$authorityPathViolations")
        appendLine()
        appendLine("=== Last mismatch ===")
        appendLine("timestamp=$lastMismatchAtMs")
        appendLine("mode=$lastMismatchMode key=$lastMismatchKey")
        appendLine("reason=$lastMismatchReason")
    }
}
