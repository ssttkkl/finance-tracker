package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*

/** Structural batches only; public-value inheritance, effects and the authoritative transaction remain caller-owned. */
internal fun prepareCashImportWriteSetRelationBatches(changes: CashWriteSetDto, facts: LedgerFactsDto): List<List<CashRelationWriteGroupDto>> {
    validateCashImportWriteSetScope(changes, facts)
    // Typed, private link keys preserve opaque references; they are neither stored IDs nor execution-order keys.
    fun key(reference: CashWriteReferenceDto): String = when (reference) {
        is ExistingCashWriteReferenceDto -> "existing:${reference.id}"
        is NewCashWriteReferenceDto -> "new:${reference.ref}"
    }
    val old = facts.transactionRelations.map { row ->
        CashRelationLink(key(ExistingCashWriteReferenceDto(row.id)), key(ExistingCashWriteReferenceDto(row.primaryTransactionId)),
            row.secondaryTransactionId?.let { key(ExistingCashWriteReferenceDto(it)) }, row.state, row.active)
    }
    val relations = changes.relations.associateBy { it.target.reference }
    val incoming = changes.relationGroups.flatMapIndexed { index, group -> group.relations.map { reference ->
        val row = relations.getValue(reference)
        CashRelationLink(key(reference), key(row.primaryTransaction.reference), row.secondaryTransaction?.let { key(it.reference) },
            row.state, row.active, "choice:$index")
    } }
    val batchByEdge = cashRelationCommitGroups(old, incoming).flatMapIndexed { index, edges -> edges.map { it to index } }.toMap()
    val ordered = linkedMapOf<Int, MutableList<CashRelationWriteGroupDto>>()
    for (group in changes.relationGroups) {
        val batch = batchByEdge.getValue(key(group.relations.first()))
        ordered.getOrPut(batch) { mutableListOf() } += group
    }
    return ordered.values.map { it.toList() }
}
