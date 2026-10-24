package com.finance.tracker.presentation

import com.finance.tracker.domain.*
import com.finance.tracker.core.instantForLocalDateTime

private fun invalidCreation(code: String): Nothing = throw DomainFailure(code, 0, FailureCategory.RECOVERABLE)
private fun creationAccount(id: String, currency: String, accounts: List<Account>): Account {
    val account = accounts.singleOrNull { it.id.toString() == id && it.active } ?: invalidCreation("invalid_account")
    if (!Regex("[A-Z]{3}").matches(currency) || currency !in account.currencies.orEmpty()) invalidCreation("invalid_currency")
    return account
}
private fun creationMagnitude(value: String, positive: Boolean): String {
    val amount = value.removePrefix("+")
    if (!Regex("\\d+(?:\\.\\d+)?").matches(amount) || positive && amount.none { it in '1'..'9' }) invalidCreation("invalid_amount")
    return amount
}
internal val cashOrdinaryFields = listOf("amount", "currency", "occurred_at", "account_id", "record_type", "record_subtype", "category_id", "counterparty", "counterparty_account", "counterparty_account_attrs", "note")
internal fun cashOrdinaryCreation(input: CashOrdinaryInput, accounts: List<Account>, timeZoneId: String): StructuredObject {
    val values = input.values
    if (values.keys.any { it !in cashOrdinaryFields }) invalidCreation("field_not_editable")
    val type = values["record_type"]
    if (type !in setOf("consumption", "income")) invalidCreation("invalid_record")
    val amount = creationMagnitude(values["amount"].orEmpty(), positive = false)
    val currency = values["currency"].orEmpty()
    val account = creationAccount(values["account_id"].orEmpty(), currency, accounts)
    val time = instantForLocalDateTime(values["occurred_at"].orEmpty(), timeZoneId) ?: invalidCreation("invalid_datetime")
    val patch = cashRecordFieldPatch(cashOrdinaryFields, values.filterKeys { it !in setOf("account_id", "amount", "occurred_at", "record_subtype") }, timeZoneId)
    return StructuredObject(patch.fields + buildMap {
        put("amount", StructuredText(if (type == "consumption" && amount.any { it in '1'..'9' }) "-$amount" else amount))
        put("account_name", StructuredText(account.name)); put("occurred_at", StructuredText(time))
        values["record_subtype"]?.takeIf { it.isNotEmpty() }?.let { put("record_subtype", StructuredText(it)) }
    })
}
internal fun cashTransferCreation(input: CashTransferInput, accounts: List<Account>, timeZoneId: String): StructuredObject {
    val from = creationAccount(input.fromAccountId, input.fromCurrency, accounts)
    val to = creationAccount(input.toAccountId, input.toCurrency, accounts)
    if (from.id == to.id && input.fromCurrency == input.toCurrency) invalidCreation("invalid_account")
    val amount = creationMagnitude(input.amount, positive = true)
    val exchange = input.fromCurrency != input.toCurrency
    val toAmount = if (exchange) creationMagnitude(input.toAmount, positive = true) else null
    val time = instantForLocalDateTime(input.occurredAt, timeZoneId) ?: invalidCreation("invalid_datetime")
    return StructuredObject(buildMap {
        put("from_account", StructuredText(from.name)); put("to_account", StructuredText(to.name))
        put("from_currency", StructuredText(input.fromCurrency)); put("to_currency", StructuredText(input.toCurrency))
        put("amount", StructuredText(amount)); toAmount?.let { put("to_amount", StructuredText(it)) }
        put("date", StructuredText(time.substringBefore('T'))); put("time", StructuredText(time.substringAfter('T').removeSuffix("Z")))
        put("note", StructuredText(input.note))
    })
}
