package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlin.time.Instant

/** Read the original relation context from one workspace's complete facts; no discovery, writes or IO. */
fun readLocalCashRelationContext(snapshot: LedgerSnapshotDto, seedIds: Set<Long>,
    decisionGroups: List<String> = emptyList()): CashRelationReadContext<Long, Instant> {
    if (seedIds.any { it <= 0 }) relationFailure("relation_data_invalid")
    val groups = decisionGroups.distinct().sortedWith(cashRelationStringOrder)
    if (groups.any { it.pythonCodePointCount() !in 1..128 }) relationFailure("relation_data_invalid")
    try { groups.forEach { it.encodeToByteArray(throwOnInvalidSequence = true) } }
    catch (_: CharacterCodingException) { relationFailure("relation_data_invalid") }
    val data = snapshot.facts
    val groupMembers = data.transactionRelations.filter { it.decisionGroupId in groups }.sortedBy { it.id.toLong() }
    if (groups.toSet() != groupMembers.mapNotNull { it.decisionGroupId }.toSet()) relationFailure("relation_group_changed")
    val wanted = seedIds + groupMembers.flatMap { listOfNotNull(it.primaryTransactionId.toLong(), it.secondaryTransactionId?.toLong()) }
    val allParents = data.cashTransactions.associateBy { it.id.toLong() }
    if (!allParents.keys.containsAll(wanted)) relationFailure("relation_endpoint_incomplete")
    val neighbors = linkedMapOf<Long, MutableSet<Long>>()
    for (row in data.transactionRelations) if (row.active && row.state == "confirmed") {
        val first = row.primaryTransactionId.toLong()
        val second = row.secondaryTransactionId?.toLong() ?: continue
        neighbors.getOrPut(first) { linkedSetOf() }.add(second)
        neighbors.getOrPut(second) { linkedSetOf() }.add(first)
    }
    val reached = wanted.toMutableSet()
    val pendingIds = wanted.toMutableList()
    while (pendingIds.isNotEmpty()) for (next in neighbors[pendingIds.removeAt(pendingIds.lastIndex)].orEmpty())
        if (reached.add(next)) pendingIds.add(next)
    val closure = reached.sorted().toSet()
    val parents = closure.mapNotNull { allParents[it] }.associateBy { it.id.toLong() }
    val rows = (data.transactionRelations.filter { it.active && (it.primaryTransactionId.toLong() in closure ||
        it.secondaryTransactionId?.toLong() in closure || it.anchorTransactionId.toLong() in closure) } + groupMembers)
        .distinctBy { it.id }.sortedBy { it.id.toLong() }
    val issues = data.cashTransactionIssues.filter { it.transactionId.toLong() in parents }.sortedBy { it.id.toLong() }
        .map { CashRelationIssueVersion(it.id.toLong(), it.transactionId.toLong(), it.revision.toLong()) }
    val attention = issues.map { it.transactionId }.toMutableSet()
    for (row in rows) if (row.active && row.state == "draft")
        listOfNotNull(row.primaryTransactionId.toLong(), row.secondaryTransactionId?.toLong()).filter { it in parents }.forEach(attention::add)
    val formal = parents.filterValues { it.state == "posted" && it.deletedAt == null }
    val parts = data.cashTransactionComponents.filter { it.cashTransactionId.toLong() in parents }.sortedBy { it.id.toLong() }
    val byPart = parts.associateBy { it.id }
    val funding = data.cashInvestmentFundingRelations.filter {
        it.cashTransactionComponentId in byPart && it.status == "accepted" && it.activeSlot == "active"
    }.sortedBy { it.id.toLong() }.map {
        CashRelationFundingVersion(it.id.toLong(), it.cashTransactionComponentId.toLong(), it.investmentEventId.toLong(), it.direction)
    }
    val fundingByParent = linkedMapOf<Long, Long>()
    funding.forEach {
        val parent = byPart.getValue(it.componentId.toString()).cashTransactionId.toLong()
        if (parent !in fundingByParent) fundingByParent[parent] = it.id
    }
    val bindings = data.cashSourceBindings.groupBy { it.transactionId }
    val sourceKeys = parents.mapValues { (_, row) -> cashSourceOrderKey(row.sourceType, row.recordId,
        bindings[row.id].orEmpty().map { listOf(it.sourceType, it.sourceScopeKind, it.sourceScopeKey, it.identityKind, it.identityKey) },
        row.recordOrigin, row.manualTransferId, row.amount?.let(::contextDecimalOrNull)?.signum) }
    val values = CashProjectionValues<Long, Instant>({ it > 0 },
        { try { formatPythonCashTimestamp(it); true } catch (_: IllegalArgumentException) { false } }, ::pythonCashUpper)
    val facts = try { formal.values.sortedBy { it.id.toLong() }.map { row ->
        CashProjectionFact.create(values, row.id.toLong(), row.accountId?.toLong(), row.occurredAt?.let(::parsePythonCashTimestamp),
            row.amount, row.currency, row.counterparty, row.categoryId, row.note, row.sourceType, row.recordId,
            fundingByParent[row.id.toLong()], row.cashGranularity, "posted", null, row.id.toLong() in attention,
            row.recordType, row.recordSubtype, sourceKeys.getValue(row.id.toLong()), row.recordOrigin)
    } } catch (_: CashProjectionFailure) { relationFailure("relation_endpoint_incompatible") }
    val components = parts.filter { it.cashTransactionId.toLong() in formal }.map { part ->
        val parent = formal.getValue(part.cashTransactionId.toLong())
        CashRelationEndpoint(part.id, part.cashTransactionId, part.amount?.let(::CashDecimalLiteral), part.currency,
            part.accountId, parent.recordType, parent.recordSubtype)
    }
    val entries = data.cashEntries.associateBy { it.id }
    val entryVersions = data.cashEntryMembers.filter { it.transactionId.toLong() in closure }.sortedBy { it.transactionId.toLong() }
        .map { member -> entries.getValue(member.entryId).let { entry ->
            CashRelationEntrySnapshot(member.transactionId.toLong(), entry.id, entry.revision.toLong(), localCashEntrySnapshotDigest(entry, member))
        } }
    return CashRelationReadContext(seedIds.toSet(), groups, closure, facts, components, rows.map { row ->
        val zeroDefault = row.kind in setOf("payment_mirror", "refund_offset") && contextDecimalOrNull(row.appliedAmount)?.signum == 0
        CashRelationGraphEdge(row.id.toLong(), row.kind, row.primaryTransactionId, row.secondaryTransactionId,
            row.primaryComponentId, row.secondaryComponentId, row.primaryRole, row.secondaryRole,
            if (zeroDefault) null else CashDecimalLiteral(row.appliedAmount), row.state, row.active)
    }, groupMembers.map { CashJointRelationMember(it.id, it.decisionGroupId, it.rowRevision.toLong(), it.state, it.active) },
        parents.values.sortedBy { it.id.toLong() }.map { it.id.toLong() to it.rowRevision.toLong() },
        parents.values.filter { it.deletedAt == null }.map { it.id.toLong() }.toSet(),
        parts.map { CashRelationComponentVersion(it.id.toLong(), it.cashTransactionId.toLong(), it.accountId?.toLong(),
            it.amount?.let(::localCashStoredDecimalText), it.currency, it.ordinal.toLong()) },
        rows.map { it.id.toLong() to it.rowRevision.toLong() }, issues, entryVersions,
        rows.mapNotNull { row -> row.decisionGroupId?.let { row.id.toLong() to it } }, funding,
        funding.filter { byPart.getValue(it.componentId.toString()).cashTransactionId.toLong() in formal }
            .map { it.componentId.toString() }.toSet(),
        sourceKeys.entries.sortedBy { it.key }.map { it.key to it.value })
}

private fun contextDecimalOrNull(text: String): LedgerArithmeticValue? = try { parseSourceAccountAmount(text) }
    catch (_: NumberFormatException) { null }
