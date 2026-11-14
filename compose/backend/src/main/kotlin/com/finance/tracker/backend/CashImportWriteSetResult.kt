package com.finance.tracker.backend

import com.finance.tracker.business.contracts.*

/** Snapshot final stored identities in the original command order, including consumed relation batches. */
internal fun cashImportWriteSetResult(context: CashSqlContext, sources: CashImportWriteSetSourceResult,
    bindings: Map<CashWriteReferenceDto, Long>, issues: Map<CashWriteReferenceDto, Long>,
    mappings: Map<CashWriteReferenceDto, Long>, relations: Map<CashWriteReferenceDto, Long>, after: LedgerFactsDto): CashWriteSetResultDto {
    sources.requireOwner(context)
    val changes = sources.changes
    val parents = after.cashTransactions.associateBy { it.id }
    val components = after.cashTransactionComponents.associateBy { it.id }
    val storedBindings = after.cashSourceBindings.associateBy { it.id }
    val storedIssues = after.cashTransactionIssues.associateBy { it.id }
    val storedMappings = after.statementAccountMappings.associateBy { it.id }
    val storedRelations = after.transactionRelations.associateBy { it.id }
    val entries = after.cashEntries.associateBy { it.rootTransactionId }
    fun parent(reference: CashWriteReferenceDto): String = (sources.transactions[reference]
        ?: (reference as ExistingCashWriteReferenceDto).id.toLong()).toString()
    val surviving = after.cashEntries.mapTo(mutableSetOf()) { it.id }
    val retired = sources.before.cashEntries.map { it.id }.filter { it !in surviving }
    val observed = changes.entryDependencies.mapTo(mutableSetOf()) { (it.reference as ExistingCashWriteReferenceDto).id }
    check(retired.all { it in observed }) { "write_set.unobserved_entry_retirement" }
    val resolved = changes.resolvedIssues.map { (it.reference as ExistingCashWriteReferenceDto).id }
    check(resolved.none { it in storedIssues }) { "write_set.unresolved_issue" }
    return CashWriteSetResultDto(
        transactions = changes.transactions.map { row ->
            val stored = parents.getValue(sources.transactions.getValue(row.target.reference).toString())
            val children = row.components.map { part ->
                val child = components.getValue(sources.components.getValue(part.reference).toString())
                check(child.cashTransactionId == stored.id) { "write_set.component_owner_changed" }
                child.id
            }
            CashWriteSetTransactionResultDto(stored.id, stored.rowRevision, children)
        },
        sourceBindings = changes.sourceBindings.map { row ->
            val stored = storedBindings.getValue(bindings.getValue(row.target.reference).toString())
            CashWriteSetIdentityDto(stored.id, stored.revision)
        },
        issues = changes.issues.map { row ->
            val stored = storedIssues.getValue(issues.getValue(row.target.reference).toString())
            CashWriteSetIdentityDto(stored.id, stored.revision)
        },
        statementAccountMappings = changes.statementAccountMappings.map { row ->
            val stored = storedMappings.getValue(mappings.getValue(row.target.reference).toString())
            CashWriteSetIdentityDto(stored.id, stored.revision)
        },
        relations = changes.relations.map { row ->
            val stored = storedRelations.getValue(relations.getValue(row.target.reference).toString())
            CashWriteSetIdentityDto(stored.id, stored.rowRevision)
        },
        entries = changes.entries.map { row ->
            val stored = entries.getValue(parent(row.rootTransaction.reference))
            CashWriteSetEntryResultDto(stored.id, stored.rootTransactionId, stored.identityTransactionId, stored.revision)
        },
        resolvedIssueIds = resolved,
        retiredEntryIds = retired,
    )
}
