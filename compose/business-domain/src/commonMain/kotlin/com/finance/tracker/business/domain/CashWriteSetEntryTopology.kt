package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*

internal data class CashWriteEntryGroup(val root: CashWriteReferenceDto, val members: Set<CashWriteReferenceDto>,
    val economicRoots: List<CashWriteReferenceDto> = emptyList())

internal data class CashWriteEntryTopology(val groups: List<CashWriteEntryGroup>, val affected: List<LedgerCashEntryFactDto>)

private fun invalidWriteEntry(path: String): Nothing = throw CashPolicyError("entry_result_invalid", listOf(path))

/** Affected business groups and old-entry dependencies only; stored identities, values and authorization remain caller-owned. */
internal fun validateCashWriteSetEntryTopology(changes: CashWriteSetDto, facts: LedgerFactsDto, aliases: CashWriteSetSourceAliases = CashWriteSetSourceAliases()): List<LedgerCashEntryFactDto> {
    val topology = prepareCashWriteSetEntryTopology(changes, facts, aliases)
    val requested = changes.entries.mapIndexed { index, row ->
        val group = row.members.mapTo(linkedSetOf()) { aliases.transaction(it.transaction.reference) }
        val path = "entries[$index]"
        if (group.isEmpty() || aliases.transaction(row.rootTransaction.reference) !in group) invalidWriteEntry(path)
        CashWriteEntryGroup(aliases.transaction(row.rootTransaction.reference), group)
    }
    val expectedByMember = topology.groups.flatMap { group -> group.members.map { it to group } }.toMap()
    requested.forEachIndexed { index, entry ->
        val expected = expectedByMember[entry.members.first()] ?: invalidWriteEntry("entries[$index]")
        if (entry.root != expected.root || entry.members != expected.members) invalidWriteEntry("entries[$index]")
    }
    val represented = requested.flatMapTo(mutableSetOf()) { it.members }
    if (topology.groups.any { !represented.containsAll(it.members) }) invalidWriteEntry("entries")
    val observed = changes.entryDependencies.map { it.reference }.toSet()
    for (entry in topology.affected) {
        if (ExistingCashWriteReferenceDto(entry.id) !in observed) throw CashWriteSetReferenceFailure("invalid_reference", "entry_dependencies")
    }
    return topology.affected
}

/** Shared selection for construction and validation; no stored identity or public value is chosen here. */
internal fun prepareCashWriteSetEntryTopology(changes: CashWriteSetDto, facts: LedgerFactsDto,
    aliases: CashWriteSetSourceAliases = CashWriteSetSourceAliases(), additionalParents: Set<CashWriteReferenceDto> = emptySet()): CashWriteEntryTopology {
    val graph = buildCashWriteSetConfirmedGraph(changes, facts, aliases)
    val normalized = graph.normalized
    val refs = facts.cashTransactions.associate { it.id to (ExistingCashWriteReferenceDto(it.id) as CashWriteReferenceDto) }.toMutableMap()
    for (row in normalized.transactions) refs[graph.parents.text(row.target.reference)] = aliases.transaction(row.target.reference)
    val groups = graph.projections.map { projection ->
        CashWriteEntryGroup(refs.getValue(projection.primaryRecord.id.toString()),
            projection.memberIds.mapTo(linkedSetOf()) { refs.getValue(it.toString()) },
            projection.economicRootIds.map { refs.getValue(it.toString()) })
    }.toMutableList()
    val replaced = normalized.transactions.map { aliases.transaction(it.target.reference) }.toSet()
    val deleted = normalized.deletedTransactions.map { it.target.reference }.toSet()
    for (row in facts.cashTransactions) {
        val ref = ExistingCashWriteReferenceDto(row.id)
        if (ref !in replaced && ref !in deleted && row.state == "draft" && row.deletedAt == null)
            groups += CashWriteEntryGroup(ref, setOf(ref))
    }
    val oldParents = facts.cashTransactions.associateBy { it.id }
    for (row in normalized.transactions) if (row.values.state == "draft" &&
        (aliases.transaction(row.target.reference) as? ExistingCashWriteReferenceDto)?.id?.let { oldParents.getValue(it).deletedAt == null } != false) {
        val ref = aliases.transaction(row.target.reference)
        groups += CashWriteEntryGroup(ref, setOf(ref))
    }
    val members = facts.cashEntryMembers.groupBy { it.entryId }.mapValues { (_, items) ->
        items.mapTo(linkedSetOf<CashWriteReferenceDto>()) { ExistingCashWriteReferenceDto(it.transactionId) }
    }
    val scope = writeEntryTopologySeeds(normalized, facts, members, aliases)
    scope += additionalParents.map(aliases::transaction)
    expandWriteEntryScope(scope, members.values + groups.map { it.members })
    val affected = facts.cashEntries.filter { ExistingCashWriteReferenceDto(it.rootTransactionId) in scope ||
        ExistingCashWriteReferenceDto(it.identityTransactionId) in scope || members[it.id].orEmpty().any { parent -> parent in scope } }
    return CashWriteEntryTopology(groups.filter { group -> group.members.any { it in scope } }, affected)
}

