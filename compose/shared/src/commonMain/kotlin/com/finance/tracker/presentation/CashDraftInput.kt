package com.finance.tracker.presentation

import com.finance.tracker.core.instantForLocalDateTime
import com.finance.tracker.core.formatLocalDateTime
import com.finance.tracker.domain.*

internal val cashDraftScalarFields = listOf("amount", "currency", "occurred_at", "cash_granularity", "account_id", "record_type", "record_subtype", "category_id", "counterparty", "counterparty_account", "counterparty_account_attrs", "note")

internal data class CashDraftAllocation(val componentId: String?, val ordinal: Int, val accountId: String, val amount: String, val currency: String)
internal data class CashDraftAllocationInput(val parts: List<CashDraftAllocation>, val singleChosen: Boolean = false) {
    fun switchMode() = copy(singleChosen = false)
    fun changeParts(value: List<CashDraftAllocation>) = copy(parts = value, singleChosen = false)
    fun chooseSingle() = copy(singleChosen = true)
    fun patchParts(split: Boolean, values: Map<String, String>, initial: List<CashDraftAllocation>): List<CashDraftAllocation>? =
        when {
            split -> parts
            singleChosen -> cashDraftSingleAllocation(values, initial)
            else -> initial
        }.takeIf { it != initial }
}

internal fun cashDraftInitialAllocations(record: CashCurrentRecord): List<CashDraftAllocation> = cashRecordInitialAllocations(record.fields)

internal fun cashRecordInitialAllocations(fields: StructuredObject): List<CashDraftAllocation> =
    (fields.fields["components"] as? StructuredArray)?.values.orEmpty().mapIndexed { index, value ->
        val fields = (value as? StructuredObject)?.fields.orEmpty()
        CashDraftAllocation(fields["component_id"].cashText(), fields["ordinal"].cashText()?.toIntOrNull() ?: index,
            fields["account_id"].cashText().orEmpty(), fields["amount"].cashText().orEmpty(), fields["currency"].cashText().orEmpty())
    }

internal fun cashDraftSingleAllocation(values: Map<String, String>, existing: List<CashDraftAllocation>): List<CashDraftAllocation> =
    listOf(CashDraftAllocation(existing.firstOrNull()?.componentId, existing.firstOrNull()?.ordinal ?: 0,
        values["account_id"].orEmpty(), values["amount"].orEmpty(), values["currency"].orEmpty()))

internal fun cashDraftSingleAllocationMatches(values: Map<String, String>, allocations: List<CashDraftAllocation>): Boolean {
    val part = allocations.singleOrNull() ?: return false
    return part.accountId == values["account_id"].orEmpty() && part.currency == values["currency"].orEmpty() &&
        (part.amount == values["amount"].orEmpty() || displayDecimalCompare(part.amount, values["amount"]) == 0)
}

internal fun cashDraftInitialValues(record: CashCurrentRecord): Map<String, String> = cashRecordInitialValues(record.fields, record.editableFields)

internal fun cashRecordInitialValues(fields: StructuredObject, editableFields: List<String>): Map<String, String> = cashDraftScalarFields.filter { it in editableFields }.associateWith { field ->
    val value = fields.fields[field]
    when (field) {
        "occurred_at" -> value.cashText()?.let { formatLocalDateTime(it).replace(' ', 'T') }.orEmpty()
        "counterparty_account_attrs" -> (value as? StructuredArray)?.values.orEmpty().mapNotNull { it.cashText() }.joinToString(",")
        else -> value.cashText().orEmpty()
    }
}

internal fun cashDraftPatch(record: CashCurrentRecord, changes: Map<String, String>, timeZoneId: String, allocations: List<CashDraftAllocation>? = null): StructuredObject {
    if (record.state != "draft") throw DomainFailure("draft_required", 0, FailureCategory.RECOVERABLE)
    return cashRecordFieldPatch(record.editableFields, changes, timeZoneId, allocations)
}

