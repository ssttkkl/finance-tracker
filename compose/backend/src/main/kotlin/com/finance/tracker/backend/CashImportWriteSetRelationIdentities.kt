package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.CASH_TRANSACTIONS
import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.normalizeCashWriteSetEntryValues
import com.finance.tracker.business.domain.prepareCashImportWriteSetRelationBatches
import com.finance.tracker.business.domain.validateCashImportWriteSetBatchChoices
import kotlinx.serialization.json.JsonElement

/** Whole applied batches only; no mutation, revision synthesis, B execution or heuristic discovery. */
internal fun resolveCashImportWriteSetRelationIdentities(context: CashSqlContext, sources: CashImportWriteSetSourceResult): Map<CashWriteReferenceDto, Long> {
    sources.requireOwner(context)
    // Source preparation already checked every original reference and observed dependency, before any filtering.
    val changes = normalizeCashWriteSetEntryValues(sources.changes)
    val batches = prepareCashImportWriteSetRelationBatches(changes, sources.before, sources.aliases)
    validateCashImportWriteSetBatchChoices(changes, batches)
    if (changes.relations.isEmpty()) return emptyMap()
    fun parent(ref: CashWriteReferenceDto): String = (sources.transactions[ref] ?: (ref as ExistingCashWriteReferenceDto).id.toLong()).toString()
    fun part(ref: CashWriteReferenceDto?): String? = ref?.let { (sources.components[it] ?: (it as ExistingCashWriteReferenceDto).id.toLong()).toString() }
    fun key(kind: String, primary: String, secondary: String?, firstPart: String?, secondPart: String?) =
        cashImportEdgeKey(CashRelationGraphEdge(0L, kind, primary, secondary, firstPart, secondPart, null, null, null))
    fun key(row: LedgerCashRelationFactDto) = key(row.kind, row.primaryTransactionId, row.secondaryTransactionId, row.primaryComponentId, row.secondaryComponentId)
    fun key(row: CashRelationWriteDto) = key(row.kind, parent(row.primaryTransaction.reference), row.secondaryTransaction?.let { parent(it.reference) }, part(row.primaryComponent), part(row.secondaryComponent))
    fun conflict(index: Int): Nothing = throw CashPolicyError("relation_context_changed", listOf("relations[$index]"))
    fun sameJson(first: JsonElement, second: JsonElement) = CashRecordPolicy.valuesEqual(cashSourceValue(first), cashSourceValue(second))
    val repository = CurrentCashRelationWriteRepository(context)
    val positions = changes.relations.withIndex().associate { it.value.target.reference to it.index }
    val rows = changes.relations.associateBy { it.target.reference }
    val incomingKeys = mutableSetOf<List<Any?>>()
    // Apply the real writer's JSON normalization/budget even when no relation DML will run.
    for ((index, row) in changes.relations.withIndex()) {
        if (row.decisionSource !in setOf("manual", "rule") || row.state != "draft" && row.candidates.isNotEmpty())
            throw CashPolicyError("invalid_field", listOf("relations[$index]"))
        repository.validateInput(mapOf("kind" to row.kind, "state" to row.state, "active" to row.active,
            "primary_transaction_id" to parent(row.primaryTransaction.reference), "secondary_transaction_id" to row.secondaryTransaction?.let { parent(it.reference) },
            "primary_component_id" to part(row.primaryComponent), "secondary_component_id" to part(row.secondaryComponent),
            "anchor_transaction_id" to parent(row.anchorTransaction.reference), "anchor_component_id" to part(row.anchorComponent),
            "primary_role" to row.primaryRole, "secondary_role" to row.secondaryRole, "applied_amount" to row.appliedAmount,
            "decision_source" to row.decisionSource, "rule_id" to row.ruleId, "decision_reason" to row.decisionReason,
            "evidence" to checkedRelationWriteEvidence(row.evidence)))
        for (candidate in row.candidates) repository.validateEvidence(checkedRelationWriteEvidence(candidate.evidence))
        if (!incomingKeys.add(key(row))) throw CashPolicyError("duplicate_reference", listOf("relations[$index]"))
    }
    val oldByKey = sources.before.transactionRelations.filter { it.active }.groupBy(::key)
    val oldCandidates = sources.before.cashRelationCandidates.groupBy { it.relationId }
    val oldGroups = sources.before.transactionRelations.filter { !it.decisionGroupId.isNullOrEmpty() }.groupBy { it.decisionGroupId }
    val candidates = changes.relations.flatMap { it.candidates }.map { parent(it.transaction.reference).toLong() }.distinct()
    val table = CASH_TRANSACTIONS
    val candidateRevisions = candidates.chunked(400).flatMap { ids -> context.sql.select(table.ID, table.ROW_REVISION).from(table)
        .where(table.WORKSPACE_ID.eq(context.workspace).and(table.ID.`in`(ids)).and(table.DELETED_AT.isNull).and(table.STATE.eq("posted"))).fetch() }
        .associate { it.value1()!!.toString() to it.value2()!!.toString() }
    fun exact(row: CashRelationWriteDto, old: LedgerCashRelationFactDto): Boolean {
        if (listOf(row.kind, row.state, row.active, parent(row.primaryTransaction.reference), row.secondaryTransaction?.let { parent(it.reference) },
                part(row.primaryComponent), part(row.secondaryComponent), parent(row.anchorTransaction.reference), part(row.anchorComponent), row.primaryRole, row.secondaryRole) !=
            listOf(old.kind, old.state, old.active, old.primaryTransactionId, old.secondaryTransactionId, old.primaryComponentId,
                old.secondaryComponentId, old.anchorTransactionId, old.anchorComponentId, old.primaryRole, old.secondaryRole)) return false
        if (CashAmount.parse(row.appliedAmount).compareTo(CashAmount.parse(old.appliedAmount)) != 0 || !sameJson(row.evidence, old.evidence)) return false
        if (row.state != "draft") return true
        val candidates = oldCandidates[old.id].orEmpty()
        if (candidates.isNotEmpty() && listOfNotNull(old.primaryTransactionId, old.secondaryTransactionId, old.anchorTransactionId)
                .any { it.toLong() in sources.affected }) return false
        if (candidates.size != row.candidates.size || candidates.any { it.validity != "valid" }) return false
        val byEndpoint = candidates.associateBy { it.candidateTransactionId to it.candidateComponentId }
        check(byEndpoint.size == candidates.size) { "write_set.duplicate_persisted_candidate" }
        return row.candidates.all { candidate ->
            val id = parent(candidate.transaction.reference)
            val previous = byEndpoint[id to part(candidate.component)]
            previous != null && candidate.validity == "valid" && previous.expectedRowRevision == candidateRevisions[id] && sameJson(candidate.evidence, previous.evidence)
        }
    }
    val result = linkedMapOf<CashWriteReferenceDto, Long>()
    for (batch in batches) {
        val members = batch.flatMap { it.relations }.map(rows::getValue)
        val matched = linkedMapOf<CashWriteReferenceDto, LedgerCashRelationFactDto>()
        for (row in members) {
            val previous = oldByKey[key(row)].orEmpty()
            if (previous.isEmpty()) continue
            if (previous.size != 1 || !exact(row, previous.single())) conflict(positions.getValue(row.target.reference))
            matched[row.target.reference] = previous.single()
        }
        if (matched.isEmpty()) continue
        if (matched.size != members.size) conflict(positions.getValue(members.first().target.reference))
        val ids = matched.values.mapTo(mutableSetOf()) { it.id }
        val joint = matched.values.mapNotNull { it.decisionGroupId?.takeIf(String::isNotEmpty) }.toSet()
        if (joint.any { group -> oldGroups[group].orEmpty().any { !it.active || it.id !in ids } })
            conflict(positions.getValue(members.first().target.reference))
        for ((ref, old) in matched) result[ref] = old.id.toLong()
    }
    return result.toMap()
}
