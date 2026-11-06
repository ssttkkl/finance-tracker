package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*

/** Coalesce validated pre-merge edits by their original owner; neither source values nor persisted entries are changed. */
fun prepareCashImportWriteSetEntryEdits(changes: CashWriteSetDto, facts: LedgerFactsDto): CashWriteSetDto {
    validateCashImportWriteSetScope(changes, facts)
    val normalized = normalizeCashWriteSetEntryValues(changes)
    val owners = facts.cashEntryMembers.associate { it.transactionId to it.entryId }
    val groups = linkedMapOf<String, MutableList<Int>>()
    for ((index, row) in normalized.transactions.withIndex()) {
        if (row.entryEdit == null) continue
        val old = row.target.reference as? ExistingCashWriteReferenceDto ?: continue
        val owner = owners[old.id] ?: continue
        groups.getOrPut(owner) { mutableListOf() } += index
    }
    val rows = normalized.transactions.toMutableList()
    for (indices in groups.values) {
        if (indices.size < 2) continue
        val patch = linkedMapOf<String, JsonElement>()
        for (index in indices) mergeCashImportEntryPatch(patch, rows[index].entryEdit!!.patch)
        val first = indices.first()
        rows[first] = rows[first].copy(entryEdit = rows[first].entryEdit!!.copy(patch = JsonObject(patch)))
        for (index in indices.drop(1)) rows[index] = rows[index].copy(entryEdit = null)
    }
    return normalized.copy(transactions = rows)
}

internal fun <V> mergeCashImportEntryPatch(target: MutableMap<String, V>, patch: Map<String, V>) {
    for ((field, value) in patch) {
        if (field in target && target[field] != value) throw CashImportPlanError("plan_input_invalid")
        target[field] = value
    }
}
