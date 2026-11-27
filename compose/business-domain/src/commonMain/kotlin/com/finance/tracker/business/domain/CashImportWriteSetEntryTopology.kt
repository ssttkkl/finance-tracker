package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*

/** File-only entry topology; final public effects and the authoritative transaction remain caller-owned. */
fun validateCashImportWriteSetEntryTopology(changes: CashWriteSetDto, facts: LedgerFactsDto, aliases: CashWriteSetSourceAliases = CashWriteSetSourceAliases()) {
    validateCashImportWriteSetScope(changes, facts, aliases)
    val affected = validateCashWriteSetEntryTopology(changes, facts, aliases)
    validateWholeCashImportEntries(changes, facts, aliases, affected)
}

internal fun validateLocalCashImportWriteSetEntryTopology(changes: CashWriteSetDto, facts: LedgerFactsDto,
    topology: CashWriteEntryTopology) {
    val aliases = CashWriteSetSourceAliases()
    validateCashImportWriteSetScope(changes, facts, aliases)
    val affected = validateCashWriteSetEntryTopology(changes, topology, aliases)
    validateWholeCashImportEntries(changes, facts, aliases, affected)
}

private fun validateWholeCashImportEntries(changes: CashWriteSetDto, facts: LedgerFactsDto, aliases: CashWriteSetSourceAliases,
    affected: List<LedgerCashEntryFactDto>) {
    val parents = facts.cashTransactions.associateBy { it.id }
    val members = facts.cashEntryMembers.groupBy { it.entryId }
    val finalGroupByMember = changes.entries.flatMapIndexed { index, entry ->
        entry.members.map { aliases.transaction(it.transaction.reference) to index }
    }.toMap()
    for (entry in affected) {
        val whole = members[entry.id].orEmpty().map { it.transactionId } + entry.identityTransactionId
        val group = finalGroupByMember[ExistingCashWriteReferenceDto(entry.identityTransactionId)]
        if (group == null || whole.any { parent -> parents.getValue(parent).deletedAt != null ||
                finalGroupByMember[ExistingCashWriteReferenceDto(parent)] != group }) {
            throw CashPolicyError("entry_result_invalid", listOf("entry_dependencies"))
        }
    }
}
