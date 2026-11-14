package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*

/** Validate effective choices, without selecting automatic values again after storage IDs have been allocated. */
fun prepareCashImportWriteSetCommonChoices(changes: CashWriteSetDto, facts: LedgerFactsDto, aliases: CashWriteSetSourceAliases = CashWriteSetSourceAliases()): List<List<CashRelationWriteGroupDto>> {
    validateCashImportWriteSetScope(changes, facts, aliases)
    val normalized = normalizeCashWriteSetEntryValues(changes)
    val batches = prepareCashImportWriteSetRelationBatches(normalized, facts, aliases)
    val relations = normalized.relations.associateBy { it.target.reference }
    fun accepted(group: CashRelationWriteGroupDto) = relations.getValue(group.relations.first()).state == "confirmed"
    if (batches.none { batch -> batch.any(::accepted) }) return batches
    val graph = buildCashWriteSetConfirmedGraph(normalized, facts, aliases)
    val references = facts.cashTransactions.associate { it.id to (ExistingCashWriteReferenceDto(it.id) as CashWriteReferenceDto) }.toMutableMap()
    for (row in normalized.transactions) references[graph.parents.text(row.target.reference)] = aliases.transaction(row.target.reference)
    val requiredByProjection = graph.projections.filter { it.memberIds.size > 1 }.map { projection ->
        projection.memberIds.mapTo(linkedSetOf()) { references.getValue(it.toString()) }
    }
    val projectionByParent = requiredByProjection.flatMapIndexed { index, members -> members.map { it to index } }.toMap()
    val manualFields = writeCommonManualFields(normalized, facts, aliases)
    val positions = normalized.relationGroups.withIndex().associate { it.value to it.index }
    fun invalid(group: CashRelationWriteGroupDto): Nothing = throw CashPolicyError("entry_result_invalid",
        listOf("relation_groups[${positions.getValue(group)}].common_fields"))
    for (batch in batches) {
        val chosen = batch.filter(::accepted)
        if (chosen.isEmpty()) continue
        val patch = chosen.first().commonFields
        for (group in chosen.drop(1)) if (group.commonFields != patch) invalid(group)
        val parents = batch.flatMapTo(mutableSetOf()) { group -> group.relations.flatMap { reference ->
            val row = relations.getValue(reference)
            listOfNotNull(row.primaryTransaction.reference, row.secondaryTransaction?.reference).map(aliases::transaction)
        } }
        val required = parents.mapNotNull(projectionByParent::get).toSet()
            .flatMapTo(mutableSetOf()) { index -> requiredByProjection[index].flatMap(manualFields) }
        if (!patch.keys.containsAll(required)) invalid(chosen.first())
    }
    return batches
}

private fun writeCommonManualFields(changes: CashWriteSetDto, facts: LedgerFactsDto, aliases: CashWriteSetSourceAliases): (CashWriteReferenceDto) -> Set<String> {
    val owners = facts.cashEntryMembers.associate { it.transactionId to it.entryId }
    val entries = facts.cashEntries.associateBy { it.id }
    val editedEntries = mutableMapOf<String, MutableSet<String>>()
    val editedParents = mutableMapOf<CashWriteReferenceDto, MutableSet<String>>()
    for (row in changes.transactions) row.entryEdit?.let { edit ->
        val owner = (aliases.transaction(row.target.reference) as? ExistingCashWriteReferenceDto)?.id?.let(owners::get)
        if (owner == null) editedParents.getOrPut(aliases.transaction(row.target.reference)) { mutableSetOf() } += edit.patch.keys
        else editedEntries.getOrPut(owner) { mutableSetOf() } += edit.patch.keys
    }
    val cached = mutableMapOf<String, Set<String>>()
    return { reference ->
        val owner = (aliases.transaction(reference) as? ExistingCashWriteReferenceDto)?.id?.let(owners::get)
        if (owner == null) editedParents[aliases.transaction(reference)].orEmpty()
        else cached.getOrPut(owner) {
            entries.getValue(owner).manualFields.jsonArray.mapTo(mutableSetOf()) {
                check(it is JsonPrimitive && it.isString)
                it.content
            }.intersect(commonCashFields) + editedEntries[owner].orEmpty()
        }
    }
}