private fun expandWriteEntryScope(scope: MutableSet<CashWriteReferenceDto>, groups: List<Set<CashWriteReferenceDto>>) {
    val byMember = mutableMapOf<CashWriteReferenceDto, MutableList<Int>>()
    groups.forEachIndexed { index, group -> for (member in group) byMember.getOrPut(member) { mutableListOf() } += index }
    val expanded = mutableSetOf<Int>()
    val pending = scope.toMutableList()
    var next = 0
    while (next < pending.size) {
        for (index in byMember[pending[next++]].orEmpty()) if (expanded.add(index)) {
            for (member in groups[index]) if (scope.add(member)) pending += member
        }
    }
}

private fun writeEntryTopologySeeds(changes: CashWriteSetDto, facts: LedgerFactsDto,
    oldMembers: Map<String, Set<CashWriteReferenceDto>>, aliases: CashWriteSetSourceAliases): MutableSet<CashWriteReferenceDto> {
    val result = linkedSetOf<CashWriteReferenceDto>()
    changes.transactions.forEach { result += aliases.transaction(it.target.reference) }
    changes.deletedTransactions.forEach { result += it.target.reference }
    val oldRelations = facts.transactionRelations.associateBy { it.id }
    for (row in changes.relations) {
        val old = (row.target.reference as? ExistingCashWriteReferenceDto)?.id?.let(oldRelations::getValue)
        if (row.active && row.state == "confirmed" || old?.let { it.active && it.state == "confirmed" } == true) {
            result += aliases.transaction(row.primaryTransaction.reference)
            row.secondaryTransaction?.let { result += aliases.transaction(it.reference) }
            old?.let { result += ExistingCashWriteReferenceDto(it.primaryTransactionId)
                it.secondaryTransactionId?.let { id -> result += ExistingCashWriteReferenceDto(id) } }
        }
    }
    val oldBindings = facts.cashSourceBindings.associateBy { it.id }
    for (row in changes.sourceBindings) {
        result += aliases.transaction(row.transaction)
        (aliases.binding(row.target.reference) as? ExistingCashWriteReferenceDto)?.id?.let { result += ExistingCashWriteReferenceDto(oldBindings.getValue(it).transactionId) }
    }
    fun oldEntry(reference: CashWriteReferenceDto) {
        (reference as? ExistingCashWriteReferenceDto)?.let { result += oldMembers[it.id].orEmpty() }
    }
    for (row in changes.entries) {
        result += aliases.transaction(row.rootTransaction.reference)
        result += row.members.map { aliases.transaction(it.transaction.reference) }
    }
    changes.entrySplitChoices.forEach { oldEntry(it.entry.reference) }
    return result
}