internal fun validateCashImportFinancialPatch(original: StructuredObject, patch: StructuredObject, accounts: List<Account>) {
    if (patch.fields.keys.none { it in setOf("amount", "currency", "account_id", "cash_granularity", "components") }) return
    fun invalid(): Nothing = throw DomainFailure("invalid_allocation", 0, FailureCategory.RECOVERABLE)
    val fields = original.fields + patch.fields
    val amount = fields["amount"].cashText()?.takeIf(::isExactDecimalString)
    val currency = fields["currency"].cashText().orEmpty()
    val accountId = fields["account_id"].cashText().orEmpty()
    val parts = cashRecordInitialAllocations(StructuredObject(fields))
    val selected = buildList {
        if ("account_id" in patch.fields || "currency" in patch.fields) add(accountId to currency)
        if ("components" in patch.fields || "currency" in patch.fields) addAll(parts.map { it.accountId to it.currency })
    }
    selected.filter { it.first.isNotEmpty() }.forEach { (id, code) ->
        val account = accounts.firstOrNull { it.id.toString() == id && it.active && it.type in setOf("cash", "loan", "lend") }
        if (account == null || code.isNotEmpty() && code !in account.currencies.orEmpty())
            throw DomainFailure("account_changed", 0, FailureCategory.RECOVERABLE)
    }
    if (parts.map { it.ordinal }.distinct().size != parts.size) invalid()
    val split = fields["cash_granularity"].cashText() == "aggregate"
    if (split && accountId.isNotEmpty()) invalid()
    if (!split && accountId.isNotEmpty() && parts.isNotEmpty() &&
        (parts.size != 1 || parts.single().accountId.isNotEmpty() && parts.single().accountId != accountId)) invalid()
    if (currency.isNotEmpty() && parts.any { it.currency.isNotEmpty() && it.currency != currency }) invalid()
    if (amount != null) {
        val sign = displayDecimalSign(amount)!!
        if (parts.any { part -> displayDecimalSign(part.amount)?.let { it != 0 && it != sign } == true }) invalid()
        if (parts.isNotEmpty() && parts.all { isExactDecimalString(it.amount) }) {
            val total = parts.fold("0") { sum, part -> displayDecimalAdd(sum, part.amount) ?: invalid() }
            if (displayDecimalCompare(total, amount) != 0) invalid()
        }
    }
}

internal fun cashRecordFieldPatch(editableFields: List<String>, changes: Map<String, String>, timeZoneId: String,
    allocations: List<CashDraftAllocation>? = null): StructuredObject {
    fun invalid(code: String): Nothing = throw DomainFailure(code, 0, FailureCategory.RECOVERABLE)
    if (changes.keys.any { it !in editableFields || it !in cashDraftScalarFields }) invalid("field_not_editable")
    val patch = changes.mapValues { (field, text) ->
        when (field) {
            "occurred_at" -> if (text.isEmpty()) StructuredNull else StructuredText(instantForLocalDateTime(text, timeZoneId) ?: invalid("invalid_datetime"))
            "amount" -> if (text.isEmpty()) StructuredNull else {
                if (!isExactDecimalString(text)) invalid("invalid_amount")
                StructuredText(text)
            }
            "currency" -> if (text.isEmpty()) StructuredNull else { if (!Regex("[A-Z]{3}").matches(text)) invalid("invalid_currency"); StructuredText(text) }
            "cash_granularity" -> { if (text !in setOf("atomic", "aggregate")) invalid("invalid_field"); StructuredText(text) }
            "account_id", "category_id", "record_type", "record_subtype" -> text.takeIf { it.isNotEmpty() }?.let(::StructuredText) ?: StructuredNull
            "counterparty_account_attrs" -> StructuredArray(text.split(',').filter { it.isNotEmpty() }.map(::StructuredText))
            else -> StructuredText(text)
        }
    }.toMutableMap()
    if (allocations != null) {
        if ("components" !in editableFields) invalid("field_not_editable")
        patch["components"] = StructuredArray(allocations.map { part ->
            if (part.amount.isNotEmpty() && !isExactDecimalString(part.amount)) invalid("invalid_amount")
            if (part.currency.isNotEmpty() && !Regex("[A-Z]{3}").matches(part.currency)) invalid("invalid_currency")
            if (part.ordinal < 0) invalid("invalid_field")
            StructuredObject(buildMap {
                part.componentId?.let { put("component_id", StructuredText(it)) }
                put("ordinal", StructuredNumber(part.ordinal.toString()))
                put("account_id", part.accountId.takeIf { it.isNotEmpty() }?.let(::StructuredText) ?: StructuredNull)
                put("amount", part.amount.takeIf { it.isNotEmpty() }?.let(::StructuredText) ?: StructuredNull)
                put("currency", part.currency.takeIf { it.isNotEmpty() }?.let(::StructuredText) ?: StructuredNull)
            })
        })
    }
    return StructuredObject(patch)
}
