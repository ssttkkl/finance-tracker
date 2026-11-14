package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*

/** Trusted source-identity results, never wire input or a substitute for observed Existing revisions. */
class CashWriteSetSourceAliases(
    transactions: Map<NewCashWriteReferenceDto, String> = emptyMap(),
    components: Map<NewCashWriteReferenceDto, String> = emptyMap(),
    bindings: Map<NewCashWriteReferenceDto, String> = emptyMap(),
) {
    private val transactions = transactions.toMap()
    private val components = components.toMap()
    private val bindings = bindings.toMap()
    fun transaction(reference: CashWriteReferenceDto): CashWriteReferenceDto = canonical(reference, transactions)
    internal fun component(reference: CashWriteReferenceDto): CashWriteReferenceDto = canonical(reference, components)
    internal fun binding(reference: CashWriteReferenceDto): CashWriteReferenceDto = canonical(reference, bindings)
    private fun canonical(reference: CashWriteReferenceDto, values: Map<NewCashWriteReferenceDto, String>): CashWriteReferenceDto =
        (reference as? NewCashWriteReferenceDto)?.let(values::get)?.let(::ExistingCashWriteReferenceDto) ?: reference

    internal fun validate(changes: CashWriteSetDto, facts: LedgerFactsDto) {
        if (transactions.isEmpty() && components.isEmpty() && bindings.isEmpty()) return
        fun valid(values: Map<NewCashWriteReferenceDto, String>, declared: Set<CashWriteReferenceDto>, stored: Set<String>) {
            check(values.all { (ref, id) -> ref in declared && id in stored }) { "write_set.invalid_source_alias" }
        }
        valid(transactions, changes.transactions.mapTo(mutableSetOf()) { it.target.reference }, facts.cashTransactions.mapTo(mutableSetOf()) { it.id })
        valid(components, changes.transactions.flatMap { it.components }.mapTo(mutableSetOf()) { it.reference }, facts.cashTransactionComponents.mapTo(mutableSetOf()) { it.id })
        valid(bindings, changes.sourceBindings.mapTo(mutableSetOf()) { it.target.reference }, facts.cashSourceBindings.mapTo(mutableSetOf()) { it.id })
        val oldParents = facts.cashTransactions.associateBy { it.id }
        val oldBindings = facts.cashSourceBindings.associateBy { it.id }
        for (row in changes.sourceBindings) {
            val id = bindings[row.target.reference] ?: continue
            val old = oldBindings.getValue(id)
            if (oldParents.getValue(old.transactionId).deletedAt == null) {
                check(transaction(row.transaction) == ExistingCashWriteReferenceDto(old.transactionId)) { "write_set.invalid_source_alias" }
            }
            check(listOf(row.sourceType, row.sourceScopeKind, row.sourceScopeKey, row.identityKind, row.identityKey) ==
                listOf(old.sourceType, old.sourceScopeKind, old.sourceScopeKey, old.identityKind, old.identityKey)) { "write_set.invalid_source_alias" }
        }
        val oldParts = facts.cashTransactionComponents.associateBy { it.id }
        for (row in changes.transactions) for (part in row.components) {
            val alias = components[part.reference] ?: continue
            check(transaction(row.target.reference) == ExistingCashWriteReferenceDto(oldParts.getValue(alias).cashTransactionId)) { "write_set.invalid_source_alias" }
        }
    }
}
