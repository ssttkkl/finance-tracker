package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*

/** Same source issue only. Existing observations and all final input/operation gates still apply. */
fun resolveCashWriteSetSourceIssueReferences(changes: CashWriteSetDto, facts: LedgerFactsDto,
    aliases: CashWriteSetSourceAliases = CashWriteSetSourceAliases()): Map<NewCashWriteReferenceDto, String> {
    val retired = changes.resolvedIssues.mapNotNull { (it.reference as? ExistingCashWriteReferenceDto)?.id }.toSet()
    val oldIssues = facts.cashTransactionIssues.associateBy { it.transactionId to it.issueKey }
    check(oldIssues.size == facts.cashTransactionIssues.size) { "write_set.duplicate_persisted_key" }
    val oldCandidates = facts.cashIssueCandidates.groupBy { it.issueId }
    val parents = facts.cashTransactions.associateBy { it.id }
    val result = linkedMapOf<NewCashWriteReferenceDto, String>()
    for ((index, row) in changes.issues.withIndex()) {
        val reference = row.target.reference as? NewCashWriteReferenceDto ?: continue
        if (reference.ref.isEmpty() || row.target.expectedRevision != null || row.issueKey.startsWith("relation:")) continue
        val owner = (aliases.transaction(row.transaction.reference) as? ExistingCashWriteReferenceDto)?.id ?: continue
        val old = oldIssues[owner to row.issueKey] ?: continue
        if (old.id in retired || old.code != row.code || old.fieldPath != row.fieldPath) continue
        val origin = cashImportWriteSetIssueOrigin(row, "issues[$index]")
        if (old.code == "source_identity_ambiguous" && row.candidates.isEmpty()) {
            result[reference] = old.id
            continue
        }
        check(listOf(old.pendingSourcePayload, old.pendingFields, old.origin).all { metadataJson(it) }) { "write_set.invalid_persisted_issue" }
        fun same(first: JsonElement, second: JsonElement) = LocalCashRecordValues.valuesEqual(decodeLocalCashValue(first), decodeLocalCashValue(second))
        if (!same(row.pendingSourcePayload, old.pendingSourcePayload) || !same(row.pendingFields, old.pendingFields) || !same(origin, old.origin)) continue
        val candidates = oldCandidates[old.id].orEmpty()
        val stored = candidates.associate { it.candidateTransactionId to it.expectedRowRevision }
        check(stored.size == candidates.size) { "write_set.duplicate_persisted_issue_candidate" }
        val desired = linkedMapOf<String, String>()
        for (candidate in row.candidates) {
            val id = (aliases.transaction(candidate.reference) as? ExistingCashWriteReferenceDto)?.id ?: continue
            val revision = if (candidate.reference is NewCashWriteReferenceDto) parents[id]?.rowRevision else candidate.expectedRevision
            if (revision != null) desired[id] = revision
        }
        if (desired.size != row.candidates.size || desired != stored || desired.any { (id, revision) -> parents[id]?.rowRevision != revision }) continue
        result[reference] = old.id
    }
    return result
}
