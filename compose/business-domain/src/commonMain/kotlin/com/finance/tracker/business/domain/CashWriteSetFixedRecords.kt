package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*

/** Check fixed fields and forward state direction; intent, protection and posting eligibility require separate checks. */
fun validateCashWriteSetFixedRecords(changes: CashWriteSetDto, facts: LedgerFactsDto) {
    validateCashWriteSetReferences(changes, facts)
    val normalized = normalizeCashWriteSetFinancialValues(changes)
    val records = facts.cashTransactions.associateBy { it.id }
    val components = facts.cashTransactionComponents.groupBy { it.cashTransactionId }
    for ((index, row) in normalized.transactions.withIndex()) {
        val path = "transactions[$index]"
        val value = row.values
        val target = row.target.reference
        if (target is NewCashWriteReferenceDto) {
            val conflicts = buildList {
                if (value.recordOrigin != "import") add("$path.values.record_origin")
                if (value.manualTransferId != null) add("$path.values.manual_transfer_id")
            }
            if (conflicts.isNotEmpty()) throw CashPolicyError("fixed_field_conflict", conflicts)
            continue
        }
        val old = records.getValue((target as ExistingCashWriteReferenceDto).id)
        val current = mapOf(
            "state" to old.state, "record_origin" to old.recordOrigin, "source_type" to old.sourceType,
            "record_id" to old.recordId, "manual_transfer_id" to old.manualTransferId,
            "amount" to old.amount, "currency" to old.currency, "occurred_at" to old.occurredAt,
            "account_id" to old.accountId, "cash_granularity" to old.cashGranularity,
            "record_type" to old.recordType, "record_subtype" to old.recordSubtype,
            "components" to components[old.id].orEmpty().sortedBy { it.ordinal.toInt() }.map { part -> mapOf(
                "component_id" to part.id, "account_id" to part.accountId, "amount" to part.amount,
                "currency" to part.currency, "ordinal" to SourceJsonInteger(part.ordinal.toInt()),
            ) },
        )
        val incoming = mapOf(
            "record_origin" to value.recordOrigin, "source_type" to value.sourceType,
            "record_id" to value.recordId, "manual_transfer_id" to value.manualTransferId,
            "amount" to value.amount, "currency" to value.currency, "occurred_at" to value.occurredAt,
            "account_id" to value.accountId, "cash_granularity" to value.cashGranularity,
            "record_type" to value.recordType, "record_subtype" to value.recordSubtype,
            "components" to row.components.sortedBy { it.ordinal.toInt() }.map { part -> mapOf(
                "component_id" to when (val reference = part.reference) {
                    is ExistingCashWriteReferenceDto -> reference.id
                    is NewCashWriteReferenceDto -> "new:${reference.ref}"
                },
                "account_id" to part.accountId, "amount" to part.amount, "currency" to part.currency,
                "ordinal" to SourceJsonInteger(part.ordinal.toInt()),
            ) },
        )
        try {
            LocalCashRecordValues.prepare(current, incoming, emptySet())
        } catch (failure: CashPolicyError) {
            throw CashPolicyError(failure.code, failure.fields.map { if (it == "components") "$path.components" else "$path.values.$it" })
        }
        if (old.state == "posted" && value.state != "posted") {
            throw CashPolicyError("invalid_state", listOf("$path.values.state"))
        }
    }
}
