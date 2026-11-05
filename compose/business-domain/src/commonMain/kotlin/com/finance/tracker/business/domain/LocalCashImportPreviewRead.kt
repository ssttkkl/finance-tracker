package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.LedgerSnapshotDto

/** Resolve a stored parent's source scope without using a file fallback to hide conflicting accounts. */
fun cashImportPreviewSourceScope(identifier: String, accounts: Set<String>, files: Set<String>): String = when {
    accounts.size == 1 -> accounts.first()
    accounts.isNotEmpty() -> "ambiguous:$identifier"
    files.size == 1 -> "file:${files.first()}"
    else -> "ambiguous:$identifier"
}

/** Read preview values from the same complete snapshot as the supplied relation context, without IO or mutation. */
fun readLocalCashImportPreviewValues(snapshot: LedgerSnapshotDto,
    context: CashRelationReadContext<*, *>): MutableMap<String, MutableMap<String, Any?>> {
    val ids = context.transactionIds.map { it.toString() }.toSet()
    val grouped = snapshot.facts.cashSourceBindings.filter { it.transactionId in ids }
        .groupBy { it.transactionId to it.sourceType }
    val scopes = grouped.mapValues { (key, bindings) -> cashImportPreviewSourceScope(key.first,
        bindings.filter { it.sourceScopeKind == "account" }.map { it.sourceScopeKey }.toSet(),
        bindings.filter { it.sourceScopeKind == "file_local" }.map { it.sourceScopeKey }.toSet()) }
    val result = linkedMapOf<String, MutableMap<String, Any?>>()
    for (row in snapshot.facts.cashTransactions.filter { it.id in ids }.sortedBy { it.id.toLong() }) {
        result[row.id] = linkedMapOf(
            "counterparty" to row.counterparty,
            "counterparty_account" to row.counterpartyAccount,
            "counterparty_account_attrs" to decodeLocalCashValue(row.counterpartyAccountAttrs),
            "note" to row.note,
            "category_id" to row.categoryId,
            "record_type" to row.recordType,
            "record_subtype" to row.recordSubtype,
            "source_payload" to decodeLocalCashValue(row.sourcePayload),
            "amount" to row.amount?.let { LocalCashRecordValues.normalize("amount", it) },
            "currency" to row.currency,
            "occurred_at" to row.occurredAt?.let(::parsePythonCashTimestamp),
            "account_id" to row.accountId,
            "components" to mutableListOf<MutableMap<String, Any?>>(),
            "cash_granularity" to row.cashGranularity,
            "source_type" to row.sourceType,
            "record_id" to row.recordId,
            "state" to row.state,
            "created_at" to parsePythonCashTimestamp(row.createdAt),
            "record_origin" to row.recordOrigin,
            "manual_transfer_id" to row.manualTransferId,
            "source_explanation" to extractCashSourceExplanation(decodeLocalCashValue(row.relationMetadata)),
            "source_scope_key" to (scopes[row.id to row.sourceType] ?: "unbound:${row.id}"),
            "matching_record_id" to "occurrence:${row.id}",
        )
    }
    for ((id, key) in context.sourceOrderKeys) result[id.toString()]?.put("stable_source_key", key.toList())
    for (part in context.componentVersions) {
        val row = result[part.transactionId.toString()] ?: continue
        @Suppress("UNCHECKED_CAST") val components = row["components"] as MutableList<MutableMap<String, Any?>>
        components += linkedMapOf("component_id" to part.id.toString(), "account_id" to part.accountId?.toString(),
            "amount" to part.amount?.let { LocalCashRecordValues.normalize("amount", it) }, "currency" to part.currency,
            "ordinal" to part.ordinal)
    }
    return result
}
