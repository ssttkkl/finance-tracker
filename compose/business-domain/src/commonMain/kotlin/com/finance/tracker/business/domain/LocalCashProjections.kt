package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import kotlin.time.Instant

/** Derive a cash ledger from one complete fact snapshot without network, storage or mutation. */
fun buildLocalCashProjections(snapshot: LedgerSnapshotDto): CashProjectionBuild<Long, Instant> {
    val data = snapshot.facts
    val values = CashProjectionValues<Long, Instant>({ it > 0 },
        { runCatching { formatPythonCashTimestamp(it) }.isSuccess }, ::pythonCashUpper)
    val projections = CashProjectionRules(values, ::localCashInstantText)
    val graph = CashRelationGraphRules(values, projections, { it as? Long }, String::toLong)
    val rows = data.cashTransactions.filter { it.deletedAt == null }.sortedBy { it.id.toLong() }
    val live = rows.map { it.id }.toSet()
    fun touches(primary: String, secondary: String?, anchor: String, ids: Set<String>) =
        primary in ids || secondary in ids || anchor in ids
    val relations = data.transactionRelations.filter { it.active && touches(it.primaryTransactionId,
        it.secondaryTransactionId, it.anchorTransactionId, live) }.sortedBy { it.id.toLong() }
    val attention = data.cashTransactionIssues.map { it.transactionId }.toMutableSet()
    for (relation in relations.filter { it.state == "draft" }) {
        attention += relation.primaryTransactionId
        relation.secondaryTransactionId?.let(attention::add)
    }
    val components = data.cashTransactionComponents.associateBy { it.id }
    val funding = data.cashInvestmentFundingRelations.filter { it.status == "accepted" && it.activeSlot == "active" }
        .sortedBy { it.id.toLong() }
    val fundingByParent = linkedMapOf<String, Long>()
    for (item in funding) components[item.cashTransactionComponentId]?.let { component ->
        if (component.cashTransactionId !in fundingByParent) fundingByParent[component.cashTransactionId] = item.id.toLong()
    }
    val bindings = data.cashSourceBindings.groupBy { it.transactionId }
    val facts = rows.map { row ->
        val key = cashSourceOrderKey(row.sourceType, row.recordId, bindings[row.id].orEmpty().map {
            listOf(it.sourceType, it.sourceScopeKind, it.sourceScopeKey, it.identityKind, it.identityKey)
        }, row.recordOrigin, row.manualTransferId, row.amount?.let { parseSourceAccountAmount(it).signum })
        CashProjectionFact.create(values, row.id.toLong(), row.accountId?.toLong(),
            row.occurredAt?.let { parsePythonCashTimestamp(it) }, row.amount, row.currency, row.counterparty,
            row.categoryId, row.note, row.sourceType, row.recordId, fundingByParent[row.id], row.cashGranularity,
            row.state, parsePythonCashTimestamp(row.createdAt), row.id in attention, row.recordType, row.recordSubtype,
            key, row.recordOrigin)
    }
    val related = relations.filter { it.state == "confirmed" }
        .flatMap { listOfNotNull(it.primaryTransactionId, it.secondaryTransactionId) }.toSet()
    if (!live.containsAll(related)) throw CashProjectionDomainFailure("projection.incomplete")
    val resolved = if (related.isEmpty()) emptyList() else {
        // Every live confirmed edge contributes both ends; this is already the complete confirmed closure.
        val formal = facts.filter { it.id.toString() in related && it.state == "posted" }
        val parents = data.cashTransactions.associateBy { it.id }
        val formalIds = formal.map { it.id.toString() }.toSet()
        val parts = data.cashTransactionComponents.filter { it.cashTransactionId in formalIds }
            .sortedBy { it.id.toLong() }.map { component ->
                val parent = parents.getValue(component.cashTransactionId)
                CashRelationEndpoint(component.id, component.cashTransactionId, component.amount?.let(::CashDecimalLiteral),
                    component.currency, component.accountId, parent.recordType, parent.recordSubtype)
            }
        val edges = data.transactionRelations.filter { it.active && touches(it.primaryTransactionId,
            it.secondaryTransactionId, it.anchorTransactionId, related) }.sortedBy { it.id.toLong() }.map { relation ->
                val zeroDefault = relation.kind in setOf("payment_mirror", "refund_offset") &&
                    parseSourceAccountAmount(relation.appliedAmount).signum == 0
                CashRelationGraphEdge(relation.id.toLong(), relation.kind, relation.primaryTransactionId,
                    relation.secondaryTransactionId, relation.primaryComponentId, relation.secondaryComponentId,
                    relation.primaryRole, relation.secondaryRole,
                    if (zeroDefault) null else CashDecimalLiteral(relation.appliedAmount), relation.state, relation.active)
            }
        val partIds = parts.map { it.componentId }.toSet()
        try {
            graph.build(formal, parts, edges, funding.map { it.cashTransactionComponentId }.filter { it in partIds })
                .projections.flatMap { it.relations }.sortedBy { it.id }
        } catch (_: CashRelationFailure) {
            throw CashProjectionDomainFailure("projection.invalid_relation")
        }
    }
    return projections.build(facts, resolved)
}
