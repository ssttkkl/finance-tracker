package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.prepareCashImportWriteSetSourceMetadata
import com.finance.tracker.business.domain.resolveCashWriteSetSourceIssueReferences

/** After bindings, in the same source transaction. Relation problems and the complete command remain separate phases. */
internal fun saveCashImportWriteSetSourceMetadata(context: CashSqlContext, sources: CashImportWriteSetSourceResult): Map<CashWriteReferenceDto, Long> {
    sources.requireOwner(context)
    val changes = prepareCashImportWriteSetSourceMetadata(sources.changes, sources.before, sources.aliases)
    CashSourceDocumentWriteRepository(context).saveEmptyEvidence(changes.sourceEmptyPages.map {
        SourceDocumentEvidence(it.sourceType, it.parserVersion, cashSourceCoverage(it.coverage)) })
    val issues = changes.issues.filterNot { it.issueKey.startsWith("relation:") }
    val identities = resolveCashWriteSetSourceIssueReferences(changes, sources.before, sources.aliases)
    val previousCandidates = sources.before.cashIssueCandidates.groupBy { it.issueId }
    val table = CASH_TRANSACTION_ISSUES
    val existing = context.sql.selectFrom(table).where(table.WORKSPACE_ID.eq(context.workspace)).fetch()
    val byId = existing.associateBy { it[table.ID]!!.toString() }
    fun changed(): Nothing = throw CashPolicyError("reference_changed", listOf("issues"))
    for (target in issues.map { it.target } + changes.resolvedIssues) {
        val ref = target.reference as? ExistingCashWriteReferenceDto ?: continue
        val old = byId[ref.id] ?: changed()
        if (old[table.REVISION]!!.toString() != target.expectedRevision) changed()
    }
    val repository = CashSourceWriteRepository(context)
    for (target in changes.resolvedIssues) {
        val old = byId.getValue((target.reference as ExistingCashWriteReferenceDto).id)
        repository.clearIssue(old[table.TRANSACTION_ID]!!, old[table.ISSUE_KEY]!!)
    }
    val removed = changes.resolvedIssues.map { (it.reference as ExistingCashWriteReferenceDto).id }.toSet()
    val keys = existing.filter { it[table.ID]!!.toString() !in removed }.associateBy { it[table.TRANSACTION_ID]!! to it[table.ISSUE_KEY]!! }
    fun resolve(reference: CashWriteReferenceDto): Long = sources.transactions[reference] ?: when (reference) {
        is ExistingCashWriteReferenceDto -> reference.id.toLong()
        is NewCashWriteReferenceDto -> error("write_set.missing_source_identity")
    }
    val candidateIds = issues.flatMap { it.candidates }.map { resolve(it.reference) }.distinct()
    val transaction = CASH_TRANSACTIONS
    val revisions = candidateIds.chunked(400).flatMap { ids -> context.sql.select(transaction.ID, transaction.ROW_REVISION).from(transaction)
        .where(transaction.WORKSPACE_ID.eq(context.workspace).and(transaction.ID.`in`(ids)).and(transaction.DELETED_AT.isNull)).fetch() }
        .associate { it.value1()!! to it.value2()!! }
    val result = linkedMapOf<CashWriteReferenceDto, Long>()
    for (row in issues) {
        val parent = resolve(row.transaction.reference)
        val old = keys[parent to row.issueKey]
        val exact = (row.target.reference as? NewCashWriteReferenceDto)?.let(identities::get)
        val expected = (row.target.reference as? ExistingCashWriteReferenceDto)?.id ?: exact
        if (old?.get(table.ID)?.toString() != expected) throw CashPolicyError("source_identity_conflict", listOf("issues"))
        if (old != null && old[table.CODE] == "source_identity_ambiguous" && row.candidates.isEmpty()) {
            result[row.target.reference] = old[table.ID]!!
            continue
        }
        val candidates = linkedMapOf<Long, Long>()
        for (candidate in row.candidates) {
            val id = resolve(candidate.reference)
            val revision = revisions[id] ?: throw CashSourceBindingConflict("source_candidate_changed")
            if (candidates.put(id, revision) != null) throw CashSourceBindingConflict("source_candidate_changed")
        }
        if (exact != null) {
            val previous = previousCandidates[exact].orEmpty().associate { it.candidateTransactionId.toLong() to it.expectedRowRevision.toLong() }
            if (candidates != previous) throw CashPolicyError("source_identity_conflict", listOf("issues"))
        }
        val saved = repository.saveIssue(parent, row.issueKey, row.code, row.fieldPath, cashSourceValue(row.pendingSourcePayload),
            cashSourceValue(row.pendingFields), cashSourceValue(row.origin), candidates)
        result[row.target.reference] = saved.id
    }
    return result
}
