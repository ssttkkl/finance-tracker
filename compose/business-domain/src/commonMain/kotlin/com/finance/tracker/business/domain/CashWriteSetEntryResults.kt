package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*

/** Compare declared business results with complete post-write facts; authorization and pre-write topology remain caller-owned. */
internal fun validateCashWriteSetEntryResults(changes: CashWriteSetDto, actual: LedgerFactsDto,
    resolve: (CashWriteReferenceDto) -> String) {
    val expected = normalizeCashWriteSetEntryValues(CashWriteSetDto(entries = changes.entries)).entries
    if (expected.isEmpty()) return
    val entries = actual.cashEntries.associateBy { it.id }
    val members = actual.cashEntryMembers.associateBy { it.transactionId }
    check(entries.size == actual.cashEntries.size && members.size == actual.cashEntryMembers.size)
    val byOwner = actual.cashEntryMembers.groupBy { it.entryId }.mapValues { (_, rows) -> rows.mapTo(mutableSetOf()) { it.transactionId } }
    val claimed = mutableSetOf<String>()
    fun invalid(path: String): Nothing = throw CashPolicyError("entry_result_invalid", listOf(path))
    for ((index, row) in expected.withIndex()) {
        val path = "entries[$index]"
        val root = resolve(row.rootTransaction.reference)
        val owner = members[root]?.entryId ?: invalid("$path.root_transaction")
        val entry = entries.getValue(owner)
        if (!claimed.add(owner)) invalid(path)
        if (entry.rootTransactionId != root) invalid("$path.root_transaction")
        val identifiers = row.members.map { resolve(it.transaction.reference) }
        val group = identifiers.toSet()
        if (group.isEmpty() || group.size != identifiers.size || root !in group || byOwner.getValue(owner) != group) invalid("$path.members")
        val values = writeEntryResultValues(row.categoryId, row.note, row.counterparty, row.counterpartyAccount, row.counterpartyAccountAttrs)
        val stored = writeEntryResultValues(entry.categoryId, entry.note, entry.counterparty, entry.counterpartyAccount, entry.counterpartyAccountAttrs)
        for (field in commonCashFields) if (values[field] != stored[field]) invalid("$path.$field")
        if (row.manualFields.toSet() != writeEntryResultFlags(entry.manualFields)) invalid("$path.manual_fields")
        for ((memberIndex, member) in row.members.withIndex()) {
            val memberPath = "$path.members[$memberIndex]"
            val current = members.getValue(identifiers[memberIndex])
            if (member.standaloneValues != current.standaloneValues) invalid("$memberPath.standalone_values")
            if (member.standaloneManualFields.toSet() != writeEntryResultFlags(current.standaloneManualFields)) {
                invalid("$memberPath.standalone_manual_fields")
            }
        }
    }
}

private fun writeEntryResultFlags(value: JsonElement): Set<String> = value.jsonArray.mapTo(mutableSetOf()) {
    check(it is JsonPrimitive && it.isString)
    it.content
}

private fun writeEntryResultValues(category: String?, note: String, counterparty: String, account: String, attrs: JsonElement): JsonObject =
    buildJsonObject {
        put("category_id", category?.let(::JsonPrimitive) ?: JsonNull)
        put("note", note); put("counterparty", counterparty); put("counterparty_account", account); put("counterparty_account_attrs", attrs)
    }
