package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlin.time.Instant

/** Normalize financial input for persistence; references, permissions and final graph still require validation. */
fun normalizeCashWriteSetFinancialValues(changes: CashWriteSetDto): CashWriteSetDto = changes.copy(
    transactions = changes.transactions.mapIndexed { index, row -> normalizeWriteTransaction(row, "transactions[$index]") },
    relations = changes.relations.mapIndexed { index, edge ->
        edge.copy(appliedAmount = requireNotNull(normalizeWriteAmount(edge.appliedAmount, "relations[$index].applied_amount")))
    },
)

private fun normalizeWriteAmount(value: String?, path: String): String? =
    (normalizeWriteField("amount", value, path) as CashAmount?)?.let { localCashStoredDecimalText(it.toString()) }

private fun normalizeWriteField(field: String, value: Any?, path: String): Any? = try {
    LocalCashRecordValues.normalize(field, value)
} catch (_: InvalidCashValue) {
    throw CashPolicyError("invalid_field", listOf(path))
}

private fun normalizeWriteTransaction(row: CashTransactionWriteDto, path: String): CashTransactionWriteDto {
    val value = row.values
    fun field(name: String, raw: Any?) = normalizeWriteField(name, raw, "$path.values.$name")
    val amount = normalizeWriteAmount(value.amount, "$path.values.amount")
    val currency = field("currency", value.currency) as String?
    val occurredAt = (field("occurred_at", value.occurredAt) as Instant?)?.let(::formatPythonCashTimestamp)
    field("account_id", value.accountId)
    field("cash_granularity", value.cashGranularity)
    val components = row.components.mapIndexed { index, part ->
        val componentPath = "$path.components[$index]"
        normalizeWriteField("account_id", part.accountId, "$componentPath.account_id")
        normalizeWriteField("currency", part.currency, "$componentPath.currency")
        if (!Regex("0|[1-9][0-9]*").matches(part.ordinal) || part.ordinal.toLongOrNull()?.let { it <= Int.MAX_VALUE } != true) {
            throw CashPolicyError("invalid_field", listOf("$componentPath.ordinal"))
        }
        part.copy(amount = normalizeWriteAmount(part.amount, "$componentPath.amount"))
    }
    val record = mapOf(
        "state" to value.state, "record_origin" to value.recordOrigin,
        "record_type" to value.recordType, "record_subtype" to value.recordSubtype,
        "amount" to amount, "currency" to currency, "occurred_at" to occurredAt,
        "account_id" to value.accountId, "cash_granularity" to value.cashGranularity,
        "components" to components.map { part -> mapOf(
            "account_id" to part.accountId, "amount" to part.amount, "currency" to part.currency,
            "ordinal" to SourceJsonInteger(part.ordinal.toInt()),
        ) },
    )
    try {
        if (value.state == "posted") LocalCashRecordValues.validateForPosting(record)
        else {
            LocalCashRecordValues.prepare(record, emptyMap<String, Any?>(), emptySet())
            LocalCashRecordValues.validateConsistency(record)
        }
    } catch (failure: CashPolicyError) {
        throw CashPolicyError(failure.code, failure.fields.map { if (it == "components") "$path.components" else "$path.values.$it" })
    }
    return row.copy(values = value.copy(amount = amount, currency = currency, occurredAt = occurredAt), components = components)
}
