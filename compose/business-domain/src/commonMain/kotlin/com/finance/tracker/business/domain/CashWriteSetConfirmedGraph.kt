package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlin.time.Instant

/** Check the final active confirmed graph, including unchanged edges; not a complete command or authorization check. */
fun validateCashWriteSetConfirmedGraph(changes: CashWriteSetDto, facts: LedgerFactsDto) {
    validateCashWriteSetReferences(changes, facts)
    val normalized = normalizeCashWriteSetFinancialValues(changes)
    val parents = WriteGraphIds(normalized.transactions.map { it.target.reference })
    val parts = WriteGraphIds(normalized.transactions.flatMap { it.components }.map { it.reference })
    val edges = WriteGraphIds(normalized.relations.map { it.target.reference })
    val replacedParents = normalized.transactions.existingIds { it.target }
    val deletedParents = normalized.deletedTransactions.existingIds { it.target }
    val oldParents = facts.cashTransactions.associateBy { it.id }
    val componentOwners = linkedMapOf<String, String>()
    for (part in facts.cashTransactionComponents) if (part.cashTransactionId !in replacedParents) {
        componentOwners[part.id] = part.cashTransactionId
    }
    for (row in normalized.transactions) for (part in row.components) componentOwners[parts.text(part.reference)] = parents.text(row.target.reference)
    val funding = facts.cashInvestmentFundingRelations.filter { it.status == "accepted" && it.activeSlot == "active" }.sortedBy { it.id.toLong() }
    val fundingByParent = linkedMapOf<String, Long>()
    for (link in funding) {
        val parent = componentOwners[link.cashTransactionComponentId] ?: relationFailure("relation_endpoint_incomplete")
        if (parent !in fundingByParent) fundingByParent[parent] = link.id.toLong()
    }
    val sourceKeys = writeGraphSourceKeys(normalized, facts, parents)
    val values = CashProjectionValues<CashImportPreviewInteger, Instant>({ it.positive },
        { it.epochSeconds >= -62135596800L && it.epochSeconds < 253402300800L }, ::pythonCashUpper)
    val projections = CashProjectionRules(values, ::formatPythonCashTimestamp)
    val graph = CashRelationGraphRules(values, projections, ::localCashPreviewInteger, CashImportPreviewInteger::parse)
    val formal = linkedMapOf<String, CashProjectionFact<CashImportPreviewInteger, Instant>>()
    fun add(id: String, accountId: String?, occurredAt: String?, amount: String?, currency: String?, counterparty: String,
        categoryId: String?, note: String, sourceType: String?, recordId: String, granularity: String,
        type: String?, subtype: String?, origin: String, manualTransferId: String?) {
        val key = cashSourceOrderKey(sourceType, recordId, sourceKeys[id].orEmpty(), origin, manualTransferId,
            amount?.let { CashAmount.parse(it).compareTo(CashAmount.parse("0")) })
        formal[id] = CashProjectionFact.create(values, CashImportPreviewInteger.parse(id), accountId?.toLong(),
            occurredAt?.let(::parsePythonCashTimestamp), amount, currency, counterparty, categoryId, note, sourceType, recordId,
            fundingByParent[id], granularity, recordType = type, recordSubtype = subtype, stableSourceKey = key, recordOrigin = origin)
    }
    for (row in facts.cashTransactions) if (row.id !in replacedParents && row.id !in deletedParents && row.state == "posted" && row.deletedAt == null) {
        add(row.id, row.accountId, row.occurredAt, row.amount, row.currency, row.counterparty, row.categoryId, row.note,
            row.sourceType, row.recordId, row.cashGranularity, row.recordType, row.recordSubtype, row.recordOrigin, row.manualTransferId)
    }
    for (row in normalized.transactions) if (row.values.state == "posted" &&
        (row.target.reference as? ExistingCashWriteReferenceDto)?.id?.let { oldParents.getValue(it).deletedAt == null } != false) {
        val value = row.values
        add(parents.text(row.target.reference), value.accountId, value.occurredAt, value.amount, value.currency,
            value.counterparty, value.categoryId, value.note, value.sourceType, value.recordId, value.cashGranularity,
            value.recordType, value.recordSubtype, value.recordOrigin, value.manualTransferId)
    }
    if (!formal.keys.containsAll(fundingByParent.keys)) relationFailure("relation_endpoint_incomplete")
    val endpoints = mutableListOf<CashRelationEndpoint>()
    fun endpoint(id: String, parentId: String, accountId: String?, amount: String?, currency: String?) {
        val parent = formal[parentId] ?: return
        endpoints += CashRelationEndpoint(id, parentId, amount?.let(::CashDecimalLiteral), currency, accountId, parent.recordType, parent.recordSubtype)
    }
    for (part in facts.cashTransactionComponents) if (part.cashTransactionId !in replacedParents) {
        endpoint(part.id, part.cashTransactionId, part.accountId, part.amount, part.currency)
    }
    for (row in normalized.transactions) for (part in row.components) {
        endpoint(parts.text(part.reference), parents.text(row.target.reference), part.accountId, part.amount, part.currency)
    }
    fun edge(id: String, kind: String, primary: String, secondary: String?, firstPart: String?, secondPart: String?,
        firstRole: String?, secondRole: String?, amount: String?): CashRelationGraphEdge {
        return CashRelationGraphEdge(CashImportPreviewInteger.parse(id), kind, primary, secondary, firstPart, secondPart,
            firstRole, secondRole, amount?.let(::CashDecimalLiteral))
    }
    val replacedEdges = normalized.relations.existingIds { it.target }
    val finalEdges = mutableListOf<CashRelationGraphEdge>()
    for (item in facts.transactionRelations) if (item.id !in replacedEdges && item.active && item.state == "confirmed") {
        val oldAmount = if (item.kind in setOf("payment_mirror", "refund_offset") && CashAmount.parse(item.appliedAmount) == CashAmount.parse("0")) null
            else item.appliedAmount
        finalEdges += edge(item.id, item.kind, item.primaryTransactionId, item.secondaryTransactionId, item.primaryComponentId,
            item.secondaryComponentId, item.primaryRole, item.secondaryRole, oldAmount)
    }
    for (item in normalized.relations) if (item.active && item.state == "confirmed") {
        finalEdges += edge(edges.text(item.target.reference), item.kind, parents.text(item.primaryTransaction.reference),
            item.secondaryTransaction?.let { parents.text(it.reference) }, item.primaryComponent?.let(parts::text),
            item.secondaryComponent?.let(parts::text), item.primaryRole, item.secondaryRole, item.appliedAmount)
    }
    graph.build(formal.values, endpoints, finalEdges, funding.map { it.cashTransactionComponentId })
}

