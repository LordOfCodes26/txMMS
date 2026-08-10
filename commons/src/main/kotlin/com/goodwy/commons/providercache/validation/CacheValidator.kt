package com.goodwy.commons.providercache.validation

import android.content.Context
import com.goodwy.commons.providercache.ProviderCacheDatabase
import com.goodwy.commons.providercache.datasource.ContactsProviderDataSource
import com.goodwy.commons.providercache.entities.CacheMetadataDomain
import com.goodwy.commons.providercache.entities.CacheMetadataEntity
import com.goodwy.commons.providercache.metadata.CacheMetadataStore

/**
 * Unified cache validation entry point (Phase 4 / N).
 *
 * - **Light** — metadata dirty/repair flags and structural row-count invariants (no provider IO).
 * - **Deep** — provider cross-check for contacts + group integrity for recents display cache.
 */
object CacheValidator {

    enum class Scope {
        LIGHT,
        DEEP,
    }

    enum class IssueReason {
        METADATA_DIRTY,
        METADATA_REPAIR_REQUIRED,
        DISPLAY_EMPTY_RAW_PRESENT,
        DEEP_VALIDATION_FAILED,
    }

    data class DomainIssue(
        val domain: String,
        val reason: IssueReason,
        val detail: String = "",
    )

    data class ValidationReport(
        val scope: Scope,
        val issues: List<DomainIssue>,
        val contactsDeep: ContactsCacheValidator.ValidationResult? = null,
        val recentsDeep: RecentDisplayCacheValidator.ValidationResult? = null,
    ) {
        val requiresRepair: Boolean get() = issues.isNotEmpty()
        fun domainsNeedingRepair(): Set<String> = issues.map { it.domain }.toSet()
    }

    suspend fun validateLight(
        metadataStore: CacheMetadataStore,
        database: ProviderCacheDatabase,
        recentsGroupByContact: Int = 0,
    ): ValidationReport {
        val entities = metadataStore.getAll()
        val rawContacts = database.contactDao().getSummaryCount()
        val displayContacts = database.contactDisplayCacheDao().getCount()
        val rawRecents = database.callLogDao().getCount()
        val displayRecents = database.recentDisplayCacheDao().getCount(recentsGroupByContact)
        val issues = evaluateLight(
            entities = entities,
            rawContacts = rawContacts,
            displayContacts = displayContacts,
            rawRecents = rawRecents,
            displayRecents = displayRecents,
        )
        return ValidationReport(scope = Scope.LIGHT, issues = issues)
    }

    /** Pure evaluation for unit tests — no Room or provider IO. */
    fun evaluateLight(
        entities: List<CacheMetadataEntity>,
        rawContacts: Int,
        displayContacts: Int,
        rawRecents: Int,
        displayRecents: Int,
    ): List<DomainIssue> {
        val issues = mutableListOf<DomainIssue>()
        val byDomain = entities.associateBy { it.domain }
        CacheMetadataDomain.ALL.forEach { domain ->
            val entity = byDomain[domain]
            if (entity?.dirty == true) {
                issues += DomainIssue(
                    domain = domain,
                    reason = IssueReason.METADATA_DIRTY,
                    detail = entity.lastMutationReason,
                )
            }
            if (entity?.repairRequired == true) {
                issues += DomainIssue(
                    domain = domain,
                    reason = IssueReason.METADATA_REPAIR_REQUIRED,
                    detail = entity.lastMutationReason,
                )
            }
        }
        if (rawContacts > 0 && displayContacts <= 0) {
            issues += DomainIssue(
                domain = CacheMetadataDomain.CONTACTS_DISPLAY,
                reason = IssueReason.DISPLAY_EMPTY_RAW_PRESENT,
                detail = "raw=$rawContacts display=$displayContacts",
            )
        }
        if (rawRecents > 0 && displayRecents <= 0) {
            issues += DomainIssue(
                domain = CacheMetadataDomain.RECENTS_DISPLAY,
                reason = IssueReason.DISPLAY_EMPTY_RAW_PRESENT,
                detail = "raw=$rawRecents display=$displayRecents",
            )
        }
        return issues.distinctBy { it.domain to it.reason }
    }

    suspend fun validateDeep(
        context: Context,
        database: ProviderCacheDatabase,
        metadataStore: CacheMetadataStore,
        contactsProviderDataSource: ContactsProviderDataSource,
        contactsMetadataStore: ContactsCacheMetadataStore,
        recentsGroupByContact: Int = 0,
    ): ValidationReport {
        val light = validateLight(metadataStore, database, recentsGroupByContact)
        val contactsDeep = ContactsCacheValidator.validate(
            context = context,
            database = database,
            providerDataSource = contactsProviderDataSource,
            metadataStore = contactsMetadataStore,
        )
        val recentsDeep = if (database.recentDisplayCacheDao().getCount(recentsGroupByContact) > 0) {
            RecentDisplayCacheValidator.validate(
                database = database,
                groupByContact = recentsGroupByContact,
            )
        } else {
            null
        }
        val deepIssues = mutableListOf<DomainIssue>()
        deepIssues.addAll(light.issues)
        if (!contactsDeep.isValid) {
            deepIssues += DomainIssue(
                domain = CacheMetadataDomain.CONTACTS_RAW,
                reason = IssueReason.DEEP_VALIDATION_FAILED,
                detail = contactsDeep.invalidReason.orEmpty(),
            )
        }
        if (recentsDeep != null && !recentsDeep.isValid) {
            deepIssues += DomainIssue(
                domain = CacheMetadataDomain.RECENTS_DISPLAY,
                reason = IssueReason.DEEP_VALIDATION_FAILED,
                detail = recentsDeep.issues.firstOrNull()?.reason?.name.orEmpty(),
            )
        }
        return ValidationReport(
            scope = Scope.DEEP,
            issues = deepIssues.distinctBy { it.domain to it.reason },
            contactsDeep = contactsDeep,
            recentsDeep = recentsDeep,
        )
    }
}
