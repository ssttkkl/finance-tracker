package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*

/** Prepare file-source values against trusted old facts; source identity and complete issue effects remain separate gates. */
fun prepareCashImportWriteSetSourceValues(changes: CashWriteSetDto, facts: LedgerFactsDto): CashWriteSetDto {
    val normalized = normalizeCashWriteSetFinancialValues(changes)
    validateCashImportWriteSetScope(normalized, facts)
    val parents = facts.cashTransactions.associateBy { it.id }
    val parts = facts.cashTransactionComponents.associateBy { it.id }
    val accounts = facts.accounts.associateBy { it.id }
    // Like record-policy patches, an equal numeric value retains its trusted old representation.
    fun retainedAmount(old: String?, value: String?): String? =
        if (old != null && value != null && CashAmount.parse(old) == CashAmount.parse(value)) old else value
    val prepared = normalized.copy(transactions = normalized.transactions.map { row ->
        val old = (row.target.reference as? ExistingCashWriteReferenceDto)?.let { parents.getValue(it.id) }
        row.copy(values = row.values.copy(amount = retainedAmount(old?.amount, row.values.amount)),
            components = row.components.map { part ->
                val previous = (part.reference as? ExistingCashWriteReferenceDto)?.let { parts.getValue(it.id) }
                part.copy(amount = retainedAmount(previous?.amount, part.amount))
            })
    })
    for ((index, row) in prepared.transactions.withIndex()) {
        val path = "transactions[$index]"
        val old = (row.target.reference as? ExistingCashWriteReferenceDto)?.let { parents.getValue(it.id) }
        fun denied(field: String): Nothing = throw CashPolicyError("field_not_editable", listOf("$path.$field"))
        val allowed = editableCashRecordFields - commonCashFields
        if (row.editedFields.distinct().size != row.editedFields.size || row.editedFields.any { it !in allowed } ||
            old?.state == "posted" && row.editedFields.isNotEmpty()) denied("edited_fields")
        val value = row.values
        validateSourceAccountValues(row, old?.state, accounts, path)
        if (value.manualOverrides != (old?.manualOverrides ?: JsonObject(emptyMap()))) denied("values.manual_overrides")
        if (value.sourceFingerprint != old?.sourceFingerprint) denied("values.source_fingerprint")
        for ((partIndex, part) in row.components.withIndex()) {
            val previous = (part.reference as? ExistingCashWriteReferenceDto)?.let { parts.getValue(it.id) }
            val partPath = "components[$partIndex]"
            if (part.label != (previous?.label ?: "")) denied("$partPath.label")
            if (part.sourceKey != (previous?.sourceKey ?: "")) denied("$partPath.source_key")
            if (part.metadataJson != (previous?.metadataJson ?: JsonObject(emptyMap()))) denied("$partPath.metadata_json")
        }
        fun normalizedField(field: String, raw: JsonElement): Any? = try {
            when (field) {
                "source_payload" -> {
                    if (raw != JsonNull && raw !is JsonObject) throw InvalidCashValue()
                    checkSourcePayloadDepth(raw)
                }
                "counterparty_account_attrs" -> if (raw !is JsonArray || raw.any { it !is JsonPrimitive || !it.isString }) {
                    throw InvalidCashValue()
                }
            }
            LocalCashRecordValues.normalize(field, decodeLocalCashValue(raw))
        } catch (_: InvalidCashValue) {
            throw CashPolicyError("invalid_field", listOf("$path.values.$field"))
        }
        val payload = normalizedField("source_payload", value.sourcePayload)
        normalizedField("counterparty_account_attrs", value.counterpartyAccountAttrs)
        val explanation = when (val raw = value.relationMetadata) {
            JsonNull -> null
            is JsonObject -> raw.mapValues { (_, item) -> if (item is JsonPrimitive) decodeLocalCashValue(item) else item }
            else -> raw
        }
        if (value.relationMetadata != old?.relationMetadata && value.relationMetadata != JsonNull) {
            @Suppress("UNCHECKED_CAST")
            val fields = explanation as? Map<String, Any?> ?: denied("values.relation_metadata")
            if (!validCashSourceExplanation(fields)) denied("values.relation_metadata")
        }
        val fields = mapOf("amount" to value.amount?.let(CashAmount::parse), "source_payload" to payload, "note" to value.note)
        // Only these source edits have derived effects; public note edits belong to the entry.
        val edited = (fields + mapOf("record_type" to value.recordType, "record_subtype" to value.recordSubtype))
            .filterKeys { it in row.editedFields }
        val effects = prepareCashSourceEffects(fields, edited, value.sourceType.orEmpty(), explanation)
        if (effects.values["note"] != value.note) denied("values.note")
        if (effects.explanation != null && !LocalCashRecordValues.valuesEqual(effects.explanation, explanation)) {
            denied("values.relation_metadata")
        }
    }
    return prepared
}

private fun validateSourceAccountValues(row: CashTransactionWriteDto, oldState: String?, accounts: Map<String, LedgerAccountFactDto>, path: String) {
    if (oldState == "posted") return
    val all = row.values.state == "posted" || "currency" in row.editedFields
    val selected = buildSet {
        if ("account_id" in row.editedFields) row.values.accountId?.let(::add)
        if ("components" in row.editedFields) row.components.mapNotNullTo(this) { it.accountId }
    }
    fun validate(id: String?, currency: String?, field: String) {
        if (id == null || !all && id !in selected) return
        val account = accounts.getValue(id)
        if (!account.active || account.type !in setOf("cash", "loan", "lend")) {
            throw CashPolicyError("account_unavailable", listOf(field))
        }
        if (currency != null && (account.currencies as? JsonArray)?.any { it is JsonPrimitive && it.isString && it.content == currency } != true) {
            throw CashPolicyError("account_currency_missing", listOf(field))
        }
    }
    for ((index, part) in row.components.withIndex()) validate(part.accountId, part.currency, "$path.components[$index].account_id")
    validate(row.values.accountId, row.values.currency, "$path.values.account_id")
}

private fun checkSourcePayloadDepth(value: JsonElement, depth: Int = 0) {
    if (depth > 64) throw InvalidCashValue()
    when (value) {
        is JsonObject -> value.values.forEach { checkSourcePayloadDepth(it, depth + 1) }
        is JsonArray -> value.forEach { checkSourcePayloadDepth(it, depth + 1) }
        is JsonPrimitive -> Unit
    }
}
