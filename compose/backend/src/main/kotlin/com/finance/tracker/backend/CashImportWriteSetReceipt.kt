package com.finance.tracker.backend

import com.finance.tracker.business.contracts.*

/** The stored command's actual business results; file-only outcomes stay with the original local batch. */
internal fun cashImportWriteSetReceiptStatistics(context: CashSqlContext, sources: CashImportWriteSetSourceResult,
    relations: Map<CashWriteReferenceDto, Long>, appliedRelations: Set<CashWriteReferenceDto>, after: LedgerFactsDto,
    updatedEntries: Int, written: Boolean): Map<String, Any?> {
    sources.requireOwner(context)
    val changes = sources.changes
    val beforeParents = sources.before.cashTransactions.associateBy { it.id }
    val parents = after.cashTransactions.associateBy { it.id }
    val storedRelations = after.transactionRelations.associateBy { it.id }
    fun parent(reference: CashWriteReferenceDto): String = (sources.transactions[reference]
        ?: (reference as ExistingCashWriteReferenceDto).id.toLong()).toString()
    val summary = listOf("created_posted", "updated_posted", "saved_draft", "finalized_draft", "duplicate",
        "relations_confirmed", "relations_deferred", "relations_ignored", "relations_duplicate").associateWith { 0 }.toMutableMap()
    fun increment(outcome: String) { summary[outcome] = summary.getValue(outcome) + 1 }
    if (updatedEntries > 0) summary["updated_entries"] = updatedEntries
    val affected = linkedSetOf<String>()
    for (row in changes.transactions) {
        val id = parent(row.target.reference)
        val old = beforeParents[id]
        val current = parents.getValue(id)
        val outcome = when {
            old != null && id.toLong() !in sources.affected -> "duplicate"
            current.state == "draft" -> "saved_draft"
            old == null -> "created_posted"
            old.state == "draft" -> "finalized_draft"
            else -> "updated_posted"
        }
        increment(outcome)
        if (outcome != "duplicate") affected += id
    }
    for (group in changes.relationGroups) {
        val first = group.relations.first()
        val outcome = if (first in appliedRelations) "duplicate" else when (storedRelations.getValue(relations.getValue(first).toString()).state) {
            "confirmed" -> "confirmed"
            "draft" -> "deferred"
            else -> "ignored"
        }
        increment("relations_$outcome")
    }
    val previousRelations = sources.before.transactionRelations.associateBy { it.id }
    for (row in after.transactionRelations) {
        if (previousRelations[row.id] == row) continue
        affected += row.primaryTransactionId
        row.secondaryTransactionId?.let(affected::add)
        affected += row.anchorTransactionId
    }
    val previousBindings = sources.before.cashSourceBindings.associateBy { it.id }
    for (binding in after.cashSourceBindings) {
        if (previousBindings[binding.id] != binding) affected += binding.transactionId
    }
    val oldIssues = sources.before.cashTransactionIssues.associateBy { it.id }
    for (issue in after.cashTransactionIssues) if (oldIssues[issue.id] != issue) affected += issue.transactionId
    changes.resolvedIssues.forEach { affected += oldIssues.getValue((it.reference as ExistingCashWriteReferenceDto).id).transactionId }
    if (updatedEntries > 0) {
        val oldEntries = sources.before.cashEntries.associateBy { it.id }
        val newEntriesByRoot = after.cashEntries.associateBy { it.rootTransactionId }
        for (row in changes.entries.filter { it.editedFields.isNotEmpty() }) {
            val now = newEntriesByRoot[parent(row.rootTransaction.reference)] ?: continue
            if (oldEntries[now.id] != now) row.members.forEach { affected += parent(it.transaction.reference) }
        }
    }
    affected.retainAll(after.cashTransactions.filter { it.deletedAt == null }.map { it.id }.toSet())
    val attention = after.cashTransactionIssues.mapTo(mutableSetOf()) { it.transactionId }
    for (row in after.transactionRelations) if (row.active && row.state == "draft") {
        attention += row.primaryTransactionId
        row.secondaryTransactionId?.let(attention::add)
    }
    val pending = attention + after.cashTransactions.filter { it.deletedAt == null && it.state == "draft" }.map { it.id }
    val members = after.cashEntryMembers.groupBy { it.entryId }
    val pendingEntries = members.values.filter { group ->
        group.any { it.transactionId in affected } && group.any { it.transactionId in pending }
    }
    val pendingTransactions = pendingEntries.flatten().map { it.transactionId }.filter { it in affected }.distinct().sorted()
    return mapOf("summary" to summary, "needs_attention_count" to affected.count { it in attention },
        "pending_item_count" to pendingEntries.size, "pending_transaction_ids" to pendingTransactions,
        "commit_outcome" to mapOf("has_new_failures" to false, "has_business_writes" to written, "reason_codes" to emptyList<String>()))
}
