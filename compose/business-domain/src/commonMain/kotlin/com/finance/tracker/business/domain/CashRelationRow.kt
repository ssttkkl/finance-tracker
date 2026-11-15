package com.finance.tracker.business.domain

/** Original relation-row field mapping; hosts retain their scalar parsing boundaries. */
fun <A> cashRelationFactFromRow(row: Map<String, Any?>, parseAmount: (Any?) -> A,
    truthy: (Any?) -> Boolean, parseOrdinal: (Any) -> Int?): RelationFact<A> {
    val payload = (row["raw_payload"] as? Map<*, *>)
        ?: (row["source_payload"] as? Map<*, *>)
    @Suppress("UNCHECKED_CAST")
    fun anyMap(value: Map<*, *>?): Map<String, Any?>? = value?.entries?.associate { it.key.toString() to it.value }
    val relationMetadata = anyMap(row["relation_metadata"] as? Map<*, *>)
    fun firstTruthy(vararg keys: String): String {
        for (key in keys) row[key]?.toString()?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }
    val sourceType = firstTruthy("source_type", "bill_source", "source")
    val ordinalRaw = row["component_ordinal"] ?: row["ordinal"]
    val ordinal = ordinalRaw?.let(parseOrdinal)
    val attrsRaw = row["counterparty_account_attrs"]
    val attrs = if (attrsRaw is List<*>) attrsRaw.map { it.toString() } else emptyList()
    val recordType = firstTruthy("record_type").ifEmpty { "other" }
    var recordSubtype = firstTruthy("record_subtype").ifEmpty { "not_applicable" }
    if (recordSubtype == "not_applicable" && recordType in setOf("transfer_in", "transfer_out", "fx_in", "fx_out",
            "repayment", "withdrawal_in", "withdrawal_out"))
        recordSubtype = defaultCashRecordSubtype(recordType)
    val deleted = truthy(row["deleted"]) || truthy(row["deleted_at"])
    return RelationFact(
        id = row.getValue("id").toString(),
        amount = parseAmount(row.getValue("amount")),
        currency = firstTruthy("currency").ifEmpty { "CNY" },
        accountId = row["account_id"]?.toString() ?: (row["account_name"] ?: "").toString(),
        accountName = (row["account_name"] ?: "").toString(),
        accountType = firstTruthy("account_type", "_record_type").ifEmpty { "cash" },
        occurredAt = row["occurred_at"] ?: row["date"] ?: "",
        counterparty = (row["counterparty"] ?: "").toString(),
        counterpartyAccount = (row["counterparty_account"] ?: "").toString(),
        counterpartyAccountAttrs = attrs,
        paymentMethod = (row["payment_method"] ?: "").toString(),
        note = (row["note"] ?: "").toString(),
        recordType = recordType,
        recordSubtype = recordSubtype,
        billSource = sourceType,
        source = sourceType,
        factType = firstTruthy("fact_type").ifEmpty { "cash" },
        deleted = deleted,
        sourceIdentity = (row["source_identity"] ?: "").toString(),
        recordId = (row["record_id"] ?: "").toString(),
        rawPayload = anyMap(payload),
        relationMetadata = relationMetadata,
        parentId = row["parent_id"]?.toString()?.takeIf { it.isNotEmpty() },
        componentOrdinal = ordinal,
        cashGranularity = firstTruthy("cash_granularity").ifEmpty { "atomic" },
        stableSourceKey = (row["stable_source_key"] as? List<*>)?.map { it.toString() }.orEmpty(),
        recordOrigin = firstTruthy("record_origin").ifEmpty { "legacy" },
    )
}
