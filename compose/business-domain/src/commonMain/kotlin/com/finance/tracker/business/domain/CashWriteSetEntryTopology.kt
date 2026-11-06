package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*

private data class WriteEntryTopology(
    val root: CashWriteReferenceDto,
    val identity: CashWriteReferenceDto,
    val members: Set<CashWriteReferenceDto>,
    val path: String,
)

private data class WriteEntryGroup(val root: CashWriteReferenceDto, val members: Set<CashWriteReferenceDto>)

private fun invalidWriteEntry(path: String): Nothing = throw CashPolicyError("entry_result_invalid", listOf(path))

/** Affected final groups only; this does not validate stable identity selection, public values, intent or authorization. */
internal fun validateCashWriteSetEntryTopology(changes: CashWriteSetDto, facts: LedgerFactsDto) {
    val graph = buildCashWriteSetConfirmedGraph(changes, facts)
    val normalized = graph.normalized
    val refs = facts.cashTransactions.associate { it.id to (ExistingCashWriteReferenceDto(it.id) as CashWriteReferenceDto) }.toMutableMap()
    for (row in normalized.transactions) refs[graph.parents.text(row.target.reference)] = row.target.reference
    val groups = graph.projections.map { projection ->
        WriteEntryGroup(refs.getValue(projection.primaryRecord.id.toString()),
            projection.memberIds.mapTo(linkedSetOf()) { refs.getValue(it.toString()) })
    }.toMutableList()
    val replaced = normalized.transactions.map { it.target.reference }.toSet()
    val deleted = normalized.deletedTransactions.map { it.target.reference }.toSet()
    for (row in facts.cashTransactions) {
        val ref = ExistingCashWriteReferenceDto(row.id)
        if (ref !in replaced && ref !in deleted && row.state == "draft" && row.deletedAt == null)
            groups += WriteEntryGroup(ref, setOf(ref))
    }
    val oldParents = facts.cashTransactions.associateBy { it.id }
    for (row in normalized.transactions) if (row.values.state == "draft" &&
        (row.target.reference as? ExistingCashWriteReferenceDto)?.id?.let { oldParents.getValue(it).deletedAt == null } != false) {
        val ref = row.target.reference
        groups += WriteEntryGroup(ref, setOf(ref))
    }
    val members = facts.cashEntryMembers.groupBy { it.entryId }.mapValues { (_, items) ->
        items.mapTo(linkedSetOf<CashWriteReferenceDto>()) { ExistingCashWriteReferenceDto(it.transactionId) }
    }
    val finalEntries = facts.cashEntries.associate { row ->
        (ExistingCashWriteReferenceDto(row.id) as CashWriteReferenceDto) to WriteEntryTopology(ExistingCashWriteReferenceDto(row.rootTransactionId),
            ExistingCashWriteReferenceDto(row.identityTransactionId), members[row.id].orEmpty(), "entries")
    }.toMutableMap()
    for (row in normalized.deletedEntries) finalEntries.remove(row.reference)
    normalized.entries.forEachIndexed { index, row ->
        val group = row.members.mapTo(linkedSetOf()) { it.transaction.reference }
        val path = "entries[$index]"
        if (group.isEmpty() || row.rootTransaction.reference !in group || row.identityTransaction.reference !in group) invalidWriteEntry(path)
        finalEntries[row.target.reference] = WriteEntryTopology(row.rootTransaction.reference, row.identityTransaction.reference, group, path)
    }
    val scope = writeEntryTopologySeeds(normalized, facts, members)
    expandWriteEntryScope(scope, members.values + groups.map { it.members })
    val expectedByMember = groups.flatMap { group -> group.members.map { it to group } }.toMap()
    val represented = mutableSetOf<CashWriteReferenceDto>()
    for ((_, entry) in finalEntries) if (entry.root in scope || entry.identity in scope || entry.members.any { it in scope }) {
        val expected = entry.members.firstOrNull()?.let(expectedByMember::get) ?: invalidWriteEntry(entry.path)
        if (entry.members != expected.members || entry.root != expected.root || entry.identity !in entry.members) invalidWriteEntry(entry.path)
        represented += entry.members
    }
    if (groups.any { group -> group.members.any { it in scope } && !represented.containsAll(group.members) }) invalidWriteEntry("entries")
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
    oldMembers: Map<String, Set<CashWriteReferenceDto>>): MutableSet<CashWriteReferenceDto> {
    val result = linkedSetOf<CashWriteReferenceDto>()
    changes.transactions.forEach { result += it.target.reference }
    changes.deletedTransactions.forEach { result += it.target.reference }
    val oldRelations = facts.transactionRelations.associateBy { it.id }
    for (row in changes.relations) {
        val old = (row.target.reference as? ExistingCashWriteReferenceDto)?.id?.let(oldRelations::getValue)
        if (row.active && row.state == "confirmed" || old?.let { it.active && it.state == "confirmed" } == true) {
            result += row.primaryTransaction.reference
            row.secondaryTransaction?.let { result += it.reference }
            old?.let { result += ExistingCashWriteReferenceDto(it.primaryTransactionId)
                it.secondaryTransactionId?.let { id -> result += ExistingCashWriteReferenceDto(id) } }
        }
    }
    val oldBindings = facts.cashSourceBindings.associateBy { it.id }
    for (row in changes.sourceBindings) {
        result += row.transaction
        (row.target.reference as? ExistingCashWriteReferenceDto)?.id?.let { result += ExistingCashWriteReferenceDto(oldBindings.getValue(it).transactionId) }
    }
    fun oldEntry(reference: CashWriteReferenceDto) {
        (reference as? ExistingCashWriteReferenceDto)?.let { result += oldMembers[it.id].orEmpty() }
    }
    for (row in changes.entries) {
        oldEntry(row.target.reference)
        result += row.rootTransaction.reference
        result += row.identityTransaction.reference
        result += row.members.map { it.transaction.reference }
    }
    changes.deletedEntries.forEach { oldEntry(it.reference) }
    changes.entrySplitChoices.forEach { oldEntry(it.entry.reference) }
    return result
}