private fun <T> List<T>.existingIds(target: (T) -> CashWriteVersionedReferenceDto): Set<String> =
    mapNotNull { (target(it).reference as? ExistingCashWriteReferenceDto)?.id }.toSet()

/** Private computation IDs exceed every stored Long and are scoped to an entity kind, never persisted. */
private class WriteGraphIds(references: List<CashWriteReferenceDto>) {
    private val fresh = references.filterIsInstance<NewCashWriteReferenceDto>().mapIndexed { index, ref ->
        ref to "1${index.toString().padStart(20, '0')}"
    }.toMap()
    fun text(ref: CashWriteReferenceDto): String = when (ref) {
        is ExistingCashWriteReferenceDto -> ref.id
        is NewCashWriteReferenceDto -> fresh.getValue(ref)
    }
}

private fun writeGraphSourceKeys(changes: CashWriteSetDto, facts: LedgerFactsDto, parents: WriteGraphIds): Map<String, List<List<String>>> {
    val replaced = changes.sourceBindings.existingIds { it.target }
    val result = linkedMapOf<String, MutableList<List<String>>>()
    for (binding in facts.cashSourceBindings) if (binding.id !in replaced) result.getOrPut(binding.transactionId) { mutableListOf() } +=
        listOf(binding.sourceType, binding.sourceScopeKind, binding.sourceScopeKey, binding.identityKind, binding.identityKey)
    for (binding in changes.sourceBindings) result.getOrPut(parents.text(binding.transaction)) { mutableListOf() } +=
        listOf(binding.sourceType, binding.sourceScopeKind, binding.sourceScopeKey, binding.identityKind, binding.identityKey)
    return result
}
