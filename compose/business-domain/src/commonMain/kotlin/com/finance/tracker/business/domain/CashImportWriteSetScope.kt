package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*

/** File-command scope only; trusted facts, source legitimacy, effects and the transaction remain caller-owned. */
internal fun validateCashImportWriteSetScope(changes: CashWriteSetDto, facts: LedgerFactsDto) {
    validateCashWriteSetFixedRecords(changes, facts)
    fun denied(path: String): Nothing = throw CashPolicyError("field_not_editable", listOf(path))
    if (changes.deletedTransactions.isNotEmpty()) denied("deleted_transactions")
    if (changes.issueResolutions.isNotEmpty()) denied("issue_resolutions")
    if (changes.entrySplitChoices.isNotEmpty()) denied("entry_split_choices")

    val parents = facts.cashTransactions.associateBy { it.id }
    for ((index, row) in changes.transactions.withIndex()) {
        val target = row.target.reference as? ExistingCashWriteReferenceDto ?: continue
        val old = parents.getValue(target.id)
        if (old.deletedAt != null || old.recordOrigin !in setOf("import", "legacy")) denied("transactions[$index].target")
    }
    for ((index, row) in changes.relations.withIndex()) {
        if (row.target.reference is ExistingCashWriteReferenceDto) denied("relations[$index].target")
    }
    val bindings = facts.cashSourceBindings.associateBy { it.id }
    for ((index, binding) in changes.sourceBindings.withIndex()) {
        val target = binding.target.reference as? ExistingCashWriteReferenceDto ?: continue
        val old = bindings.getValue(target.id)
        if (binding.transaction != ExistingCashWriteReferenceDto(old.transactionId) && parents.getValue(old.transactionId).deletedAt == null) {
            denied("source_bindings[$index].transaction")
        }
    }
}
